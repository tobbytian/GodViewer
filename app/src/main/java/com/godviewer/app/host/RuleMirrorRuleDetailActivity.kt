package com.godviewer.app.host

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import com.godviewer.app.R
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.databinding.ItemDetailRowBinding
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.model.ViewAttrSnapshot
import com.godviewer.app.shared.model.ViewRule
import com.godviewer.app.host.ui.ConfirmDialog
import com.godviewer.app.host.ui.HostActivity
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.godviewer.app.databinding.ActivityRuleMirrorRuleDetailBinding

/**
 * Host-side rule detail: identity card (thumb + fields) + modified/original value cards
 * + a prominent danger delete button. Values are read-only; delete is dispatched to the
 * target process (authority lives in the target's private dir).
 */
class RuleMirrorRuleDetailActivity : HostActivity() {
    private val binding by lazy { ActivityRuleMirrorRuleDetailBinding.inflate(layoutInflater) }
    private lateinit var packageName: String
    private lateinit var rule: ViewRule

    /** 「删除规则」下发后校验结果用（目标进程可能已不存在，广播会被静默丢弃） */
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val json = intent.getStringExtra(EXTRA_RULE_JSON)
        val parsed = runCatching { json?.let { Gson().fromJson(it, ViewRule::class.java) } }
            .getOrNull()
        if (parsed == null) {
            finish()
            return
        }
        rule = parsed

        setContentView(binding.root)

        val hidden = rule.changedVisibility && rule.modified.visibility == View.GONE
        val prefix = if (hidden) getString(R.string.hidden_status) + " · " else ""
        val title = prefix + rule.viewClass.substringAfterLast('.')
        binding.toolbar.title = title
        binding.toolbar.setNavigationOnClickListener { finish() }
        // 右上角溢出菜单：删除入口（弹窗样式由 HostSecondaryToolbar 的 popupTheme 统一）
        binding.toolbar.inflateMenu(R.menu.menu_rule_mirror_rule_detail)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_delete_rule) {
                confirmDeleteRule()
                true
            } else {
                false
            }
        }

        binding.detailTitle.text = title
        binding.detailActivity.text = rule.activityClass

        val thumb = RuleMirror.loadThumbnail(this, packageName, rule)
        if (thumb != null) {
            binding.detailThumb.setImageBitmap(thumb)
            binding.detailThumb.isVisible = true
        } else {
            binding.detailThumb.isVisible = false
        }

        fillIdentityRows()
        fillPropRows(binding.modifiedRows, rule.modified)
        fillPropRows(binding.originalRows, rule.original)

        binding.deleteButton.setOnClickListener { confirmDeleteRule() }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun fillIdentityRows() {
        rule.resourceName?.let { addRow(binding.identityRows, getString(R.string.rule_field_resource), it) }
        rule.text?.let { addRow(binding.identityRows, getString(R.string.rule_field_text), it) }
        rule.description?.let { addRow(binding.identityRows, getString(R.string.rule_field_desc), it) }
        addRow(binding.identityRows, getString(R.string.rule_field_depth), rule.depth.joinToString("/"))
        addRow(binding.identityRows, getString(R.string.rule_field_package), rule.packageName)
        addRow(
            binding.identityRows,
            getString(R.string.modify_time),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(rule.timestamp)),
        )
    }

    /** 按 changed* 标志填充属性行；无任何修改时显示占位破折号 */
    private fun fillPropRows(container: LinearLayout, snapshot: ViewAttrSnapshot) {
        if (rule.changedSize) {
            addRow(
                container,
                getString(R.string.size),
                "${formatDim(snapshot.width)} × ${formatDim(snapshot.height)}",
            )
        }
        if (rule.changedMargin) {
            addRow(
                container,
                getString(R.string.margin),
                "${snapshot.marginLeft}, ${snapshot.marginTop}, " +
                    "${snapshot.marginRight}, ${snapshot.marginBottom}",
            )
        }
        if (rule.changedPadding) {
            addRow(
                container,
                getString(R.string.padding),
                "${snapshot.paddingLeft}, ${snapshot.paddingTop}, " +
                    "${snapshot.paddingRight}, ${snapshot.paddingBottom}",
            )
        }
        if (rule.changedVisibility) {
            val value = if (snapshot.visibility == View.GONE) {
                getString(R.string.hidden_status)
            } else {
                getString(R.string.visible)
            }
            addRow(container, getString(R.string.visibility), value)
        }
        if (rule.changedText) {
            addRow(container, getString(R.string.text_content), snapshot.text ?: "")
        }
        if (rule.changedHint) {
            addRow(container, getString(R.string.text_hint), snapshot.hint ?: "")
        }
        if (rule.changedImage) {
            addRow(container, getString(R.string.image_url), snapshot.imageUrl ?: "")
            snapshot.scaleType?.let { addRow(container, getString(R.string.scale_type), it) }
        }
        if (container.childCount == 0) {
            val placeholder = TextView(this).apply {
                text = "—"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(getColor(R.color.home_subtitle_text))
                setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            container.addView(placeholder)
        }
    }

    private fun addRow(container: LinearLayout, label: String, value: String) {
        val row = ItemDetailRowBinding.bind(
            layoutInflater.inflate(R.layout.item_detail_row, container, false),
        )
        row.rowLabel.text = label
        row.rowValue.text = value
        container.addView(row.root)
    }

    private fun formatDim(value: Int): String = when (value) {
        ViewGroup.LayoutParams.MATCH_PARENT -> "match_parent"
        ViewGroup.LayoutParams.WRAP_CONTENT -> "wrap_content"
        else -> value.toString()
    }

    /** 单条删除：权威规则在目标应用内，只能由目标进程执行（还原视图 + 落盘 + 回推镜像） */
    private fun confirmDeleteRule() {
        ConfirmDialog.showDelete(
            this,
            getString(R.string.delete_rule),
            getString(R.string.delete_rule_confirm_message),
        ) { requestDeleteRule() }
    }

    private fun requestDeleteRule() {
        val dispatched = HostControlBridge.dispatchRulesToPackage(this, packageName, listOf(rule))
        if (!dispatched) {
            Toast.makeText(this, R.string.delete_rule_failed, Toast.LENGTH_LONG).show()
            return
        }
        // 目标进程删除成功会推回镜像，稍后按 key 确认该规则已消失
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val gone = !RuleMirror.loadRules(this, packageName).any { it.key() == rule.key() }
            if (gone) {
                Toast.makeText(this, R.string.delete_rule_done, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, R.string.delete_rule_failed, Toast.LENGTH_LONG).show()
            }
        }, DELETE_VERIFY_DELAY_MS)
    }

    companion object {
        const val EXTRA_PACKAGE = "package_name"
        const val EXTRA_RULE_JSON = "rule_json"

        /** 下发「删除规则」后等待目标进程推回镜像的时间（ms） */
        private const val DELETE_VERIFY_DELAY_MS = 1_200L
    }
}