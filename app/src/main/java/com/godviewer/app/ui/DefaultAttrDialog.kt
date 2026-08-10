package com.godviewer.app.ui

import android.view.View
import com.godviewer.app.data.BaseViewAttrData

class DefaultAttrDialog(view: View) : BaseAttrDialog<BaseViewAttrData>(view) {
    override val attrData: BaseViewAttrData
        get() = baseAttrData
}