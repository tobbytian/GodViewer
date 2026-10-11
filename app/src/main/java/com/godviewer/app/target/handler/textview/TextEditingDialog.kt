package com.godviewer.app.target.handler.textview

import android.os.Bundle
import android.text.SpannableString
import android.widget.TextView
import com.godviewer.app.R
import com.godviewer.app.target.dialog.ModuleDialogUi
import com.godviewer.app.target.hook.ModuleRes
import com.godviewer.app.target.hook.ModuleRes.moduleRes
import com.godviewer.app.databinding.LayoutTextViewAttrBinding
import com.godviewer.app.target.ui.BaseAttrDialog

/**
 * @author hhvvg
 *
 * Editing attributes in TextView.
 */
class TextEditingDialog(private val view: TextView) : BaseAttrDialog<TextViewAttrData>(view) {
    private val binding by lazy {
        LayoutTextViewAttrBinding.bind(
            ModuleDialogUi.inflate(context, R.layout.layout_text_view_attr)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appendAttrPanelView(binding.root)

        binding.editText.hint = SpannableString(moduleRes.getString(R.string.enter_text))
        binding.textMaxLine.hint = SpannableString(moduleRes.getString(R.string.max_line))
        binding.textContentTitle.text = SpannableString(moduleRes.getText(R.string.text_content))
        binding.textMaxLineTitle.text = SpannableString(moduleRes.getText(R.string.max_line))
        binding.textHintTitle.text = SpannableString(moduleRes.getText(R.string.text_hint))
        binding.editHint.hint = SpannableString(moduleRes.getString(R.string.enter_text))

        binding.editText.setText(SpannableString(view.text))
        binding.textMaxLine.setText(SpannableString(view.maxLines.toString()))
        binding.editHint.setText(SpannableString(view.hint ?: ""))
    }

    override val attrData: TextViewAttrData
        get() {
            return TextViewAttrData(
                baseAttrData,
                binding.editText.text.toString(),
                binding.textMaxLine.text.toString().toIntOrNull() ?: view.maxLines,
                binding.editHint.text.toString().ifEmpty { null }
            )
        }

    override fun onApply(data: TextViewAttrData) {
        super.onApply(data)
        // 记录应用前的文本 / 最大行数，用于判断它们是否真的被改过。
        // 只修改 hint 时不能让 changedText 变成 true：规则回放会持续把
        // view.text 写回原值，用户之后输入的内容会被清空。
        val textChanged = data.text != view.text?.toString() || data.maxLine != view.maxLines
        view.text = SpannableString(data.text)
        view.maxLines = data.maxLine
        view.hint = data.hint ?: ""
        // 写入持久化规则
        pendingRule?.let { rule ->
            rule.modified = rule.modified.copy(
                text = data.text,
                maxLines = data.maxLine,
                hint = data.hint
            )
            // 已置位的标记保持不变，避免编辑已有规则时把它清掉
            rule.changedText = rule.changedText || textChanged
            rule.changedHint = true
        }
    }
}
