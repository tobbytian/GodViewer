package com.godviewer.app.data

import android.app.Activity
import android.app.Application
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.godviewer.app.glide.GlideApp
import com.godviewer.app.util.getAttachedActivityFromView
import com.godviewer.app.util.getViewHierarchyDepth
import com.godviewer.app.util.isInActivityWindow
import com.godviewer.app.util.resourceNameOf
import com.godviewer.app.util.versionCode

/**
 * 持久化规则管理（单例，运行在被注入的目标进程内）。
 *
 * - [init]：Application.onCreate 时加载规则
 * - [createRule] / [saveRule] / [deleteRule]：对话框保存 / 撤销修改
 * - [applyRuleToView]：重放规则到视图（Activity 恢复时调用）
 * - [restoreView]：把视图还原为规则创建前的状态（"重置"）
 */
object ViewRuleManager {

    private const val TAG = "GodViewer.Rule"

    @Volatile
    private var initialized = false
    private var store: RuleStore? = null

    @Volatile
    private var rules: List<ViewRule> = emptyList()

    /** 已应用图片的规则键 -> URL，避免全局布局回调里反复用 Glide 加载 */
    private val appliedImages = HashMap<ViewRule.RuleKey, String>()

    fun init(application: Application) {
        if (initialized) {
            return
        }
        initialized = true
        val ruleStore = RuleStore(application.applicationContext)
        store = ruleStore
        rules = ruleStore.load()
        Log.d(TAG, "rules loaded: ${rules.size}")
    }

    /** 某 Activity 的全部规则（重放时使用） */
    fun rulesForActivity(activityClass: String): List<ViewRule> =
        rules.filter { it.activityClass == activityClass }

    /** 查找已存在的规则（对话框打开时判断是否已有规则） */
    fun findRule(view: View): ViewRule? {
        val activity = getAttachedActivityFromView(view) ?: return null
        val key = ViewRule.RuleKey(
            activity.componentName.className,
            getViewHierarchyDepth(view),
            view.javaClass.name
        )
        return rules.firstOrNull { it.key() == key }
    }

    /**
     * 为视图创建一个规则外壳（原始值 = 当前值，修改值 = 当前值，changed* 全 false）。
     * 视图不在 Activity 窗口（如对话框 / Popup 内部）或找不到 Activity 时返回 null。
     */
    fun createRule(view: View): ViewRule? {
        val activity = getAttachedActivityFromView(view) ?: return null
        if (!isInActivityWindow(view, activity)) {
            return null
        }
        val snapshot = captureSnapshot(view)
        return ViewRule(
            packageName = activity.packageName,
            matchVersionCode = versionCode(activity),
            activityClass = activity.componentName.className,
            viewClass = view.javaClass.name,
            depth = getViewHierarchyDepth(view),
            resourceName = resourceNameOf(view),
            text = (view as? TextView)?.text?.toString(),
            description = view.contentDescription?.toString(),
            original = snapshot,
            modified = snapshot,
            timestamp = System.currentTimeMillis()
        )
    }

    /** 保存（或更新）一条规则 */
    fun saveRule(rule: ViewRule) {
        rule.timestamp = System.currentTimeMillis()
        appliedImages.remove(rule.key())
        val index = rules.indexOfFirst { it.key() == rule.key() }
        rules = if (index >= 0) {
            rules.toMutableList().apply { set(index, rule) }
        } else {
            rules + rule
        }
        store?.save(rules)
        Log.d(TAG, "rule saved: $rule")
    }

    /** 删除一条规则 */
    fun deleteRule(rule: ViewRule) {
        appliedImages.remove(rule.key())
        rules = rules.filterNot { it.key() == rule.key() }
        store?.save(rules)
        Log.d(TAG, "rule deleted: ${rule.key()}")
    }

