package com.godviewer.app.target.dispatch

import android.view.View
import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.edit.SelectedViewHighlight
import com.godviewer.app.target.edit.ViewHitUtil
import com.godviewer.app.target.handler.DefaultViewDispatchHandler
import com.godviewer.app.target.handler.ViewDispatchHandler
import com.godviewer.app.target.handler.imageview.ImageViewDispatchHandler
import com.godviewer.app.target.handler.textview.TextViewDispatchHandler
import kotlin.reflect.KClass

/**
 * @author hhvvg
 *
 * Dispatches views to certain handler.
 */
class ViewDispatcher private constructor() {
    private val handlers = ArrayList<ViewDispatchHandler>()

    init {
        val registries = sRegistryHandler
        for (reg in registries) {
            val instance = reg.java.newInstance() as ViewDispatchHandler
            handlers.add(instance)
        }
    }

    private fun dispatchInner(view: View): Boolean {
        for (handler in handlers) {
            if (!handler.support(view)) continue
            val name = handler.javaClass.simpleName
            GvLog.d(TAG, "handler=$name view=${view.javaClass.name}")
            return runCatching {
                handler.handle(view)
                true
            }.onFailure {
                GvLog.e(TAG, "handler failed name=$name view=${view.javaClass.name}", it)
            }.getOrDefault(false)
        }
        GvLog.w(TAG, "no handler view=${view.javaClass.name}")
        return false
    }

    companion object {
        private const val TAG = "Dispatch"

        @JvmStatic
        private val sRegistryHandler = arrayOf<KClass<*>>(
            TextViewDispatchHandler::class,
            ImageViewDispatchHandler::class,
            DefaultViewDispatchHandler::class
        )

        @JvmStatic
        private val sInstance: ViewDispatcher by lazy(mode = LazyThreadSafetyMode.SYNCHRONIZED) { ViewDispatcher() }

        @JvmStatic
        fun getInstance(): ViewDispatcher = sInstance

        @JvmStatic
        fun dispatch(view: View): Boolean {
            // 模块自有 UI（系统 Alert 取消等）绝不进入属性编辑
            if (ViewHitUtil.isGodViewerUi(view)) {
                GvLog.d(TAG, "skip module ui ${view.javaClass.simpleName}")
                return false
            }
            GvLog.d(
                TAG,
                "select ${view.javaClass.name} ${view.width}x${view.height} " +
                    "attached=${view.isAttachedToWindow}",
            )
            // 选中即蓝框高亮（含父/子重新分发）；弹窗打开期间保持
            val hl = runCatching {
                SelectedViewHighlight.show(view)
                true
            }.onFailure {
                GvLog.e(TAG, "highlight failed view=${view.javaClass.name}", it)
            }.getOrDefault(false)
            if (!hl) {
                GvLog.w(TAG, "highlight not shown, still try dialog")
            }
            val instance = getInstance()
            val handled = instance.dispatchInner(view)
            GvLog.d(TAG, "done handled=$handled highlight=$hl view=${view.javaClass.simpleName}")
            return handled
        }
    }
}
