package com.godviewer.app.target.dialog

import android.app.Dialog
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ListView
import com.godviewer.app.R
import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.hook.ModuleRes.moduleRes

/**
 * 注入弹窗窗口外观：半透明圆角底板、宽度/最大高度、系统 Alert 列表 chrome 软化。
 */
internal object ModuleDialogWindow {

    private const val TAG = "UI"

    /** 编辑界面整体不透明度：92%，微微透出被编辑的下层界面（dim 背景另行控制） */
    private const val WINDOW_ALPHA = 0.92f

    fun apply(
        dialog: Dialog,
        contentRoot: View? = null,
        preferMaxHeight: Boolean = false,
    ) {
        val window = dialog.window ?: run {
            GvLog.w(TAG, "window apply skipped: no window dialog=${dialog.javaClass.name}")
            return
        }
        val metrics = window.context.resources.displayMetrics
        val width = (metrics.widthPixels * 0.94f).toInt().coerceAtLeast(1)
        val maxHeight = (metrics.heightPixels * 0.88f).toInt().coerceAtLeast(1)

        val panelBg = ModuleDialogPalette.dialogPanelBg
        // 整窗与卡片/开关同语言：2dp 渐变环 + 90% 面板色圆角底
        val bg = moduleRes.getDrawable(R.drawable.bg_dialog_panel, null)
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        window.setBackgroundDrawable(bg)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.18f)
        // 整体 92% 不透明度（窗口级 alpha，含面板/文字/边框一起变透）
        window.attributes = window.attributes.apply { alpha = WINDOW_ALPHA }
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
            root.setBackgroundColor(Color.TRANSPARENT)
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
            ModuleDialogStyler.normalizeTree(root)
        }

        window.decorView.post {
            runCatching { softenSystemDialogChrome(window.decorView, panelBg) }
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

    private fun softenSystemDialogChrome(root: View, panelBg: Int) {
        if (root is ListView) {
            root.setBackgroundColor(Color.TRANSPARENT)
            root.cacheColorHint = Color.TRANSPARENT
            root.selector = ColorDrawable(Color.TRANSPARENT)
        }
        when (root.id) {
            android.R.id.title,
            android.R.id.content,
            -> {
                if (root.background != null) {
                    root.setBackgroundColor(Color.TRANSPARENT)
                }
            }
        }
        val bg = root.background
        if (bg is ColorDrawable) {
            val c = bg.color
            val opaque = ((c ushr 24) and 0xFF) >= 0xF0
            if (opaque && (c and 0x00FFFFFF) == 0x00FFFFFF) {
                root.setBackgroundColor(panelBg)
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                softenSystemDialogChrome(root.getChildAt(i), panelBg)
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
}
