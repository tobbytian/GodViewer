package com.godviewer.app.target.rule

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.model.ViewAttrSnapshot
import com.godviewer.app.shared.model.ViewRule
import com.godviewer.app.target.glide.GlideApp
import java.util.WeakHashMap

/**
 * 把 [ViewRule] 的 original/modified 快照应用到具体 View（重放 / 重置）。
 *
 * 不含持久化；[appliedImages] 仅防止布局回调里对同一 URL 反复 Glide。
 * 第二阶段迁包时预期落入 `target`。
 *
 * repeatable 规则（列表项）走独立的 [applyRepeatable] / [restoreRepeatable] 路径：
 * 每个视图应用前先记录**它自己**的基线（[repeatableBaselines]），还原时按各自基线
 * 恢复——列表各项原文不同，用规则创建例的 original 还原会写坏其他项的内容。
 */
internal object ViewRuleApplier {

    private const val TAG = "Rule"

    /** 已应用图片的规则键 -> URL */
    private val appliedImages = HashMap<ViewRule.RuleKey, String>()

    /** repeatable 已应用登记：视图 -> 规则键（弱引用，随视图回收自动清理） */
    private val appliedRepeatable = WeakHashMap<View, ViewRule.RuleKey>()

    /** repeatable 应用前各视图自身的基线快照 */
    private val repeatableBaselines = WeakHashMap<View, ViewAttrSnapshot>()

    /** repeatable 视图 -> 应用时的规则 timestamp（幂等与"换值重应用"判断） */
    private val appliedTimestamps = WeakHashMap<View, Long>()

    // ---------- 错杀防线：还原只碰规则声明过（changed*）的属性 ----------
    // 列表项会被宿主持续重写内容：还原若把整个基线盖回去，会把基线里的旧文本
    // 覆盖到宿主刚绑定的新内容上（只改了可见性的规则尤其危险）。掩码在应用时
    // 记录，还原时逐属性判断。

    private const val MASK_SIZE = 1
    private const val MASK_MARGIN = 1 shl 1
    private const val MASK_PADDING = 1 shl 2
    private const val MASK_VISIBILITY = 1 shl 3
    private const val MASK_TEXT = 1 shl 4
    private const val MASK_HINT = 1 shl 5
    private const val MASK_IMAGE = 1 shl 6

    /** repeatable 视图 -> 应用时规则声明的 changed* 掩码 */
    private val repeatableChangedMasks = WeakHashMap<View, Int>()

    private fun changedMaskOf(rule: ViewRule): Int {
        var mask = 0
        if (rule.changedSize) mask = mask or MASK_SIZE
        if (rule.changedMargin) mask = mask or MASK_MARGIN
        if (rule.changedPadding) mask = mask or MASK_PADDING
        if (rule.changedVisibility) mask = mask or MASK_VISIBILITY
        if (rule.changedText) mask = mask or MASK_TEXT
        if (rule.changedHint) mask = mask or MASK_HINT
        if (rule.changedImage) mask = mask or MASK_IMAGE
        return mask
    }

    /**
     * repeatable 视图 -> 应用时的绑定代次（epoch）。
     *
     * [EPOCH_RECONCILER] = 布局期对账器（RepeatableRuleReconciler，ListView 等无
     * bind hook 场景）所属；> 0 = [RecyclerBindingHooker] 的某次 ViewHolder 绑定
     * 所属。epoch 隔离两个写者的效果所有权：hook 的 discard/revoke 按 epoch 过滤，
     * 不会触碰对账器的登记项；对账器跳过 hook 管辖的 RecyclerView 子树。
     */
    private val appliedEpochs = WeakHashMap<View, Long>()

    /** 对账器命名空间的 epoch（hook 路径从 1 起自增） */
    const val EPOCH_RECONCILER = 0L

    /**
     * RecyclerView 绑定期 hook 是否已激活（[RecyclerBindingHooker] 安装成功时置位）。
     * 对账器据此让位 hook 管辖的 RecyclerView 子树——状态住在本对象避免
     * rule → hook 的反向依赖。
     */
    @Volatile
    var bindHookActive = false

    /**
     * bind hook 激活后由 hook 侧注入：对 [activity] 内已绑定可见的 RecyclerView item
     * 立即重应用规则（新规则保存 / 换值后的即时生效路径，不必等下一次 rebind）。
     * 对账器让位了 RecyclerView 子树，规则变更的即时生效必须借这条回调回到 hook——
     * 状态与回调都住在本对象，保持 rule 侧不反向依赖 hook。
     */
    @Volatile
    var boundItemRefresher: ((android.app.Activity) -> Unit)? = null

