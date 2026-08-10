package com.godviewer.app

import android.view.View
import com.godviewer.app.util.EditMode

const val IGNORE_HOOK = "GODVIEWER_IGNORE_HOOK"

/**
 * @author hhvvg
 */
class ViewClickWrapper(
    val originListener: View.OnClickListener?,
    val originClickable: Boolean,
    private val view: View
): View.OnClickListener {

    override fun onClick(v: View?) {
        if (v == null) {
            return
        }
        // 编辑模式关闭时透传原始点击，不弹编辑弹窗（无感使用目标应用）
        if (!EditMode.isEnabled()) {
            performOriginClick()
            return
        }
        val dispatched = ViewDispatcher.dispatch(v)
        if (!dispatched) {
            performOriginClick()
        }
    }

    fun performOriginClick() {
        originListener?.onClick(view)
    }
}