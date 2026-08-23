package com.godviewer.app.util

/**
 * Host-side activation probe.
 *
 * Default is false. When LSPosed loads this module into the host process, it hooks
 * [isActivated] via the **app** ClassLoader and forces true.
 *
 * Call sites must use [check] (reflection) so R8 cannot inline the false constant.
 */
object ModuleStatus {
    private const val CLASS_NAME = "com.godviewer.app.util.ModuleStatus"
    private const val METHOD_NAME = "isActivated"

    /**
     * Target of Xposed hook. Do not call this directly from UI code.
     */
    @JvmStatic
    fun isActivated(): Boolean = false

    /**
     * Safe activation check for host UI. Uses reflection so the result always goes
     * through the (possibly hooked) method body.
     */
    @JvmStatic
    fun check(): Boolean {
        return runCatching {
            val clazz = Class.forName(CLASS_NAME)
            val method = clazz.getDeclaredMethod(METHOD_NAME)
            method.isAccessible = true
            method.invoke(null) as Boolean
        }.getOrDefault(false)
    }
}
