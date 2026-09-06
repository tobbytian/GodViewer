package com.godviewer.app.target.handler.imageview

import android.view.View
import android.widget.ImageView
import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.handler.ViewDispatchHandler
import com.godviewer.app.target.ui.QuickAttrDialog

/**
 * @author hhvvg
 *
 * Handling ImageView.
 */
class ImageViewDispatchHandler : ViewDispatchHandler {
    override fun support(view: View): Boolean {
        return view is ImageView
    }

    override fun handle(view: View) {
        val imageView = view as ImageView
        GvLog.d(TAG, "open QuickAttr ImageView ${imageView.javaClass.name}")
        runCatching {
            QuickAttrDialog(imageView) { ImageViewAttrDialog(imageView) }.show()
        }.onFailure {
            GvLog.e(TAG, "ImageViewDispatch failed ${imageView.javaClass.name}", it)
        }
    }

    companion object {
        private const val TAG = "UI"
    }
}
