package com.godviewer.app.ui

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.SpannableString
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.godviewer.app.R
import com.godviewer.app.data.ViewAttrSnapshot
import com.godviewer.app.data.ViewRule
import com.godviewer.app.data.ViewRuleManager
import com.godviewer.app.databinding.LayoutRuleDetailDialogBinding
import com.godviewer.app.hook.AnyHookZygote.Companion.moduleRes
import com.godviewer.app.util.ModuleDialogUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 规则详情弹窗（只读）：展示规则的标识信息、修改时间、修改值与该值对应的原始值。
 *
 * 由规则管理列表点击某行打开。所有文案通过 moduleRes 获取，视图带 IGNORE_HOOK 标签。
 * 使用 [ModuleDialogUi] 隔离目标应用主题。
 */
class RuleDetailDialog(context: Context, private val rule: ViewRule) :
    AlertDialog(ModuleDialogUi.wrap(context)) {

    private val binding by lazy {
        LayoutRuleDetailDialogBinding.bind(
            ModuleDialogUi.inflate(this.context, R.layout.layout_rule_detail_dialog)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        val hidden = rule.changedVisibility && rule.modified.visibility == View.GONE
        val prefix = if (hidden) moduleRes.getString(R.string.hidden_status) + " · " else ""
        setTitle(prefix + rule.viewClass.substringAfterLast('.'))
        // 顶部展示该规则对应的缩略图（无缩略图时隐藏）
        val thumb = ViewRuleManager.thumbnailFor(rule)
        if (thumb != null) {
            binding.detailThumb.setImageBitmap(thumb)
        } else {
            binding.detailThumb.isVisible = false
        }
        binding.detailText.text = SpannableString(buildDetail())
    }

    override fun show() {
        super.show()
        ModuleDialogUi.applyWindow(this, binding.root)
    }

    override fun setTitle(title: CharSequence?) {
        binding.title.text = SpannableString(title)
    }

    private fun buildDetail(): String {
        val lines = ArrayList<String>()
        lines += rule.activityClass.substringAfterLast('.') + " · " + rule.packageName
        rule.resourceName?.let { lines += "res: $it" }
        rule.text?.let { lines += "text: $it" }
        rule.description?.let { lines += "desc: $it" }
        lines += "depth: " + rule.depth.joinToString("/")
        lines += ""
        lines += moduleRes.getString(R.string.modify_time) + ": " +
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(rule.timestamp))
        lines += ""
        lines += moduleRes.getString(R.string.modified_values) + ":"
        lines += buildProps(rule.modified).ifEmpty { "  —" }
        lines += ""
        lines += moduleRes.getString(R.string.original_values) + ":"
        lines += buildProps(rule.original).ifEmpty { "  —" }
        return lines.joinToString("\n")
    }

    /** 按 changed* 标志输出已生效属性的值（每个属性一行，两空格缩进） */
    private fun buildProps(snapshot: ViewAttrSnapshot): String {
        val lines = ArrayList<String>()
        if (rule.changedSize) {
            lines += "  ${moduleRes.getString(R.string.size)}: " +
                "${formatDim(snapshot.width)} × ${formatDim(snapshot.height)}"
        }
        if (rule.changedMargin) {
            lines += "  ${moduleRes.getString(R.string.margin)}: ${snapshot.marginLeft}, " +
                "${snapshot.marginTop}, ${snapshot.marginRight}, ${snapshot.marginBottom}"
        }
        if (rule.changedPadding) {
            lines += "  ${moduleRes.getString(R.string.padding)}: ${snapshot.paddingLeft}, " +
                "${snapshot.paddingTop}, ${snapshot.paddingRight}, ${snapshot.paddingBottom}"
        }
        if (rule.changedVisibility) {
            val value = if (snapshot.visibility == View.GONE) {
                moduleRes.getString(R.string.hidden_status)
            } else {
                moduleRes.getString(R.string.visible)
            }
            lines += "  ${moduleRes.getString(R.string.visibility)}: $value"
        }
        if (rule.changedText) {
            lines += "  ${moduleRes.getString(R.string.text_content)}: ${snapshot.text ?: ""}"
        }
        if (rule.changedImage) {
            lines += "  ${moduleRes.getString(R.string.image_url)}: ${snapshot.imageUrl ?: ""}"
        }
        return lines.joinToString("\n")
    }

    /** 尺寸的友好显示：MATCH_PARENT / WRAP_CONTENT 映射为字面量，其余输出像素值 */
    private fun formatDim(value: Int): String = when (value) {
        ViewGroup.LayoutParams.MATCH_PARENT -> "match_parent"
        ViewGroup.LayoutParams.WRAP_CONTENT -> "wrap_content"
        else -> value.toString()
    }
}
