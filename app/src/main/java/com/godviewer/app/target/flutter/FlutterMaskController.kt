package com.godviewer.app.target.flutter

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import com.godviewer.app.IGNORE_HOOK
import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.flutter.FlutterRuleManager.FlutterRule
import com.godviewer.app.target.hook.hookers.ActivityLifecycleHooker
import java.lang.ref.WeakReference

/**
 * Flutter 规则遮罩：把隐藏规则命中的语义矩形盖住，用户可感知层面**等效原生 View 的 GONE**。
 *
 * Flutter 的渲染树在 Dart 侧，Java 层无法真正移除 widget（release AOT 无 VM Service）；
 * 这里做等效实现：
 * - 遮罩是挂在 decorView 上的**真实 View**：拦截该区域全部触摸，编辑模式关闭时被隐藏
 *   控件点不动（交互 = GONE）；
 * - 颜色用 [PixelCopy] 抓窗口内容后采样遮罩**四周一圈**的背景色填充 —— 视觉上像
 *   "这里本来就没画东西"，而不是打了码（API < 26 / 采样失败退回暗色）；
 * - 编辑模式开启时，Activity.dispatchTouchEvent 的编辑拦截先于遮罩收到触摸
 *   （遮罩已打 IGNORE_HOOK 标不参与编辑命中），语义 hitTest 弹出管理框可恢复。
 *
 * 矩形来自语义树，每次 [onTreeUpdated] 后重算跟随；App 内部仍渲染该 widget
 * （性能无差，渲染树层面与真 GONE 的差异原理上无法消除，见 FLUTTER_FEASIBILITY.md）。
 */
object FlutterMaskController {

    private const val TAG = "FlutterMask"
    private const val FALLBACK_COLOR = 0xEE141416.toInt() // 采样失败时的退回暗色
    private const val APPLY_THROTTLE_MS = 60L
    private const val SAMPLE_BAND_PX = 10 // 背景采样圈宽
    private const val RESAMPLE_MOVE_PX = 24 // 位移超过该值才重新采样背景

    private val mainHandler = Handler(Looper.getMainLooper())
    private val applyRunnable = Runnable { applyNow() }

    private var decorRef: WeakReference<View>? = null
    private val masks = LinkedHashMap<String, MaskView>()
    private val nodeRect = Rect()
    private val decorLoc = IntArray(2)
    private val viewLoc = IntArray(2)

    /** 语义树更新后重算遮罩（FlutterBridge 回调，主线程） */
    fun onTreeUpdated() {
        schedule()
    }

    /** 外部立即请求一次重算（规则增删 / Activity 切换） */
    fun refresh() {
        schedule()
    }

    /** 撤下当前 decor 上的全部遮罩 */
    fun clear() {
        mainHandler.removeCallbacks(applyRunnable)
        clearInternal()
    }

    private fun schedule() {
        mainHandler.removeCallbacks(applyRunnable)
        mainHandler.postDelayed(applyRunnable, APPLY_THROTTLE_MS)
    }

    private fun clearInternal() {
        val decor = decorRef?.get() as? ViewGroup
        if (decor != null) {
            for (mask in masks.values) {
                runCatching { decor.removeView(mask) }
            }
        }
        masks.clear()
    }

