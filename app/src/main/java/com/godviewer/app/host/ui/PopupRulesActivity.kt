package com.godviewer.app.host.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.godviewer.app.R
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.databinding.ActivityPopupRulesBinding
import com.godviewer.app.databinding.ItemPopupRuleBinding
import com.godviewer.app.databinding.ItemSectionHeaderBinding
import com.godviewer.app.host.control.PendingCommandStore
import com.godviewer.app.shared.model.DialogRule
import java.text.DateFormat
import java.util.Date

/**
 * 弹窗规则页：点按编辑文本指纹，长按删除，溢出菜单手动新建。
 * 带 [EXTRA_PACKAGE] 打开 = 单应用过滤模式（单应用规则页入口），标题换应用名、
 * 不显示分区头、可新建；不带 = 全部应用（历史入口）。
 * 权威数据在目标应用内：编辑/新建 = 删旧 + upsert，都走待同步队列，
 * 目标进程不在时下次打开该应用自动补发。
 */
class PopupRulesActivity : HostActivity() {
    private val binding by lazy { ActivityPopupRulesBinding.inflate(layoutInflater) }
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = Adapter()
    private val timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    /** 单应用过滤模式的包名；null = 全部应用 */
    private var filterPackage: String? = null

    private sealed class Row {
        data class Header(val title: String) : Row()

