package com.godviewer.app.target.hook.hookers

import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupWindow
import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.edit.EditMode
import com.godviewer.app.target.hook.GvHook
import com.godviewer.app.target.hook.GvMethodHook
import com.godviewer.app.target.hook.IHooker
import com.godviewer.app.target.hook.MethodHookParam
import com.godviewer.app.target.util.getObjectField
import com.godviewer.app.target.util.setGlobalHookClick

/**
 * 延迟安装的 PopupWindow hook：未开编辑不拦截 invokePopup。
 */
object PopupWindowClickHook {
    @Volatile
    private var installed = false

    private const val TAG = "PopupHook"

    fun installIfNeeded() {
        if (installed) {
            GvLog.d(TAG, "PopupWindow.invokePopup already hooked")
            return
        }
        val handle = GvHook.findAndHookMethod(
            PopupWindow::class.java,
            "invokePopup",
            object : GvMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!EditMode.isEnabled()) return
                    try {
                        val decorView =
                            getObjectField(param.thisObject, "mDecorView") as? ViewGroup
                                ?: return
                        decorView.setGlobalHookClick(
                            enabled = true,
                            traversalChildren = true,
                            forceClickable = true,
                        )
                    } catch (_: Throwable) {
                        // 单次 Popup 包装失败不影响弹出
                    }
                }
            },
            WindowManager.LayoutParams::class.java,
        )
        if (handle != null) {
            installed = true
            GvLog.i(TAG, "PopupWindow.invokePopup hooked (wrap only while edit on)")
        }
    }
}

/**
 * @author hhvvg
 *
 * Hooks PupupWindow（类名拼写保持历史兼容）。
 */
class PupupWindowHooker : IHooker {
    override fun onHook() {
        // 延迟安装，见 PopupWindowClickHook.installIfNeeded()
    }
}
