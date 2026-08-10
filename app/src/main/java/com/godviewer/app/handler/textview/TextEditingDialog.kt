package com.godviewer.app.handler.textview

import android.os.Bundle
import android.text.SpannableString
import android.view.LayoutInflater
import android.widget.TextView
import com.godviewer.app.R
import com.godviewer.app.databinding.LayoutTextViewAttrBinding
import com.godviewer.app.ui.BaseAttrDialog
import com.godviewer.app.hook.AnyHookZygote.Companion.moduleRes

/**
 * @author hhvvg
 *
 * Editing attributes in TextView.
 */
class TextEditingDialog(private val view: TextView) : BaseAttrDialog<TextViewAttrData>(view) {
    private val rootView by lazy {
        val layout = moduleRes.getLayout(R.layout.layout_text_view_attr)
        val inflater = LayoutInflater.from(context)
        inflater.inflate(layout, null)
    }
    private val binding by lazy {
        LayoutTextViewAttrBinding.bind(rootView)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appendAttrPanelView(binding.root)

        binding.editText.hint = SpannableString(moduleRes.getString(R.string.enter_text))
        binding.textMaxLine.hint = SpannableString(moduleRes.getString(R.string.max_line))
        binding.textContentTitle.text = SpannableString(moduleRes.getText(R.string.text_content))
        binding.textMaxLineTitle.text = SpannableString(moduleRes.getText(R.string.max_line))

        binding.editText.setText(SpannableString(view.text))
        binding.textMaxLine.setText(SpannableString(view.maxLines.toString()))
    }

    override val attrData: TextViewAttrData
        get() {
            return TextViewAttrData(
                baseAttrData,
                binding.editText.text.toString(),
                binding.textMaxLine.text.toString().toIntOrNull() ?: view.maxLines
            )
        }

    override fun onApply(data: TextViewAttrData) {
        super.onApply(data)
        view.text = SpannableString(data.text)
        view.maxLines = data.maxLine
        // 写入持久化规则
        pendingRule?.let { rule ->
            rule.modified = rule.modified.copy(
                text = data.text,
                maxLines = data.maxLine
            )
            rule.changedText = true
        }
    }
}