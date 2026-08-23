package com.godviewer.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.godviewer.app.ui.host.HostActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.data.ViewRule
import com.godviewer.app.databinding.ActivityRuleMirrorDetailBinding
import com.godviewer.app.databinding.ItemMirroredRuleBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Host-side package rule list, styled like the in-target [com.godviewer.app.ui.RuleManagerDialog] rows.
 */
class RuleMirrorDetailActivity : HostActivity() {
    private val binding by lazy { ActivityRuleMirrorDetailBinding.inflate(layoutInflater) }
    private lateinit var packageName: String
    private val adapter = RuleAdapter { _, index ->
        startActivity(
            Intent(this, RuleMirrorRuleDetailActivity::class.java)
                .putExtra(RuleMirrorRuleDetailActivity.EXTRA_PACKAGE, packageName)
                .putExtra(RuleMirrorRuleDetailActivity.EXTRA_RULE_INDEX, index),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val label = intent.getStringExtra(EXTRA_LABEL)
            ?.takeIf { it.isNotBlank() }
            ?: RuleMirror.loadAppLabel(this, packageName)

        binding.toolbar.title = getString(R.string.manage_rules)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.appLabelView.text = label
        binding.packageNameView.text = packageName
        val icon = RuleMirror.loadAppIcon(this, packageName)
        if (icon != null) {
            binding.appIcon.setImageDrawable(icon)
        } else {
            binding.appIcon.setImageResource(R.mipmap.ic_launcher)
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val rules = RuleMirror.loadRules(this, packageName)
            .sortedByDescending { it.timestamp }
        adapter.submit(packageName, rules)
        binding.emptyView.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class RuleAdapter(
        private val onClick: (ViewRule, Int) -> Unit,
    ) : RecyclerView.Adapter<RuleAdapter.Holder>() {
        private var pkg: String = ""
        private val items = ArrayList<ViewRule>()

        fun submit(packageName: String, list: List<ViewRule>) {
            pkg = packageName
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemMirroredRuleBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], position)
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(
            private val binding: ItemMirroredRuleBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(rule: ViewRule, index: Int) {
                val hidden = rule.changedVisibility && rule.modified.visibility == View.GONE
                val prefix = if (hidden) getString(R.string.hidden_status) + " · " else ""
                val shortView = rule.viewClass.substringAfterLast('.')
                binding.ruleTitle.text = prefix + shortView.ifBlank { rule.viewClass }
                binding.ruleSubtitle.text = formatTimestamp(rule.timestamp)

                val thumb = RuleMirror.loadThumbnail(this@RuleMirrorDetailActivity, pkg, rule)
                if (thumb != null) {
                    binding.ruleThumb.setImageBitmap(thumb)
                } else {
                    binding.ruleThumb.setImageResource(android.R.drawable.ic_menu_gallery)
                }
                binding.root.setOnClickListener { onClick(rule, index) }
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "package_name"
        const val EXTRA_LABEL = "label"

        fun formatTimestamp(timestamp: Long): String {
            val date = Date(timestamp)
            val cal = Calendar.getInstance()
            val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
            cal.time = date
            val that = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
            val pattern = if (today == that) "HH:mm:ss" else "yyyy-MM-dd"
            return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
        }
    }
}
