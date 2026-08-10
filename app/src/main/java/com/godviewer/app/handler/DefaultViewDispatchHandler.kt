package com.godviewer.app.handler

import android.view.View
import com.godviewer.app.ui.DefaultAttrDialog
import com.godviewer.app.ui.QuickAttrDialog

class DefaultViewDispatchHandler : ViewDispatchHandler {
    override fun support(view: View): Boolean {
        return true
    }

    override fun handle(view: View) {
        val dialog = QuickAttrDialog(view) { DefaultAttrDialog(view) }
        dialog.show()
    }
}