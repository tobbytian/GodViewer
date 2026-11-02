package com.godviewer.app.target.rule

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.view.View
import android.widget.TextView
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.model.ViewRule
import com.godviewer.app.target.rule.findViewBestMatch
import com.godviewer.app.target.rule.getAttachedActivityFromView
import com.godviewer.app.target.rule.getViewHierarchyDepth
import com.godviewer.app.target.rule.isInActivityWindow
import com.godviewer.app.target.rule.resourceNameOf
import com.godviewer.app.target.rule.versionCode

/**
 * 持久化规则管理（单例，运行在被注入的目标进程内）— 薄 facade + 编排。
 *
 * 实现按职责拆到：
 * - [RuleStore] — JSON 落盘
 * - [ViewRuleThumbnails] — 缩略图内存/磁盘
 * - [ViewRuleApplier] — 快照应用到 View / 还原
 *
 * 对外 API 保持不变。第二阶段迁包时整体预期落入 `target`。
 */
object ViewRuleManager {

    private const val TAG = "Rule"

    /** 撤销栈深度上限（内存态，进程重启即清空） */
    private const val MAX_UNDO_STEPS = 10

    @Volatile
    private var initialized = false
    private var store: RuleStore? = null

    @Volatile
    private var rules: List<ViewRule> = emptyList()

    /**
     * 内存撤销栈：每次 [saveRule] / [deleteRule] 前压入当前规则列表，
     * [undoLastOperation] 出栈恢复上一步状态（规则数据 + 持久化 + 视图回放）。
     */
    private val undoStack = ArrayDeque<List<ViewRule>>()

    fun init(application: Application) {
        if (initialized) {
            return
        }
        // 先置位，避免 init 中异常导致反复进入；失败时 rules 保持空列表
        initialized = true
        runCatching {
            ViewRuleThumbnails.attach(application.applicationContext)
            val ruleStore = RuleStore(application.applicationContext)
            store = ruleStore
            rules = ruleStore.load()
            GvLog.d(TAG, "rules loaded: ${rules.size}")
        }.onFailure {
            rules = emptyList()
            GvLog.e(TAG, "ViewRuleManager.init failed; continue with empty rules", it)
        }
    }

    /** 某 Activity 的全部规则（重放时使用） */
    fun rulesForActivity(activityClass: String): List<ViewRule> =
        rules.filter { it.activityClass == activityClass }

    /**
     * 统一的规则键计算：视图在列表项内（RecyclerView/ListView 直接子项树中）时用
     * repeatable 键（depth = 自 item 根的路径），否则用绝对 depth 键。
     * [createRule] 与 [findRule] 共用，保证创建与查找的键一致。
     *
     * @return 键 + item 根（不在列表内时为 null）
     */
    private fun ruleKeyFor(activity: Activity, view: View): Pair<ViewRule.RuleKey, View?> {
        val anchor = listItemAnchor(view)
        val depth = if (anchor != null) {
            pathWithin(view, anchor) ?: getViewHierarchyDepth(view)
        } else {
            getViewHierarchyDepth(view)
        }
        return ViewRule.RuleKey(activity.componentName.className, depth, view.javaClass.name) to anchor
    }

    /** 查找已存在的规则（对话框打开时判断是否已有规则） */
    fun findRule(view: View): ViewRule? {
        val activity = getAttachedActivityFromView(view) ?: return null
        val (key, _) = ruleKeyFor(activity, view)
        return rules.firstOrNull { it.key() == key }
    }

    /**
     * 为视图创建一个规则外壳（原始值 = 当前值，修改值 = 当前值，changed* 全 false）。
     *
     * 视图在列表项内时自动创建 repeatable 规则（对该 Activity 内所有同类项生效，
     * depth 语义变为自 item 根的路径）；否则为普通规则。
     * 视图不在 Activity 窗口（如对话框 / Popup 内部）或找不到 Activity 时返回 null。
     */
    fun createRule(view: View): ViewRule? {
        val activity = getAttachedActivityFromView(view) ?: return null
        if (!isInActivityWindow(view, activity)) {
            return null
        }
        val snapshot = ViewRuleApplier.captureSnapshot(view)
        val (key, anchor) = ruleKeyFor(activity, view)
        val rule = ViewRule(
            packageName = activity.packageName,
            matchVersionCode = versionCode(activity),
            activityClass = activity.componentName.className,
            viewClass = view.javaClass.name,
            depth = key.depth,
            resourceName = resourceNameOf(view),
            text = (view as? TextView)?.text?.toString(),
            description = view.contentDescription?.toString(),
            original = snapshot,
            modified = snapshot,
            repeatable = anchor != null,
            itemRootClass = anchor?.javaClass?.name,
            timestamp = System.currentTimeMillis()
        )
        // 视图此刻仍可见，截取缩略图供规则管理列表使用（与编辑弹窗预览一致），并持久化
        ViewRuleThumbnails.capture(view, rule)
        return rule
    }

