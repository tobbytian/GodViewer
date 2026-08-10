package com.godviewer.app

import android.view.View
import com.godviewer.app.handler.DefaultViewDispatchHandler
import com.godviewer.app.handler.ViewDispatchHandler
import com.godviewer.app.handler.imageview.ImageViewDispatchHandler
import com.godviewer.app.handler.textview.TextViewDispatchHandler
import com.godviewer.app.util.getOnClickListener
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
        var handled = false
        for (handler in handlers) {
            if (handler.support(view)) {
                handler.handle(view)
                handled = true
                break
            }
        }
        return handled
    }

    companion object {
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
            val instance = getInstance()
            return instance.dispatchInner(view)
        }
    }
}