package com.godviewer.app.host

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.godviewer.app.R
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.host.control.HostControlNotifier
import com.godviewer.app.host.control.PendingCommandStore
import com.godviewer.app.host.io.RuleBundle
import com.godviewer.app.host.io.RuleTransfer
import com.godviewer.app.host.prefs.HostPrefs
import com.godviewer.app.host.ui.RuleTransferUi
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.model.DialogRule
import com.godviewer.app.shared.model.ViewRule
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.godviewer.app.databinding.ActivityRuleMirrorDetailBinding
import com.godviewer.app.databinding.ItemMirroredRuleBinding
import com.godviewer.app.databinding.ItemSectionHeaderBinding
import com.godviewer.app.host.ui.ConfirmDialog
import com.godviewer.app.host.ui.HostActivity
import com.godviewer.app.host.ui.PopupSettingsActivity

/**
 * Host-side package rule list, styled like the in-target [com.godviewer.app.target.ui.RuleManagerDialog] rows.
 */
class RuleMirrorDetailActivity : HostActivity() {
    private val binding by lazy { ActivityRuleMirrorDetailBinding.inflate(layoutInflater) }
    private lateinit var packageName: String

    /** 「删除全部规则」下发后校验结果用（目标进程可能已不存在，广播会被静默丢弃） */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 导入：SAF 选 JSON → 只取本应用 entry → 入队下发 */
    private val importDocLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val json = RuleTransfer.readText(this, uri)
        if (json == null) {
            Toast.makeText(
                this,
                getString(R.string.import_invalid, "read failed"),
                Toast.LENGTH_LONG,
            ).show()
            return@registerForActivityResult
        }
        RuleTransferUi.startImportFlow(this, json, onlyPackage = packageName) { _, _ -> reload() }
    }

    /** 导出：SAF 建文件 → 结果返回时读最新镜像写出 */
    private val exportDocLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val bundle = RuleBundle.forPackage(this, packageName)
        if (bundle == null) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val ok = RuleTransfer.writeText(this, uri, RuleBundle.encode(bundle))
        Toast.makeText(
            this,
            if (ok) {
                getString(
                    R.string.export_done,
                    1,
                    bundle.apps.sumOf { it.viewRules.size + it.dialogRules.size },
                )
            } else {
                getString(R.string.export_save_failed)
            },
            Toast.LENGTH_LONG,
        ).show()
    }

    /** 程序化设置每应用通知开关时抑制回调 */
    private var suppressNotifyCallback = false

    /** 多选模式（勾选多条规则批量删除） */
    private var selectionMode = false
    private val selectedKeys = LinkedHashSet<ViewRule.RuleKey>()

    /** 最近一次加载的规则（按 timestamp 降序），供选择/删除取值 */
    private var rulesCache: List<ViewRule> = emptyList()

    /** 最近一次加载的弹窗规则（长按删除取值） */
    private var dialogRulesCache: List<DialogRule> = emptyList()

    /** 待同步命令涉及的规则 key（目标未在线时用户的删除/编辑还没生效），行上标「待同步」 */
    private var pendingDialogKeys: Set<DialogRule.Key> = emptySet()
    private var pendingViewKeys: Set<ViewRule.RuleKey> = emptySet()

    private val adapter = RuleAdapter(
        onClick = { rule -> onRuleClicked(rule) },
        onLongClick = { rule -> onRuleLongClicked(rule) },
        onDialogLongClick = { rule -> confirmDeleteDialogRule(rule) },
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
        // 分组分割线：MenuAdapter 只在 group 切换处画线，且该开关默认关闭
        (binding.toolbar.menu as? MenuBuilder)?.setGroupDividerEnabled(true)
        tintMenuItemDanger(R.id.action_delete_all)
        tintMenuItemDanger(R.id.action_delete_selected)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_delete_all -> {
                    confirmDeleteAllRules()
                    true
                }
                R.id.action_export -> {
                    onExportClicked()
                    true
                }
                R.id.action_import -> {
                    onImportClicked()
                    true
                }
                R.id.action_refresh_mirror -> {
                    refreshMirror()
                    true
                }
                R.id.action_select_multiple -> {
                    setSelectionMode(true)
                    true
                }
                R.id.action_select_all -> {
                    // 多选只针对视图规则（弹窗规则无选择态，长按单删）
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

        // 弹窗检测设置：进入该应用的二级设置页（检测开关 / 时长 / 弹窗规则）
        binding.popupSettingsRow.setOnClickListener {
            startActivity(
                Intent(this, PopupSettingsActivity::class.java)
                    .putExtra(PopupSettingsActivity.EXTRA_PACKAGE, packageName),
            )
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

    /** 把溢出菜单里的破坏性选项标成红色，与普通项区分 */
    private fun tintMenuItemDanger(itemId: Int) {
        val item = binding.toolbar.menu.findItem(itemId) ?: return
        val title = item.title?.toString() ?: return
        item.title = android.text.SpannableString(title).apply {
            setSpan(
                android.text.style.ForegroundColorSpan(
                    androidx.core.content.ContextCompat.getColor(
                        this@RuleMirrorDetailActivity, R.color.md_theme_light_error,
                    ),
                ),
                0,
                length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun reload() {
        val rules = RuleMirror.loadRules(this, packageName)
            .sortedByDescending { it.timestamp }
        val dialogRules = RuleMirror.loadDialogRules(this, packageName)
            .sortedByDescending { it.timestamp }
        rulesCache = rules
        dialogRulesCache = dialogRules
        pendingDialogKeys = PendingCommandStore.pendingDialogDeleteKeys(this, packageName)
        pendingViewKeys = PendingCommandStore.pendingViewDeleteKeys(this, packageName) +
            PendingCommandStore.pendingViewUpdateKeys(this, packageName)
        // 镜像刷新后清掉已不存在的选择（例如目标端删了规则）
        selectedKeys.retainAll(rules.map { it.key() })
        val rows = buildList {
            // 弹窗规则分区置顶（有关联数据时），视图规则区带分组标题隔开
            if (dialogRules.isNotEmpty()) {
                add(Row.Header(getString(R.string.dialog_rules_section)))
                dialogRules.forEach { add(Row.DialogRuleRow(it)) }
                add(Row.Header(getString(R.string.view_rules_section)))
            }
            rules.forEach { add(Row.ViewRuleRow(it)) }
        }
        adapter.submit(packageName, rows)
        binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.toolbar.menu.findItem(R.id.action_delete_all)?.isEnabled = rows.isNotEmpty()
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
            findItem(R.id.action_export)?.isVisible = !selectionMode
            findItem(R.id.action_import)?.isVisible = !selectionMode
            findItem(R.id.action_refresh_mirror)?.isVisible = !selectionMode
            findItem(R.id.action_select_all)?.isVisible = selectionMode
            findItem(R.id.action_delete_selected)?.apply {
                isVisible = selectionMode
                isEnabled = selectedKeys.isNotEmpty()
            }
        }
    }

    // ---------- 导入 / 导出 / 刷新 ----------

    private fun onExportClicked() {
        // 打开选项即请求目标补推镜像：用户挑选期间若目标存活，导出更新鲜
        HostControlBridge.requestMirrorPush(this, packageName)
        RuleTransferUi.showExportOptions(
            activity = this,
            onPickFile = {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                exportDocLauncher.launch("godviewer-$packageName-$stamp.json")
            },
            onShare = { json ->
                val ok = RuleTransfer.shareJson(
                    this,
                    json,
                    getString(R.string.rules_export_title),
                )
                if (!ok) {
                    Toast.makeText(this, R.string.export_save_failed, Toast.LENGTH_SHORT).show()
                }
            },
            onCopy = { json ->
                val ok = RuleTransfer.copyToClipboard(this, json)
                Toast.makeText(
                    this,
                    if (ok) R.string.export_copy_done else R.string.export_save_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            },
            buildJson = { buildExportJson() },
        )
    }

    private fun buildExportJson(): String? {
        val bundle = RuleBundle.forPackage(this, packageName) ?: return null
        return RuleBundle.encode(bundle)
    }

    private fun onImportClicked() {
        RuleTransferUi.showImportOptions(
            activity = this,
            onPickFile = { importDocLauncher.launch(arrayOf("*/*")) },
            onPaste = {
                val json = RuleTransfer.pasteFromClipboard(this)
                if (json == null) {
                    Toast.makeText(this, R.string.import_clipboard_empty, Toast.LENGTH_SHORT)
                        .show()
                } else {
                    RuleTransferUi.startImportFlow(this, json, onlyPackage = packageName) {
                        _, _ -> reload()
                    }
                }
            },
        )
    }

    /** 请求目标补推镜像后延迟回读（目标不在则镜像不变，列表无感） */
    private fun refreshMirror() {
        HostControlBridge.requestMirrorPush(this, packageName)
        Toast.makeText(this, R.string.mirror_refresh_requested, Toast.LENGTH_SHORT).show()
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            reload()
        }, MIRROR_REFRESH_DELAY_MS)
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

    // ---------- 弹窗规则删除（长按） ----------

    private fun confirmDeleteDialogRule(rule: DialogRule) {
        ConfirmDialog.showDelete(
            this,
            getString(R.string.delete_dialog_rule),
            getString(R.string.delete_dialog_rule_confirm_message),
        ) { requestDeleteDialogRules(listOf(rule)) }
    }

    /**
     * 下发删除弹窗规则（权威 dialog_rules.json 在目标应用内，由目标进程执行删除+回推镜像），
     * 稍后回读镜像按 key 确认结果；目标不存活则留在待同步队列，打开应用自动同步删除。
     */
    private fun requestDeleteDialogRules(rules: List<DialogRule>) {
        if (rules.isEmpty()) return
        val submitted = PendingCommandStore.submitDeleteDialogRules(this, packageName, rules)
        if (!submitted) {
            Toast.makeText(this, R.string.delete_dialog_rule_failed, Toast.LENGTH_LONG).show()
            return
        }
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val remaining = RuleMirror.loadDialogRules(this, packageName)
            val allGone = rules.none { r -> remaining.any { it.key() == r.key() } }
            Toast.makeText(
                this,
                if (allGone) R.string.delete_dialog_rule_done
                else R.string.delete_queued,
                if (allGone) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
            ).show()
            reload()
        }, DELETE_VERIFY_DELAY_MS)
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
     * 稍后回读镜像按 key 确认结果；目标不存活则留在待同步队列，打开应用自动同步删除。
     */
    private fun requestDeleteRules(rules: List<ViewRule>) {
        if (rules.isEmpty()) return
        val single = rules.size == 1
        val submitted = PendingCommandStore.submitDeleteRules(this, packageName, rules)
        if (!submitted) {
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
                }
            } else {
                // 未确认 = 目标不在：命令已在待同步队列，提示等待打开目标应用同步
                Toast.makeText(this, R.string.delete_queued, Toast.LENGTH_LONG).show()
            }
            // 无论确认与否命令都已提交，退出多选回到普通模式
            if (!single) setSelectionMode(false)
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

    /** 一键删除该应用全部规则：权威规则在目标应用内，只能由目标进程执行（连带清空弹窗规则） */
    private fun confirmDeleteAllRules() {
        val viewCount = RuleMirror.loadRules(this, packageName).size
        val dialogCount = RuleMirror.loadDialogRules(this, packageName).size
        val count = viewCount + dialogCount
        if (count <= 0) return
        val label = binding.appLabelView.text?.toString().orEmpty().ifBlank { packageName }
        ConfirmDialog.showDelete(
            this,
            getString(R.string.delete_all_rules),
            getString(R.string.delete_all_rules_confirm_message, label, count),
        ) { requestDeleteAllRules(count) }
    }

    private fun requestDeleteAllRules(expectedCount: Int) {
        val submitted = PendingCommandStore.submitDeleteAll(this, packageName)
        if (!submitted) {
            Toast.makeText(this, R.string.delete_all_rules_failed, Toast.LENGTH_LONG).show()
            return
        }
        // 目标删除成功会推回空镜像（宿主镜像目录被清除），稍后据此确认结果；
        // 目标不存活则留在待同步队列，打开应用自动清空
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val remaining = RuleMirror.loadRules(this, packageName).size +
                RuleMirror.loadDialogRules(this, packageName).size
            val msg = if (remaining == 0) {
                getString(R.string.delete_all_rules_done, expectedCount)
            } else {
                getString(R.string.delete_queued)
            }
            Toast.makeText(
                this,
                msg,
                if (remaining == 0) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
            ).show()
            reload()
        }, DELETE_VERIFY_DELAY_MS)
    }

    /** 列表行：分组标题 / 视图规则 / 弹窗规则（三 viewType 单 adapter） */
    private sealed class Row {
        data class Header(val title: String) : Row()
        data class ViewRuleRow(val rule: ViewRule) : Row()
        data class DialogRuleRow(val rule: DialogRule) : Row()
    }

    private inner class RuleAdapter(
        private val onClick: (ViewRule) -> Unit,
        private val onLongClick: (ViewRule) -> Boolean,
        private val onDialogLongClick: (DialogRule) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var pkg: String = ""
        private val items = ArrayList<Row>()

        fun submit(packageName: String, rows: List<Row>) {
            pkg = packageName
            items.clear()
            items.addAll(rows)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is Row.Header -> VIEW_TYPE_HEADER
            is Row.ViewRuleRow -> VIEW_TYPE_VIEW_RULE
            is Row.DialogRuleRow -> VIEW_TYPE_DIALOG_RULE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                VIEW_TYPE_HEADER -> SectionHeaderHolder(
                    ItemSectionHeaderBinding.inflate(inflater, parent, false),
                )
                VIEW_TYPE_DIALOG_RULE -> DialogRuleHolder(
                    ItemMirroredRuleBinding.inflate(inflater, parent, false),
                )
                else -> RuleHolder(
                    ItemMirroredRuleBinding.inflate(inflater, parent, false),
                )
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = items[position]) {
                is Row.Header -> (holder as SectionHeaderHolder).bind(row)
                is Row.ViewRuleRow -> (holder as RuleHolder).bind(row.rule)
                is Row.DialogRuleRow -> (holder as DialogRuleHolder).bind(row.rule)
            }
        }

        override fun getItemCount(): Int = items.size

        /** 分组标题行（不可点） */
        private inner class SectionHeaderHolder(
            private val binding: ItemSectionHeaderBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(row: Row.Header) {
                binding.sectionTitle.text = row.title
            }
        }

        /** 视图规则行：缩略图 + 多选勾 + 点击详情 / 长按删除 */
        private inner class RuleHolder(
            private val binding: ItemMirroredRuleBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(rule: ViewRule) {
                val hidden = rule.changedVisibility && rule.modified.visibility == View.GONE
                val prefix = if (hidden) getString(R.string.hidden_status) + " · " else ""
                val shortView = rule.viewClass.substringAfterLast('.')
                binding.ruleTitle.text = prefix + shortView.ifBlank { rule.viewClass }
                val pending = rule.key() in pendingViewKeys
                binding.ruleSubtitle.text = if (pending) {
                    getString(R.string.popup_pending_sync) + " · " + formatTimestamp(rule.timestamp)
                } else {
                    formatTimestamp(rule.timestamp)
                }

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

        /** 弹窗规则行：无缩略图/多选，标题 = 首条指纹文本，长按删除 */
        private inner class DialogRuleHolder(
            private val binding: ItemMirroredRuleBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(rule: DialogRule) {
                binding.ruleTitle.text = rule.title().take(48)
                val shortActivity = rule.activityClass.substringAfterLast('.')
                binding.ruleSubtitle.text = buildString {
                    if (rule.key() in pendingDialogKeys) {
                        append(getString(R.string.popup_pending_delete))
                        append(" · ")
                    }
                    append(shortActivity.ifBlank { "-" })
                    append(" · ")
                    append(formatTimestamp(rule.timestamp))
                }
                binding.ruleThumb.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                // 弹窗规则不参与多选
                binding.ruleCheckbox.isVisible = false
                binding.root.setOnClickListener { onDialogLongClick(rule) }
                binding.root.setOnLongClickListener {
                    onDialogLongClick(rule)
                    true
                }
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "package_name"
        const val EXTRA_LABEL = "label"

        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_VIEW_RULE = 1
        private const val VIEW_TYPE_DIALOG_RULE = 2

        /** 下发「删除全部规则」后等待目标进程推回空镜像的时间（ms） */
        private const val DELETE_VERIFY_DELAY_MS = 1_200L

        /** 请求补推镜像后等待回读的时间（ms） */
        private const val MIRROR_REFRESH_DELAY_MS = 800L

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
