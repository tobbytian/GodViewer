package com.godviewer.app.target.edit

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.godviewer.app.IGNORE_HOOK
import com.godviewer.app.R
import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.dialog.ModuleDialogUi
import com.godviewer.app.target.hook.ModuleRes
import com.godviewer.app.target.hook.ModuleRes.moduleRes
import java.lang.ref.WeakReference

/**
 * 编辑模式下给「当前选中」控件画蓝色描边高亮。
 *
 * 在 decor 上盖一层模块自有 overlay，不改目标 background/foreground，
 * 避免干扰规则回放与缩略图。overlay 打 [IGNORE_HOOK]，不参与命中与 click 包装。
 */
object SelectedViewHighlight {

    private const val TAG = "Highlight"
    private const val STROKE_DP = 2.5f
    private const val CORNER_DP = 4f
    private const val FALLBACK_STROKE = 0xFF1565C0.toInt()
    private const val FALLBACK_FILL = 0x331565C0

    private var overlayRef: WeakReference<View>? = null
    private var targetRef: WeakReference<View>? = null
    private var hostDecorRef: WeakReference<ViewGroup>? = null
    private var layoutListener: View.OnLayoutChangeListener? = null
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    fun show(target: View) {
        runCatching {
            if (!EditMode.isEnabled()) {
                clearInternal()
                return
            }
            if (!target.isAttachedToWindow) {
                GvLog.d(TAG, "show skip: target not attached")
                return
            }
            val decor = target.rootView as? ViewGroup ?: run {
                GvLog.w(TAG, "show skip: no decor root")
                return
            }
            // 同一目标已在显示则只刷新位置
            if (targetRef?.get() === target && overlayRef?.get()?.parent === decor) {
                updateOverlayBounds()
                return
            }
            clearInternal()
            val overlay = View(decor.context).apply {
                tag = IGNORE_HOOK
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = buildHighlightDrawable(this)
            }
            val lp = ViewGroup.LayoutParams(
                target.width.coerceAtLeast(1),
                target.height.coerceAtLeast(1),
            )
            decor.addView(overlay, lp)
            overlayRef = WeakReference(overlay)
            targetRef = WeakReference(target)
            hostDecorRef = WeakReference(decor)
            attachTracking(target)
            updateOverlayBounds()
            GvLog.d(
                TAG,
                "show ${target.javaClass.simpleName} ${target.width}x${target.height}",
            )
        }.onFailure { GvLog.e(TAG, "show failed", it) }
    }

    fun clear() {
        runCatching { clearInternal() }
            .onFailure { GvLog.e(TAG, "clear failed", it) }
    }

    fun clearIfActivity(activity: Activity) {
        runCatching {
            val decor = activity.window?.decorView
            val host = hostDecorRef?.get()
            val target = targetRef?.get()
            val belongs =
                (host != null && decor != null && host === decor) ||
                    (target != null && ModuleDialogUi.activityOf(target.context) === activity)
            if (belongs) {
                clearInternal()
            }
        }.onFailure { GvLog.e(TAG, "clearIfActivity failed", it) }
    }

    /** 目标仍附着时刷新位置（布局变化 / resume） */
    fun update() {
        runCatching {
            if (!EditMode.isEnabled()) {
                clearInternal()
                return
            }
            val target = targetRef?.get()
            if (target == null || !target.isAttachedToWindow) {
                clearInternal()
                return
            }
            updateOverlayBounds()
        }.onFailure { GvLog.e(TAG, "update failed", it) }
    }

    private fun clearInternal() {
        detachTracking()
        val overlay = overlayRef?.get()
        val parent = overlay?.parent as? ViewGroup
        if (overlay != null && parent != null) {
            parent.removeView(overlay)
        }
        overlayRef = null
        targetRef = null
        hostDecorRef = null
    }

    private fun attachTracking(target: View) {
        val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateOverlayBounds()
        }
        layoutListener = layout
        target.addOnLayoutChangeListener(layout)

        val preDraw = ViewTreeObserver.OnPreDrawListener {
            // 滚动 / 动画位移时 layout 不一定变，用 preDraw 轻量对齐
            if (EditMode.isEnabled() && targetRef?.get() != null) {
                updateOverlayBounds()
            } else if (!EditMode.isEnabled()) {
                clearInternal()
            }
            true
        }
        preDrawListener = preDraw
        runCatching {
            target.viewTreeObserver.addOnPreDrawListener(preDraw)
        }
    }

    private fun detachTracking() {
        val target = targetRef?.get()
        layoutListener?.let { listener ->
            target?.removeOnLayoutChangeListener(listener)
        }
        layoutListener = null
        preDrawListener?.let { listener ->
            runCatching {
                val t = target ?: return@let
                if (t.viewTreeObserver.isAlive) {
                    t.viewTreeObserver.removeOnPreDrawListener(listener)
                }
            }
        }
        preDrawListener = null
    }

    private fun updateOverlayBounds() {
        val target = targetRef?.get() ?: run {
            clearInternal()
            return
        }
        val overlay = overlayRef?.get() ?: run {
            clearInternal()
            return
        }
        if (!target.isAttachedToWindow) {
            clearInternal()
            return
        }
        // 目标被隐藏（GONE/INVISIBLE）后高亮立即失效：
        // GONE 的 View 坐标停留在最后布局值，兄弟控件顶上来会让蓝框盖住别的控件
        if (target.visibility != View.VISIBLE) {
            GvLog.d(TAG, "target not visible, clear highlight")
            clearInternal()
            return
        }
        // 若目标换了 window（少见），重建
        val decor = target.rootView as? ViewGroup
        if (decor == null) {
            clearInternal()
            return
        }
        if (overlay.parent !== decor) {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            decor.addView(
                overlay,
                ViewGroup.LayoutParams(
                    target.width.coerceAtLeast(1),
                    target.height.coerceAtLeast(1),
                ),
            )
            hostDecorRef = WeakReference(decor)
            runCatching { overlay.bringToFront() }
        }

        val loc = IntArray(2)
        target.getLocationInWindow(loc)
        val w = target.width.coerceAtLeast(1)
        val h = target.height.coerceAtLeast(1)
        val lp = overlay.layoutParams
        if (lp != null && (lp.width != w || lp.height != h)) {
            lp.width = w
            lp.height = h
            overlay.layoutParams = lp
        }
        // x/y 相对 parent（decor）原点，与 getLocationInWindow 一致
        val nx = loc[0].toFloat()
        val ny = loc[1].toFloat()
        if (overlay.x != nx) overlay.x = nx
        if (overlay.y != ny) overlay.y = ny
    }

    private fun buildHighlightDrawable(host: View): GradientDrawable {
        val metrics = host.resources.displayMetrics
        val strokePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            STROKE_DP,
            metrics,
        ).toInt().coerceAtLeast(2)
        val cornerPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            CORNER_DP,
            metrics,
        )
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(colorOr(R.color.edit_selection_fill, FALLBACK_FILL))
            setStroke(strokePx, colorOr(R.color.edit_selection_stroke, FALLBACK_STROKE))
            cornerRadius = cornerPx
        }
    }

    private fun colorOr(resId: Int, fallback: Int): Int {
        if (!ModuleRes.isModuleResReady()) return fallback
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                moduleRes.getColor(resId, null)
            } else {
                @Suppress("DEPRECATION")
                moduleRes.getColor(resId)
            }
        }.getOrDefault(fallback)
    }
}
