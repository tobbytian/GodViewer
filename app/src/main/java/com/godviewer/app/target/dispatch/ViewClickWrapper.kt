package com.godviewer.app.target.dispatch

import android.view.View
import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.edit.EditMode
import com.godviewer.app.target.edit.ViewHitUtil

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
        // 模块自有 UI（含系统 Alert 取消按钮等）：永远执行原逻辑，绝不进入属性编辑
        if (ViewHitUtil.isGodViewerUi(v) || ViewHitUtil.isGodViewerUi(view)) {
            GvLog.d(TAG, "click skip module ui ${v.javaClass.simpleName}")
            performOriginClick()
            return
        }
        GvLog.d(TAG, "click -> ${v.javaClass.name}")
        val dispatched = ViewDispatcher.dispatch(v)
        if (!dispatched) {
            GvLog.w(TAG, "dispatch false, origin click ${v.javaClass.simpleName}")
            performOriginClick()
        }
    }

    fun performOriginClick() {
        originListener?.onClick(view)
    }

    companion object {
        private const val TAG = "Click"
    }
}