    private fun applyNow() {
        val activity = ActivityLifecycleHooker.resumedActivity()
        if (activity == null || activity.isFinishing) {
            clearInternal()
            return
        }
        val decor = activity.window?.decorView
        if (decor == null) {
            clearInternal()
            return
        }
        if (decorRef?.get() !== decor) {
            // decor 重建（旋转 / 主题切换）：旧遮罩随旧树消失，这里只登记新 decor
            decorRef = WeakReference(decor)
            masks.clear()
        }
        val rules = FlutterRuleManager.rulesForActivity(activity.componentName.className)
        if (rules.isEmpty() || !FlutterBridge.flutterViewLocation(viewLoc)) {
            clearInternal()
            return
        }
        decor.getLocationOnScreen(decorLoc)
        val wanted = HashMap<String, Rect>(rules.size)
        for (rule in rules) {
            val rect = rectForRule(activity, rule) ?: continue
            rect.offset(-decorLoc[0], -decorLoc[1])
            wanted[rule.key()] = rect
        }
        val decorGroup = decor as? ViewGroup ?: run {
            GvLog.w(TAG, "decor not a ViewGroup; skip masking")
            return
        }
        // 回收不再命中的遮罩
        val iterator = masks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!wanted.containsKey(entry.key)) {
                runCatching { decorGroup.removeView(entry.value) }
                iterator.remove()
            }
        }
        // 增改命中的遮罩（translation 定位 + 通用 LayoutParams，不依赖 decor 的具体布局类）
        for ((key, rect) in wanted) {
            val mask = masks[key] ?: MaskView(activity).also { newMask ->
                masks[key] = newMask
                runCatching {
                    decorGroup.addView(
                        newMask,
                        ViewGroup.LayoutParams(rect.width(), rect.height()),
                    )
                }.onFailure {
                    GvLog.w(TAG, "mask addView failed; skip this mask", it)
                    masks.remove(key)
                }
            } ?: continue
            if (mask.width <= 0) {
                // 刚 addView 尚未布局：先记录期望矩形，布局后由 sampleBackground 上色
                mask.pendingRect = Rect(rect)
            }
            mask.translationX = rect.left.toFloat()
            mask.translationY = rect.top.toFloat()
            mask.pendingRect = Rect(rect)
            mask.post { sampleBackground(mask) }
        }
    }

    private fun sampleBackground(mask: MaskView) {
        val rect = mask.pendingRect ?: return
        if (mask.width <= 0 || mask.height <= 0) return
        val decor = decorRef?.get() ?: return
        val moved = mask.sampledRect == null ||
            Math.abs(rect.centerX() - mask.sampledRect!!.centerX()) > RESAMPLE_MOVE_PX ||
            Math.abs(rect.centerY() - mask.sampledRect!!.centerY()) > RESAMPLE_MOVE_PX
        if (!moved) return
        mask.sampledRect = Rect(rect)
        val activity = ActivityLifecycleHooker.resumedActivity() ?: return
        if (Build.VERSION.SDK_INT < 26) {
            mask.setBackgroundColor(FALLBACK_COLOR)
            return
        }
        // 优先直接抓 Flutter 内容所在的 SurfaceView（合成更可靠），失败退 Window 整体
        val surfaceView = findFlutterSurfaceView()
        if (surfaceView != null && surfaceView.width > 0 && surfaceView.height > 0) {
            val surfaceLoc = IntArray(2)
            surfaceView.getLocationOnScreen(surfaceLoc)
            // pendingRect 是 decor 坐标；换成 surface 坐标 = 减去 surface 相对 decor 的偏移
            val relRect = Rect(rect).apply {
                offset(decorLoc[0] - surfaceLoc[0], decorLoc[1] - surfaceLoc[1])
            }
            copyAndFill(
                mask,
                rect,
                { bitmap, listener ->
                    PixelCopy.request(surfaceView, bitmap, listener, mainHandler)
                },
                { surfaceView.width.coerceAtLeast(1) to surfaceView.height.coerceAtLeast(1) },
                relRect,
                "surfaceView",
            )
        } else {
            copyAndFill(
                mask,
                rect,
                { bitmap, listener ->
                    PixelCopy.request(activity.window, bitmap, listener, mainHandler)
                },
                { decor.width.coerceAtLeast(1) to decor.height.coerceAtLeast(1) },
                rect,
                "window",
            )
        }
    }

    private fun findFlutterSurfaceView(): android.view.SurfaceView? {
        val flutterView = FlutterBridge.flutterView() ?: return null
        if (flutterView is android.view.SurfaceView) return flutterView
        if (flutterView is ViewGroup) {
            for (i in 0 until flutterView.childCount) {
                val child = flutterView.getChildAt(i)
                if (child is android.view.SurfaceView) return child
            }
        }
        return null
    }

    private fun copyAndFill(
        mask: MaskView,
        decorRect: Rect,
        request: (Bitmap, (Int) -> Unit) -> Unit,
        size: () -> Pair<Int, Int>,
        sampleRect: Rect,
        sourceName: String,
    ) {
        runCatching {
            val (w, h) = size()
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            request(bitmap) { result ->
                if (result == PixelCopy.SUCCESS) {
                    val color = runCatching { ringColor(bitmap, sampleRect) }
                        .getOrDefault(FALLBACK_COLOR)
                    mask.setBackgroundColor(color)
                    GvLog.d(TAG, "mask bg sampled via $sourceName color=#${Integer.toHexString(color)}")
                } else {
                    GvLog.w(TAG, "pixel copy($sourceName) result=$result; fallback color")
                    mask.setBackgroundColor(FALLBACK_COLOR)
                }
                bitmap.recycle()
            }
        }.onFailure {
            GvLog.w(TAG, "pixel copy($sourceName) failed; fallback color", it)
            mask.setBackgroundColor(FALLBACK_COLOR)
        }
    }

    /** 采样 [rect] 四周 [SAMPLE_BAND_PX] 环带的平均色（跳过透明像素），即背景色 */
    private fun ringColor(bitmap: Bitmap, rect: Rect): Int {
        val left = (rect.left - SAMPLE_BAND_PX).coerceAtLeast(0)
        val top = (rect.top - SAMPLE_BAND_PX).coerceAtLeast(0)
        val right = (rect.right + SAMPLE_BAND_PX).coerceAtMost(bitmap.width - 1)
        val bottom = (rect.bottom + SAMPLE_BAND_PX).coerceAtMost(bitmap.height - 1)
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0L
        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                val inInner = x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom
                if (!inInner) {
                    val c = bitmap.getPixel(x, y)
                    if (Color.alpha(c) > 0) {
                        r += Color.red(c)
                        g += Color.green(c)
                        b += Color.blue(c)
                        count++
                    }
                }
                x += 2
            }
            y += 2
        }
        if (count == 0L) return FALLBACK_COLOR
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    /**
     * 遮罩 View：吞掉该区域全部触摸（等效 GONE 的交互层）。
     * 编辑模式的管理走 Activity 级触摸拦截（本 View 已打 IGNORE_HOOK 不参与编辑命中）。
     */
    private class MaskView(activity: Activity) : View(activity) {
        var pendingRect: Rect? = null
        var sampledRect: Rect? = null

        init {
            tag = IGNORE_HOOK
            isClickable = true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = true
    }

    private fun rectForRule(activity: Activity, rule: FlutterRule): Rect? {
        val node = FlutterBridge.findNodeForRule(rule) ?: return null
        if (node.isHidden) return null
        if (!node.screenRect(viewLoc, nodeRect)) return null
        if (nodeRect.isEmpty) return null
        // 版本守卫：应用升级后布局可能大改，错盖比不盖更糟
        if (rule.matchVersionCode != 0 &&
            rule.matchVersionCode != com.godviewer.app.target.rule.versionCode(activity)
        ) {
            return null
        }
        return Rect(nodeRect)
    }
}
