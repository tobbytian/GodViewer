package com.godviewer.app.util

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.LayoutRes
import com.godviewer.app.IGNORE_HOOK
import com.godviewer.app.R
import com.godviewer.app.hook.AnyHookZygote.Companion.moduleRes

/**
 * 注入到目标进程的弹窗 UI 工具。
 *
 * 布局 XML 来自 moduleRes；Context 包系统 Dialog 主题，避免吃目标 AppTheme。
 * 配色与宿主 Home UI 的 home_* 色板对齐（蓝主色 + 浅灰底），不跟目标应用走。
 */
object ModuleDialogUi {

    // 与 res/values/colors.xml 中 home_* / md error 保持一致（经 moduleRes 读取）
    private val dialogBg get() = color(R.color.home_page_background)
    private val dialogText get() = color(R.color.home_title_text)
    private val dialogHint get() = color(R.color.home_subtitle_text)
    private val dialogButtonBg get() = color(R.color.home_card_background)
    private val dialogButtonText get() = color(R.color.home_title_text)
    private val dialogPrimaryBg get() = color(R.color.home_activated_card)
    private val dialogPrimaryText get() = color(R.color.home_activated_on_card)
    private val dialogDangerBg get() = color(R.color.md_theme_light_errorContainer)
    private val dialogDangerText get() = color(R.color.md_theme_light_onErrorContainer)
    private val dialogInputBg get() = color(R.color.home_link_chip_background)
    private val dialogDivider get() = color(R.color.home_divider)

    private fun color(@ColorRes id: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            moduleRes.getColor(id, null)
        } else {
            @Suppress("DEPRECATION")
            moduleRes.getColor(id)
        }
    }

    /**
     * 用系统 DeviceDefault 浅色 Dialog 主题包装目标 Context。
     * 仍保留 token / display metrics，但不再吃目标 AppTheme。
     * 使用 framework 的 [ContextThemeWrapper]，避免在目标进程依赖 AppCompat。
     */
    fun wrap(base: Context): Context {
        return ContextThemeWrapper(
            base,
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
        )
    }

    fun activityOf(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) {
                return current
            }
            current = current.baseContext
        }
        return current as? Activity
    }

    fun inflate(context: Context, @LayoutRes layoutId: Int): View {
        val layout = moduleRes.getLayout(layoutId)
        val view = LayoutInflater.from(context).inflate(layout, null, false)
        view.tag = IGNORE_HOOK
        normalizeTree(view)
        return view
    }

    /**
     * 展示后固定窗口外观：宿主同色圆角卡片、合理宽度、最大高度。
     *
     * @param contentRoot 业务 setContentView 的根视图。高级页必须传入，才能把
     *   ScrollView(height=0, weight=1) 的父链撑到明确高度。
     * @param preferMaxHeight true 时窗口与内容占满最大高度（高级编辑页）
     */
    fun applyWindow(
        dialog: Dialog,
        contentRoot: View? = null,
        preferMaxHeight: Boolean = false,
    ) {
        val window = dialog.window ?: return
        val metrics = window.context.resources.displayMetrics
        val width = (metrics.widthPixels * 0.94f).toInt().coerceAtLeast(1)
        val maxHeight = (metrics.heightPixels * 0.88f).toInt().coerceAtLeast(1)

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(dialogBg)
            // 宿主卡片约 24dp 圆角
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                24f,
                metrics,
            )
        }
        window.setBackgroundDrawable(bg)
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
        )
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN,
        )

        fun applyContentHeight(exactHeight: Boolean) {
            val root = contentRoot ?: return
            root.setBackgroundColor(dialogBg)
            val targetH = if (exactHeight) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
            val lp = root.layoutParams
            if (lp != null) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = targetH
                root.layoutParams = lp
            } else {
                root.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    targetH,
                )
            }
            if (exactHeight) {
                expandParentsToMatch(root, maxDepth = 6)
            }
            normalizeTree(root)
        }

        if (preferMaxHeight) {
            window.setLayout(width, maxHeight)
            applyContentHeight(exactHeight = true)
            contentRoot?.post {
                window.setLayout(width, maxHeight)
                applyContentHeight(exactHeight = true)
                contentRoot.requestLayout()
            }
        } else {
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            applyContentHeight(exactHeight = false)
            window.decorView.post {
                if (window.decorView.height > maxHeight) {
                    window.setLayout(width, maxHeight)
                    applyContentHeight(exactHeight = true)
                    contentRoot?.requestLayout()
                }
            }
        }
    }

    private fun expandParentsToMatch(view: View, maxDepth: Int) {
        var parent = view.parent as? ViewGroup
        var depth = 0
        while (parent != null && depth < maxDepth) {
            val lp = parent.layoutParams
            if (lp != null) {
                var changed = false
                if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    changed = true
                }
                if (lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                    changed = true
                }
                if (changed) {
                    parent.layoutParams = lp
                }
            }
            if (parent.id == android.R.id.content) {
                break
            }
            parent = parent.parent as? ViewGroup
            depth++
        }
    }

    /** 注入树强制使用宿主色板，避免目标主题浅字/透明字叠在浅底上看不见。 */
    fun normalizeTree(root: View) {
        when (root) {
            is EditText -> styleEditText(root)
            is Button -> styleButton(root)
            is TextView -> root.setTextColor(dialogText)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                normalizeTree(root.getChildAt(i))
            }
        }
    }

    private fun styleEditText(editText: EditText) {
        val metrics = editText.resources.displayMetrics
        val radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, metrics)
        val hPad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, metrics).toInt()
        val vPad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, metrics).toInt()
        editText.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(dialogInputBg)
            cornerRadius = radius
            setStroke(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, metrics).toInt()
                    .coerceAtLeast(1),
                dialogDivider,
            )
        }
        editText.setTextColor(dialogText)
        editText.setHintTextColor(dialogHint)
        editText.setPadding(hPad, vPad, hPad, vPad)
    }

    private fun styleButton(button: Button) {
        val label = button.text?.toString().orEmpty()
        val (bg, fg) = when {
            looksDanger(label) -> dialogDangerBg to dialogDangerText
            looksPrimary(label) -> dialogPrimaryBg to dialogPrimaryText
            else -> dialogButtonBg to dialogButtonText
        }
        val metrics = button.resources.displayMetrics
        val radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14f, metrics)
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = radius
        }
        button.setTextColor(fg)
        button.minHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            44f,
            metrics,
        ).toInt()
        val hPad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14f, metrics).toInt()
        button.setPadding(hPad, button.paddingTop, hPad, button.paddingBottom)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.elevation = 0f
            button.stateListAnimator = null
        }
    }

    private fun looksPrimary(label: String): Boolean {
        val t = label.lowercase()
        return t.contains("apply") ||
            t.contains("应用") ||
            t.contains("确定") ||
            t.contains("ok") ||
            t.contains("advanced") ||
            t.contains("高级")
    }

    private fun looksDanger(label: String): Boolean {
        val t = label.lowercase()
        return t.contains("delete") ||
            t.contains("删除") ||
            t.contains("hide") ||
            t.contains("隐藏")
    }
}
