package com.godviewer.app.target.dialog

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * 注入弹窗控件强制宿主色板，避免目标主题浅字/透明字叠在浅底上看不见。
 */
internal object ModuleDialogStyler {

    fun normalizeTree(root: View) {
        when (root) {
            is EditText -> styleEditText(root)
            is Button -> styleButton(root)
            is TextView -> root.setTextColor(ModuleDialogPalette.dialogText)
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
            setColor(ModuleDialogPalette.dialogInputBg)
            cornerRadius = radius
            setStroke(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, metrics).toInt()
                    .coerceAtLeast(1),
                ModuleDialogPalette.dialogDivider,
            )
        }
        editText.setTextColor(ModuleDialogPalette.dialogText)
        editText.setHintTextColor(ModuleDialogPalette.dialogHint)
        editText.setPadding(hPad, vPad, hPad, vPad)
    }

    private fun styleButton(button: Button) {
        val label = button.text?.toString().orEmpty()
        val (bg, fg) = when {
            looksDanger(label) ->
                ModuleDialogPalette.dialogDangerBg to ModuleDialogPalette.dialogDangerText
            looksPrimary(label) ->
                ModuleDialogPalette.dialogPrimaryBg to ModuleDialogPalette.dialogPrimaryText
            else ->
                ModuleDialogPalette.dialogButtonBg to ModuleDialogPalette.dialogButtonText
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
        // minSdk 23 (LOLLIPOP+21) 恒成立：压平按钮阴影，避免目标主题 elevation 叠加
        button.elevation = 0f
        button.stateListAnimator = null
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