    fun thumbnailFor(rule: ViewRule): Bitmap? = ViewRuleThumbnails.thumbnailFor(rule)

    fun captureThumbnail(view: View, rule: ViewRule) {
        ViewRuleThumbnails.capture(view, rule)
    }

    /**
     * 保存（或更新）一条规则。
     *
     * @param applyIn repeatable 规则保存后立即在该 Activity 内对账生效
     * （普通规则忽略此参数，维持"调用方自行 applyRuleToView"的旧流程）
     */
    fun saveRule(rule: ViewRule, applyIn: Activity? = null) {
        pushUndoState()
        rule.timestamp = System.currentTimeMillis()
        ViewRuleApplier.forgetAppliedImage(rule)
        val index = rules.indexOfFirst { it.key() == rule.key() }
        rules = if (index >= 0) {
            rules.toMutableList().apply { set(index, rule) }
        } else {
            rules + rule
        }
        store?.save(rules)
        GvLog.d(TAG, "rule saved: $rule")
        if (rule.repeatable && applyIn != null) {
            RepeatableRuleReconciler.reconcile(applyIn)
        }
    }

    /** 删除一条规则 */
    fun deleteRule(rule: ViewRule) {
        pushUndoState()
        ViewRuleApplier.forgetAppliedImage(rule)
        // repeatable 规则先按登记表还原全部同类项（findViewBestMatch 对 itemPath 无效）
        RepeatableRuleReconciler.restoreRule(rule)
        ViewRuleThumbnails.remove(rule)
        rules = rules.filterNot { it.key() == rule.key() }
        store?.save(rules)
        GvLog.d(TAG, "rule deleted: ${rule.key()}")
    }

    /**
     * 删除一条规则；先在给定各 Activity 中还原该规则关联的视图（找不到则跳过）。
     * repeatable 规则按登记表还原后再对存活 Activity 对账一次，让其余规则接管空出的视图。
     */
    fun deleteRule(rule: ViewRule, restoreIn: Collection<Activity>) {
        if (rule.repeatable) {
            RepeatableRuleReconciler.restoreRule(rule)
            for (activity in restoreIn) {
                RepeatableRuleReconciler.reconcile(activity)
            }
        } else {
            for (activity in restoreIn) {
                findViewBestMatch(activity, rule)?.let { view ->
                    ViewRuleApplier.restoreView(view, rule)
                }
            }
        }
        deleteRule(rule)
    }

    /**
     * 批量删除多条规则（宿主多选删除走此路径）：先在给定各 Activity 中还原关联视图，
     * 一次压入撤销栈、一次落盘（避免逐条 save 反复推镜像）。
     *
     * @param restoreIn 需要还原视图的存活 Activity
     * @return 实际删除的规则条数（仅按 key 匹配权威列表，[toDelete] 中不存在的忽略）
     */
    fun deleteRules(toDelete: Collection<ViewRule>, restoreIn: Collection<Activity>): Int {
        if (toDelete.isEmpty()) return 0
        val keys = toDelete.map { it.key() }.toSet()
        val removed = rules.filter { it.key() in keys }
        if (removed.isEmpty()) return 0
        pushUndoState()
        for (rule in removed) {
            if (rule.repeatable) {
                RepeatableRuleReconciler.restoreRule(rule)
            }
        }
        for (activity in restoreIn) {
            for (rule in removed) {
                if (rule.repeatable) continue
                findViewBestMatch(activity, rule)?.let { view ->
                    ViewRuleApplier.restoreView(view, rule)
                }
            }
        }
        for (rule in removed) {
            ViewRuleApplier.forgetAppliedImage(rule)
            ViewRuleThumbnails.remove(rule)
        }
        rules = rules.filterNot { it.key() in keys }
        store?.save(rules)
        if (removed.any { it.repeatable }) {
            for (activity in restoreIn) {
                RepeatableRuleReconciler.reconcile(activity)
            }
        }
        GvLog.i(TAG, "rules deleted count=${removed.size}")
        return removed.size
    }

    /**
     * 删除本进程（= 该应用）的全部规则：先在存活 Activity 中还原相关视图，
     * 再清空规则与缩略图并落盘（空规则会连带推送空镜像，宿主镜像目录随之清除）。
     *
     * 与单条删除一致：压入撤销栈，误删可用「撤销」恢复。
     *
     * @param restoreIn 需要还原视图的存活 Activity
     * @return 被删除的规则条数
     */
    fun deleteAllRules(restoreIn: Collection<Activity> = emptyList()): Int {
        if (rules.isEmpty()) {
            return 0
        }
        val removed = rules
        pushUndoState()
        for (rule in removed) {
            if (rule.repeatable) {
                RepeatableRuleReconciler.restoreRule(rule)
            }
        }
        for (activity in restoreIn) {
            for (rule in removed) {
                if (rule.repeatable) continue
                findViewBestMatch(activity, rule)?.let { view ->
                    ViewRuleApplier.restoreView(view, rule)
                }
            }
        }
        ViewRuleApplier.clearAppliedImages()
        for (rule in removed) {
            ViewRuleThumbnails.remove(rule)
        }
        rules = emptyList()
        store?.save(rules)
        if (removed.any { it.repeatable }) {
            for (activity in restoreIn) {
                RepeatableRuleReconciler.reconcile(activity)
            }
        }
        GvLog.i(TAG, "all rules deleted count=${removed.size}")
        return removed.size
    }

