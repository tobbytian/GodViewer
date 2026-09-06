package com.godviewer.app.target.rule

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.godviewer.app.shared.model.ViewAttrSnapshot
import com.godviewer.app.shared.model.ViewRule
import com.godviewer.app.target.glide.GlideApp

/**
 * 把 [ViewRule] 的 original/modified 快照应用到具体 View（重放 / 重置）。
 *
 * 不含持久化；[appliedImages] 仅防止布局回调里对同一 URL 反复 Glide。
 * 第二阶段迁包时预期落入 `target`。
 */
internal object ViewRuleApplier {

    /** 已应用图片的规则键 -> URL */
    private val appliedImages = HashMap<ViewRule.RuleKey, String>()

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
            if (rule.changedImage && view is ImageView) {
                val key = rule.key()
                if (appliedImages[key] != s.imageUrl) {
                    appliedImages[key] = s.imageUrl ?: ""
                    if (!s.imageUrl.isNullOrEmpty()) {
                        // 部分目标进程 Glide/Context 在 onResume 早期不可用；失败只跳过图片
                        runCatching { GlideApp.with(view).load(s.imageUrl).into(view) }
                    }
                    s.scaleType?.let { name ->
                        runCatching { ImageView.ScaleType.valueOf(name) }
                            .getOrNull()
                            ?.let { view.scaleType = it }
                    }
                }
            }
            changed
        }.getOrDefault(false)
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
            maxLines = (view as? TextView)?.maxLines ?: Int.MAX_VALUE,
            imageUrl = null,
            scaleType = (view as? ImageView)?.scaleType?.name
        )
    }

    fun forgetAppliedImage(rule: ViewRule) {
        appliedImages.remove(rule.key())
    }

    fun clearAppliedImages() {
        appliedImages.clear()
    }
}
