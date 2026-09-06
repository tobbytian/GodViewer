package com.godviewer.app.target.ui

import android.text.SpannableString
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import com.godviewer.app.R
import com.godviewer.app.target.hook.ModuleRes
import com.godviewer.app.target.hook.ModuleRes.moduleRes
import com.godviewer.app.databinding.LayoutBaseAttrDialogBinding
import com.godviewer.app.shared.dp
import com.godviewer.app.shared.px

/**
 * BaseAttrDialog 表单区绑定：宽高 spinner、margin/padding 四向与 identical 同步。
 * 不持有规则/生命周期，只读写 [binding] 与宽高状态回调。
 */
internal object AttrDialogFormBinder {

    /**
     * 绑定宽高 spinner + 数值输入。顺序与原 BaseAttrDialog 一致：
     * 先 spinner（自定义尺寸时 px→dp），再写入 EditText，避免文本仍是 px。
     */
    fun bindSizeInputs(
        binding: LayoutBaseAttrDialogBinding,
        initialWidth: Int,
        initialHeight: Int,
        onWidth: (Int) -> Unit,
        onHeight: (Int) -> Unit,
    ) {
        var viewWidth = initialWidth
        var viewHeight = initialHeight

        val specArray = moduleRes.getStringArray(R.array.spec_spinner_values)
        binding.heightSpinner.apply {
            adapter =
                ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, specArray)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    when (position) {
                        0 -> {
                            viewHeight = ViewGroup.LayoutParams.MATCH_PARENT
                            binding.heightValue.isVisible = false
                            onHeight(viewHeight)
                        }
                        1 -> {
                            viewHeight = ViewGroup.LayoutParams.WRAP_CONTENT
                            binding.heightValue.isVisible = false
                            onHeight(viewHeight)
                        }
                        2 -> {
                            binding.heightValue.isVisible = true
                            viewHeight = binding.heightValue.text.toString().toIntOrNull() ?: return
                            onHeight(viewHeight)
                        }
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                }
            }
        }
        binding.widthSpinner.apply {
            adapter =
                ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, specArray)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    when (position) {
                        0 -> {
                            viewWidth = ViewGroup.LayoutParams.MATCH_PARENT
                            binding.widthValue.isVisible = false
                            onWidth(viewWidth)
                        }
                        1 -> {
                            viewWidth = ViewGroup.LayoutParams.WRAP_CONTENT
                            binding.widthValue.isVisible = false
                            onWidth(viewWidth)
                        }
                        2 -> {
                            binding.widthValue.isVisible = true
                            viewWidth = binding.widthValue.text.toString().toIntOrNull() ?: return
                            onWidth(viewWidth)
                        }
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                }
            }
        }
        when (viewWidth) {
            ViewGroup.LayoutParams.MATCH_PARENT -> binding.widthSpinner.setSelection(0)
            ViewGroup.LayoutParams.WRAP_CONTENT -> binding.widthSpinner.setSelection(1)
            else -> {
                viewWidth = viewWidth.dp()
                onWidth(viewWidth)
                binding.widthSpinner.setSelection(2)
            }
        }
        when (viewHeight) {
            ViewGroup.LayoutParams.MATCH_PARENT -> binding.heightSpinner.setSelection(0)
            ViewGroup.LayoutParams.WRAP_CONTENT -> binding.heightSpinner.setSelection(1)
            else -> {
                viewHeight = viewHeight.dp()
                onHeight(viewHeight)
                binding.heightSpinner.setSelection(2)
            }
        }

        // 在 spinner 完成 px→dp 后再写入 EditText（与原 setupInput 顺序一致）
        binding.heightValue.setText(SpannableString(viewHeight.toString()))
        binding.widthValue.setText(SpannableString(viewWidth.toString()))
        binding.widthValue.addTextChangedListener {
            val width = it?.toString()?.toIntOrNull() ?: return@addTextChangedListener
            viewWidth = width
            onWidth(width)
        }
        binding.heightValue.addTextChangedListener {
            val height = it?.toString()?.toIntOrNull() ?: return@addTextChangedListener
            viewHeight = height
            onHeight(height)
        }
    }

    fun bindMargin(binding: LayoutBaseAttrDialogBinding, itemView: View) {
        val margin = itemView.layoutParams
        if (margin !is ViewGroup.MarginLayoutParams) {
            binding.marginValues.isVisible = false
            return
        }
        binding.marginLeft.setText(SpannableString(margin.leftMargin.dp().toString()))
        binding.marginTop.setText(SpannableString(margin.topMargin.dp().toString()))
        binding.marginBottom.setText(SpannableString(margin.bottomMargin.dp().toString()))
        binding.marginRight.setText(SpannableString(margin.rightMargin.dp().toString()))
        binding.marginIdenticalCheckbox.text =
            SpannableString(moduleRes.getString(R.string.identical))

        for (child in binding.marginInputs.children) {
            if (child !is EditText) continue
            child.addTextChangedListener {
                if (!child.isFocused || !binding.marginIdenticalCheckbox.isChecked) {
                    return@addTextChangedListener
                }
                setIdenticalEditValues(binding.marginInputs, it.toString())
            }
        }
    }

    fun bindPadding(binding: LayoutBaseAttrDialogBinding, itemView: View) {
        binding.paddingLeft.setText(SpannableString(itemView.paddingLeft.dp().toString()))
        binding.paddingTop.setText(SpannableString(itemView.paddingTop.dp().toString()))
        binding.paddingBottom.setText(SpannableString(itemView.paddingBottom.dp().toString()))
        binding.paddingRight.setText(SpannableString(itemView.paddingRight.dp().toString()))
        binding.paddingIdenticalCheckbox.text =
            SpannableString(moduleRes.getString(R.string.identical))

        for (child in binding.paddingInputs.children) {
            if (child !is EditText) continue
            child.addTextChangedListener {
                if (!child.isFocused || !binding.paddingIdenticalCheckbox.isChecked) {
                    return@addTextChangedListener
                }
                setIdenticalEditValues(binding.paddingInputs, it.toString())
            }
        }
    }

    fun bindStaticLabels(binding: LayoutBaseAttrDialogBinding) {
        binding.widthTitle.text = SpannableString(moduleRes.getString(R.string.width))
        binding.heightTitle.text = SpannableString(moduleRes.getString(R.string.height))
        binding.cancelButton.text = SpannableString(moduleRes.getText(R.string.cancel))
        binding.applyButton.text = SpannableString(moduleRes.getText(R.string.apply))
        binding.originClickButton.text =
            SpannableString(moduleRes.getText(R.string.perform_origin_click))
        binding.hideButton.text = SpannableString(moduleRes.getText(R.string.hide))
        binding.resetButton.text = SpannableString(moduleRes.getText(R.string.reset))
        binding.exitEditModeButton.text =
            SpannableString(moduleRes.getText(R.string.exit_edit_mode))
        binding.marginTitle.text = SpannableString(moduleRes.getString(R.string.margin))
        binding.paddingTitle.text = SpannableString(moduleRes.getString(R.string.padding))
        binding.marginLeft.hint = moduleRes.getString(R.string.left)
        binding.marginTop.hint = moduleRes.getString(R.string.top)
        binding.marginRight.hint = moduleRes.getString(R.string.right)
        binding.marginBottom.hint = moduleRes.getString(R.string.bottom)
        binding.paddingLeft.hint = moduleRes.getString(R.string.left)
        binding.paddingTop.hint = moduleRes.getString(R.string.top)
        binding.paddingRight.hint = moduleRes.getString(R.string.right)
        binding.paddingBottom.hint = moduleRes.getString(R.string.bottom)
        binding.parentSpinnerTitle.text = SpannableString(moduleRes.getString(R.string.parent))
        binding.childrenSpinnerTitle.text = SpannableString(moduleRes.getString(R.string.children))
    }

    private fun setIdenticalEditValues(container: ViewGroup, value: String) {
        for (child in container.children) {
            if (child !is EditText) continue
            if (child.text.toString() == value) continue
            child.setText(SpannableString(value))
        }
    }
}
