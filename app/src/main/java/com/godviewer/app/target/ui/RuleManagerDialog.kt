package com.godviewer.app.target.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.SpannableString
import androidx.core.view.isVisible
import com.godviewer.app.IGNORE_HOOK
import com.godviewer.app.R
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.model.ViewRule
import com.godviewer.app.target.dialog.ModuleDialogUi
import com.godviewer.app.target.hook.ModuleRes
import com.godviewer.app.target.hook.ModuleRes.moduleRes
import com.godviewer.app.target.hook.hookers.ActivityLifecycleHooker
import com.godviewer.app.target.rule.ViewRuleManager
import com.godviewer.app.target.ui.adapter.RuleListAdapter
import com.godviewer.app.databinding.LayoutRuleDeleteConfirmBinding
import com.godviewer.app.databinding.LayoutRuleManagerDialogBinding

/**
 * 规则管理弹窗（悬浮于目标应用界面之上，非新 Activity）。
 *
 * 由通知栏「规则」按钮打开，展示目标进程内的全部规则；每条规则可单独删除，
 * 删除前弹确认框。支持勾选 + 全选批量删除。删除时若视图位于当前 Activity
 * 则先还原（被隐藏的视图会重新出现），删除会压入撤销栈，误删可用通知栏「撤销」恢复。
 *
 * 使用 [ModuleDialogUi] 隔离目标应用主题。
 */
class RuleManagerDialog(context: Context) : AlertDialog(ModuleDialogUi.wrap(context)) {

    private val hostActivity: Activity? = ModuleDialogUi.activityOf(context)

    /** 勾选的规则（按 RuleKey），随列表刷新清理失效项 */
    private val selection = mutableSetOf<ViewRule.RuleKey>()

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
        refreshList()
    }

    override fun setTitle(title: CharSequence?) {
        binding.title.text = SpannableString(title)
    }

    override fun show() {
        super.show()
        ModuleDialogUi.applyWindow(this, binding.root)
    }

    /** 重建规则列表（按修改时间从新到旧）；无规则时显示空态 */
    private fun refreshList() {
        val rules = ViewRuleManager.allRules().sortedByDescending { it.timestamp }
        binding.emptyView.isVisible = rules.isEmpty()
        binding.ruleList.isVisible = rules.isNotEmpty()
        binding.selectionBar.isVisible = rules.isNotEmpty()
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
        binding.batchDelete.isVisible = selection.isNotEmpty()
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

    companion object {
        private const val TAG = "RuleMgr"
    }
}
