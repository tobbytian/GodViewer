package com.godviewer.app.ui

import android.app.AlertDialog
import android.app.AndroidAppHelper
import android.os.Bundle
import android.text.SpannableString
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import androidx.annotation.LayoutRes
import androidx.core.view.ancestors
import androidx.core.view.children
import androidx.core.view.drawToBitmap
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import com.godviewer.app.R
import com.godviewer.app.ViewClickWrapper
import com.godviewer.app.ViewDispatcher
import com.godviewer.app.data.BaseViewAttrData
import com.godviewer.app.data.ViewRule
import com.godviewer.app.data.ViewRuleManager
import com.godviewer.app.databinding.LayoutBaseAttrDialogBinding
import com.godviewer.app.hook.AnyHookZygote.Companion.moduleRes
import com.godviewer.app.ui.adapter.ViewItemListAdapter
import com.godviewer.app.util.APP_FIELD_FORCE_CLICKABLE
import com.godviewer.app.util.APP_FIELD_SHOW_BOUNDS
import com.godviewer.app.util.EditMode
import com.godviewer.app.util.ModuleDialogUi
import com.godviewer.app.util.dp
import com.godviewer.app.util.drawLayoutBounds
import com.godviewer.app.util.getInjectedField
import com.godviewer.app.util.getOnClickListener
import com.godviewer.app.util.injectField
import com.godviewer.app.util.px
import com.godviewer.app.util.setGlobalHookClick

/**
 * @author hhvvg
 *
 * Base dialog for editing basic view attributes.
 *
 * 使用 [ModuleDialogUi] 隔离目标应用主题，避免在极简/残缺主题下样式崩坏。
 */
