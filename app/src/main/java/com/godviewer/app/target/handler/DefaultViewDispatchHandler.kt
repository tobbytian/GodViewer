package com.godviewer.app.target.handler

import android.view.View
import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.ui.DefaultAttrDialog
import com.godviewer.app.target.ui.QuickAttrDialog

class DefaultViewDispatchHandler : ViewDispatchHandler {
    override fun support(view: View): Boolean {
        return true
    }

    override fun handle(view: View) {
        GvLog.d(TAG, "open QuickAttr default ${view.javaClass.name}")
        runCatching {
            QuickAttrDialog(view) { DefaultAttrDialog(view) }.show()
        }.onFailure {
            GvLog.e(TAG, "DefaultViewDispatch failed ${view.javaClass.name}", it)
        }
    }

    companion object {
        private const val TAG = "UI"
    }
}
