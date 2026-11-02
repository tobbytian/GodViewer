package com.godviewer.app.target.rule

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.TextView
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.model.ViewRule

/**
 * 视图标识与匹配工具（GodMode ViewHelper 移植，只使用公共 API）。
 *
 * 持久化重放的核心：规则里记录视图在 Activity 视图树中的深度路径
 * （自 decorView 向下逐层 childIndex），重启后用相同路径重新定位视图。
 *
 * 匹配原则（双锚，借鉴 GodMode-Pro CompositeMatcher）：depth 是主锚，resourceName
 * 是交叉验证锚；**错配宿主内容的代价高于暂时漏应用**——漏应用可由下一帧布局
 * 重放兜底，错配（改错视图）不可恢复。因此 depth 命中但资源名不符时直接判失配，
 * 且带资源名锚的规则不再做 text 兜底（text 兜底是"命中同文本其他视图"的误配源）。
 */

/** 诊断码：匹配失败的原因，"规则没生效"排查的第一入口 */
object MatchMiss {
    const val DEPTH_PATH_MISSING = "depth_path_missing"
    const val CLASS_MISMATCH = "class_mismatch"
    const val NAME_MISMATCH = "name_mismatch"
    const val NAME_ANCHOR_FAILED = "name_anchor_failed"
    const val TEXT_MISS = "text_miss"
    const val NO_LOCATOR = "no_locator"
}

/**
 * 一次匹配的结果：[view] 非空即命中；为空时 [reason] 是 [MatchMiss] 中的诊断码。
 */
data class MatchAttempt(val view: View?, val reason: String?) {
    companion object {
        fun hit(view: View) = MatchAttempt(view, null)
        fun miss(reason: String) = MatchAttempt(null, reason)
    }
}

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

/**
 * 视图相对 [root] 的 childIndex 路径（[view] 即 [root] 时为空路径）；
 * [view] 不在 [root] 子树内时返回 null。
 */
