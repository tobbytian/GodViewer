package com.godviewer.app.target.rule

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.model.ViewRule

/**
 * repeatable（列表项）规则的布局期对账器。
 *
 * 不新增任何 Xposed hook：复用 Activity 每帧的 OnGlobalLayout 重放时机
 * （ActivityLifecycleHooker.replay 尾部调用 [reconcile]），对每条 repeatable 规则：
 *
 * 1. 单次遍历 decorView 收集所有 repeatable 规则的命中项（先命中者优先，
 *    [collectAllMatches]——每帧一次树遍历，不随规则数翻倍）；
 * 2. 扫描应用登记表，把"此前应用过、本次不再命中 / 已换主"的视图按各自基线还原
 *    （列表项回收复用后内容变了、或规则已删除/撤销）；
 * 3. 再应用本轮命中项（幂等）。
 *
 * 分工边界：hook 安装成功后（[RecyclerBindingHooker.isActive]），RecyclerView
 * 子树的 repeatable 规则由 bind 期精确应用（对账器跳过这些子树，不与之争抢
 * 登记表）；对账器保留 ListView 等无 bind hook 的列表容器 + 非 RecyclerView
 * 场景的全量兜底。hook 部分安装失败时 isActive() 为 false，对账器恢复全覆盖。
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
            if (rules.isEmpty()) return
            val hookActive = ViewRuleApplier.bindHookActive
            // 本轮命中表（一视图只归先命中的规则所有）
            val matched = HashMap<View, ViewRule>()
            collectAllMatches(activity, rules, hookActive, matched)
            // 先还原：登记项不再命中（回收复用 / 规则删除撤销）或已换主。
            // hook 管辖（epoch > 0）的登记项归 RecyclerBindingHooker 的
            // discard/revoke 语义管，对账器不触碰
            for ((view, key) in ViewRuleApplier.appliedRepeatableEntries()) {
                if (ViewRuleApplier.epochOf(view) != ViewRuleApplier.EPOCH_RECONCILER) continue
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
     * 只处理登记表内的视图——登记表即本进程应用效果的唯一事实来源；
     * 含 hook 管辖的登记项（规则删除时两者都要还原）。
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

    /**
     * 单次遍历收集全部 repeatable 规则的命中项。[hookActive] 时跳过 RecyclerView
     * 子树（其规则归 bind hook 管辖），避免两个写者争抢同一登记表。
     */
    private fun collectAllMatches(
        activity: Activity,
        rules: List<ViewRule>,
        hookActive: Boolean,
        matched: HashMap<View, ViewRule>,
    ) {
        val decor = runCatching { activity.window?.decorView }.getOrNull() ?: return
        val recyclerClass = listContainerClassesOf(decor).first
        collect(decor, rules, hookActive, recyclerClass, matched)
    }

    private fun collect(
        view: View,
        rules: List<ViewRule>,
        hookActive: Boolean,
        recyclerClass: Class<*>?,
        matched: HashMap<View, ViewRule>,
    ) {
        if (matched.size >= MAX_TOTAL_REPEATABLE_MATCHES) return
        if (hookActive && recyclerClass != null && recyclerClass.isInstance(view)) {
            // RecyclerView 子树让位 bind hook（其内 item 的规则由绑定期精确应用）
            return
        }
        for (rule in rules) {
            if (matchRepeatableView(rule, view)) {
                matched.putIfAbsent(view, rule)
                break
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collect(view.getChildAt(i), rules, hookActive, recyclerClass, matched)
            }
        }
    }

    /** 对账器单次遍历的总命中上限（多规则共享，防极端树规模拖垮布局帧） */
    private const val MAX_TOTAL_REPEATABLE_MATCHES = 300
}
