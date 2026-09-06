package com.godviewer.app.target.ui

import android.app.AlertDialog
import com.godviewer.app.shared.AndroidAppCompat
import android.os.Bundle
import android.text.SpannableString
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.LayoutRes
import androidx.core.view.isVisible
import com.godviewer.app.R
import com.godviewer.app.shared.APP_FIELD_FORCE_CLICKABLE
import com.godviewer.app.shared.APP_FIELD_SHOW_BOUNDS
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.ViewSnapshot
import com.godviewer.app.shared.model.ViewRule
import com.godviewer.app.target.dialog.ModuleDialogUi
import com.godviewer.app.target.dispatch.ViewClickWrapper
import com.godviewer.app.target.edit.EditMode
import com.godviewer.app.target.edit.SelectedViewHighlight
import com.godviewer.app.target.hook.ModuleRes
import com.godviewer.app.target.hook.ModuleRes.moduleRes
import com.godviewer.app.target.rule.ViewRuleManager
import com.godviewer.app.databinding.LayoutBaseAttrDialogBinding
import com.godviewer.app.shared.model.BaseViewAttrData
import com.godviewer.app.shared.px
import com.godviewer.app.target.util.drawLayoutBounds
import com.godviewer.app.target.util.getInjectedField
import com.godviewer.app.target.util.getOnClickListener
import com.godviewer.app.target.util.injectField
import com.godviewer.app.target.util.setGlobalHookClick

/**
 * @author hhvvg
 *
 * Base dialog for editing basic view attributes.
 *
 * 使用 [ModuleDialogUi] 隔离目标应用主题。表单绑定见 [AttrDialogFormBinder]，
 * 父/子导航见 [AttrDialogHierarchy]。
 */
