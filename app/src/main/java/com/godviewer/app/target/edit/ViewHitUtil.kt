package com.godviewer.app.target.edit

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.godviewer.app.IGNORE_HOOK
import com.godviewer.app.target.util.getObjectField

/**
 * 编辑模式下的命中辅助：跳过淘宝 WaterMask 等全屏穿透遮罩，
 * 优先选中下方真正可交互 / 有内容的 View。
 */
object ViewHitUtil {

    /**
     * 从 [root] 起找手指下最合适的编辑目标（非模块 UI、非穿透遮罩）。
     */
    fun findEditTarget(root: View, rawX: Float, rawY: Float): View? {
        return findBest(root, rawX, rawY)?.takeUnless { isGodViewerUi(it) }
    }

    /**
     * 是否应视为「点穿」遮罩：不参与编辑选中，也不应被 forceClickable。
     * 典型：淘宝 `com.taobao.tbpoplayer.watermask.WaterMaskView`。
     */
    fun isPassThroughOverlay(view: View): Boolean {
        if (view.visibility != View.VISIBLE) return true
        if (view.alpha <= 0.01f) return true
        if (isGodViewerUi(view)) return false

        val cn = view.javaClass.name
        val simple = view.javaClass.simpleName
        val lower = cn.lowercase()
        if (WATERMARK_NAME.containsMatchIn(lower) ||
            WATERMARK_SIMPLE.containsMatchIn(simple.lowercase())
        ) {
            return true
        }

        // 大面积、无交互、无内容的覆盖层（常见水印/防截图层）
        if (coversMostOfWindow(view) && !looksInteractive(view) && !hasVisibleContent(view)) {
            return true
        }
        return false
    }

    private fun findBest(root: View, rawX: Float, rawY: Float): View? {
        if (!pointInView(root, rawX, rawY)) return null
        if (root.visibility != View.VISIBLE || root.alpha <= 0.01f) return null

        if (root is ViewGroup) {
            // 前到后：先子 View
            var bestChild: View? = null
            var bestScore = Int.MIN_VALUE
            for (i in root.childCount - 1 downTo 0) {
                val child = root.getChildAt(i)
                val hit = findBest(child, rawX, rawY) ?: continue
                val score = score(hit)
                if (score > bestScore) {
                    bestScore = score
                    bestChild = hit
                }
            }
            if (bestChild != null) return bestChild
        }

        // 自身是穿透遮罩：不当目标（子 View 已在上面处理）
        if (isPassThroughOverlay(root)) return null
        return root
    }

    /**
     * 分数越高越像用户想点的控件。穿透遮罩应极低分。
     */
    private fun score(view: View): Int {
        if (isPassThroughOverlay(view)) return -1000
        var s = 0
        if (view.isClickable || view.isLongClickable) s += 40
        if (hasClickListener(view)) s += 30
        if (view is TextView && !view.text.isNullOrBlank()) s += 50
        if (view is ImageView && view.drawable != null) s += 35
        if (view is ViewGroup) s -= 5
        // 面积过大略降权（全屏容器），过小也降权
        val area = view.width.toLong() * view.height.toLong()
        if (area > 800_000L) s -= 15
        if (area in 1..400L) s -= 10
        // 叶子略优先
        if (view !is ViewGroup || view.childCount == 0) s += 8
        return s
    }

    private fun looksInteractive(view: View): Boolean {
        if (view.isClickable || view.isLongClickable || view.isFocusable) return true
        if (hasClickListener(view)) return true
        if (view is TextView || view is ImageView) return true
        return false
    }

    private fun hasVisibleContent(view: View): Boolean {
        if (view is TextView && !view.text.isNullOrBlank()) return true
        if (view is ImageView && view.drawable != null) return true
        if (view.background != null) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val c = view.getChildAt(i)
                if (c.visibility == View.VISIBLE && c.alpha > 0.01f) return true
            }
        }
        return false
    }

    private fun hasClickListener(view: View): Boolean {
        val info = runCatching { getObjectField(view, "mListenerInfo") }.getOrNull()
            ?: return false
        val click = runCatching {
            getObjectField(info, "mOnClickListener")
        }.getOrNull()
        return click != null
    }

    private fun coversMostOfWindow(view: View): Boolean {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return false
        val dm = view.resources.displayMetrics
        val sw = dm.widthPixels.coerceAtLeast(1)
        val sh = dm.heightPixels.coerceAtLeast(1)
        // 覆盖屏幕大部分区域
        return w * h >= (sw * sh * 0.45f)
    }

    fun isGodViewerUi(view: View): Boolean {
        var cur: View? = view
        while (cur != null) {
            if (cur.tag == com.godviewer.app.IGNORE_HOOK) return true
            cur = cur.parent as? View
        }
        return false
    }

    fun pointInView(view: View, rawX: Float, rawY: Float): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val left = loc[0].toFloat()
        val top = loc[1].toFloat()
        return rawX >= left && rawX < left + view.width &&
            rawY >= top && rawY < top + view.height
    }

    private val WATERMARK_NAME = Regex(
        "watermask|watermark|water_mask|water_mark|securitymask|securemask|antimask|floatmask",
    )
    private val WATERMARK_SIMPLE = Regex(
        "watermask|watermark|maskview|masklayer|securitylayer",
    )
}
