package com.godviewer.app.target.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.godviewer.app.IGNORE_HOOK
import com.godviewer.app.R
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.model.DialogRule
import com.godviewer.app.shared.model.ViewRule
import com.godviewer.app.target.dialog.ModuleDialogUi
import com.godviewer.app.target.hook.ModuleRes
import com.godviewer.app.target.hook.ModuleRes.moduleRes
import com.godviewer.app.target.hook.hookers.ActivityLifecycleHooker
import com.godviewer.app.target.rule.DialogRuleStore
import com.godviewer.app.target.rule.ViewRuleManager
import com.godviewer.app.target.ui.adapter.DialogRuleListAdapter
import com.godviewer.app.target.ui.adapter.RuleListAdapter
import com.godviewer.app.databinding.LayoutRuleDeleteConfirmBinding
import com.godviewer.app.databinding.LayoutRuleManagerDialogBinding

/**
 * 规则管理弹窗（悬浮于目标应用界面之上，非新 Activity）。
 *
 * 由通知栏「规则」按钮打开，顶部可在**视图规则 / 弹窗规则**间切换。
 * 视图规则：可单独删除（删除前确认）+ 勾选全选批量删除；删除时若视图位于
 * 当前 Activity 则先还原，删除会压入撤销栈，误删可用通知栏「撤销」恢复。
 * 弹窗规则：行点或删除按钮 → 确认后直接落盘删除（规则权威就在目标进程，
 * 连带回推镜像供宿主同步）。
 *
 * 使用 [ModuleDialogUi] 隔离目标应用主题。
 */
class RuleManagerDialog(context: Context) : AlertDialog(ModuleDialogUi.wrap(context)) {

    private val hostActivity: Activity? = ModuleDialogUi.activityOf(context)

    /** 勾选的规则（按 RuleKey），随列表刷新清理失效项 */
    private val selection = mutableSetOf<ViewRule.RuleKey>()

    /** 顶部类型切换：false = 视图规则（默认），true = 弹窗规则 */
    private var isDialogTab = false

