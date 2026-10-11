package com.godviewer.app.host

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.godviewer.app.R
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.host.control.HostControlNotifier
import com.godviewer.app.host.prefs.HostPrefs
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.model.ViewRule
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.godviewer.app.databinding.ActivityRuleMirrorDetailBinding
import com.godviewer.app.databinding.ItemMirroredRuleBinding
import com.godviewer.app.host.ui.ConfirmDialog
import com.godviewer.app.host.ui.HostActivity

/**
 * Host-side package rule list, styled like the in-target [com.godviewer.app.target.ui.RuleManagerDialog] rows.
 */
class RuleMirrorDetailActivity : HostActivity() {
    private val binding by lazy { ActivityRuleMirrorDetailBinding.inflate(layoutInflater) }
    private lateinit var packageName: String

    /** 「删除全部规则」下发后校验结果用（目标进程可能已不存在，广播会被静默丢弃） */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 程序化设置每应用通知开关时抑制回调 */
    private var suppressNotifyCallback = false

    /** 多选模式（勾选多条规则批量删除） */
    private var selectionMode = false
    private val selectedKeys = LinkedHashSet<ViewRule.RuleKey>()

    /** 最近一次加载的规则（按 timestamp 降序），供选择/删除取值 */
    private var rulesCache: List<ViewRule> = emptyList()

    private val adapter = RuleAdapter(
        onClick = { rule -> onRuleClicked(rule) },
        onLongClick = { rule -> onRuleLongClicked(rule) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val label = intent.getStringExtra(EXTRA_LABEL)
            ?.takeIf { it.isNotBlank() }
            ?: RuleMirror.loadAppLabel(this, packageName)

        binding.toolbar.title = getString(R.string.manage_rules)
        binding.toolbar.setNavigationOnClickListener {
            // 多选模式下返回箭头先退出多选，而非关闭页面
            if (selectionMode) setSelectionMode(false) else finish()
        }
        binding.toolbar.inflateMenu(R.menu.menu_rule_mirror_detail)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_delete_all -> {
                    confirmDeleteAllRules()
                    true
                }
                R.id.action_select_multiple -> {
                    setSelectionMode(true)
                    true
                }
                R.id.action_select_all -> {
                    rulesCache.forEach { selectedKeys.add(it.key()) }
                    updateSelectionUi()
                    adapter.notifyDataSetChanged()
                    true
                }
                R.id.action_delete_selected -> {
                    confirmDeleteSelectedRules()
                    true
                }
                else -> false
            }
        }
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

