package com.godviewer.app.ui.adapter

import android.text.SpannableString
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.View.GONE
import android.widget.BaseAdapter
import com.godviewer.app.IGNORE_HOOK
import com.godviewer.app.R
import com.godviewer.app.data.ViewRule
import com.godviewer.app.databinding.LayoutRuleItemBinding
import com.godviewer.app.hook.AnyHookZygote.Companion.moduleRes

/**
 * 规则管理列表适配器：展示目标进程内的全部规则。
 *
 * 行标题 =（已隐藏 ·）+ 视图类简名；副标题 = Activity 简名 · resourceName / text / depth。
 * 「删除」按钮回调给 [onDelete]，由弹窗负责确认与删除。按钮带 IGNORE_HOOK 标签，
 * 避免编辑模式下点击被 ViewClickWrapper 拦截。
 */
class RuleListAdapter(
    private val rules: List<ViewRule>,
    private val onDelete: (ViewRule) -> Unit
) : BaseAdapter() {

    override fun getCount(): Int = rules.size

    override fun getItem(position: Int): Any = rules[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val rule = rules[position]
        val itemView: View = if (convertView == null) {
            val layout = moduleRes.getLayout(R.layout.layout_rule_item)
            val inflater = LayoutInflater.from(parent?.context)
            inflater.inflate(layout, parent, false)
        } else {
            convertView
        }
        itemView.tag = IGNORE_HOOK
        val binding = LayoutRuleItemBinding.bind(itemView)

        val hidden = rule.changedVisibility && rule.modified.visibility == GONE
        val prefix = if (hidden) moduleRes.getString(R.string.hidden_status) + " · " else ""
        binding.ruleTitle.text =
            SpannableString(prefix + rule.viewClass.substringAfterLast('.'))
        val activity = rule.activityClass.substringAfterLast('.')
        val detail = rule.resourceName
            ?: rule.text?.let { "text: $it" }
            ?: rule.depth.joinToString("/")
        binding.ruleSubtitle.text = SpannableString("$activity · $detail")
        binding.deleteButton.text = SpannableString(moduleRes.getString(R.string.delete))
        binding.deleteButton.setOnClickListener { onDelete(rule) }
        return itemView
    }
}
