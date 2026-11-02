package com.godviewer.app.host

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import com.godviewer.app.R
import com.godviewer.app.databinding.ActivityRuleEditBinding
import com.godviewer.app.host.control.PendingCommandStore
import com.godviewer.app.host.ui.HostActivity
import com.godviewer.app.shared.model.ViewRule
import com.google.gson.Gson

/**
 * Host-side rule edit: 每张卡 = 一个 changed* 属性组（开关决定是否生效），
 * 编辑的是 [ViewRule.modified] 快照；[ViewRule.original] 只读不可改。
 *
 * 保存走 [PendingCommandStore.submitUpdate]（目标存活即达；不在则待同步队列
 * 在目标下次上报前台时重发，upsert 幂等）。成功后把编辑结果回传给详情页。
 */
class RuleMirrorRuleEditActivity : HostActivity() {
    private val binding by lazy { ActivityRuleEditBinding.inflate(layoutInflater) }
    private lateinit var packageName: String
    private lateinit var rule: ViewRule

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
        binding.toolbar.title = getString(R.string.rule_edit)
        binding.toolbar.setNavigationOnClickListener { finish() }

        bindSpinners()
        prefill()
        bindSwitchGates()
        binding.saveButton.setOnClickListener { save() }
    }

    // ---------- 初始化 ----------

    private fun bindSpinners() {
        // 可见性：标签可翻译，值固定 VISIBLE/INVISIBLE/GONE
        val visLabels = listOf(
            getString(R.string.visible),
            getString(R.string.invisible),
            getString(R.string.hidden_status),
        )
        binding.visibilitySpinner.adapter = hostSpinnerAdapter(visLabels)
        binding.visibilitySpinner.setSelection(
            when (rule.modified.visibility) {
                View.INVISIBLE -> 1
                View.GONE -> 2
                else -> 0
            },
        )
        // scaleType：标准 API 值，无需翻译
        binding.scaleTypeSpinner.adapter = hostSpinnerAdapter(SCALE_TYPES.toList())
        val currentScale = rule.modified.scaleType
        val scaleIndex = SCALE_TYPES.indexOf(currentScale).takeIf { it >= 0 }
            ?: SCALE_TYPES.indexOf(DEFAULT_SCALE_TYPE)
        binding.scaleTypeSpinner.setSelection(scaleIndex.coerceAtLeast(0))
    }

    /** 宿主风格下拉框：收起项 / 展开项都用 home_* 配色，不走系统默认 */
    private fun hostSpinnerAdapter(items: Collection<String>): ArrayAdapter<String> =
        ArrayAdapter(this, R.layout.item_spinner, items.toList()).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }

    private fun prefill() {
        val m = rule.modified
        binding.sizeSwitch.isChecked = rule.changedSize
        binding.widthInput.setText(m.width.toString())
        binding.heightInput.setText(m.height.toString())
        binding.marginSwitch.isChecked = rule.changedMargin
        binding.marginInput.setText(
            "${m.marginLeft}, ${m.marginTop}, ${m.marginRight}, ${m.marginBottom}",
        )
        binding.paddingSwitch.isChecked = rule.changedPadding
        binding.paddingInput.setText(
            "${m.paddingLeft}, ${m.paddingTop}, ${m.paddingRight}, ${m.paddingBottom}",
        )
        binding.visibilitySwitch.isChecked = rule.changedVisibility
        binding.textSwitch.isChecked = rule.changedText
        binding.textInput.setText(m.text.orEmpty())
        binding.maxLinesInput.setText(m.maxLines.toString())
        binding.hintSwitch.isChecked = rule.changedHint
        binding.hintInput.setText(m.hint.orEmpty())
        binding.imageSwitch.isChecked = rule.changedImage
        binding.imageUrlInput.setText(m.imageUrl.orEmpty())
    }

    /** 开关状态 → 对应输入区可编辑性（关掉时置灰，值保留以便再次开启） */
    private fun bindSwitchGates() {
        binding.sizeSwitch.setOnCheckedChangeListener { _, checked -> setEnabled(binding.sizeFields, checked) }
        binding.marginSwitch.setOnCheckedChangeListener { _, checked -> setEnabled(binding.marginInput, checked) }
        binding.paddingSwitch.setOnCheckedChangeListener { _, checked -> setEnabled(binding.paddingInput, checked) }
        binding.visibilitySwitch.setOnCheckedChangeListener { _, checked -> setEnabled(binding.visibilitySpinner, checked) }
        binding.textSwitch.setOnCheckedChangeListener { _, checked ->
            setEnabled(binding.textInput, checked)
            setEnabled(binding.maxLinesInput, checked)
        }
        binding.hintSwitch.setOnCheckedChangeListener { _, checked -> setEnabled(binding.hintInput, checked) }
        binding.imageSwitch.setOnCheckedChangeListener { _, checked ->
            setEnabled(binding.imageUrlInput, checked)
            setEnabled(binding.scaleTypeSpinner, checked)
        }
        // 应用初始状态（prefill 设置的是相同值，不会触发监听器，这里显式执行）
        setEnabled(binding.sizeFields, binding.sizeSwitch.isChecked)
        setEnabled(binding.marginInput, binding.marginSwitch.isChecked)
        setEnabled(binding.paddingInput, binding.paddingSwitch.isChecked)
        setEnabled(binding.visibilitySpinner, binding.visibilitySwitch.isChecked)
        setEnabled(binding.textInput, binding.textSwitch.isChecked)
        setEnabled(binding.maxLinesInput, binding.textSwitch.isChecked)
        setEnabled(binding.hintInput, binding.hintSwitch.isChecked)
        setEnabled(binding.imageUrlInput, binding.imageSwitch.isChecked)
        setEnabled(binding.scaleTypeSpinner, binding.imageSwitch.isChecked)
    }

    private fun setEnabled(target: View, enabled: Boolean) {
        target.isEnabled = enabled
        target.alpha = if (enabled) 1f else 0.4f
    }

    private fun setEnabled(target: android.view.ViewGroup, enabled: Boolean) {
        target.isEnabled = enabled
        target.alpha = if (enabled) 1f else 0.4f
        for (i in 0 until target.childCount) setEnabled(target.getChildAt(i), enabled)
    }

    // ---------- 保存 ----------

    private fun save() {
        val m = rule.modified

        val width = binding.widthInput.text.toString().trim().toIntOrNull()
        val height = binding.heightInput.text.toString().trim().toIntOrNull()
        if (width == null || height == null) {
            toastNumberError()
            return
        }
        val margin = parseDims(binding.marginInput.text.toString())
        if (margin == null) {
            toastDimsError()
            return
        }
        val padding = parseDims(binding.paddingInput.text.toString())
        if (padding == null) {
            toastDimsError()
            return
        }
        val maxLines = binding.maxLinesInput.text.toString().trim().toIntOrNull()
        if (maxLines == null) {
            toastNumberError()
            return
        }

        val visibility = when (binding.visibilitySpinner.selectedItemPosition) {
            1 -> View.INVISIBLE
            2 -> View.GONE
            else -> View.VISIBLE
        }
        val scaleType = SCALE_TYPES
            .getOrNull(binding.scaleTypeSpinner.selectedItemPosition)
            ?: m.scaleType

        val modified = m.copy(
            width = width,
            height = height,
            marginLeft = margin[0],
            marginTop = margin[1],
            marginRight = margin[2],
            marginBottom = margin[3],
            paddingLeft = padding[0],
            paddingTop = padding[1],
            paddingRight = padding[2],
            paddingBottom = padding[3],
            visibility = visibility,
            text = binding.textInput.text.toString(),
            hint = binding.hintInput.text.toString(),
            maxLines = maxLines,
            imageUrl = binding.imageUrlInput.text.toString().ifBlank { null },
            scaleType = scaleType,
        )
        val edited = rule.copy(
            modified = modified,
            changedSize = binding.sizeSwitch.isChecked,
            changedMargin = binding.marginSwitch.isChecked,
            changedPadding = binding.paddingSwitch.isChecked,
            changedVisibility = binding.visibilitySwitch.isChecked,
            changedText = binding.textSwitch.isChecked,
            changedHint = binding.hintSwitch.isChecked,
            changedImage = binding.imageSwitch.isChecked,
            timestamp = System.currentTimeMillis(),
        )

        val sent = PendingCommandStore.submitUpdate(this, packageName, listOf(edited))
        if (!sent) {
            Toast.makeText(this, R.string.update_rule_failed, Toast.LENGTH_LONG).show()
            return
        }
        setResult(
            RESULT_OK,
            Intent().putExtra(RuleMirrorRuleDetailActivity.EXTRA_RULE_JSON, Gson().toJson(edited)),
        )
        finish()
    }

    /** "左, 上, 右, 下" → 4 个整数；格式不符返回 null */
    private fun parseDims(raw: String): IntArray? {
        val parts = raw.split(Regex("[,，\\s]+")).filter { it.isNotEmpty() }
        if (parts.size != 4) return null
        val nums = IntArray(4)
        for (i in 0..3) {
            nums[i] = parts[i].toIntOrNull() ?: return null
        }
        return nums
    }

    private fun toastNumberError() {
        Toast.makeText(this, R.string.edit_error_number, Toast.LENGTH_SHORT).show()
    }

    private fun toastDimsError() {
        Toast.makeText(this, R.string.edit_error_dims, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_PACKAGE = "package_name"
        const val EXTRA_RULE_JSON = "rule_json"

        /** ImageView.scaleType 标准值（API 命名，无需翻译） */
        private val SCALE_TYPES = arrayOf(
            "matrix",
            "fitXY",
            "fitStart",
            "fitCenter",
            "fitEnd",
            "center",
            "centerCrop",
            "centerInside",
        )
        private const val DEFAULT_SCALE_TYPE = "fitCenter"
    }
}