        // 每应用通知开关：全局开关打开时，可单独关闭「该应用前台时的本体通知」
        binding.appNotifySwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressNotifyCallback) return@setOnCheckedChangeListener
            HostPrefs.setAppNotifyDisabled(this, packageName, !checked)
            // 立即生效：若当前前台目标就是该应用则刷新（隐藏/恢复）本体通知
            HostControlNotifier.refresh(this)
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
        refreshAppNotifySwitch()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selectionMode) {
            setSelectionMode(false)
            return
        }
        super.onBackPressed()
    }

    private fun reload() {
        val rules = RuleMirror.loadRules(this, packageName)
            .sortedByDescending { it.timestamp }
        rulesCache = rules
        // 镜像刷新后清掉已不存在的选择（例如目标端删了规则）
        selectedKeys.retainAll(rules.map { it.key() })
        adapter.submit(packageName, rules)
        binding.emptyView.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        binding.toolbar.menu.findItem(R.id.action_delete_all)?.isEnabled = rules.isNotEmpty()
        applyMenuMode()
    }

    // ---------- 多选模式 ----------

    private fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (!enabled) selectedKeys.clear()
        updateSelectionUi()
        adapter.notifyDataSetChanged()
    }

    /** 同步标题（已选计数）与菜单可见性/可用性 */
    private fun updateSelectionUi() {
        binding.toolbar.title = if (selectionMode) {
            getString(R.string.selected_count, selectedKeys.size)
        } else {
            getString(R.string.manage_rules)
        }
        applyMenuMode()
    }

    private fun applyMenuMode() {
        binding.toolbar.menu.apply {
            findItem(R.id.action_delete_all)?.isVisible = !selectionMode
            findItem(R.id.action_select_multiple)?.isVisible = !selectionMode
            findItem(R.id.action_select_all)?.isVisible = selectionMode
            findItem(R.id.action_delete_selected)?.apply {
                isVisible = selectionMode
                isEnabled = selectedKeys.isNotEmpty()
            }
        }
    }

    private fun toggleSelection(rule: ViewRule) {
        val key = rule.key()
        if (!selectedKeys.remove(key)) selectedKeys.add(key)
        updateSelectionUi()
        adapter.notifyDataSetChanged()
    }

    private fun onRuleClicked(rule: ViewRule) {
        if (selectionMode) {
            toggleSelection(rule)
            return
        }
        // 传规则 JSON 而非下标：镜像在列表与详情之间刷新时下标会失效
        startActivity(
            Intent(this, RuleMirrorRuleDetailActivity::class.java)
                .putExtra(RuleMirrorRuleDetailActivity.EXTRA_PACKAGE, packageName)
                .putExtra(RuleMirrorRuleDetailActivity.EXTRA_RULE_JSON, Gson().toJson(rule)),
        )
    }

    private fun onRuleLongClicked(rule: ViewRule): Boolean {
        if (selectionMode) {
            toggleSelection(rule)
        } else {
            confirmDeleteSingleRule(rule)
        }
        return true
    }

    // ---------- 单条删除（长按） ----------

    private fun confirmDeleteSingleRule(rule: ViewRule) {
        ConfirmDialog.showDelete(
            this,
            getString(R.string.delete_rule),
            getString(R.string.delete_rule_confirm_message),
        ) { requestDeleteRules(listOf(rule)) }
    }

    // ---------- 批量删除（多选） ----------

    private fun confirmDeleteSelectedRules() {
        val selected = rulesCache.filter { it.key() in selectedKeys }
        if (selected.isEmpty()) return
        ConfirmDialog.showDelete(
            this,
            getString(R.string.delete_selected),
            getString(R.string.delete_selected_confirm_message, selected.size),
        ) { requestDeleteRules(selected) }
    }

    /**
     * 下发单条/批量删除（权威规则在目标应用内，由目标进程执行还原+落盘+回推镜像），
     * 稍后回读镜像按 key 确认结果。
     */
    private fun requestDeleteRules(rules: List<ViewRule>) {
        if (rules.isEmpty()) return
        val single = rules.size == 1
        val dispatched = HostControlBridge.dispatchRulesToPackage(this, packageName, rules)
        if (!dispatched) {
            Toast.makeText(
                this,
                if (single) R.string.delete_rule_failed else R.string.delete_rules_failed,
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val remaining = RuleMirror.loadRules(this, packageName)
            val allGone = rules.none { r -> remaining.any { it.key() == r.key() } }
            if (allGone) {
                if (single) {
                    Toast.makeText(this, R.string.delete_rule_done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.delete_rules_done, rules.size),
                        Toast.LENGTH_SHORT,
                    ).show()
                    setSelectionMode(false)
                }
            } else {
                Toast.makeText(
                    this,
                    if (single) R.string.delete_rule_failed else R.string.delete_rules_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
            reload()
        }, DELETE_VERIFY_DELAY_MS)
    }

    /** 按全局开关与该应用的单独开关同步 UI；全局开关关闭时此功能不可用（置灰并提示） */
    private fun refreshAppNotifySwitch() {
        val globalOn = HostPrefs.isHostNotifyEnabled(this)
        binding.appNotifySwitch.isEnabled = globalOn
        binding.appNotifyHint.setText(
            if (globalOn) R.string.mirror_detail_notify_subtitle
            else R.string.mirror_detail_notify_disabled_hint
        )
        suppressNotifyCallback = true
        binding.appNotifySwitch.isChecked =
            globalOn && !HostPrefs.isAppNotifyDisabled(this, packageName)
        suppressNotifyCallback = false
    }

    /** 一键删除该应用全部规则：权威规则在目标应用内，只能由目标进程执行 */
    private fun confirmDeleteAllRules() {
        val count = RuleMirror.loadRules(this, packageName).size
        if (count <= 0) return
        val label = binding.appLabelView.text?.toString().orEmpty().ifBlank { packageName }
        ConfirmDialog.showDelete(
            this,
            getString(R.string.delete_all_rules),
            getString(R.string.delete_all_rules_confirm_message, label, count),
        ) { requestDeleteAllRules(count) }
    }

    private fun requestDeleteAllRules(expectedCount: Int) {
        val dispatched = HostControlBridge.dispatchToPackage(
            this,
            packageName,
            HostControlBridge.ACTION_DELETE_ALL_RULES,
        )
        if (!dispatched) {
            Toast.makeText(this, R.string.delete_all_rules_failed, Toast.LENGTH_LONG).show()
            return
        }
        // 目标进程删除成功会推回空镜像（宿主镜像目录被清除），稍后据此确认结果
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val remaining = RuleMirror.loadRules(this, packageName).size
            val msg = if (remaining == 0) {
                getString(R.string.delete_all_rules_done, expectedCount)
            } else {
                getString(R.string.delete_all_rules_failed)
            }
            Toast.makeText(
                this,
                msg,
                if (remaining == 0) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
            ).show()
            reload()
        }, DELETE_VERIFY_DELAY_MS)
    }

    private inner class RuleAdapter(
        private val onClick: (ViewRule) -> Unit,
        private val onLongClick: (ViewRule) -> Boolean,
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
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(
            private val binding: ItemMirroredRuleBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(rule: ViewRule) {
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
                binding.ruleCheckbox.isVisible = selectionMode
                binding.ruleCheckbox.isChecked = rule.key() in selectedKeys
                // 勾选框消费点击，不会冒泡到卡片
                binding.ruleCheckbox.setOnClickListener { toggleSelection(rule) }
                binding.root.setOnClickListener { onClick(rule) }
                binding.root.setOnLongClickListener { onLongClick(rule) }
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "package_name"
        const val EXTRA_LABEL = "label"

        /** 下发「删除全部规则」后等待目标进程推回空镜像的时间（ms） */
        private const val DELETE_VERIFY_DELAY_MS = 1_200L

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
