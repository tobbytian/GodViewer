package com.godviewer.app

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.godviewer.app.ui.host.HostActivity
import androidx.core.view.isVisible
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.data.ViewAttrSnapshot
import com.godviewer.app.data.ViewRule
import com.godviewer.app.databinding.ActivityRuleMirrorRuleDetailBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Host-side read-only rule detail, matching [com.godviewer.app.ui.RuleDetailDialog] content.
 */
class RuleMirrorRuleDetailActivity : HostActivity() {
    private val binding by lazy { ActivityRuleMirrorRuleDetailBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val index = intent.getIntExtra(EXTRA_RULE_INDEX, -1)
        val rules = RuleMirror.loadRules(this, packageName)
            .sortedByDescending { it.timestamp }
        val rule = rules.getOrNull(index)
        if (rule == null) {
            finish()
            return
        }

        val hidden = rule.changedVisibility && rule.modified.visibility == View.GONE
        val prefix = if (hidden) getString(R.string.hidden_status) + " · " else ""
        val title = prefix + rule.viewClass.substringAfterLast('.')
        binding.toolbar.title = title
        binding.toolbar.setNavigationOnClickListener { finish() }

        val thumb = RuleMirror.loadThumbnail(this, packageName, rule)
        if (thumb != null) {
            binding.detailThumb.setImageBitmap(thumb)
            binding.detailThumb.isVisible = true
        } else {
            binding.detailThumb.isVisible = false
        }
        binding.detailText.text = buildDetail(rule)
    }

    private fun buildDetail(rule: ViewRule): String {
        val lines = ArrayList<String>()
        lines += rule.activityClass.substringAfterLast('.') + " · " + rule.packageName
        rule.resourceName?.let { lines += "res: $it" }
        rule.text?.let { lines += "text: $it" }
        rule.description?.let { lines += "desc: $it" }
        lines += "depth: " + rule.depth.joinToString("/")
        lines += ""
        lines += getString(R.string.modify_time) + ": " +
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(rule.timestamp))
        lines += ""
        lines += getString(R.string.modified_values) + ":"
        lines += buildProps(rule, rule.modified).ifEmpty { "  —" }
        lines += ""
        lines += getString(R.string.original_values) + ":"
        lines += buildProps(rule, rule.original).ifEmpty { "  —" }
        return lines.joinToString("\n")
    }

    private fun buildProps(rule: ViewRule, snapshot: ViewAttrSnapshot): String {
        val lines = ArrayList<String>()
        if (rule.changedSize) {
            lines += "  ${getString(R.string.size)}: " +
                "${formatDim(snapshot.width)} × ${formatDim(snapshot.height)}"
        }
        if (rule.changedMargin) {
            lines += "  ${getString(R.string.margin)}: ${snapshot.marginLeft}, " +
                "${snapshot.marginTop}, ${snapshot.marginRight}, ${snapshot.marginBottom}"
        }
        if (rule.changedPadding) {
            lines += "  ${getString(R.string.padding)}: ${snapshot.paddingLeft}, " +
                "${snapshot.paddingTop}, ${snapshot.paddingRight}, ${snapshot.paddingBottom}"
        }
        if (rule.changedVisibility) {
            val value = if (snapshot.visibility == View.GONE) {
                getString(R.string.hidden_status)
            } else {
                getString(R.string.visible)
            }
            lines += "  ${getString(R.string.visibility)}: $value"
        }
        if (rule.changedText) {
            lines += "  ${getString(R.string.text_content)}: ${snapshot.text ?: ""}"
        }
        if (rule.changedImage) {
            lines += "  ${getString(R.string.image_url)}: ${snapshot.imageUrl ?: ""}"
            snapshot.scaleType?.let {
                lines += "  ${getString(R.string.scale_type)}: $it"
            }
        }
        return lines.joinToString("\n")
    }

    private fun formatDim(value: Int): String = when (value) {
        ViewGroup.LayoutParams.MATCH_PARENT -> "match_parent"
        ViewGroup.LayoutParams.WRAP_CONTENT -> "wrap_content"
        else -> value.toString()
    }

    companion object {
        const val EXTRA_PACKAGE = "package_name"
        const val EXTRA_RULE_INDEX = "rule_index"
    }
}
