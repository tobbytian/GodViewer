package com.godviewer.app.handler.imageview

import android.view.View
import android.widget.ImageView
import com.godviewer.app.handler.ViewDispatchHandler

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
        val dialog = ImageViewAttrDialog(view as ImageView)
        dialog.show()
    }
}
