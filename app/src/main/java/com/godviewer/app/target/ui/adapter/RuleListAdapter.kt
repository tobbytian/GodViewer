package com.godviewer.app.target.ui.adapter

import android.app.Activity
import com.godviewer.app.shared.AndroidAppCompat
import android.text.SpannableString
import android.view.View
import android.view.View.GONE
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.godviewer.app.IGNORE_HOOK
import com.godviewer.app.R
import com.godviewer.app.shared.ViewSnapshot
import com.godviewer.app.shared.model.ViewRule
import com.godviewer.app.target.dialog.ModuleDialogUi
import com.godviewer.app.target.dispatch.ViewClickWrapper
import com.godviewer.app.target.hook.ModuleRes
import com.godviewer.app.target.hook.ModuleRes.moduleRes
import com.godviewer.app.target.rule.ViewRuleManager
import com.godviewer.app.target.rule.findViewBestMatch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.godviewer.app.databinding.LayoutRuleItemBinding

/**
 * 规则管理列表适配器：展示目标进程内的全部规则。
 *
 * 行标题 =（已隐藏 ·）+ 视图类简名；副标题 = 修改时间（当天 HH:mm:ss，非当天 yyyy-MM-dd）。
 * 行首勾选框参与批量选择（选中集合由弹窗持有，[onSelectionChanged] 通知刷新批量栏）。
 * 行首缩略图：通过 [findViewBestMatch] 在当前 Activity 中定位该规则对应的活视图，
 * 用 Glide 自定义 loader 绘制缩略图；定位不到（规则属于其他 Activity 或视图已销毁）时显示占位图标。
 * 「删除」按钮回调给 [onDelete]，由弹窗负责确认与删除。按钮带 IGNORE_HOOK 标签，
 * 避免编辑模式下点击被 ViewClickWrapper 拦截。
 */
class RuleListAdapter(
    private val rules: List<ViewRule>,
    private val activity: Activity?,
    private val selection: MutableSet<ViewRule.RuleKey>,
    private val onDelete: (ViewRule) -> Unit,
    private val onRowClick: (ViewRule) -> Unit,
    private val onSelectionChanged: () -> Unit
) : BaseAdapter() {

    override fun getCount(): Int = rules.size

    override fun getItem(position: Int): Any = rules[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val rule = rules[position]
        // 模块运行在目标进程内，currentApplication() 必然非空；永不向 Adapter 绑定抛异常
        val ctx = parent?.context ?: activity ?: AndroidAppCompat.currentApplication()
            ?: return convertView ?: View(parent?.context)
        val itemView: View = if (convertView == null) {
            ModuleDialogUi.inflate(ctx, R.layout.layout_rule_item)
        } else {
            convertView
        }
        itemView.tag = IGNORE_HOOK
        val binding = LayoutRuleItemBinding.bind(itemView)

        val hidden = rule.changedVisibility && rule.modified.visibility == GONE
        val prefix = if (hidden) moduleRes.getString(R.string.hidden_status) + " · " else ""
        binding.ruleTitle.text =
            SpannableString(prefix + rule.viewClass.substringAfterLast('.'))
        binding.ruleSubtitle.text = SpannableString(formatTimestamp(rule.timestamp))

        // 勾选框：先摘掉监听再回填状态，避免复用时误触发
        binding.ruleCheck.setOnCheckedChangeListener(null)
        binding.ruleCheck.isChecked = selection.contains(rule.key())
        binding.ruleCheck.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                selection.add(rule.key())
            } else {
                selection.remove(rule.key())
            }
            onSelectionChanged()
        }

        // 缩略图：优先用规则创建时缓存的图（与编辑弹窗预览一致）；无缓存则尝试
        // 当前 Activity 中的活视图直接绘制；都不行则用占位图标
        val cachedThumb = ViewRuleManager.thumbnailFor(rule)
        if (cachedThumb != null) {
            binding.ruleThumb.setImageBitmap(cachedThumb)
        } else {
            val liveView = activity?.let { findViewBestMatch(it, rule) }
            val liveBmp = ViewSnapshot.capture(liveView, maxEdge = 128)
            if (liveBmp != null) {
                binding.ruleThumb.setImageBitmap(liveBmp)
            } else {
                binding.ruleThumb.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        }

        binding.deleteButton.text = SpannableString(moduleRes.getString(R.string.delete))
        binding.deleteButton.setOnClickListener { onDelete(rule) }
        // 文案就绪后再归一化样式（按钮主/危险色依赖 label）
        ModuleDialogUi.normalizeTree(itemView)
        // 整行可点：打开规则详情（行带 IGNORE_HOOK 标签，不会被编辑模式点击拦截）
        itemView.setOnClickListener { onRowClick(rule) }
        return itemView
    }

    /** 修改时间显示：当天 HH:mm:ss，非当天 yyyy-MM-dd */
    private fun formatTimestamp(timestamp: Long): String {
        val date = Date(timestamp)
        val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val sameDay = day.format(date) == day.format(Date())
        val pattern = if (sameDay) "HH:mm:ss" else "yyyy-MM-dd"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
    }
}
