package com.godviewer.app.host.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 承载 Tab 内容的容器：检测左右滑动手势并通过 [onSwipe] 回调切页。
 * 只有水平位移明显大于垂直位移时才拦截事件，页面内垂直滚动不受影响；
 * 一旦垂直滚动先接管，本次手势不再劫持。
 * 滑过阈值的瞬间即触发（不等抬手），一次手势只触发一次。
 */
class SwipeSwitchFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** true = 左滑（下一页），false = 右滑（上一页） */
    var onSwipe: ((Boolean) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var horizontal = false
    private var scrollClaimed = false
    private var fired = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                horizontal = false
                scrollClaimed = false
                fired = false
            }
            MotionEvent.ACTION_MOVE -> if (!scrollClaimed && !horizontal) {
                val dx = ev.x - downX
                val dy = ev.y - downY
                when {
                    abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f -> horizontal = true
                    abs(dy) > touchSlop -> scrollClaimed = true
                }
            }
        }
        return horizontal
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> maybeFire(ev)
            MotionEvent.ACTION_UP -> {
                maybeFire(ev)
                horizontal = false
                fired = false
            }
            MotionEvent.ACTION_CANCEL -> {
                horizontal = false
                fired = false
            }
        }
        return true
    }

    private fun maybeFire(ev: MotionEvent) {
        if (fired || !horizontal || width == 0) return
        // 滑过约 1/7 屏宽即刻切换（调小提高灵敏度，仍留误触余量）
        val dx = ev.x - downX
        if (abs(dx) > width / 7f) {
            fired = true
            onSwipe?.invoke(dx < 0)
        }
    }
}