fun pathWithin(view: View, root: View): List<Int>? {
    if (view === root) return emptyList()
    val path = ArrayList<Int>()
    var v: View = view
    var parent: ViewParent? = view.parent
    while (parent is ViewGroup) {
        path.add(0, parent.indexOfChild(v))
        v = parent
        parent = v.parent
        if (v === root) return path
    }
    return null
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
 * 在 Activity 中按规则定位视图（老签名，重放之外的还原路径使用）。
 *
 * 匹配顺序：depth 路径（命中后必须通过资源名交叉验证）→ resourceName 锚
 * → text（仅无资源名锚的规则）。每个候选都校验 viewClass。
 * 详见 [findViewBestMatchAttempt] 与类头的"双锚"原则说明。
 */
fun findViewBestMatch(activity: Activity, rule: ViewRule): View? {
    return findViewBestMatchAttempt(activity, rule).view
}

/**
 * 在 Activity 中按规则定位视图，失败时带 [MatchMiss] 诊断码。
 *
 * - depth 主锚：命中后若有资源名则强制交叉验证，不符即失配（宁漏勿错）；
 * - 非严格模式（应用已升级、depth 可能漂移）下 depth 失配可回落资源名锚恢复；
 * - text 兜底只对没有资源名锚的规则启用（带锚规则的 text 兜底是误配源）。
 */
fun findViewBestMatchAttempt(activity: Activity, rule: ViewRule): MatchAttempt {
    val strict = versionCode(activity) == rule.matchVersionCode
    val ruleName = rule.resourceName
    // 1) depth 主锚
    findViewByDepth(activity, rule.depth)?.let { view ->
        if (view.javaClass.name != rule.viewClass) {
            return@let
        }
        if (ruleName.isNullOrEmpty() || resourceNameOf(view) == ruleName) {
            return MatchAttempt.hit(view)
        }
        // depth 命中但资源名不符：布局已漂移或路径撞上同类节点。
        // 严格模式（同版本）直接失配；非严格模式回落资源名锚尝试恢复。
        if (strict) {
            return MatchAttempt.miss(MatchMiss.NAME_MISMATCH)
        }
    }
    // 2) resourceName 锚（depth 失配或非严格漂移时的恢复路径）
    if (!ruleName.isNullOrEmpty()) {
        val id = resolveResourceId(activity.window.decorView, ruleName)
        if (id != View.NO_ID) {
            activity.findViewById<View>(id)?.let { view ->
                if (view.javaClass.name == rule.viewClass) {
                    return MatchAttempt.hit(view)
                }
            }
        }
        return MatchAttempt.miss(MatchMiss.NAME_ANCHOR_FAILED)
    }
    // 3) text 兜底（仅无资源名锚的规则）
    if (!rule.text.isNullOrEmpty()) {
        findViewByText(activity.window.decorView, rule.text)?.let { view ->
            if (view.javaClass.name == rule.viewClass) {
                return MatchAttempt.hit(view)
            }
        }
        return MatchAttempt.miss(MatchMiss.TEXT_MISS)
    }
    return MatchAttempt.miss(if (rule.depth.isEmpty()) MatchMiss.NO_LOCATOR else MatchMiss.DEPTH_PATH_MISSING)
}

// ---------- repeatable（列表项规则）匹配 ----------

/** 单条 repeatable 规则在一次对账/预览里的最大命中数，防极端列表整帧卡顿 */
private const val MAX_REPEATABLE_MATCHES = 300

/** (RecyclerView, AbsListView) 按目标 ClassLoader 缓存；RecyclerView 不存在时为 null */
private val listContainerClasses = HashMap<ClassLoader, Pair<Class<*>?, Class<*>?>>()

internal fun listContainerClassesOf(view: View): Pair<Class<*>?, Class<*>?> {
    val loader = view.javaClass.classLoader ?: return null to android.widget.AbsListView::class.java
    return synchronized(listContainerClasses) {
        listContainerClasses.getOrPut(loader) {
            val listView = runCatching { android.widget.AbsListView::class.java }.getOrNull()
            val recyclerView = runCatching {
                Class.forName("androidx.recyclerview.widget.RecyclerView", false, loader)
            }.getOrNull()
            recyclerView to listView
        }
    }
}

/**
 * 视图所在列表项的 item 根（RecyclerView / ListView 的直接子项）。
 * 不在任何列表内时返回 null —— 此时按普通规则处理。
 */
fun listItemAnchor(view: View): View? {
    val (recyclerView, listView) = listContainerClassesOf(view)
    var v: View = view
    var parent: ViewParent? = view.parent
    while (parent is ViewGroup) {
        if ((recyclerView != null && recyclerView.isInstance(parent)) ||
            (listView != null && listView.isInstance(parent))
        ) {
            return v
        }
        v = parent
        parent = v.parent
    }
    return null
}

/**
 * 捕获侧：[anchor]（item 根）在 RecyclerView 中的 adapter viewType，
 * bind 路径按它过滤混类型列表的同类项。ListView 或反射失败一律返回 0
 * （= [ViewRule.viewType] 不过滤，向后兼容）。
 */
fun itemViewTypeOf(anchor: View): Int {
    val (recyclerViewClass, _) = listContainerClassesOf(anchor)
    val container = anchor.parent as? ViewGroup ?: return 0
    if (recyclerViewClass == null || !recyclerViewClass.isInstance(container)) return 0
    return runCatching {
        val position = container.javaClass
            .getMethod("getChildAdapterPosition", View::class.java)
            .invoke(container, anchor) as? Int ?: return 0
        if (position < 0) return 0
        val adapter = container.javaClass.getMethod("getAdapter")
            .invoke(container) ?: return 0
        adapter.javaClass
            .getMethod("getItemViewType", Int::class.javaPrimitiveType)
            .invoke(adapter, position) as? Int ?: 0
    }.getOrDefault(0)
}

/**
 * repeatable 安全锚检查：text 或 resourceName 至少一个非空。
 * 两者皆无的规则会命中列表里**所有**同路径视图（混合 item 类型时即错杀）——
 * 错杀零容忍：创建侧据此拒绝 repeatable（降级普通规则），匹配侧据此刻底
 * （防宿主编辑 / 导入产出此类规则）。
 */
fun hasRepeatableAnchor(view: View): Boolean {
    if (!resourceNameOf(view).isNullOrEmpty()) return true
    return (view as? TextView)?.text?.isNotEmpty() == true
}

/**
 * 单视图是否命中 repeatable 规则：自 [view] 上溯 [ViewRule.depth]（itemPath）锚定
 * item 根并校验 [ViewRule.itemRootClass]，再 AND 校验 viewClass / resourceName /
 * text（按 textMatchMode）。所有非空字段必须全部匹配。
 */
fun matchRepeatableView(rule: ViewRule, view: View): Boolean {
    if (!rule.repeatable) return false
    val rootClass = rule.itemRootClass ?: return false
    // 错杀零容忍：无任何区分锚的规则不参与匹配（创建侧已拦截，这里兜底
    // 宿主编辑 / 导入下发的规则）
    if (rule.text.isNullOrEmpty() && rule.resourceName.isNullOrEmpty()) return false
    if (view.javaClass.name != rule.viewClass) return false
    var v: View = view
    for (i in rule.depth.indices.reversed()) {
        val parent = v.parent as? ViewGroup ?: return false
        if (parent.indexOfChild(v) != rule.depth[i]) return false
        v = parent
    }
    if (v.javaClass.name != rootClass) return false
    // viewType 判别（对账路径）：bind 路径由 viewTypeMatches 过滤；hook 未激活
    // 由对账器覆盖 RecyclerView 时同样要防混类型错杀。候选类型读不到（≤0）
    // 视为不匹配——宁漏勿错。
    if (rule.viewType > 0) {
        val candidateType = itemViewTypeOf(v)
        if (candidateType <= 0 || candidateType != rule.viewType) return false
    }
    if (!rule.resourceName.isNullOrEmpty() && resourceNameOf(view) != rule.resourceName) {
        return false
    }
    if (!rule.text.isNullOrEmpty() &&
        !textMatches((view as? TextView)?.text?.toString(), rule.text, rule.textMatchMode)
    ) {
        return false
    }
    return true
}

/**
 * bind 路径（[RecyclerBindingHooker]）专用：自 item 根按 [ViewRule.depth]
 * （itemPath 语义）向下导航到目标视图并做 AND 校验。
 *
 * 与逐候选上溯验证的 [matchRepeatableView] 相反：这是"从 item 根往下走"的
 * 精确管线（借鉴 GodMode-Pro navigateAndValidate）——导航本身就是主校验
 * （逐级 childIndex），终点再验 viewClass / resourceName / text。
 * 本规则 schema 未存逐级类名，故不做类名链回退（布局期对账兜底漏应用）。
 *
 * @return 验证通过的目标视图；导航失败或校验失败返回 null
 */
fun findTargetInItem(itemRoot: View, rule: ViewRule): View? {
    if (!rule.repeatable) return null
    // 错杀零容忍：无任何区分锚的规则不参与 bind 路径应用（同 matchRepeatableView）
    if (rule.text.isNullOrEmpty() && rule.resourceName.isNullOrEmpty()) return null
    if (rule.itemRootClass != null && itemRoot.javaClass.name != rule.itemRootClass) {
        return null
    }
    var v: View = itemRoot
    for (index in rule.depth) {
        v = if (v is ViewGroup && index in 0 until v.childCount) {
            v.getChildAt(index)
        } else {
            return null
        }
    }
    if (v.javaClass.name != rule.viewClass) return null
    if (!rule.resourceName.isNullOrEmpty() && resourceNameOf(v) != rule.resourceName) {
        return null
    }
    if (!rule.text.isNullOrEmpty() &&
        !textMatches((v as? TextView)?.text?.toString(), rule.text, rule.textMatchMode)
    ) {
        return null
    }
    return v
}

/** [viewType] 过滤：规则未记录（0）不过滤；否则必须与当前 item 的 viewType 一致
 * （候选类型未知也视为不匹配——错杀零容忍，宁漏勿错） */
fun viewTypeMatches(rule: ViewRule, boundViewType: Int): Boolean {
    return rule.viewType <= 0 || rule.viewType == boundViewType
}

/** 在 Activity 中收集 repeatable 规则命中的全部视图（上限 [MAX_REPEATABLE_MATCHES]） */
fun findRepeatableMatches(activity: Activity, rule: ViewRule): List<View> {
    val out = ArrayList<View>()
    if (!rule.repeatable) return out
    val decor = runCatching { activity.window?.decorView }.getOrNull() ?: return out
    collectRepeatableMatches(decor, rule, out)
    return out
}

private fun collectRepeatableMatches(view: View, rule: ViewRule, out: ArrayList<View>) {
    if (out.size >= MAX_REPEATABLE_MATCHES) return
    if (matchRepeatableView(rule, view)) {
        out.add(view)
        return
    }
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            collectRepeatableMatches(view.getChildAt(i), rule, out)
        }
    }
}

/** 按 [mode] 匹配文本；正则非法等异常一律回落精确匹配，绝不抛给调用方 */
fun textMatches(candidate: String?, pattern: String, mode: String?): Boolean {
    val c = candidate ?: return false
    return runCatching {
        when (mode) {
            ViewRule.MATCH_MODE_CONTAINS -> c.contains(pattern)
            ViewRule.MATCH_MODE_PREFIX -> c.startsWith(pattern)
            ViewRule.MATCH_MODE_SUFFIX -> c.endsWith(pattern)
            ViewRule.MATCH_MODE_REGEX -> Regex(pattern).containsMatchIn(c)
            else -> c == pattern
        }
    }.getOrDefault(c == pattern)
}
