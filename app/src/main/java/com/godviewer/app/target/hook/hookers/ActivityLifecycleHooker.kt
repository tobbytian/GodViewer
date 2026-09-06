package com.godviewer.app.target.hook.hookers

import android.app.Activity
import android.view.ViewTreeObserver
import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.dialog.ModuleDialogUi
import com.godviewer.app.target.edit.SelectedViewHighlight
import com.godviewer.app.target.hook.GvHook
import com.godviewer.app.target.hook.GvMethodHook
import com.godviewer.app.target.hook.IHooker
import com.godviewer.app.target.hook.MethodHookParam
import com.godviewer.app.target.rule.ViewRuleManager
import com.godviewer.app.target.rule.findViewBestMatch
import java.util.Collections
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
        private const val TAG = "Lifecycle"

        /** 最近一次 onPostResume 的 Activity（撤销时用于回放当前界面的视图） */
        @Volatile
        private var resumedActivity: Activity? = null

        /** 存活（已 onPostResume 且未 onDestroy）的 Activity，删除规则时用于还原各 Activity 中的视图 */
        private val liveActivities =
            Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

        fun resumedActivity(): Activity? = resumedActivity

        fun liveActivities(): Set<Activity> = liveActivities
    }

    override fun onHook() {
        GvHook.findAndHookMethod(
            Activity::class.java,
            "onPostResume",
            object : GvMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // 绝不能让规则重放 / window 访问异常拖垮目标 Activity
                    runCatching {
                        val activity = param.thisObject as? Activity ?: return
                        GvLog.d(TAG, "onPostResume ${activity.javaClass.name}")
                        resumedActivity = activity
                        liveActivities.add(activity)
                        // 弹窗 BadToken 回退：View.context 不是 Activity 时用
                        ModuleDialogUi.noteResumedActivity(activity)
                        replay(activity, captureThumbs = true)
                        registerLayoutListener(activity)
                    }.onFailure {
                        GvLog.e(TAG, "onPostResume hook failed", it)
                    }
                }
            },
        )
        GvHook.findAndHookMethod(
            Activity::class.java,
            "onDestroy",
            object : GvMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val activity = param.thisObject as? Activity ?: return
                        if (resumedActivity === activity) {
                            resumedActivity = null
                            ModuleDialogUi.noteResumedActivity(null)
                        }
                        liveActivities.remove(activity)
                        unregisterLayoutListener(activity)
                        SelectedViewHighlight.clearIfActivity(activity)
                    }.onFailure {
                        GvLog.e(TAG, "onDestroy hook failed", it)
                    }
                }
            },
        )
    }

    private fun registerLayoutListener(activity: Activity) {
        if (layoutListeners.containsKey(activity)) {
            return
        }
        val decor = runCatching { activity.window?.decorView }.getOrNull() ?: return
        val vto = runCatching { decor.viewTreeObserver }.getOrNull() ?: return
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            runCatching { replay(activity, captureThumbs = false) }
                .onFailure { GvLog.e(TAG, "layout replay failed", it) }
        }
        runCatching { vto.addOnGlobalLayoutListener(listener) }
            .onSuccess { layoutListeners[activity] = listener }
            .onFailure { GvLog.e(TAG, "addOnGlobalLayoutListener failed", it) }
    }

    private fun unregisterLayoutListener(activity: Activity) {
        layoutListeners.remove(activity)?.let { listener ->
            runCatching {
                activity.window?.decorView?.viewTreeObserver
                    ?.removeOnGlobalLayoutListener(listener)
            }
        }
    }

    private fun replay(activity: Activity, captureThumbs: Boolean = true) {
        val activityClass = runCatching { activity.componentName?.className }.getOrNull()
            ?: return
        val rules = runCatching { ViewRuleManager.rulesForActivity(activityClass) }
            .getOrDefault(emptyList())
        if (rules.isEmpty()) return
        for (rule in rules) {
            runCatching {
                val view = findViewBestMatch(activity, rule) ?: return@runCatching
                ViewRuleManager.applyRuleToView(view, rule)
                if (captureThumbs) {
                    // 仅 Activity 恢复时补抓一次缩略图（已有则跳过）；布局过程中跳过，
                    // 避免地图类等高频重布局场景每帧重抓导致掉帧。
                    ViewRuleManager.captureThumbnail(view, rule)
                }
            }.onFailure {
                GvLog.e(TAG, "apply rule failed key=${rule.key()}", it)
            }
        }
    }
}
