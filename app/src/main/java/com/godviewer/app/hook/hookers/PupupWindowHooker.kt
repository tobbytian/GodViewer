package com.godviewer.app.hook.hookers

import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupWindow
import com.godviewer.app.hook.IHooker
import com.godviewer.app.util.EditMode
import com.godviewer.app.util.setGlobalHookClick
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * @author hhvvg
 *
 * Hooks PupupWindow.
 */
class PupupWindowHooker : IHooker {
    override fun onHook(param: XC_LoadPackage.LoadPackageParam) {
        val popupMethodHook = PopupWindowInvokePopupMethodHook()
        val popupMethod = XposedHelpers.findMethodBestMatch(
            PopupWindow::class.java,
            "invokePopup",
            WindowManager.LayoutParams::class.java
        )
        XposedBridge.hookMethod(popupMethod, popupMethodHook)
    }

    private class PopupWindowInvokePopupMethodHook : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam?) {
            if (param == null) {
                return
            }
            val decorView =
                XposedHelpers.getObjectField(param.thisObject, "mDecorView") as ViewGroup
            // Popup is a separate window: still wrap clicks so edit mode can reach menu items.
            val force = EditMode.isEnabled()
            decorView.setGlobalHookClick(
                enabled = true,
                traversalChildren = true,
                forceClickable = force,
            )
        }
    }
}