    private val binding by lazy {
        LayoutRuleManagerDialogBinding.bind(
            ModuleDialogUi.inflate(this.context, R.layout.layout_rule_manager_dialog)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setTitle(moduleRes.getString(R.string.manage_rules))
        // 注入 UI 必须经 moduleRes 取字符串，XML 里只是英文兜底
        binding.selectAll.text = SpannableString(moduleRes.getString(R.string.select_all))
        binding.batchDelete.text = SpannableString(moduleRes.getString(R.string.delete_selected))
        binding.batchDelete.setOnClickListener { showBatchDeleteConfirm() }
        binding.tabViewRules.text =
            SpannableString(moduleRes.getString(R.string.view_rules_section))
        binding.tabDialogRules.text =
            SpannableString(moduleRes.getString(R.string.dialog_rules_section))
        binding.tabViewRules.setOnClickListener { switchTab(toDialog = false) }
        binding.tabDialogRules.setOnClickListener { switchTab(toDialog = true) }
        renderTabStyles()
        refreshList()
    }

    override fun setTitle(title: CharSequence?) {
        binding.title.text = SpannableString(title)
    }

    override fun show() {
        GvLog.i(TAG, "rule manager show begin")
        super.show()
        ModuleDialogUi.applyWindow(this, binding.root)
        // applyWindow 的 normalizeTree 会把 tab 文字统一刷成 dialogText，
        // 它 post 的二次归一化先入队，这里再 post 恢复选中态配色（同队列 FIFO）
        binding.root.post { renderTabStyles() }
        GvLog.d(TAG, "rule manager shown showing=$isShowing")
    }

    /** 顶部类型切换到 [toDialog]：重绘胶囊样式并按新类型重建列表 */
    private fun switchTab(toDialog: Boolean) {
        if (isDialogTab == toDialog) return
        isDialogTab = toDialog
        GvLog.d(TAG, "switch tab dialogTab=$toDialog")
        renderTabStyles()
        refreshList()
    }

    /** 选中 tab = 深色加粗 + 胶囊底；未选中 = 灰色常规、无底 */
    private fun renderTabStyles() {
        val pill = moduleRes.getDrawable(R.drawable.bg_rule_tab_selected, null)
        binding.tabViewRules.apply {
            background = if (isDialogTab) null else pill
            setTextColor(if (isDialogTab) TAB_COLOR_UNSELECTED else TAB_COLOR_SELECTED)
            typeface = if (isDialogTab) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        }
        binding.tabDialogRules.apply {
            background = if (isDialogTab) pill else null
            setTextColor(if (isDialogTab) TAB_COLOR_SELECTED else TAB_COLOR_UNSELECTED)
            typeface = if (isDialogTab) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    /** 按当前类型重建列表（按修改时间从新到旧）；无规则时显示对应空态 */
    private fun refreshList() {
        if (isDialogTab) {
            refreshDialogList()
        } else {
            refreshViewList()
        }
    }

    /**
     * 视图规则列表（勾选 + 批量删除 + 行点详情）。
     * 选择栏用 INVISIBLE 占位（GONE 会让弹窗整体高度跳变），列表恒显示，
     * 空态文字叠加在列表中央 —— 切换类型时窗口尺寸位置完全不变。
     */
    private fun refreshViewList() {
        val rules = ViewRuleManager.allRules().sortedByDescending { it.timestamp }
        binding.emptyView.text = SpannableString(moduleRes.getString(R.string.empty_view_rules))
        binding.emptyView.isVisible = rules.isEmpty()
        binding.selectionBar.isInvisible = rules.isEmpty()
        // 清掉已不存在的规则选择
        selection.retainAll(rules.map { it.key() }.toSet())
        binding.ruleList.adapter = RuleListAdapter(
            rules,
            hostActivity,
            selection,
            onDelete = { rule -> showDeleteConfirm(rule) },
            onRowClick = { rule -> RuleDetailDialog(context, rule).show() },
            onSelectionChanged = { updateSelectionBar(rules) }
        )
        updateSelectionBar(rules)
    }

    /** 弹窗规则列表（批量选择只适用于视图规则；选择栏 INVISIBLE 占位保持高度恒定） */
    private fun refreshDialogList() {
        val rules = DialogRuleStore.all().sortedByDescending { it.timestamp }
        binding.emptyView.text = SpannableString(moduleRes.getString(R.string.empty_dialog_rules))
        binding.emptyView.isVisible = rules.isEmpty()
        binding.selectionBar.isInvisible = true
        binding.ruleList.adapter = DialogRuleListAdapter(
            rules,
            onDelete = { rule -> showDialogRuleDeleteConfirm(rule) },
        )
    }

    /** 同步全选框 / 计数 / 批量删除按钮 */
    private fun updateSelectionBar(rules: List<ViewRule>) {
        val adapter = binding.ruleList.adapter as? RuleListAdapter ?: return
        binding.selectAll.setOnCheckedChangeListener(null)
        binding.selectAll.isChecked = rules.isNotEmpty() && selection.size == rules.size
        binding.selectAll.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                selection.addAll(rules.map { it.key() })
            } else {
                selection.clear()
            }
            adapter.notifyDataSetChanged()
            updateSelectionBar(rules)
        }
        binding.selectedCount.text =
            SpannableString(moduleRes.getString(R.string.selected_count, selection.size))
        // INVISIBLE 而非 GONE：占住按钮自身高度，出现/消失时弹窗高度不变，避免整体跳位
        binding.batchDelete.isInvisible = selection.isEmpty()
    }

    /** 批量删除确认框：与单条删除同样的还原 + 可撤销语义 */
    private fun showBatchDeleteConfirm() {
        val selected = ViewRuleManager.allRules().filter { selection.contains(it.key()) }
        if (selected.isEmpty()) return
        val view = ModuleDialogUi.inflate(context, R.layout.layout_rule_delete_confirm)
        val confirmBinding = LayoutRuleDeleteConfirmBinding.bind(view)
        confirmBinding.confirmMessage.text =
            SpannableString(
                moduleRes.getString(R.string.delete_selected_confirm_message, selected.size)
            )
        confirmBinding.confirmCancel.text = SpannableString(moduleRes.getString(R.string.cancel))
        confirmBinding.confirmDelete.text = SpannableString(moduleRes.getString(R.string.delete))

        val confirmDialog = Builder(context)
            .setView(view)
            .setCancelable(true)
            .create()
        confirmBinding.confirmCancel.setOnClickListener { confirmDialog.dismiss() }
        confirmBinding.confirmDelete.setOnClickListener {
            confirmDialog.dismiss()
            GvLog.d(TAG, "batch delete confirmed n=${selected.size}")
            // 还原各存活 Activity 中的目标视图后再删除（删除可被撤销）
            val live = ActivityLifecycleHooker.liveActivities()
            selected.forEach { rule ->
                runCatching { ViewRuleManager.deleteRule(rule, live) }
                    .onFailure { GvLog.e(TAG, "batch delete failed key=${rule.key()}", it) }
            }
            selection.clear()
            refreshList()
        }
        confirmDialog.setOnShowListener {
            runCatching { confirmDialog.window?.decorView?.let { ModuleDialogUi.markIgnoreTree(it) } }
        }
        confirmDialog.show()
        ModuleDialogUi.applyWindow(confirmDialog, view)
        runCatching { confirmDialog.window?.decorView?.let { ModuleDialogUi.markIgnoreTree(it) } }
    }

    /** 删除确认框：自定义带 IGNORE_HOOK 标签的视图，避免被编辑模式点击拦截 */
    private fun showDeleteConfirm(rule: ViewRule) {
        val view = ModuleDialogUi.inflate(context, R.layout.layout_rule_delete_confirm)
        val confirmBinding = LayoutRuleDeleteConfirmBinding.bind(view)
        confirmBinding.confirmMessage.text =
            SpannableString(moduleRes.getString(R.string.delete_rule_confirm_message))
        confirmBinding.confirmCancel.text = SpannableString(moduleRes.getString(R.string.cancel))
        confirmBinding.confirmDelete.text = SpannableString(moduleRes.getString(R.string.delete))

        val confirmDialog = Builder(context)
            .setView(view)
            .setCancelable(true)
            .create()
        confirmBinding.confirmCancel.setOnClickListener { confirmDialog.dismiss() }
        confirmBinding.confirmDelete.setOnClickListener {
            confirmDialog.dismiss()
            GvLog.d(TAG, "delete confirm clicked key=${rule.key()}")
            // 还原各存活 Activity 中的目标视图后再删除（删除可被撤销）
            ViewRuleManager.deleteRule(rule, ActivityLifecycleHooker.liveActivities())
            refreshList()
        }
        confirmDialog.setOnShowListener {
            runCatching { confirmDialog.window?.decorView?.let { ModuleDialogUi.markIgnoreTree(it) } }
        }
        confirmDialog.show()
        ModuleDialogUi.applyWindow(confirmDialog, view)
        runCatching { confirmDialog.window?.decorView?.let { ModuleDialogUi.markIgnoreTree(it) } }
    }

    /** 弹窗规则删除确认：确认后直接落盘删除（目标进程即权威，连带回推镜像） */
    private fun showDialogRuleDeleteConfirm(rule: DialogRule) {
        val view = ModuleDialogUi.inflate(context, R.layout.layout_rule_delete_confirm)
        val confirmBinding = LayoutRuleDeleteConfirmBinding.bind(view)
        confirmBinding.confirmMessage.text =
            SpannableString(moduleRes.getString(R.string.delete_dialog_rule_confirm_message))
        confirmBinding.confirmCancel.text = SpannableString(moduleRes.getString(R.string.cancel))
        confirmBinding.confirmDelete.text = SpannableString(moduleRes.getString(R.string.delete))

        val confirmDialog = Builder(context)
            .setView(view)
            .setCancelable(true)
            .create()
        confirmBinding.confirmCancel.setOnClickListener { confirmDialog.dismiss() }
        confirmBinding.confirmDelete.setOnClickListener {
            confirmDialog.dismiss()
            val removed = DialogRuleStore.delete(listOf(rule.key()))
            GvLog.d(TAG, "dialog rule delete clicked removed=$removed")
            refreshList()
        }
        confirmDialog.setOnShowListener {
            runCatching { confirmDialog.window?.decorView?.let { ModuleDialogUi.markIgnoreTree(it) } }
        }
        confirmDialog.show()
        ModuleDialogUi.applyWindow(confirmDialog, view)
        runCatching { confirmDialog.window?.decorView?.let { ModuleDialogUi.markIgnoreTree(it) } }
    }

    companion object {
        private const val TAG = "RuleMgr"

        /** 顶部类型切换的选中/未选中文字色（不依赖目标应用主题） */
        private val TAB_COLOR_SELECTED = Color.parseColor("#1F1F1F")
        private val TAB_COLOR_UNSELECTED = Color.parseColor("#888888")
    }
}