abstract class BaseAttrDialog<T : BaseViewAttrData>(protected val itemView: View) :
    AlertDialog(ModuleDialogUi.wrap(itemView.context)) {
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
        // 捕获修改值到持久化规则（子类再补充各自的属性）
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
        setSpecSpinner()
        setupInput()
        setupText()
        setupMargin()
        setupPadding()
        setupChildrenParentSpinner()
        renderPreview()
        setTitle(itemView::class.java.name)
        // 文案设置后再刷一遍按钮主/危险色
        ModuleDialogUi.normalizeTree(binding.root)
    }

    protected fun renderPreview() {
        if (!itemView.isLaidOut) {
            return
        }
        binding.previewImage.setImageBitmap(itemView.drawToBitmap())
    }

    override fun setTitle(title: CharSequence?) {
        binding.title.text = SpannableString(title)
    }

    private fun setupChildrenParentSpinner() {
        val ancestors = findAncestors()
        val children = findChildren()
        binding.parentSpinnerTitle.text = SpannableString(moduleRes.getString(R.string.parent))
        binding.childrenSpinnerTitle.text = SpannableString(moduleRes.getString(R.string.children))
        binding.childrenButton.setOnClickListener {
            val dialog = Builder(context)
                .setTitle(moduleRes.getString(R.string.select_children))
                .setAdapter(ViewItemListAdapter(children, context)) { d, which ->
                    d.dismiss()
                    val selected = children[which]
                    ViewDispatcher.dispatch(selected)
                    dismiss()
                }
                .create()
            dialog.show()
            ModuleDialogUi.applyWindow(dialog)
        }
        binding.parentButton.setOnClickListener {
            val dialog = Builder(context)
                .setTitle(moduleRes.getString(R.string.select_parent))
                .setAdapter(ViewItemListAdapter(ancestors, context)) { d, which ->
                    d.dismiss()
                    val selected = ancestors[which]
                    ViewDispatcher.dispatch(selected)
                    dismiss()
                }
                .create()
            dialog.show()
            ModuleDialogUi.applyWindow(dialog)
        }
    }

    private fun setupText() {
        binding.widthTitle.text =
            SpannableString(moduleRes.getString(R.string.width))
        binding.heightTitle.text =
            SpannableString(moduleRes.getString(R.string.height))
        binding.cancelButton.text = SpannableString(moduleRes.getText(R.string.cancel))
        binding.applyButton.text = SpannableString(moduleRes.getText(R.string.apply))
        binding.originClickButton.text =
            SpannableString(moduleRes.getText(R.string.perform_origin_click))
        binding.hideButton.text = SpannableString(moduleRes.getText(R.string.hide))
        binding.resetButton.text = SpannableString(moduleRes.getText(R.string.reset))
        binding.exitEditModeButton.text =
            SpannableString(moduleRes.getText(R.string.exit_edit_mode))
        // 布局 XML 里的 @string 在目标进程 inflate 时解析不出，这里用 moduleRes 统一设置
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
    }

    private fun setupButtons() {
        binding.cancelButton.setOnClickListener {
            dismiss()
        }
        // 退出编辑模式：状态写 OFF 后关闭，目标应用恢复正常点击（无感，本次运行生效）
        binding.exitEditModeButton.setOnClickListener {
            EditMode.setEnabled(false)
            dismiss()
        }
        binding.applyButton.setOnClickListener {
            onApply(attrData)
            persist()
            dismiss()
        }
        // 隐藏视图（持久化，等价于 GodMode 的核心动作）
        binding.hideButton.setOnClickListener {
            val rule = pendingRule
            if (rule != null) {
                rule.modified = rule.modified.copy(visibility = View.GONE)
                rule.changedVisibility = true
                ViewRuleManager.applyRuleToView(itemView, rule)
                ViewRuleManager.saveRule(rule)
            }
            dismiss()
        }
        // 重置：删除规则并恢复视图原始状态（仅当已存在规则时可用）
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
        val app = AndroidAppHelper.currentApplication()
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
            itemView.rootView.setGlobalHookClick(
                enabled = true,
                traversalChildren = true,
                forceClickable = isChecked
            )
        }
    }

    private fun findChildren(): List<View> {
        if (itemView !is ViewGroup || itemView.childCount <= 0) {
            return emptyList()
        }
        return itemView.children.toList()
    }

    private fun findAncestors(): List<ViewGroup> {
        val ancestor = itemView.ancestors
        val result = ArrayList<ViewGroup>()
        for (a in ancestor) {
            if (a is ViewGroup) {
                result.add(a)
            }
        }
        return result
    }

    private fun setupInput() {
        binding.heightValue.setText(SpannableString(viewHeight.toString()))
        binding.widthValue.setText(SpannableString(viewWidth.toString()))
        binding.widthValue.addTextChangedListener {
            val width = it?.toString()?.toIntOrNull() ?: return@addTextChangedListener
            viewWidth = width
        }
        binding.heightValue.addTextChangedListener {
            val height = it?.toString()?.toIntOrNull() ?: return@addTextChangedListener
            viewHeight = height
        }
    }

    private fun setSpecSpinner() {
        val specArray = moduleRes.getStringArray(R.array.spec_spinner_values)
        binding.heightSpinner.apply {
            adapter =
                ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, specArray)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    when (position) {
                        0 -> {
                            viewHeight = ViewGroup.LayoutParams.MATCH_PARENT
                            binding.heightValue.isVisible = false
                        }
                        1 -> {
                            viewHeight = ViewGroup.LayoutParams.WRAP_CONTENT
                            binding.heightValue.isVisible = false
                        }
                        2 -> {
                            binding.heightValue.isVisible = true
                            viewHeight = binding.heightValue.text.toString().toIntOrNull() ?: return
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
                    id: Long
                ) {
                    when (position) {
                        0 -> {
                            viewWidth = ViewGroup.LayoutParams.MATCH_PARENT
                            binding.widthValue.isVisible = false
                        }
                        1 -> {
                            viewWidth = ViewGroup.LayoutParams.WRAP_CONTENT
                            binding.widthValue.isVisible = false
                        }
                        2 -> {
                            binding.widthValue.isVisible = true
                            viewWidth = binding.widthValue.text.toString().toIntOrNull() ?: return
                        }
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                }
            }
        }
        when (viewWidth) {
            ViewGroup.LayoutParams.MATCH_PARENT -> {
                binding.widthSpinner.setSelection(0)
            }
            ViewGroup.LayoutParams.WRAP_CONTENT -> {
                binding.widthSpinner.setSelection(1)
            }
            else -> {
                viewWidth = viewWidth.dp()
                binding.widthSpinner.setSelection(2)
            }
        }
        when (viewHeight) {
            ViewGroup.LayoutParams.MATCH_PARENT -> {
                binding.heightSpinner.setSelection(0)
            }
            ViewGroup.LayoutParams.WRAP_CONTENT -> {
                binding.heightSpinner.setSelection(1)
            }
            else -> {
                viewHeight = viewHeight.dp()
                binding.heightSpinner.setSelection(2)
            }
        }
    }

    private fun setupMargin() {
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

        val children = binding.marginInputs.children
        for (child in children) {
            if (child !is EditText) {
                continue
            }
            child.addTextChangedListener {
                if (!child.isFocused || !binding.marginIdenticalCheckbox.isChecked) {
                    return@addTextChangedListener
                }
                setMarginValues(it.toString())
            }
        }
    }

    private fun setMarginValues(value: String) {
        val children = binding.marginInputs.children
        for (child in children) {
            if (child !is EditText) {
                continue
            }
            if (child.text.toString() == value) {
                continue
            }
            child.setText(SpannableString(value))
        }
    }

    private fun setupPadding() {
        binding.paddingLeft.setText(SpannableString(itemView.paddingLeft.dp().toString()))
        binding.paddingTop.setText(SpannableString(itemView.paddingTop.dp().toString()))
        binding.paddingBottom.setText(SpannableString(itemView.paddingBottom.dp().toString()))
        binding.paddingRight.setText(SpannableString(itemView.paddingRight.dp().toString()))
        binding.paddingIdenticalCheckbox.text =
            SpannableString(moduleRes.getString(R.string.identical))

        val children = binding.paddingInputs.children
        for (child in children) {
            if (child !is EditText) {
                continue
            }
            child.addTextChangedListener {
                if (!child.isFocused || !binding.paddingIdenticalCheckbox.isChecked) {
                    return@addTextChangedListener
                }
                setPaddingValues(it.toString())
            }
        }
    }

    private fun setPaddingValues(value: String) {
        val children = binding.paddingInputs.children
        for (child in children) {
            if (child !is EditText) {
                continue
            }
            if (child.text.toString() == value) {
                continue
            }
            child.setText(SpannableString(value))
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
        super.show()
        // 高级编辑页：窗口 + binding.root 固定高度，ScrollView(weight) 才能显示内容
        ModuleDialogUi.applyWindow(this, binding.root, preferMaxHeight = true)
    }
}