    /** 重放：把规则的修改值应用到视图。返回是否发生了布局变化。 */
    fun applyRuleToView(view: View, rule: ViewRule): Boolean {
        return runCatching {
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
            if (rule.changedHint && view is TextView) {
                val hint = s.hint ?: ""
                if (view.hint?.toString() != hint) {
                    view.hint = hint
                }
            }
            if (rule.changedImage && view is ImageView) {
                val key = rule.key()
                if (appliedImages[key] != s.imageUrl) {
                    appliedImages[key] = s.imageUrl ?: ""
                    if (!s.imageUrl.isNullOrEmpty()) {
                        // 部分目标进程 Glide/Context 在 onResume 早期不可用；失败只跳过图片
                        runCatching { GlideApp.with(view).load(s.imageUrl).into(view) }
                            .onFailure {
                                GvLog.d(
                                    TAG,
                                    "image load enqueue failed url=${s.imageUrl} err=$it",
                                )
                            }
                    }
                    s.scaleType?.let { name ->
                        runCatching { ImageView.ScaleType.valueOf(name) }
                            .getOrNull()
                            ?.let { view.scaleType = it }
                    }
                }
            }
            changed
        }.getOrElse { tr ->
            // 视图状态异常不能无声吞掉：这是"规则没生效"的关键证据
            GvLog.w(TAG, "apply failed key=${rule.key()} view=${view.javaClass.name}", tr)
            false
        }
    }

