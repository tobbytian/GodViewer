package com.godviewer.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.SpannableString
import androidx.core.view.isVisible
import com.godviewer.app.R
import com.godviewer.app.data.ViewRule
import com.godviewer.app.data.ViewRuleManager
import com.godviewer.app.databinding.LayoutRuleDeleteConfirmBinding
import com.godviewer.app.databinding.LayoutRuleManagerDialogBinding
import com.godviewer.app.hook.AnyHookZygote.Companion.moduleRes
import com.godviewer.app.hook.hookers.ActivityLifecycleHooker
import com.godviewer.app.ui.adapter.RuleListAdapter
import com.godviewer.app.util.ModuleDialogUi

/**
 * 规则管理弹窗（悬浮于目标应用界面之上，非新 Activity）。
 *
 * 由通知栏「规则」按钮打开，展示目标进程内的全部规则；每条规则可单独删除，
 * 删除前弹确认框。删除时若视图位于当前 Activity 则先还原（被隐藏的视图会重新出现），
 * 删除会压入撤销栈，误删可用通知栏「撤销」恢复。
 *
 * 使用 [ModuleDialogUi] 隔离目标应用主题。
 */
class RuleManagerDialog(context: Context) : AlertDialog(ModuleDialogUi.wrap(context)) {

    private val hostActivity: Activity? = ModuleDialogUi.activityOf(context)

    private val binding by lazy {
        LayoutRuleManagerDialogBinding.bind(
            ModuleDialogUi.inflate(this.context, R.layout.layout_rule_manager_dialog)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setTitle(moduleRes.getString(R.string.manage_rules))
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
        binding.ruleList.adapter = RuleListAdapter(
            rules,
            hostActivity,
            onDelete = { rule -> showDeleteConfirm(rule) },
            onRowClick = { rule -> RuleDetailDialog(context, rule).show() }
        )
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
        confirmDialog.show()
        ModuleDialogUi.applyWindow(confirmDialog, view)
    }
}
