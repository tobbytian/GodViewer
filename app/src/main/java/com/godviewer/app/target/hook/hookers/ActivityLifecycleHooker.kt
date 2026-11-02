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
import com.godviewer.app.target.rule.RepeatableRuleReconciler
import com.godviewer.app.target.rule.ViewRuleManager
import com.godviewer.app.target.rule.findViewBestMatchAttempt
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
        var applied = 0
        for (rule in rules) {
            // repeatable（列表项）规则由尾部对账统一处理，depth 语义不同不能走绝对路径匹配
            if (rule.repeatable) continue
            runCatching {
                val attempt = findViewBestMatchAttempt(activity, rule)
                val view = attempt.view
                if (view == null) {
                    // 布局监听每帧都会跑：仅 Activity 恢复路径记 miss，避免刷屏。
                    // "重启后规则没生效"的第一嫌疑点就在这条日志里（reason 为诊断码）
                    if (captureThumbs) {
                        GvLog.d(
                            TAG,
                            "replay miss view key=${rule.key()} activity=$activityClass " +
                                "reason=${attempt.reason}",
                        )
                    }
                    return@runCatching
                }
                ViewRuleManager.applyRuleToView(view, rule)
                applied++
                if (captureThumbs) {
                    // 仅 Activity 恢复时补抓一次缩略图（已有则跳过）；布局过程中跳过，
                    // 避免地图类等高频重布局场景每帧重抓导致掉帧。
                    ViewRuleManager.captureThumbnail(view, rule)
                }
            }.onFailure {
                GvLog.e(TAG, "apply rule failed key=${rule.key()}", it)
            }
        }
        if (captureThumbs) {
            GvLog.d(TAG, "replay done activity=$activityClass applied=$applied/${rules.size}")
        }
        // repeatable（列表项）规则布局期对账：应用新命中的同类项、还原失效项。
        // 无 repeatable 规则时其内部直接返回，逐帧零开销。
        RepeatableRuleReconciler.reconcile(activity)
    }
}
