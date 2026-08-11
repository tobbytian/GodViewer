package com.godviewer.app.ui.adapter

import android.app.Activity
import android.text.SpannableString
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.View.GONE
import android.widget.BaseAdapter
import androidx.core.view.drawToBitmap
import com.godviewer.app.IGNORE_HOOK
import com.godviewer.app.R
import com.godviewer.app.data.ViewRule
import com.godviewer.app.data.ViewRuleManager
import com.godviewer.app.databinding.LayoutRuleItemBinding
import com.godviewer.app.hook.AnyHookZygote.Companion.moduleRes
import com.godviewer.app.util.findViewBestMatch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 规则管理列表适配器：展示目标进程内的全部规则。
 *
 * 行标题 =（已隐藏 ·）+ 视图类简名；副标题 = 修改时间（当天 HH:mm:ss，非当天 yyyy-MM-dd）。
 * 行首缩略图：通过 [findViewBestMatch] 在当前 Activity 中定位该规则对应的活视图，
 * 用 Glide 自定义 loader 绘制缩略图；定位不到（规则属于其他 Activity 或视图已销毁）时显示占位图标。
 * 「删除」按钮回调给 [onDelete]，由弹窗负责确认与删除。按钮带 IGNORE_HOOK 标签，
 * 避免编辑模式下点击被 ViewClickWrapper 拦截。
 */
class RuleListAdapter(
    private val rules: List<ViewRule>,
    private val activity: Activity?,
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
        binding.ruleSubtitle.text = SpannableString(formatTimestamp(rule.timestamp))

        // 缩略图：优先用规则创建时缓存的图（与编辑弹窗预览一致）；无缓存则尝试
        // 当前 Activity 中的活视图直接绘制；都不行则用占位图标
        val cachedThumb = ViewRuleManager.thumbnailFor(rule)
        if (cachedThumb != null) {
            binding.ruleThumb.setImageBitmap(cachedThumb)
        } else {
            val liveView = activity?.let { findViewBestMatch(it, rule) }
            if (liveView != null && liveView.isLaidOut && liveView.width > 0 && liveView.height > 0) {
                runCatching { binding.ruleThumb.setImageBitmap(liveView.drawToBitmap()) }
                    .getOrElse { binding.ruleThumb.setImageResource(android.R.drawable.ic_menu_gallery) }
            } else {
                binding.ruleThumb.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        }

        binding.deleteButton.text = SpannableString(moduleRes.getString(R.string.delete))
        binding.deleteButton.setOnClickListener { onDelete(rule) }
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
