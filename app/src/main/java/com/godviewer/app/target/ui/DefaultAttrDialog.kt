package com.godviewer.app.target.ui

import android.view.View
import com.godviewer.app.shared.model.BaseViewAttrData

class DefaultAttrDialog(view: View) : BaseAttrDialog<BaseViewAttrData>(view) {
    override val attrData: BaseViewAttrData
        get() = baseAttrData
}
