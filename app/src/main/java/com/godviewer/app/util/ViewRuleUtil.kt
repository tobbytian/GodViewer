package com.godviewer.app.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.TextView
import com.godviewer.app.data.ViewRule

/**
 * 视图标识与匹配工具（GodMode ViewHelper 移植，只使用公共 API）。
 *
 * 持久化重放的核心：规则里记录视图在 Activity 视图树中的深度路径
 * （自 decorView 向下逐层 childIndex），重启后用相同路径重新定位视图。
 */

/** 自 decorView 向下逐层 childIndex 组成的路径 */
fun getViewHierarchyDepth(view: View): List<Int> {
    val depth = ArrayList<Int>()
    var v: View = view
    var parent: ViewParent? = view.parent
    while (parent is ViewGroup) {
        depth.add(0, parent.indexOfChild(v))
        v = parent
        parent = v.parent
    }
    return depth
}

/** 从 Activity 的 decorView 按 depth 路径定位视图 */
fun findViewByDepth(activity: Activity, depth: List<Int>): View? {
    var view: View = activity.window.decorView
    for (index in depth) {
        view = if (view is ViewGroup && index in 0 until view.childCount) {
            view.getChildAt(index)
        } else {
            return null
        }
    }
    return view
}

/** 递归按文字查找视图 */
fun findViewByText(view: View, text: String): View? {
    if (view is TextView && view.text?.toString() == text) {
        return view
    }
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            findViewByText(view.getChildAt(i), text)?.let { return it }
        }
    }
    return null
}

/** 从视图上溯 Context 链找到所属 Activity */
fun getAttachedActivityFromView(view: View): Activity? {
    getActivityFromViewContext(view.context)?.let { return it }
    val parent = view.parent
    return if (parent is ViewGroup) getAttachedActivityFromView(parent) else null
}

private fun getActivityFromViewContext(context: Context): Activity? {
    if (context is Activity) {
        return context
    }
    if (context is ContextWrapper) {
        // 不直接取 baseContext，某些 App（如微信的 PluginContextWrapper）
        // getBaseContext 返回自身会导致死循环，这里兜底
        val base = context.baseContext
        return if (base == context) null else getActivityFromViewContext(base)
    }
    return null
}

/** 视图是否属于 Activity 自身的窗口（排除对话框 / PopupWindow 的视图） */
fun isInActivityWindow(view: View, activity: Activity): Boolean {
    var v: View = view
    var parent: ViewParent? = view.parent
    while (parent is ViewGroup) {
        v = parent
        parent = v.parent
    }
    return v === activity.window.decorView
}

/** 目标应用当前版本号 */
fun versionCode(activity: Activity): Int {
    return try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode
    } catch (e: PackageManager.NameNotFoundException) {
        0
    }
}

/** 视图资源名（如 "com.example:id/title"），无资源 id 时为 null */
fun resourceNameOf(view: View): String? {
    if (view.id == View.NO_ID) {
        return null
    }
    return try {
        view.resources.getResourceName(view.id)
    } catch (e: Exception) {
        null
    }
}

/** 把 "pkg:type/entry" 形式的资源名解析为资源 id */
fun resolveResourceId(view: View, resourceName: String): Int {
    return try {
        val start = resourceName.split(":")
        if (start.size < 2) return View.NO_ID
        val end = start[1].split("/")
        if (end.size < 2) return View.NO_ID
        view.resources.getIdentifier(end[1], end[0], start[0])
    } catch (e: Exception) {
        View.NO_ID
    }
}

/**
 * 在 Activity 中按规则定位视图。
 *
 * 匹配顺序：depth 路径（严格模式下同时校验资源名，避免应用升级后误匹配）
 * → resourceName → text。每个候选都校验 viewClass。
 */
fun findViewBestMatch(activity: Activity, rule: ViewRule): View? {
    val strict = versionCode(activity) == rule.matchVersionCode
    // 1) depth 优先
    findViewByDepth(activity, rule.depth)?.let { view ->
        if (view.javaClass.name == rule.viewClass) {
            if (strict || rule.resourceName.isNullOrEmpty() || resourceNameOf(view) == rule.resourceName) {
                return view
            }
        }
    }
    // 2) resourceName 兜底
    if (!rule.resourceName.isNullOrEmpty()) {
        val id = resolveResourceId(activity.window.decorView, rule.resourceName)
        if (id != View.NO_ID) {
            activity.findViewById<View>(id)?.let { view ->
                if (view.javaClass.name == rule.viewClass) {
                    return view
                }
            }
        }
    }
    // 3) text 兜底
    if (!rule.text.isNullOrEmpty()) {
        findViewByText(activity.window.decorView, rule.text)?.let { view ->
            if (view.javaClass.name == rule.viewClass) {
                return view
            }
        }
    }
    return null
}
