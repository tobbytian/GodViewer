package com.godviewer.app.target.handler.textview

import android.view.View
import android.widget.TextView
import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.handler.ViewDispatchHandler
import com.godviewer.app.target.ui.QuickAttrDialog

/**
 * @author hhvvg
 *
 * Handles TextView
 */
class TextViewDispatchHandler : ViewDispatchHandler {
    override fun support(view: View): Boolean {
        return view is TextView
    }

    override fun handle(view: View) {
        val textView = view as TextView
        GvLog.d(TAG, "open QuickAttr TextView ${textView.javaClass.name}")
        runCatching {
            QuickAttrDialog(textView) { TextEditingDialog(textView) }.show()
        }.onFailure {
            GvLog.e(TAG, "TextViewDispatch failed ${textView.javaClass.name}", it)
        }
    }

    companion object {
        private const val TAG = "UI"
    }
}
