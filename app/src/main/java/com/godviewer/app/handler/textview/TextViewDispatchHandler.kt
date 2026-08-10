package com.godviewer.app.handler.textview

import android.view.View
import android.widget.TextView
import com.godviewer.app.handler.ViewDispatchHandler
import com.godviewer.app.ui.QuickAttrDialog

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
        val dialog = QuickAttrDialog(textView) { TextEditingDialog(textView) }
        dialog.show()
    }
}