        data class Item(
            val rule: DialogRule,
            val pendingLabel: Int = 0,
            // 规则版本号与已安装版本不等：以高阈值降级匹配（跨版本仍会尝试拦截）
            val crossVersion: Boolean = false,
        ) : Row()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        filterPackage = intent.getStringExtra(EXTRA_PACKAGE)
        filterPackage?.let { binding.toolbar.title = RuleMirror.loadAppLabel(this, it) }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_popup_rules)
        // 新建只在单应用过滤模式下开放（需要确定的包名与已安装版本号）
        binding.toolbar.menu.findItem(R.id.action_new_rule)?.isVisible = filterPackage != null
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_new_rule) {
                showCreate()
                true
            } else {
                false
            }
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun reload() {
        val filter = filterPackage
        val rows = buildList {
            val packages = RuleMirror.listPackages(this@PopupRulesActivity)
                .filter { filter == null || it.packageName == filter }
            for (pkg in packages) {
                val mirror = RuleMirror.loadDialogRules(this@PopupRulesActivity, pkg.packageName)
                val mirrorKeys = mirror.mapTo(HashSet()) { it.key() }
                val deleteKeys = PendingCommandStore
                    .pendingDialogDeleteKeys(this@PopupRulesActivity, pkg.packageName)
                // 编辑后目标还没收到：新版本尚不在镜像里，也要显示出来
                val upserts = PendingCommandStore
                    .pendingDialogUpserts(this@PopupRulesActivity, pkg.packageName)
                    .filter { it.key() !in mirrorKeys }
                val installedVer = installedVersionOf(pkg.packageName)
                val items = (mirror.map {
                    Row.Item(
                        rule = it,
                        pendingLabel = if (it.key() in deleteKeys) {
                            R.string.popup_pending_delete
                        } else {
                            0
                        },
                        crossVersion = installedVer != null && it.matchVersionCode != installedVer,
                    )
                } + upserts.map { Row.Item(it, R.string.popup_pending_sync) })
                    .sortedByDescending { it.rule.timestamp }
                if (items.isEmpty()) continue
                // 单应用过滤模式只有一个分区，不需要分区头
                if (filter == null) add(Row.Header(pkg.label))
                items.forEach { add(it) }
            }
        }
        adapter.submit(rows)
        binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 手动新建弹窗规则：文本指纹必填；包名取过滤模式，版本号取当前已安装版本 */
    private fun showCreate() {
        val pkg = filterPackage ?: return
        val content = layoutInflater.inflate(R.layout.dialog_popup_rule_create, null)
        val textsInput = content.findViewById<EditText>(R.id.create_texts)
        val classInput = content.findViewById<EditText>(R.id.create_class)
        val activityInput = content.findViewById<EditText>(R.id.create_activity)
        AlertDialog.Builder(this)
            .setTitle(R.string.popup_rule_create_title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.popup_save) { _, _ ->
                val texts = textsInput.text.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { it.take(MAX_TEXT_LEN) }
                    .distinct()
                    .take(MAX_TEXTS)
                if (texts.isEmpty()) {
                    Toast.makeText(this, R.string.popup_rule_edit_empty, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val rule = DialogRule(
                    packageName = pkg,
                    matchVersionCode = installedVersionOf(pkg) ?: 0,
                    activityClass = activityInput.text.toString().trim(),
                    dialogClass = classInput.text.toString().trim(),
                    texts = texts,
                    timestamp = System.currentTimeMillis(),
                )
                val sent = PendingCommandStore.submitImport(
                    this,
                    pkg,
                    emptyList(),
                    listOf(rule),
                )
                Toast.makeText(
                    this,
                    if (sent) {
                        R.string.popup_rule_create_done
                    } else {
                        R.string.popup_rule_create_queued
                    },
                    Toast.LENGTH_LONG,
                ).show()
                reloadLater()
            }
            .show()
    }

    /** 已安装版本号（best-effort：应用已卸载/不可见返回 null，不标记角标） */
    private fun installedVersionOf(packageName: String): Int? = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionCode
    }.getOrNull()

    private fun reloadLater() {
        handler.postDelayed({
            if (!isFinishing && !isDestroyed) reload()
        }, VERIFY_DELAY_MS)
    }

    private fun confirmDelete(rule: DialogRule) {
        ConfirmDialog.showDelete(
            this,
            getString(R.string.delete_dialog_rule),
            getString(R.string.delete_dialog_rule_confirm_message),
        ) {
            if (PendingCommandStore.submitDeleteDialogRules(this, rule.packageName, listOf(rule))) {
                Toast.makeText(this, R.string.delete_dialog_rule_done, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.delete_queued, Toast.LENGTH_LONG).show()
            }
            reloadLater()
        }
    }

    private fun showEdit(rule: DialogRule) {
        val input = EditText(this).apply {
            // 新拟物输入框：凹陷底（随后 setPadding 20dp 覆盖 drawable 内边距）
            background = getDrawable(R.drawable.bg_text_input)
            setText(rule.texts.joinToString("\n"))
            minLines = 4
            gravity = Gravity.TOP
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        input.setPadding(pad, pad, pad, pad)
        AlertDialog.Builder(this)
            .setTitle(R.string.popup_rule_edit_title)
            .setMessage(R.string.popup_rule_edit_message)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.delete) { _, _ -> confirmDelete(rule) }
            .setPositiveButton(R.string.popup_save) { _, _ ->
                val texts = input.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    .map { it.take(MAX_TEXT_LEN) }.distinct().take(MAX_TEXTS)
                if (texts.isEmpty()) {
                    Toast.makeText(this, R.string.popup_rule_edit_empty, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (texts == rule.texts) return@setPositiveButton
                val updated = rule.copy(texts = texts, timestamp = System.currentTimeMillis())
                PendingCommandStore.submitDeleteDialogRules(this, rule.packageName, listOf(rule))
                PendingCommandStore.submitImport(
                    this, rule.packageName, emptyList(), listOf(updated),
                )
                Toast.makeText(this, R.string.popup_rule_edit_done, Toast.LENGTH_SHORT).show()
                reloadLater()
            }
            .show()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = ArrayList<Row>()

        fun submit(rows: List<Row>) {
            items.clear()
            items.addAll(rows)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) =
            if (items[position] is Row.Header) VIEW_HEADER else VIEW_ITEM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val root = if (viewType == VIEW_HEADER) {
                ItemSectionHeaderBinding.inflate(inflater, parent, false).root
            } else {
                ItemPopupRuleBinding.inflate(inflater, parent, false).root
            }
            return object : RecyclerView.ViewHolder(root) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = items[position]) {
                is Row.Header ->
                    ItemSectionHeaderBinding.bind(holder.itemView).sectionTitle.text = row.title

                is Row.Item -> {
                    val b = ItemPopupRuleBinding.bind(holder.itemView)
                    val rule = row.rule
                    b.ruleTitle.text = rule.title()
                    val base = getString(
                        R.string.popup_rule_subtitle,
                        rule.texts.size,
                        timeFormat.format(Date(rule.timestamp)),
                    )
                    val tags = buildList {
                        if (row.crossVersion) add(getString(R.string.popup_rule_cross_version))
                        if (row.pendingLabel != 0) add(getString(row.pendingLabel))
                    }
                    b.ruleSubtitle.text = if (tags.isEmpty()) base else "${tags.joinToString(" · ")} · $base"
                    b.root.setOnClickListener { showEdit(rule) }
                    b.root.setOnLongClickListener {
                        confirmDelete(rule)
                        true
                    }
                }
            }
        }

        override fun getItemCount() = items.size
    }

    companion object {
        const val EXTRA_PACKAGE = "extra_package"

        private const val VERIFY_DELAY_MS = 1_200L
        private const val MAX_TEXTS = 10
        private const val MAX_TEXT_LEN = 80
        private const val VIEW_HEADER = 0
        private const val VIEW_ITEM = 1
    }
}