    /** 恢复：把视图还原为规则创建前的原始状态（图片 URL 无法还原，仅还原 scaleType） */
    fun restoreView(view: View, rule: ViewRule) {
        runCatching {
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
                // 仅当规则确实修改过 hint 时才还原，否则旧规则（没有 hint 字段，
                // 反序列化为 null）会把视图原本的 hint 清掉。
                if (rule.changedHint) view.hint = s.hint ?: ""
            }
            if (view is ImageView) {
                s.scaleType?.let { name ->
                    runCatching { ImageView.ScaleType.valueOf(name) }
                        .getOrNull()
                        ?.let { view.scaleType = it }
                }
            }
            appliedImages.remove(rule.key())
        }.onFailure {
            GvLog.w(TAG, "restore failed key=${rule.key()} view=${view.javaClass.name}", it)
        }
    }

    /** 捕获视图当前属性快照（像素值） */
    fun captureSnapshot(view: View): ViewAttrSnapshot {
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
            hint = (view as? TextView)?.hint?.toString(),
            maxLines = (view as? TextView)?.maxLines ?: Int.MAX_VALUE,
            imageUrl = null,
            scaleType = (view as? ImageView)?.scaleType?.name
        )
    }

    /**
     * 把 repeatable 规则应用到单个命中视图（效果与 [applyRuleToView] 相同，但按
     * 每视图基线记账）。
     *
     * 幂等：同一规则同一 timestamp 已应用则跳过；规则换值（timestamp 变化）先还原
     * 旧效果再应用新值；视图已被其他 repeatable 规则占用时跳过（先到先得）。
     *
     * @param epoch 应用所属的绑定代次（见 [appliedEpochs]）
     */
    fun applyRepeatable(view: View, rule: ViewRule, epoch: Long = EPOCH_RECONCILER): Boolean {
        return runCatching {
            val key = rule.key()
            val existing = appliedRepeatable[view]
            if (existing != null && existing != key) {
                return false
            }
            if (existing == key && appliedTimestamps[view] == rule.timestamp) {
                // 同规则同值已应用（bind 路径 attach 恢复高频进入）：仍补记 epoch，
                // 覆盖跨 epoch 复用同一视图但内容未变的场景
                appliedEpochs[view] = epoch
                return false
            }
            if (existing == key) {
                // 同规则换值：先按基线还原旧效果，避免旧 modified 残留
                restoreFromBaseline(view)
            }
            if (repeatableBaselines[view] == null) {
                repeatableBaselines[view] = captureSnapshot(view)
            }
            appliedRepeatable[view] = key
            appliedTimestamps[view] = rule.timestamp
            appliedEpochs[view] = epoch
            repeatableChangedMasks[view] = changedMaskOf(rule)
            // appliedImages 去重按规则键记账，repeatable 一键多视图会被去重误跳过，
            // 图片规则逐视图强制重新 enqueue
            if (rule.changedImage) {
                appliedImages.remove(key)
            }
            applyRuleToView(view, rule)
        }.getOrElse { tr ->
            GvLog.w(TAG, "applyRepeatable failed key=${rule.key()} view=${view.javaClass.name}", tr)
            false
        }
    }

    /**
     * ViewHolder 重绑定（bindViewHolder before）：丢弃 itemView 子树内属于
     * [epoch] 这一次绑定的全部已应用效果——**只清记账、不还原**。
     *
     * 借鉴 GodMode-Pro `discardBindingEffects`：宿主 adapter 紧接着会写入下一行
     * 内容，把旧效果按旧基线还原反而会把旧行内容写进新行（复用正确性的关键）。
     * 与 [revokeRepeatableByEpoch] 的区别：后者用于回收/分离边界，需还原基线。
     */
    fun discardRepeatableByEpoch(itemRoot: View, epoch: Long) {
        for (view in repeatableViewsInSubtree(itemRoot)) {
            if (appliedEpochs[view] != epoch) continue
            runCatching {
                repeatableBaselines.remove(view)
                appliedRepeatable.remove(view)
                appliedTimestamps.remove(view)
                appliedEpochs.remove(view)
            }
        }
    }

    /**
     * ViewHolder 回收 / 分离子窗口（onViewRecycled / onDetachedFromWindow）：
     * 按 [epoch] 还原 itemView 子树内的已应用效果（恢复各自基线）。
     * epoch 过滤保证旧代次的回收事件不会还原新代次刚写入的效果。
     */
    fun revokeRepeatableByEpoch(itemRoot: View, epoch: Long) {
        for (view in repeatableViewsInSubtree(itemRoot)) {
            if (appliedEpochs[view] != epoch) continue
            restoreRepeatable(view, appliedRepeatable[view] ?: continue)
        }
    }

    /** [root] 子树内已在 repeatable 登记表中的视图（item 子树很小，直接递归） */
    private fun repeatableViewsInSubtree(root: View): List<View> {
        val out = ArrayList<View>()
        fun collect(view: View) {
            if (appliedRepeatable.containsKey(view)) {
                out.add(view)
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    collect(view.getChildAt(i))
                }
            }
        }
        runCatching { collect(root) }
        return out
    }

    /** 把 repeatable 规则在 [view] 上的效果按其自身基线还原（未登记时为 no-op） */
    fun restoreRepeatable(view: View, ruleKey: ViewRule.RuleKey) {
        runCatching {
            if (appliedRepeatable[view] != ruleKey) return
            restoreFromBaseline(view)
        }.onFailure {
            GvLog.w(TAG, "restoreRepeatable failed key=$ruleKey view=${view.javaClass.name}", it)
        }
    }

    /** 当前登记在该规则键下的全部视图（快照，可安全边遍历边还原） */
    fun appliedViewsOf(ruleKey: ViewRule.RuleKey): List<View> {
        return synchronized(appliedRepeatable) {
            appliedRepeatable.entries.filter { it.value == ruleKey }.map { it.key }
        }
    }

    /** 视图当前登记的绑定代次；未登记返回 [Long.MIN_VALUE] */
    fun epochOf(view: View): Long = appliedEpochs[view] ?: Long.MIN_VALUE

    /** 全部 repeatable 登记项的快照（对账遍历用） */
    fun appliedRepeatableEntries(): List<Pair<View, ViewRule.RuleKey>> {
        return synchronized(appliedRepeatable) {
            appliedRepeatable.entries.map { it.key to it.value }
        }
    }

    /**
     * 清空全部 repeatable 记账。注意：只应在"所有已应用效果都已被还原"之后调用
     * （如进程级清理），否则基线丢失、已应用效果无法还原。
     */
    fun clearRepeatableState() {
        synchronized(appliedRepeatable) {
            appliedRepeatable.clear()
            repeatableBaselines.clear()
            appliedTimestamps.clear()
            appliedEpochs.clear()
            repeatableChangedMasks.clear()
        }
    }

    private fun restoreFromBaseline(view: View) {
        val baseline = repeatableBaselines.remove(view)
        appliedRepeatable.remove(view)
        appliedTimestamps.remove(view)
        appliedEpochs.remove(view)
        val mask = repeatableChangedMasks.remove(view) ?: -1
        if (baseline == null) return
        // 错杀防线：只还原规则声明过的属性；宿主自己写入的内容（如 rebind 后的
        // 新文本）不属于规则，绝不回写。掩码缺失（理论不可达，登记即有掩码）时
        // 退回全量还原保底。
        if (mask and (MASK_SIZE or MASK_MARGIN) != 0) {
            val lp = view.layoutParams
            if (lp != null) {
                if (mask and MASK_SIZE != 0) {
                    lp.width = baseline.width
                    lp.height = baseline.height
                }
                if (mask and MASK_MARGIN != 0 && lp is ViewGroup.MarginLayoutParams) {
                    lp.setMargins(baseline.marginLeft, baseline.marginTop, baseline.marginRight, baseline.marginBottom)
                }
                view.layoutParams = lp
            }
        }
        if (mask and MASK_PADDING != 0) {
            view.setPadding(baseline.paddingLeft, baseline.paddingTop, baseline.paddingRight, baseline.paddingBottom)
        }
        if (mask and MASK_VISIBILITY != 0) {
            view.visibility = baseline.visibility
        }
        if (view is TextView) {
            if (mask and MASK_TEXT != 0) {
                view.text = baseline.text ?: ""
                view.maxLines = baseline.maxLines
            }
            if (mask and MASK_HINT != 0) {
                view.hint = baseline.hint ?: ""
            }
        }
        if (view is ImageView && mask and MASK_IMAGE != 0) {
            baseline.scaleType?.let { name ->
                runCatching { ImageView.ScaleType.valueOf(name) }
                    .getOrNull()
                    ?.let { view.scaleType = it }
            }
        }
    }

    fun forgetAppliedImage(rule: ViewRule) {
        appliedImages.remove(rule.key())
    }

    fun clearAppliedImages() {
        appliedImages.clear()
    }
}
