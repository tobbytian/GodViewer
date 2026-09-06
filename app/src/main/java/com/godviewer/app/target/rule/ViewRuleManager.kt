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

    /** 查找已存在的规则（对话框打开时判断是否已有规则） */
    fun findRule(view: View): ViewRule? {
        val activity = getAttachedActivityFromView(view) ?: return null
        val key = ViewRule.RuleKey(
            activity.componentName.className,
            getViewHierarchyDepth(view),
            view.javaClass.name
        )
        return rules.firstOrNull { it.key() == key }
    }

    /**
     * 为视图创建一个规则外壳（原始值 = 当前值，修改值 = 当前值，changed* 全 false）。
     * 视图不在 Activity 窗口（如对话框 / Popup 内部）或找不到 Activity 时返回 null。
     */
    fun createRule(view: View): ViewRule? {
        val activity = getAttachedActivityFromView(view) ?: return null
        if (!isInActivityWindow(view, activity)) {
            return null
        }
        val snapshot = ViewRuleApplier.captureSnapshot(view)
        val rule = ViewRule(
            packageName = activity.packageName,
            matchVersionCode = versionCode(activity),
            activityClass = activity.componentName.className,
            viewClass = view.javaClass.name,
            depth = getViewHierarchyDepth(view),
            resourceName = resourceNameOf(view),
            text = (view as? TextView)?.text?.toString(),
            description = view.contentDescription?.toString(),
            original = snapshot,
            modified = snapshot,
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

    /** 保存（或更新）一条规则 */
    fun saveRule(rule: ViewRule) {
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
    }

    /** 删除一条规则 */
    fun deleteRule(rule: ViewRule) {
        pushUndoState()
        ViewRuleApplier.forgetAppliedImage(rule)
        ViewRuleThumbnails.remove(rule)
        rules = rules.filterNot { it.key() == rule.key() }
        store?.save(rules)
        GvLog.d(TAG, "rule deleted: ${rule.key()}")
    }

    /**
     * 删除一条规则；先在给定各 Activity 中还原该规则关联的视图（找不到则跳过）。
     */
    fun deleteRule(rule: ViewRule, restoreIn: Collection<Activity>) {
        for (activity in restoreIn) {
            findViewBestMatch(activity, rule)?.let { view ->
                ViewRuleApplier.restoreView(view, rule)
            }
        }
        deleteRule(rule)
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
        activity?.let { act ->
            for (rule in previous) {
                findViewBestMatch(act, rule)?.let { view ->
                    ViewRuleApplier.restoreView(view, rule)
                    ViewRuleApplier.applyRuleToView(view, rule)
                }
            }
            for (rule in current) {
                if (previous.none { it.key() == rule.key() }) {
                    findViewBestMatch(act, rule)?.let { view ->
                        ViewRuleApplier.restoreView(view, rule)
                    }
                }
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
