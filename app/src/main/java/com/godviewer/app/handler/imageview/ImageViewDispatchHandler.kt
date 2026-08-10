package com.godviewer.app.handler.imageview

import android.view.View
import android.widget.ImageView
import com.godviewer.app.handler.ViewDispatchHandler
import com.godviewer.app.ui.QuickAttrDialog

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
        val dialog = QuickAttrDialog(imageView) { ImageViewAttrDialog(imageView) }
        dialog.show()
    }
}
