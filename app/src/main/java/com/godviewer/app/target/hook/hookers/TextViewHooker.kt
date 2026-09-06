package com.godviewer.app.target.hook.hookers

import android.view.View
import com.godviewer.app.IGNORE_HOOK
import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.dispatch.ViewClickWrapper
import com.godviewer.app.target.edit.EditMode
import com.godviewer.app.target.edit.ViewHitUtil
import com.godviewer.app.target.hook.GvHook
import com.godviewer.app.target.hook.GvMethodHook
import com.godviewer.app.target.hook.IHooker
import com.godviewer.app.target.hook.MethodHookParam
import com.godviewer.app.target.util.replaceOnClickListener

/**
 * 延迟安装的 setOnClickListener hook。
 *
 * 仅勾选 LSPosed 作用域、未开编辑时**不** hook，避免无意义拦截应用自己的点击注册。
 * 首次开启编辑模式时由 [installIfNeeded] 安装。
 */
object ClickListenerHook {
    @Volatile
    private var installed = false

    private const val TAG = "ClickHook"

    fun installIfNeeded() {
        if (installed) {
            GvLog.d(TAG, "setOnClickListener already hooked")
            return
        }
        val handle = GvHook.findAndHookMethod(
            View::class.java,
            "setOnClickListener",
            object : GvMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!EditMode.isEnabled()) return
                    val view = param.thisObject as? View ?: return
                    // 模块弹窗整树（含系统 Alert 取消）不包装
                    if (view.tag == IGNORE_HOOK) return
                    if (ViewHitUtil.isGodViewerUi(view)) return
                    runCatching {
                        view.replaceOnClickListener { origin ->
                            if (origin is ViewClickWrapper) origin
                            else ViewClickWrapper(origin, view.isClickable, view)
                        }
                    }
                }
            },
            View.OnClickListener::class.java,
        )
        if (handle != null) {
            installed = true
            GvLog.i(TAG, "setOnClickListener hooked (wrap only while edit on)")
        }
    }
}

/**
 * @author hhvvg
 *
 * 保留 IHooker 入口以兼容注册表；实际 hook 延迟到编辑模式开启。
 */
class TextViewHooker : IHooker {
    override fun onHook() {
        // 不在 package ready 时安装，见 ClickListenerHook.installIfNeeded()
    }
}
