package com.godviewer.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.SpannableString
import android.view.View
import androidx.core.view.drawToBitmap
import androidx.core.view.isVisible
import com.godviewer.app.R
import com.godviewer.app.data.ViewRuleManager
import com.godviewer.app.databinding.LayoutQuickAttrDialogBinding
import com.godviewer.app.hook.AnyHookZygote.Companion.moduleRes
import com.godviewer.app.util.EditMode
import com.godviewer.app.util.ModuleDialogUi
import com.godviewer.app.util.getAttachedActivityFromView

/**
 * 精简编辑弹窗：只展示元素名称、元素图标和 隐藏/高级/取消。
 *
 * 点击"高级"后通过 [editorFactory] 进入完整的 [BaseAttrDialog] 编辑界面
 * （尺寸/边距/内边距/父控件/子控件/全局开关等都在那边）。
 *
 * 使用 [ModuleDialogUi] 隔离目标应用主题。
 */
class QuickAttrDialog(
    private val itemView: View,
    private val editorFactory: () -> BaseAttrDialog<*>
) : AlertDialog(ModuleDialogUi.wrap(itemView.context)) {

    private val binding by lazy {
        LayoutQuickAttrDialogBinding.bind(
            ModuleDialogUi.inflate(context, R.layout.layout_quick_attr_dialog)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupText()
        setupButtons()
        renderPreview()
        setTitle(itemView::class.java.name)
        ModuleDialogUi.normalizeTree(binding.root)
    }

    override fun setTitle(title: CharSequence?) {
        binding.title.text = SpannableString(title)
    }

    override fun show() {
        super.show()
        ModuleDialogUi.applyWindow(this, binding.root)
    }

    private fun setupText() {
        binding.undoButton.text = SpannableString(moduleRes.getText(R.string.undo))
        binding.hideButton.text = SpannableString(moduleRes.getText(R.string.hide))
        binding.advancedButton.text = SpannableString(moduleRes.getText(R.string.advanced))
        binding.cancelButton.text = SpannableString(moduleRes.getText(R.string.cancel))
        binding.exitEditModeButton.text =
            SpannableString(moduleRes.getText(R.string.exit_edit_mode))
    }

    private fun setupButtons() {
        binding.cancelButton.setOnClickListener {
            dismiss()
        }
        // 撤销上一个规则操作（如刚隐藏了别的视图）；无可撤销操作时隐藏按钮
        binding.undoButton.setOnClickListener {
            ViewRuleManager.undoLastOperation(getAttachedActivityFromView(itemView))
            dismiss()
        }
        binding.undoButton.isVisible = ViewRuleManager.canUndo()
        // 退出编辑模式：状态写 OFF 后关闭，目标应用恢复正常点击（无感，本次运行生效）
        binding.exitEditModeButton.setOnClickListener {
            EditMode.setEnabled(false)
            dismiss()
        }
        binding.advancedButton.setOnClickListener {
            dismiss()
            editorFactory().show()
        }
        binding.hideButton.setOnClickListener {
            hideAndDismiss()
        }
    }

    private fun renderPreview() {
        if (itemView.isLaidOut) {
            binding.previewImage.setImageBitmap(itemView.drawToBitmap())
        }
    }

    // 与 BaseAttrDialog 的"隐藏"逻辑一致：持久化 visibility = GONE 并立即生效
    private fun hideAndDismiss() {
        val rule = ViewRuleManager.findRule(itemView) ?: ViewRuleManager.createRule(itemView)
        if (rule != null) {
            rule.modified = rule.modified.copy(visibility = View.GONE)
            rule.changedVisibility = true
            ViewRuleManager.applyRuleToView(itemView, rule)
            ViewRuleManager.saveRule(rule)
        }
        dismiss()
    }
}
