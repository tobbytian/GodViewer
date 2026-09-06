package com.godviewer.app.target.edit

import android.app.Activity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.godviewer.app.shared.APP_FIELD_FORCE_CLICKABLE
import com.godviewer.app.shared.AndroidAppCompat
import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.dispatch.ViewDispatcher
import com.godviewer.app.target.hook.GvHook
import com.godviewer.app.target.hook.GvMethodHook
import com.godviewer.app.target.hook.MethodHookParam
import com.godviewer.app.target.hook.hookers.ClickListenerHook
import com.godviewer.app.target.hook.hookers.PopupWindowClickHook
import com.godviewer.app.target.util.findMethodBestMatch
import com.godviewer.app.target.util.getInjectedField
import com.godviewer.app.target.util.setGlobalHookClick
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * 仅在「编辑模式开启」时介入触摸与 click 包装。
 *
 * 仅勾选 LSPosed、未开编辑：不 hook dispatchTouchEvent / setOnClickListener / Popup，
 * 不遍历 View 树，不创建 ListenerInfo —— 目标应用触摸应与未注入一致。
 *
 * 命中使用 [ViewHitUtil]，跳过淘宝 WaterMask 等全屏穿透遮罩。
 */
object EditModeTouchInterceptor {
    private val downByActivity = WeakHashMap<Activity, DownState>()
    private var touchHooked = false

    private const val TAG = "Touch"

    private data class DownState(
        val downTime: Long,
        val rawX: Float,
        val rawY: Float,
        val pointerId: Int,
    )

    /** 首次开启编辑时安装全部与编辑相关的 Xposed hook */
    fun installIfNeeded() {
        GvLog.i(TAG, "installIfNeeded click+popup+dispatchTouch")
        ClickListenerHook.installIfNeeded()
        PopupWindowClickHook.installIfNeeded()
        installTouchHookIfNeeded()
    }

    private fun installTouchHookIfNeeded() {
        if (touchHooked) {
            GvLog.d(TAG, "dispatchTouchEvent already hooked")
            return
        }
        val handle = GvHook.findAndHookMethod(
            Activity::class.java,
            "dispatchTouchEvent",
            object : GvMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // 退出编辑后 hook 仍在：必须首行返回，零额外逻辑
                    if (!EditMode.isEnabled()) return
                    try {
                        val activity = param.thisObject as? Activity ?: return
                        val event = param.args.getOrNull(0) as? MotionEvent ?: return
                        if (handleEditTap(activity, event)) {
                            // 跳过原方法，直接消费本次触摸
                            param.setResult(true)
                        }
                    } catch (t: Throwable) {
                        GvLog.e(TAG, "dispatchTouchEvent hook body failed", t)
                    }
                }
            },
            MotionEvent::class.java,
        )
        if (handle != null) {
            touchHooked = true
            GvLog.i(TAG, "dispatchTouchEvent hooked (active only while edit on)")
        }
    }

    /**
     * 同步当前 Activity 的 click 包装状态。
     * 编辑关：仅 unwrap 已有 wrapper；编辑开：wrap + 可选 forceClickable。
     */
    fun refreshActivity(activity: Activity?, forceClickable: Boolean = true) {
        val decor = activity?.window?.decorView as? ViewGroup
        if (decor == null) {
            GvLog.w(TAG, "refreshActivity skip: no decor activity=$activity")
            return
        }
        val editOn = EditMode.isEnabled()
        if (editOn) {
            installIfNeeded()
        }
        val userForce = if (editOn) {
            runCatching {
                AndroidAppCompat.currentApplication()
                    ?.getInjectedField(APP_FIELD_FORCE_CLICKABLE, false) ?: false
            }.getOrDefault(false)
        } else {
            false
        }
        val force = editOn && (forceClickable || userForce)
        GvLog.d(
            TAG,
            "refreshActivity ${activity?.javaClass?.simpleName} edit=$editOn wrap=$editOn force=$force",
        )
        runCatching {
            decor.setGlobalHookClick(
                enabled = editOn,
                traversalChildren = true,
                forceClickable = force,
            )
        }.onFailure { GvLog.e(TAG, "setGlobalHookClick failed", it) }
        if (!editOn) {
            downByActivity.clear()
            runCatching { SelectedViewHighlight.clear() }
        } else {
            runCatching { SelectedViewHighlight.update() }
        }
    }

    private fun handleEditTap(activity: Activity, event: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downByActivity[activity] = DownState(
                    downTime = event.eventTime,
                    rawX = event.rawX,
                    rawY = event.rawY,
                    pointerId = event.getPointerId(0),
                )
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_MOVE,
            -> {
                val down = downByActivity[activity] ?: return false
                val idx = event.findPointerIndex(down.pointerId)
                if (idx < 0) {
                    downByActivity.remove(activity)
                    return false
                }
                if (abs(event.getRawX(idx) - down.rawX) > slop ||
                    abs(event.getRawY(idx) - down.rawY) > slop
                ) {
                    downByActivity.remove(activity)
                }
                return false
            }
            MotionEvent.ACTION_UP -> {
                val down = downByActivity.remove(activity) ?: return false
                val idx = event.findPointerIndex(down.pointerId)
                if (idx < 0) return false
                if (abs(event.getRawX(idx) - down.rawX) > slop ||
                    abs(event.getRawY(idx) - down.rawY) > slop
                ) {
                    return false
                }
                if (event.eventTime - down.downTime > 700L) return false

                // 跳过 WaterMask 等穿透层，优先命中下方真实控件
                val target = ViewHitUtil.findEditTarget(
                    root = activity.window.decorView,
                    rawX = event.getRawX(idx),
                    rawY = event.getRawY(idx),
                ) ?: return false
                GvLog.d(
                    TAG,
                    "edit tap -> ${target.javaClass.name} ${target.width}x${target.height}",
                )
                return runCatching { ViewDispatcher.dispatch(target) }.getOrDefault(false)
            }
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_UP,
            -> {
                downByActivity.remove(activity)
                return false
            }
        }
        return false
    }
}
