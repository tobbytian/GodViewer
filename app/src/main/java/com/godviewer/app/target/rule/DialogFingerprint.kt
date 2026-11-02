package com.godviewer.app.target.rule

import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

/**
 * 弹窗文本指纹：遍历 Dialog decorView 收集 TextView 文本，供 [DialogRule] 匹配。
 *
 * 排除 Button（"确定/取消"等通用按钮文字区分度低且随语言变化）；
 * 每条截 80 字、去空白、去重、上限 10 条 — 宁可信息少，不可误匹配。
 */
internal object DialogFingerprint {
    private const val MAX_TEXTS = 10
    private const val MAX_TEXT_LEN = 80
    private const val MIN_STRUCT_TOKENS = 2
    private const val ID_PREFIX = "id:"
    private const val DESC_PREFIX = "desc:"

    /** 收集弹窗内非按钮 TextView 文本（decor 尚未布局完时返回空列表） */
    fun textsOf(dialog: Dialog): List<String> {
        val decor = runCatching { dialog.window?.decorView }.getOrNull() ?: return emptyList()
        val out = LinkedHashSet<String>()
        collect(decor, out)
        if (out.isEmpty()) {
            // 纯图片类弹窗没有文字：退回控件 id 名 / 内容描述作结构指纹。
            // 不足 MIN_STRUCT_TOKENS 条说明内容还没填充或太泛，宁可视为无指纹
            collectStructure(decor, out)
            if (out.size < MIN_STRUCT_TOKENS) out.clear()
        }
        return out.toList()
    }

    private fun collectStructure(view: View, out: MutableSet<String>) {
        if (out.size >= MAX_TEXTS) return
        val desc = view.contentDescription?.toString()?.trim().orEmpty()
        if (desc.isNotEmpty()) out.add(DESC_PREFIX + desc.take(MAX_TEXT_LEN))
        val id = view.id
        // 高字节 0x01 是系统框架资源（content、parentPanel 等），太泛不取
        if (id != View.NO_ID && (id ushr 24) != 0x01) {
            val name = runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
            if (!name.isNullOrEmpty()) out.add(ID_PREFIX + name)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectStructure(view.getChildAt(i), out)
            }
        }
    }

    private fun collect(view: View, out: MutableSet<String>) {
        if (out.size >= MAX_TEXTS) return
        if (view is TextView && view !is Button) {
            val text = view.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                out.add(text.take(MAX_TEXT_LEN))
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collect(view.getChildAt(i), out)
            }
        }
    }

    /** 目标应用当前版本号（失败返回 0，与 ViewRule 的 versionCode 行为一致） */
    fun versionCodeOf(context: Context): Int {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (e: PackageManager.NameNotFoundException) {
            0
        }
    }
}