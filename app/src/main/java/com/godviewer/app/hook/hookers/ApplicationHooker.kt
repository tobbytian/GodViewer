package com.godviewer.app.hook.hookers

import android.app.Activity
import android.app.AndroidAppHelper
import android.app.Application
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.godviewer.app.data.ViewRuleManager
import com.godviewer.app.hook.IHooker
import com.godviewer.app.util.*
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * @author hhvvg
 *
 * Hooks application.
 */
class ApplicationHooker : IHooker {
    override fun onHook(param: XC_LoadPackage.LoadPackageParam) {
        val appClazz = Application::class.java
        val onCreateHook = ApplicationOnCreateMethodHook()
        val method = XposedHelpers.findMethodBestMatch(appClazz, "onCreate", arrayOf(), arrayOf())
        XposedBridge.hookMethod(method, onCreateHook)
    }

    private class ApplicationOnCreateMethodHook : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam?) {
            if (param == null) {
                return
            }
            val app = AndroidAppHelper.currentApplication()
            // 加载持久化规则（目标应用自身数据目录）
            ViewRuleManager.init(app)
            // 编辑模式状态（每次启动默认开启，退出只影响本次运行）
            EditMode.init(app)
            // 通知栏编辑模式开关（注册切换接收器 + 发布常驻通知）
            EditModeNotification.init(app)
            val appClazz = app::class.java
            val callback = XposedHelpers.findField(appClazz, "mActivityLifecycleCallbacks")
            val callbackArray =
                callback.get(app) as ArrayList<Application.ActivityLifecycleCallbacks>
            callbackArray.add(ActivityCallback())
        }
    }

    private class ActivityCallback : Application.ActivityLifecycleCallbacks {
        override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
            val contentView = activity.window.decorView as ViewGroup
            contentView.viewTreeObserver.addOnGlobalLayoutListener {
                val app = AndroidAppHelper.currentApplication()
                val showBounds = app.getInjectedField(APP_FIELD_SHOW_BOUNDS, false) ?: false
                val forceClickable = app.getInjectedField(APP_FIELD_FORCE_CLICKABLE, false) ?: false
                contentView.drawLayoutBounds(showBounds, true)
                contentView.setGlobalHookClick(
                    enabled = true,
                    traversalChildren = true,
                    forceClickable
                )
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            // Do nothing
        }

        override fun onActivityStarted(activity: Activity) {
            // Do nothing
        }

        override fun onActivityResumed(activity: Activity) {
            val app = AndroidAppHelper.currentApplication()
            val showBounds = app.getInjectedField(APP_FIELD_SHOW_BOUNDS, false) ?: false
            val forceClickable = app.getInjectedField(APP_FIELD_FORCE_CLICKABLE, false) ?: false
            val decor = activity.window.decorView as ViewGroup
            decor.drawLayoutBounds(showBounds, true)
            decor.setGlobalHookClick(enabled = true, traversalChildren = true, forceClickable)
            decor.attachEditModeReentryGesture()
            // 每次界面恢复都刷新通知（状态变化后同步；被划掉也会回来）
            EditModeNotification.post(app)
        }

        override fun onActivityPaused(activity: Activity) {
            // Do nothing
        }

        override fun onActivityStopped(activity: Activity) {
            // Do nothing
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
            // Do nothing
        }

        override fun onActivityDestroyed(activity: Activity) {
            // Do nothing
        }

    }
}

private const val TAG_EDIT_MODE_GESTURE = "GODVIEWER_EDIT_MODE_GESTURE"
private const val GESTURE_LONG_PRESS_MS = 1000L

/**
 * 编辑模式关闭时重新进入的入口：长按未被任何子 View 消费的空白处（≥1s 且无明显位移）。
 * 按钮、滚动、文本选择等交互区域不会触发，避免与目标应用交互冲突。
 */
private fun View.attachEditModeReentryGesture() {
    if (tag == TAG_EDIT_MODE_GESTURE) {
        return
    }
    tag = TAG_EDIT_MODE_GESTURE
    val ctx = context
    val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    var downTime = 0L
    var downX = 0f
    var downY = 0f
    setOnTouchListener { _, event ->
        if (EditMode.isEnabled()) {
            return@setOnTouchListener false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = event.eventTime
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (Math.abs(event.x - downX) > slop || Math.abs(event.y - downY) > slop) {
                    downTime = 0L
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (downTime != 0L && event.eventTime - downTime >= GESTURE_LONG_PRESS_MS) {
                    EditMode.setEnabled(true)
                }
                downTime = 0L
            }
        }
        false
    }
}