    /** 重放：把规则的修改值应用到视图。返回是否发生了布局变化。 */
    fun applyRuleToView(view: View, rule: ViewRule): Boolean {
        var changed = false
        val s = rule.modified
        val lp = view.layoutParams

        if (rule.changedSize && lp != null) {
            if (lp.width != s.width || lp.height != s.height) {
                lp.width = s.width
                lp.height = s.height
                changed = true
            }
        }
        if (rule.changedMargin && lp is ViewGroup.MarginLayoutParams) {
            if (lp.leftMargin != s.marginLeft || lp.topMargin != s.marginTop ||
                lp.rightMargin != s.marginRight || lp.bottomMargin != s.marginBottom
            ) {
                lp.setMargins(s.marginLeft, s.marginTop, s.marginRight, s.marginBottom)
                changed = true
            }
        }
        if (changed) {
            view.layoutParams = lp
        }
        if (rule.changedPadding) {
            if (view.paddingLeft != s.paddingLeft || view.paddingTop != s.paddingTop ||
                view.paddingRight != s.paddingRight || view.paddingBottom != s.paddingBottom
            ) {
                view.setPadding(s.paddingLeft, s.paddingTop, s.paddingRight, s.paddingBottom)
            }
        }
        if (rule.changedVisibility && view.visibility != s.visibility) {
            view.visibility = s.visibility
        }
        if (rule.changedText && view is TextView) {
            if (view.text?.toString() != s.text) {
                view.text = s.text ?: ""
            }
            if (view.maxLines != s.maxLines) {
                view.maxLines = s.maxLines
            }
        }
        if (rule.changedImage && view is ImageView) {
            val key = rule.key()
            if (appliedImages[key] != s.imageUrl) {
                appliedImages[key] = s.imageUrl ?: ""
                if (!s.imageUrl.isNullOrEmpty()) {
                    GlideApp.with(view).load(s.imageUrl).into(view)
                }
                s.scaleType?.let { name ->
                    runCatching { ImageView.ScaleType.valueOf(name) }
                        .getOrNull()
                        ?.let { view.scaleType = it }
                }
            }
        }
        return changed
    }

    /** 恢复：把视图还原为规则创建前的原始状态（图片 URL 无法还原，仅还原 scaleType） */
    fun restoreView(view: View, rule: ViewRule) {
        val s = rule.original
        val lp = view.layoutParams
        if (lp != null) {
            lp.width = s.width
            lp.height = s.height
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.setMargins(s.marginLeft, s.marginTop, s.marginRight, s.marginBottom)
            }
            view.layoutParams = lp
        }
        view.setPadding(s.paddingLeft, s.paddingTop, s.paddingRight, s.paddingBottom)
        view.visibility = s.visibility
        if (view is TextView) {
            view.text = s.text ?: ""
            view.maxLines = s.maxLines
        }
        if (view is ImageView) {
            s.scaleType?.let { name ->
                runCatching { ImageView.ScaleType.valueOf(name) }
                    .getOrNull()
                    ?.let { view.scaleType = it }
            }
        }
        appliedImages.remove(rule.key())
    }

    /** 捕获视图当前属性快照（像素值） */
    private fun captureSnapshot(view: View): ViewAttrSnapshot {
        val lp = view.layoutParams
        val mlp = lp as? ViewGroup.MarginLayoutParams
        return ViewAttrSnapshot(
            width = lp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            height = lp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            marginLeft = mlp?.leftMargin ?: 0,
            marginTop = mlp?.topMargin ?: 0,
            marginRight = mlp?.rightMargin ?: 0,
            marginBottom = mlp?.bottomMargin ?: 0,
            paddingLeft = view.paddingLeft,
            paddingTop = view.paddingTop,
            paddingRight = view.paddingRight,
            paddingBottom = view.paddingBottom,
            visibility = view.visibility,
            text = (view as? TextView)?.text?.toString(),
            maxLines = (view as? TextView)?.maxLines ?: Int.MAX_VALUE,
            imageUrl = null,
            scaleType = (view as? ImageView)?.scaleType?.name
        )
    }
}
