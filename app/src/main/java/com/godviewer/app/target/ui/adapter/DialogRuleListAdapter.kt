package com.godviewer.app.target.ui.adapter

import android.text.SpannableString
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.godviewer.app.IGNORE_HOOK
import com.godviewer.app.R
import com.godviewer.app.shared.AndroidAppCompat
import com.godviewer.app.shared.model.DialogRule
import com.godviewer.app.target.dialog.ModuleDialogPalette
import com.godviewer.app.target.dialog.ModuleDialogUi
import com.godviewer.app.target.hook.ModuleRes.moduleRes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.godviewer.app.databinding.LayoutDialogRuleItemBinding

/**
 * 弹窗规则列表适配器（规则管理弹窗顶部切换到「弹窗规则」时使用）。
 *
 * 行标题 = 首条指纹文本（无文本回退弹窗类简名）；副标题 = Activity 简名 · 时间。
 * 「删除」与整行点击都回调 [onDelete]，由弹窗负责确认与落盘删除
 * （规则权威就在目标进程，直接 [com.godviewer.app.target.rule.DialogRuleStore] 删）。
 */
class DialogRuleListAdapter(
    private val rules: List<DialogRule>,
    private val onDelete: (DialogRule) -> Unit,
) : BaseAdapter() {

    override fun getCount(): Int = rules.size

    override fun getItem(position: Int): Any = rules[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val rule = rules[position]
        val ctx = parent?.context ?: AndroidAppCompat.currentApplication()
            ?: return convertView ?: View(parent?.context)
        val itemView: View = if (convertView == null) {
            ModuleDialogUi.inflate(ctx, R.layout.layout_dialog_rule_item)
        } else {
            convertView
        }
        itemView.tag = IGNORE_HOOK
        val binding = LayoutDialogRuleItemBinding.bind(itemView)
        // 注入布局不得引用模块 @drawable/@color（会被目标资源表解析）：卡片底代码注入
        itemView.background = moduleRes.getDrawable(R.drawable.bg_neu_raised, null)

        val title = rule.title()
        binding.dialogRuleTitle.text = SpannableString(title)
        val activity = rule.activityClass.substringAfterLast('.').ifBlank { "—" }
        binding.dialogRuleSubtitle.text =
            SpannableString("$activity · ${formatTimestamp(rule.timestamp)}")

        binding.deleteButton.text = SpannableString(moduleRes.getString(R.string.delete))
        binding.deleteButton.setOnClickListener { onDelete(rule) }
        // 文案就绪后再归一化样式（按钮主/危险色依赖 label）
        ModuleDialogUi.normalizeTree(itemView)
        // normalizeTree 把所有 TextView 刷成 dialogText，副标题需恢复次级灰
        binding.dialogRuleSubtitle.setTextColor(ModuleDialogPalette.dialogHint)
        // 整行可点 = 删除确认（行带 IGNORE_HOOK 标签，不会被编辑模式点击拦截）
        itemView.setOnClickListener { onDelete(rule) }
        return itemView
    }

    /** 修改时间显示：当天 HH:mm:ss，非当天 yyyy-MM-dd（与视图规则行一致） */
    private fun formatTimestamp(timestamp: Long): String {
        val date = Date(timestamp)
        val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val sameDay = day.format(date) == day.format(Date())
        val pattern = if (sameDay) "HH:mm:ss" else "yyyy-MM-dd"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
    }
}