abstract class BaseAttrDialog<T : BaseViewAttrData>(protected val itemView: View) :
    AlertDialog(ModuleDialogUi.dialogContext(itemView)) {
    private val binding by lazy {
        LayoutBaseAttrDialogBinding.bind(
            ModuleDialogUi.inflate(context, R.layout.layout_base_attr_dialog)
        )
    }

    /**
     * 当前视图的持久化规则：对话框打开时由已保存规则（存在时）或视图现状创建，
     * Apply / "隐藏"时写入修改值并保存，重启后自动重放。
     */
    protected var pendingRule: ViewRule? = null
        private set

    /** 打开对话框时该视图是否已存在持久化规则（决定"重置"按钮可见性） */
    private var hasSavedRule: Boolean = false

    /**
     * This is the basic view attributes holder.
     */
    protected val baseAttrData: BaseViewAttrData
        get() {
            val width = when (viewWidth) {
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT -> {
                    viewWidth
                }
                else -> {
                    viewWidth.px()
                }
            }
            val height = when (viewHeight) {
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT -> {
                    viewHeight
                }
                else -> {
                    viewHeight.px()
                }
            }
            val marginLeft = binding.marginLeft.text.toString().toIntOrNull() ?: 0
            val marginTop = binding.marginTop.text.toString().toIntOrNull() ?: 0
            val marginBottom = binding.marginBottom.text.toString().toIntOrNull() ?: 0
            val marginRight = binding.marginRight.text.toString().toIntOrNull() ?: 0
            val paddingTop = binding.paddingTop.text.toString().toIntOrNull() ?: 0
            val paddingLeft = binding.paddingLeft.text.toString().toIntOrNull() ?: 0
            val paddingBottom = binding.paddingBottom.text.toString().toIntOrNull() ?: 0
            val paddingRight = binding.paddingRight.text.toString().toIntOrNull() ?: 0
            return BaseViewAttrData(
                width,
                height,
                paddingLeft.px(),
                paddingTop.px(),
                paddingBottom.px(),
                paddingRight.px(),
                marginLeft.px(),
                marginTop.px(),
                marginBottom.px(),
                marginRight.px()
            )
        }

    protected var viewWidth: Int = itemView.layoutParams.width
    protected var viewHeight: Int = itemView.layoutParams.height

    protected abstract val attrData: T

    protected open fun onApply(data: T) {
        val baseData = baseAttrData
        val param = itemView.layoutParams
        param.width = baseData.width
        param.height = baseData.height
        if (param is ViewGroup.MarginLayoutParams) {
            param.setMargins(data.marginLeft, data.marginTop, data.marginRight, data.marginBottom)
        }
        itemView.layoutParams = param
        itemView.setPadding(
            data.paddingLeft,
            data.paddingTop,
            data.paddingRight,
            data.paddingBottom
        )
        pendingRule?.let { rule ->
            rule.modified = rule.modified.copy(
                width = baseData.width,
                height = baseData.height,
                marginLeft = data.marginLeft,
                marginTop = data.marginTop,
                marginRight = data.marginRight,
                marginBottom = data.marginBottom,
                paddingLeft = data.paddingLeft,
                paddingTop = data.paddingTop,
                paddingRight = data.paddingRight,
                paddingBottom = data.paddingBottom
            )
            rule.changedSize = true
            rule.changedMargin = true
            rule.changedPadding = true
        }
    }

    /**
     * 保存持久化规则。在子类各自完成属性修改后由 Apply 按钮统一调用。
     */
    protected open fun persist() {
        pendingRule?.let { ViewRuleManager.saveRule(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        val existing = ViewRuleManager.findRule(itemView)
        pendingRule = existing ?: ViewRuleManager.createRule(itemView)
        hasSavedRule = existing != null
        setupButtons()
        AttrDialogFormBinder.bindSizeInputs(
            binding = binding,
            initialWidth = viewWidth,
            initialHeight = viewHeight,
            onWidth = { viewWidth = it },
            onHeight = { viewHeight = it },
        )
        AttrDialogFormBinder.bindStaticLabels(binding)
        AttrDialogFormBinder.bindMargin(binding, itemView)
        AttrDialogFormBinder.bindPadding(binding, itemView)
        setupChildrenParentSpinner()
        renderPreview()
        setTitle(itemView::class.java.name)
        ModuleDialogUi.normalizeTree(binding.root)
    }

    protected fun renderPreview() {
        val bmp = ViewSnapshot.capture(itemView, maxEdge = 400)
        if (bmp != null) {
            binding.previewImage.setImageBitmap(bmp)
            binding.previewImage.isVisible = true
        } else {
            binding.previewImage.isVisible = false
            GvLog.d(TAG, "preview skipped for ${itemView.javaClass.name}")
        }
    }

    override fun setTitle(title: CharSequence?) {
        binding.title.text = SpannableString(title)
    }

    private fun setupChildrenParentSpinner() {
        val ancestors = AttrDialogHierarchy.findAncestors(itemView)
        val children = AttrDialogHierarchy.findChildren(itemView)
        binding.childrenButton.setOnClickListener {
            AttrDialogHierarchy.openPicker(
                anchor = itemView,
                dismissCurrent = { dismiss() },
                titleRes = R.string.select_children,
                candidates = children,
            )
        }
        binding.parentButton.setOnClickListener {
            AttrDialogHierarchy.openPicker(
                anchor = itemView,
                dismissCurrent = { dismiss() },
                titleRes = R.string.select_parent,
                candidates = ancestors,
            )
        }
    }

    private fun setupButtons() {
        binding.cancelButton.setOnClickListener {
            dismiss()
        }
        binding.exitEditModeButton.setOnClickListener {
            EditMode.setEnabled(false)
            dismiss()
        }
        binding.applyButton.setOnClickListener {
            onApply(attrData)
            persist()
            dismiss()
        }
        binding.hideButton.setOnClickListener {
            val rule = pendingRule
            if (rule != null) {
                rule.modified = rule.modified.copy(visibility = View.GONE)
                rule.changedVisibility = true
                ViewRuleManager.applyRuleToView(itemView, rule)
                ViewRuleManager.saveRule(rule)
            }
            // 隐藏后立刻取消高亮，等下一次点击再亮
            SelectedViewHighlight.clear()
            dismiss()
        }
        binding.resetButton.setOnClickListener {
            val rule = pendingRule
            if (rule != null) {
                ViewRuleManager.restoreView(itemView, rule)
                ViewRuleManager.deleteRule(rule)
            }
            dismiss()
        }
        binding.resetButton.isVisible = hasSavedRule
        val listener = itemView.getOnClickListener()
        if (listener == null || (listener is ViewClickWrapper && listener.originListener == null)) {
            binding.originClickButton.isVisible = false
        } else {
            binding.originClickButton.setOnClickListener {
                if (listener is ViewClickWrapper) {
                    listener.performOriginClick()
                } else {
                    listener.onClick(itemView)
                }
                dismiss()
            }
        }
        val app = AndroidAppCompat.currentApplication() ?: return
        val showBoundsNow = app.getInjectedField(APP_FIELD_SHOW_BOUNDS, false) ?: false
        binding.showLayoutBoundsSwitch.isChecked = showBoundsNow
        binding.showLayoutBoundsSwitch.text =
            SpannableString(moduleRes.getString(R.string.show_global_layout_bounds))
        binding.showLayoutBoundsSwitch.setOnCheckedChangeListener { _, isChecked ->
            app.injectField(APP_FIELD_SHOW_BOUNDS, isChecked)
            itemView.rootView.drawLayoutBounds(isChecked, true)
            renderPreview()
        }

        val ignoreEmptyVg = app.getInjectedField(APP_FIELD_FORCE_CLICKABLE, false) ?: false
        binding.ignoreEmptyVgSwitch.isChecked = ignoreEmptyVg
        binding.ignoreEmptyVgSwitch.text =
            SpannableString(moduleRes.getString(R.string.force_clickable))
        binding.ignoreEmptyVgSwitch.setOnCheckedChangeListener { _, isChecked ->
            app.injectField(APP_FIELD_FORCE_CLICKABLE, isChecked)
            if (EditMode.isEnabled()) {
                itemView.rootView.setGlobalHookClick(
                    enabled = true,
                    traversalChildren = true,
                    forceClickable = isChecked
                )
            }
        }
    }

    protected fun appendAttrPanelView(view: View) {
        val param = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        binding.attrParentContainer.addView(view, param)
    }

    protected fun appendAttrPanelView(@LayoutRes resId: Int): View {
        val view = ModuleDialogUi.inflate(context, resId)
        appendAttrPanelView(view)
        return view
    }

    override fun show() {
        runCatching {
            super.show()
            ModuleDialogUi.applyWindow(this, binding.root, preferMaxHeight = true)
            binding.root.post {
                runCatching {
                    ModuleDialogUi.applyWindow(this, binding.root, preferMaxHeight = true)
                    binding.scrollParent.requestLayout()
                    binding.attrParentContainer.requestLayout()
                }
            }
        }.onFailure {
            GvLog.e(TAG, "BaseAttrDialog.show failed", it)
        }
    }

    companion object {
        private const val TAG = "UI"
    }
}
