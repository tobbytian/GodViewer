package com.godviewer.app.ui.host

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Dispatches touches only to the first child (page content), so later overlay
 * children (e.g. watermark) can draw on top without intercepting clicks.
 */
class ContentFirstFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val content = getChildAt(0) ?: return super.dispatchTouchEvent(ev)
        return content.dispatchTouchEvent(ev)
    }
}
