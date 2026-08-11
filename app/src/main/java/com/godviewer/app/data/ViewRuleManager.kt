package com.godviewer.app.data

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.drawToBitmap
import com.godviewer.app.glide.GlideApp
import com.godviewer.app.util.findViewBestMatch
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

    /** 撤销栈深度上限（内存态，进程重启即清空） */
    private const val MAX_UNDO_STEPS = 10

    @Volatile
    private var initialized = false
    private var store: RuleStore? = null

    @Volatile
    private var rules: List<ViewRule> = emptyList()

    /**
     * 内存撤销栈：每次 [saveRule] / [deleteRule] 前压入当前规则列表，
     * [undoLastOperation] 出栈恢复上一步状态（规则数据 + 持久化 + 视图回放）。
     */
    private val undoStack = ArrayDeque<List<ViewRule>>()

    /** 已应用图片的规则键 -> URL，避免全局布局回调里反复用 Glide 加载 */
    private val appliedImages = HashMap<ViewRule.RuleKey, String>()

    /**
     * 规则键 -> 缩略图（内存缓存，进程重启即清空）。
     * 在 [createRule] 时（视图仍可见、尚未被隐藏/修改）截取，与编辑弹窗预览图一致，
     * 供规则管理列表展示；视图被隐藏后 drawToBitmap 画不出有效内容。
     */
    private val thumbnails = HashMap<ViewRule.RuleKey, Bitmap>()

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
        val rule = ViewRule(
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
        // 视图此刻仍可见，截取缩略图供规则管理列表使用（与编辑弹窗预览一致）
        if (view.isLaidOut && view.width > 0 && view.height > 0) {
            runCatching { thumbnails[rule.key()] = scaleDownThumbnail(view.drawToBitmap()) }
        }
        return rule
    }

    /** 规则对应的缩略图（无缓存时为 null） */
    fun thumbnailFor(rule: ViewRule): Bitmap? = thumbnails[rule.key()]

    /** 缩略图最大边长限制，避免大视图占用过多内存 */
    private fun scaleDownThumbnail(bitmap: Bitmap, max: Int = 256): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= max && height <= max) {
            return bitmap
        }
        val scale = max.toFloat() / maxOf(width, height)
        val newWidth = (width * scale).toInt().coerceAtLeast(1)
        val newHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /** 保存（或更新）一条规则 */
    fun saveRule(rule: ViewRule) {
        pushUndoState()
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
        pushUndoState()
        appliedImages.remove(rule.key())
        thumbnails.remove(rule.key())
        rules = rules.filterNot { it.key() == rule.key() }
        store?.save(rules)
        Log.d(TAG, "rule deleted: ${rule.key()}")
    }

    /** 删除一条规则；[restoreIn] 非空时先在对应 Activity 中还原该规则关联的视图 */
    fun deleteRule(rule: ViewRule, restoreIn: Activity?) {
        restoreIn?.let { activity ->
            findViewBestMatch(activity, rule)?.let { view ->
                restoreView(view, rule)
            }
        }
        deleteRule(rule)
    }

    /** 当前全部规则（规则管理列表使用） */
    fun allRules(): List<ViewRule> = rules

    /** 是否有可撤销的操作 */
    fun canUndo(): Boolean = undoStack.isNotEmpty()

    /** 撤销栈是否已满，满时丢弃最旧的记录 */
    private fun pushUndoState() {
        undoStack.addLast(rules)
        if (undoStack.size > MAX_UNDO_STEPS) {
            undoStack.removeFirst()
        }
    }

    /**
     * 撤销上一个规则操作：恢复上一步的规则列表并持久化；[activity] 非空时
     * 对当前界面的视图做精确回放（先还原原始值再套用上一步的 modified），
     * 被撤销掉的新建规则（如 新建+隐藏）则直接还原视图，让隐藏的视图重新可见。
     *
     * @return 是否成功撤销（撤销栈为空时返回 false）
     */
    fun undoLastOperation(activity: Activity?): Boolean {
        if (undoStack.isEmpty()) {
            return false
        }
        val previous = undoStack.removeLast()
        val current = rules
        rules = previous
        appliedImages.clear()
        store?.save(rules)
        activity?.let { act ->
            // 恢复后的每条规则：先还原原始值，再套用上一步的修改状态
            for (rule in previous) {
                findViewBestMatch(act, rule)?.let { view ->
                    restoreView(view, rule)
                    applyRuleToView(view, rule)
                }
            }
            // 撤销后消失的规则（上一步才新建）：还原视图为创建前状态
            for (rule in current) {
                if (previous.none { it.key() == rule.key() }) {
                    findViewBestMatch(act, rule)?.let { view ->
                        restoreView(view, rule)
                    }
                }
            }
        }
        Log.d(TAG, "rule undone: ${previous.size} rules restored")
        return true
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
