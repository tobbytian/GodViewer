package com.godviewer.app.hook.hookers

import android.app.Activity
import android.view.ViewTreeObserver
import com.godviewer.app.data.ViewRuleManager
import com.godviewer.app.hook.IHooker
import com.godviewer.app.util.findViewBestMatch
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.WeakHashMap

/**
 * 重放持久化规则（GodMode ActivityLifecycleHook 移植，只使用公共 API）：
 *
 * - hook Activity.onPostResume：Activity 恢复时应用该 Activity 的全部规则
 * - 每个 Activity 注册一次 onGlobalLayoutListener：布局变化（列表刷新、
 *   数据加载完成等）后重新应用规则
 * - onDestroy 时移除监听，WeakHashMap 防泄漏
 */
class ActivityLifecycleHooker : IHooker {

    private val layoutListeners = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()

    companion object {
        /** 最近一次 onPostResume 的 Activity（撤销时用于回放当前界面的视图） */
        @Volatile
        private var resumedActivity: Activity? = null

        fun resumedActivity(): Activity? = resumedActivity
    }

    override fun onHook(param: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "onPostResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    resumedActivity = activity
                    replay(activity)
                    registerLayoutListener(activity)
                }
            }
        )
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "onDestroy",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (resumedActivity === activity) {
                        resumedActivity = null
                    }
                    unregisterLayoutListener(activity)
                }
            }
        )
    }

    private fun registerLayoutListener(activity: Activity) {
        if (layoutListeners.containsKey(activity)) {
            return
        }
        val decor = activity.window.decorView
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            replay(activity)
        }
        decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
        layoutListeners[activity] = listener
    }

    private fun unregisterLayoutListener(activity: Activity) {
        layoutListeners.remove(activity)?.let { listener ->
            activity.window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }

    private fun replay(activity: Activity) {
        val rules = ViewRuleManager.rulesForActivity(activity.componentName.className)
        for (rule in rules) {
            val view = findViewBestMatch(activity, rule) ?: continue
            ViewRuleManager.applyRuleToView(view, rule)
        }
    }
}
