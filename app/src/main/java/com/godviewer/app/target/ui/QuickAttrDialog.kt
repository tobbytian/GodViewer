package com.godviewer.app.target.ui

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import com.godviewer.app.R
import com.godviewer.app.databinding.LayoutQuickAttrDialogBinding
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.ViewSnapshot
import com.godviewer.app.shared.px
import com.godviewer.app.target.dialog.ModuleDialogUi
import com.godviewer.app.target.edit.EditMode
import com.godviewer.app.target.edit.SelectedViewHighlight
import com.godviewer.app.target.hook.ModuleRes
import com.godviewer.app.target.hook.ModuleRes.moduleRes
import com.godviewer.app.target.rule.ViewRuleManager
import com.godviewer.app.target.rule.getAttachedActivityFromView

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
) : AlertDialog(ModuleDialogUi.dialogContext(itemView)) {

    private val binding by lazy {
        LayoutQuickAttrDialogBinding.bind(
            ModuleDialogUi.inflate(context, R.layout.layout_quick_attr_dialog)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        // 注入布局不得引用模块 @drawable（LayoutInflater 用目标 Context inflate，
        // @drawable 会拿模块 ID 撞目标资源表直接崩）：缩略图底框改代码注入
        binding.previewImage.background = moduleRes.getDrawable(R.drawable.bg_thumb_frame, null)
        setupText()
        setupButtons()
        // 返回键 / 点外部关闭都会走到 cancel()：与按钮各留一条痕迹，
        // 弹窗关闭后若完全没有对应日志，说明点击没到达任何监听器（被吞）
        setOnCancelListener { GvLog.d(TAG, "canceled by back/outside") }
        renderPreview()
        setTitle(itemView::class.java.name)
        ModuleDialogUi.normalizeTree(binding.root)
    }

    override fun setTitle(title: CharSequence?) {
        binding.title.text = SpannableString(title)
    }

    override fun show() {
        runCatching {
            if (!ModuleRes.isModuleResReady()) {
                GvLog.w(TAG, "QuickAttr show skip: moduleRes not ready view=${itemView.javaClass.name}")
                return
            }
            val host = ModuleDialogUi.activityOf(context)
                ?: ModuleDialogUi.activityFromView(itemView)
            if (host != null && (host.isFinishing || host.isDestroyed)) {
                GvLog.w(
                    TAG,
                    "QuickAttr show skip: host finishing view=${itemView.javaClass.name}",
                )
                return
            }
            GvLog.d(
                TAG,
                "QuickAttr show begin ${itemView.javaClass.name} " +
                    "host=${host?.javaClass?.simpleName ?: "?"} tokenOk=${host != null}",
            )
            super.show()
            ModuleDialogUi.applyWindow(this, binding.root)
            GvLog.d(
                TAG,
                "QuickAttr show ok showing=$isShowing view=${itemView.javaClass.simpleName}",
            )
        }.onFailure {
            GvLog.e(TAG, "QuickAttr show failed view=${itemView.javaClass.name}", it)
        }
    }

    companion object {
        private const val TAG = "UI"
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
            GvLog.d(TAG, "cancel clicked")
            dismiss()
        }
        // 撤销上一个规则操作（如刚隐藏了别的视图）；无可撤销操作时隐藏按钮
        binding.undoButton.setOnClickListener {
            val undone = ViewRuleManager.undoLastOperation(getAttachedActivityFromView(itemView))
            GvLog.d(TAG, "undo clicked undone=$undone")
            dismiss()
        }
        binding.undoButton.isVisible = ViewRuleManager.canUndo()
        // 撤销按钮消失时清掉取消按钮的单侧 8dp 间距，左缘与上一行对齐
        (binding.cancelButton.layoutParams as ViewGroup.MarginLayoutParams).marginStart =
            if (binding.undoButton.isVisible) 8.px() else 0
        // 退出编辑模式：状态写 OFF 后关闭，目标应用恢复正常点击（无感，本次运行生效）
        binding.exitEditModeButton.setOnClickListener {
            EditMode.setEnabled(false)
            dismiss()
        }
        binding.advancedButton.setOnClickListener {
            GvLog.d(TAG, "open advanced editor view=${itemView.javaClass.name}")
            val next = editorFactory()
            // 高级页标题栏的返回箭头回本精简页：持实例，返回时重新 show
            next.backAction = { show() }
            // 先关精简弹窗，再下一帧打开高级页，避免 window token 冲突导致空白/闪退
            dismiss()
            Handler(Looper.getMainLooper()).post {
                runCatching { next.show() }
                    .onFailure { GvLog.e(TAG, "open advanced editor failed", it) }
            }
        }
        binding.hideButton.setOnClickListener {
            hideAndDismiss()
        }
    }

    private fun renderPreview() {
        val bmp = ViewSnapshot.capture(itemView, maxEdge = 400)
        if (bmp != null) {
            binding.previewImage.setImageBitmap(bmp)
            binding.previewImage.isVisible = true
        } else {
            binding.previewImage.isVisible = false
        }
    }

    // 与 BaseAttrDialog 的"隐藏"逻辑一致：持久化 visibility = GONE 并立即生效
    private fun hideAndDismiss() {
        val rule = ViewRuleManager.findRule(itemView) ?: ViewRuleManager.createRule(itemView)
        if (rule != null) {
            rule.modified = rule.modified.copy(visibility = View.GONE)
            rule.changedVisibility = true
            if (rule.repeatable) {
                // 列表项规则：保存后对账一次，所有同类项立即生效
                ViewRuleManager.saveRule(rule, applyIn = getAttachedActivityFromView(itemView))
                toastRepeatableApplied()
            } else {
                ViewRuleManager.applyRuleToView(itemView, rule)
                ViewRuleManager.saveRule(rule)
            }
            GvLog.d(
                TAG,
                "quick hide view=${itemView.javaClass.name} key=${rule.key()} " +
                    "repeatable=${rule.repeatable}",
            )
        } else {
            GvLog.w(TAG, "quick hide skipped: createRule null view=${itemView.javaClass.name}")
        }
        // 隐藏后立刻取消高亮，等下一次点击再亮
        SelectedViewHighlight.clear()
        dismiss()
    }

    /** repeatable 规则保存后的用户提示：本规则作用于所有同类项，不是只隐藏点选的那一个 */
    private fun toastRepeatableApplied() {
        runCatching {
            Toast.makeText(
                context,
                moduleRes.getString(R.string.repeatable_applied_toast),
                Toast.LENGTH_SHORT,
            ).show()
        }.onFailure { GvLog.w(TAG, "repeatable toast failed", it) }
    }
}