    /**
     * 批量 upsert（宿主编辑 / 导入下发）：按 key 替换或新增。入参规则原样保存
     * （不重写 timestamp，便于宿主回读镜像按全等确认）；对存活 Activity 先还原
     * 旧规则再应用新值，即时生效。一次压撤销栈、一次落盘（单次镜像回推）。
     *
     * 幂等：重复下发同一批规则结果不变（待同步队列重发安全）。
     *
     * @param incoming 已由调用方过滤为本包的规则
     * @param restoreIn 需要即时生效的存活 Activity
     * @return 实际合并的规则条数
     */
    fun upsertRules(
        incoming: List<ViewRule>,
        restoreIn: Collection<Activity> = emptyList(),
    ): Int {
        if (incoming.isEmpty()) return 0
        val oldByKey = rules.associateBy { it.key() }
        pushUndoState()
        val merged = rules.toMutableList()
        for (rule in incoming) {
            val index = merged.indexOfFirst { it.key() == rule.key() }
            if (index >= 0) {
                // 图片规则的应用态按旧实例记账，换新值前先作废
                ViewRuleApplier.forgetAppliedImage(merged[index])
                merged[index] = rule
            } else {
                merged.add(rule)
            }
        }
        rules = merged
        // repeatable 规则的换值/新增统一走对账（按每视图基线还原旧效果再应用新值）
        if (incoming.any { it.repeatable }) {
            for (activity in restoreIn) {
                RepeatableRuleReconciler.reconcile(activity)
            }
        }
        for (activity in restoreIn) {
            for (rule in incoming) {
                if (rule.repeatable) continue
                val view = findViewBestMatch(activity, rule) ?: continue
                oldByKey[rule.key()]?.let { ViewRuleApplier.restoreView(view, it) }
                ViewRuleApplier.applyRuleToView(view, rule)
            }
        }
        store?.save(rules)
        GvLog.i(TAG, "rules upserted count=${incoming.size}")
        return incoming.size
    }

    /** 当前全部规则（规则管理列表使用） */
    fun allRules(): List<ViewRule> = rules

    /** 是否有可撤销的操作 */
    fun canUndo(): Boolean = undoStack.isNotEmpty()

    private fun pushUndoState() {
        undoStack.addLast(rules)
        if (undoStack.size > MAX_UNDO_STEPS) {
            undoStack.removeFirst()
        }
    }

    /**
     * 撤销上一个规则操作：恢复上一步的规则列表并持久化；[activity] 非空时
     * 对当前界面的视图做精确回放。
     *
     * @return 是否成功撤销（撤销栈为空时返回 false）
     */
    fun undoLastOperation(activity: Activity?): Boolean {
        if (undoStack.isEmpty()) {
            return false
        }
        val previous = undoStack.removeLast()
        val current = rules
        rules = previous
        ViewRuleApplier.clearAppliedImages()
        store?.save(rules)
        if (activity != null) {
            val hasRepeatable = previous.any { it.repeatable } || current.any { it.repeatable }
            for (rule in previous) {
                // repeatable 规则由对账统一处理（findViewBestMatch 对 itemPath 无效）
                if (rule.repeatable) continue
                findViewBestMatch(activity, rule)?.let { view ->
                    ViewRuleApplier.restoreView(view, rule)
                    ViewRuleApplier.applyRuleToView(view, rule)
                }
            }
            for (rule in current) {
                if (rule.repeatable) continue
                if (previous.none { it.key() == rule.key() }) {
                    findViewBestMatch(activity, rule)?.let { view ->
                        ViewRuleApplier.restoreView(view, rule)
                    }
                }
            }
            // 撤销涉及的 repeatable 规则：对账一轮即可同时完成
            // "恢复回来的规则重新应用"与"被撤销规则的效果还原"
            if (hasRepeatable) {
                RepeatableRuleReconciler.reconcile(activity)
            }
        }
        GvLog.d(TAG, "rule undone: ${previous.size} rules restored")
        return true
    }

    fun applyRuleToView(view: View, rule: ViewRule): Boolean =
        ViewRuleApplier.applyRuleToView(view, rule)

    fun restoreView(view: View, rule: ViewRule) {
        ViewRuleApplier.restoreView(view, rule)
    }
}
