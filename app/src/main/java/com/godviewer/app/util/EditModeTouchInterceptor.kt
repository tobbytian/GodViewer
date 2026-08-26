package com.godviewer.app.util

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.godviewer.app.IGNORE_HOOK
import com.godviewer.app.ViewDispatcher
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/**
 * Window-level touch gate for edit mode.
 *
 * Why: wrapping only [View.OnClickListener] misses non-clickable views, RecyclerView
 * rows, custom touch handlers, etc. When edit mode is on, a short tap is intercepted
 * here, the top-most visible view under the finger is resolved, and dispatched to the
 * attribute dialog — regardless of whether that view has a click listener.
 */
object EditModeTouchInterceptor {
    private val downByActivity = WeakHashMap<Activity, DownState>()
    private var hooked = false

    private data class DownState(
        val downTime: Long,
        val rawX: Float,
        val rawY: Float,
        val pointerId: Int,
    )

    fun install() {
        if (hooked) return
        hooked = true
        val method = XposedHelpers.findMethodBestMatch(
            Activity::class.java,
            "dispatchTouchEvent",
            MotionEvent::class.java,
        )
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!EditMode.isEnabled()) return
                val activity = param.thisObject as? Activity ?: return
                val event = param.args.getOrNull(0) as? MotionEvent ?: return
                if (handle(activity, event)) {
                    param.result = true
                }
            }
        })
    }

    /**
     * After edit mode turns on, make the current hierarchy clickable and re-wrap listeners
     * so the legacy path still works for normal buttons.
     */
    fun refreshActivity(activity: Activity?, forceClickable: Boolean = true) {
        val decor = activity?.window?.decorView as? ViewGroup ?: return
        runCatching {
            decor.setGlobalHookClick(
                enabled = true,
                traversalChildren = true,
                forceClickable = forceClickable && EditMode.isEnabled(),
            )
        }
    }

    private fun handle(activity: Activity, event: MotionEvent): Boolean {
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
                val dx = kotlin.math.abs(event.getRawX(idx) - down.rawX)
                val dy = kotlin.math.abs(event.getRawY(idx) - down.rawY)
                if (dx > slop || dy > slop) {
                    // Treat as scroll / drag — do not intercept.
                    downByActivity.remove(activity)
                }
                return false
            }
            MotionEvent.ACTION_UP -> {
                val down = downByActivity.remove(activity) ?: return false
                val idx = event.findPointerIndex(down.pointerId)
                if (idx < 0) return false
                val dx = kotlin.math.abs(event.getRawX(idx) - down.rawX)
                val dy = kotlin.math.abs(event.getRawY(idx) - down.rawY)
                if (dx > slop || dy > slop) return false
                // Long-press reentry gesture uses blank-area hold; keep short taps only.
                if (event.eventTime - down.downTime > 700L) return false

                val target = findTopmostView(
                    root = activity.window.decorView,
                    rawX = event.getRawX(idx),
                    rawY = event.getRawY(idx),
                ) ?: return false
                if (target.tag == IGNORE_HOOK) return false
                // Don't steal our own dialog chrome if somehow reached.
                if (isInsideGodViewerUi(target)) return false

                val handled = runCatching { ViewDispatcher.dispatch(target) }.getOrDefault(false)
                return handled
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

    private fun isInsideGodViewerUi(view: View): Boolean {
        var cur: View? = view
        while (cur != null) {
            if (cur.tag == IGNORE_HOOK) return true
            val p = cur.parent
            cur = p as? View
        }
        return false
    }

    private fun findTopmostView(root: View, rawX: Float, rawY: Float): View? {
        if (!pointInView(root, rawX, rawY)) return null
        if (root is ViewGroup) {
            // Front-most child first.
            for (i in root.childCount - 1 downTo 0) {
                val child = root.getChildAt(i)
                if (child.visibility != View.VISIBLE || child.alpha <= 0.01f) continue
                val hit = findTopmostView(child, rawX, rawY)
                if (hit != null) return hit
            }
        }
        return root
    }

    private fun pointInView(view: View, rawX: Float, rawY: Float): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val left = loc[0].toFloat()
        val top = loc[1].toFloat()
        val right = left + view.width
        val bottom = top + view.height
        return rawX >= left && rawX < right && rawY >= top && rawY < bottom
    }
}
