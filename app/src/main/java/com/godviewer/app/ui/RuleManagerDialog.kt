package com.godviewer.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.SpannableString
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.isVisible
import com.godviewer.app.IGNORE_HOOK
import com.godviewer.app.R
import com.godviewer.app.data.ViewRule
import com.godviewer.app.data.ViewRuleManager
import com.godviewer.app.databinding.LayoutRuleDeleteConfirmBinding
import com.godviewer.app.databinding.LayoutRuleManagerDialogBinding
import com.godviewer.app.hook.AnyHookZygote.Companion.moduleRes
import com.godviewer.app.ui.adapter.RuleListAdapter

/**
 * 规则管理弹窗（悬浮于目标应用界面之上，非新 Activity）。
 *
 * 由通知栏「规则」按钮打开，展示目标进程内的全部规则；每条规则可单独删除，
 * 删除前弹确认框。删除时若视图位于当前 Activity 则先还原（被隐藏的视图会重新出现），
 * 删除会压入撤销栈，误删可用通知栏「撤销」恢复。
 */
class RuleManagerDialog(context: Context) : AlertDialog(context) {

    private val binding by lazy {
        val layout = moduleRes.getLayout(R.layout.layout_rule_manager_dialog)
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(layout, null)
        view.tag = IGNORE_HOOK
        LayoutRuleManagerDialogBinding.bind(view)
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

    /** 重建规则列表（按修改时间从新到旧）；无规则时显示空态 */
    private fun refreshList() {
        val rules = ViewRuleManager.allRules().sortedByDescending { it.timestamp }
        binding.emptyView.isVisible = rules.isEmpty()
        binding.ruleList.isVisible = rules.isNotEmpty()
        binding.ruleList.adapter = RuleListAdapter(rules, context as? Activity) { rule ->
            showDeleteConfirm(rule)
        }
        // 点击规则行打开详情
        binding.ruleList.setOnItemClickListener { _, _, position, _ ->
            rules.getOrNull(position)?.let { RuleDetailDialog(context, it).show() }
        }
    }

    /** 删除确认框：自定义带 IGNORE_HOOK 标签的视图，避免被编辑模式点击拦截 */
    private fun showDeleteConfirm(rule: ViewRule) {
        val layout = moduleRes.getLayout(R.layout.layout_rule_delete_confirm)
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(layout, null)
        view.tag = IGNORE_HOOK
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
            // 还原当前 Activity 中的目标视图后再删除（删除可被撤销）
            ViewRuleManager.deleteRule(rule, context as? Activity)
            refreshList()
        }
        confirmDialog.show()
    }
}
