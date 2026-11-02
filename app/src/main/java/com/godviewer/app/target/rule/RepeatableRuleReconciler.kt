package com.godviewer.app.target.rule

import android.app.Activity
import android.view.View
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.model.ViewRule

/**
 * repeatable（列表项）规则的布局期对账器。
 *
 * 不新增任何 Xposed hook：复用 Activity 每帧的 OnGlobalLayout 重放时机
 * （ActivityLifecycleHooker.replay 尾部调用 [reconcile]），对每条 repeatable 规则：
 *
 * 1. 收集当前命中的同类项（[findRepeatableMatches]，先命中者优先）；
 * 2. 扫描应用登记表，把"此前应用过、本次不再命中 / 已换主"的视图按各自基线还原
 *    （列表项回收复用后内容变了、或规则已删除/撤销）；
 * 3. 再应用本轮命中项（幂等）。
 *
 * 全程 runCatching 隔离，任何异常只丢一次对账，绝不影响宿主应用。
 */
internal object RepeatableRuleReconciler {

    private const val TAG = "Rule"

    /** 单个 Activity 的一次对账 */
    fun reconcile(activity: Activity) {
        runCatching {
            val activityClass = activity.componentName?.className ?: return
            val rules = ViewRuleManager.rulesForActivity(activityClass).filter { it.repeatable }
            // 本轮命中表（一视图只归先命中的规则所有）
            val matched = HashMap<View, ViewRule>()
            for (rule in rules) {
                for (view in findRepeatableMatches(activity, rule)) {
                    matched.putIfAbsent(view, rule)
                }
            }
            // 先还原：登记项不再命中（回收复用 / 规则删除撤销）或已换主
            for ((view, key) in ViewRuleApplier.appliedRepeatableEntries()) {
                val newRule = matched[view]
                if (newRule == null || newRule.key() != key) {
                    ViewRuleApplier.restoreRepeatable(view, key)
                }
            }
            // 再应用：命中且未被其他规则占用（applyRepeatable 内部幂等）
            for ((view, rule) in matched) {
                ViewRuleApplier.applyRepeatable(view, rule)
            }
        }.onFailure {
            GvLog.e(TAG, "repeatable reconcile failed activity=${activity.javaClass.name}", it)
        }
    }

    /**
     * 还原一条 repeatable 规则的全部已应用效果（删除 / 撤销规则时调用）。
     * 只处理登记表内的视图——登记表即本进程应用效果的唯一事实来源。
     */
    fun restoreRule(rule: ViewRule) {
        if (!rule.repeatable) return
        runCatching {
            for (view in ViewRuleApplier.appliedViewsOf(rule.key())) {
                ViewRuleApplier.restoreRepeatable(view, rule.key())
            }
        }.onFailure {
            GvLog.e(TAG, "restoreRule failed key=${rule.key()}", it)
        }
    }
}
