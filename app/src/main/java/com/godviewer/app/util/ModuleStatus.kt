package com.godviewer.app.util

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Host-side activation probe.
 *
 * 三个信号，任一为真即「已激活」（[check]）：
 * 1. [serviceEnabled]：libxposed/service 绑定成功 —— LSPosed 只对**已启用**模块下发
 *    manager binder，宿主 App 直读，**不要求把本模块勾进作用域**（主信号，host 进程）；
 * 2. [injectedActivated]：入口把模块注入自身进程时经 app ClassLoader 反射置位
 *    （不依赖 hook 是否生效，后备信号）；
 * 3. hook `isActivated` → true（后备信号；入口类在 ModuleClassLoader，必须用
 *    app ClassLoader + 类名字符串，两个 Class 对象不同）。
 *
 * Call sites must use [check] (reflection) so R8 cannot inline the false constant.
 */
object ModuleStatus {
    private const val CLASS_NAME = "com.godviewer.app.util.ModuleStatus"
    private const val METHOD_NAME = "isActivated"

    /** 由注入入口经 app ClassLoader 反射置位（hook 之外的双保险，见类注释）。 */
    @Volatile
    private var injectedActivated = false

    /** libxposed/service 绑定状态（仅 host 进程会置位）。 */
    @Volatile
    private var serviceEnabled = false

    @Volatile
    private var serviceScopeCount = 0

    private val activatedListeners = CopyOnWriteArrayList<() -> Unit>()

    fun isServiceEnabled(): Boolean = serviceEnabled

    fun serviceScopeCount(): Int = serviceScopeCount

    /** host 进程：LspService 绑定成功后调用；从 false 变 true 时通知 UI 刷新。 */
    fun setServiceEnabled(enabled: Boolean, scopeCount: Int = 0) {
        val changed = serviceEnabled != enabled
        serviceEnabled = enabled
        serviceScopeCount = scopeCount
        if (changed && enabled) {
            for (listener in activatedListeners) {
                runCatching { listener.invoke() }
            }
        }
    }

    /** 激活状态变化监听（回调可能来自 Binder 线程，UI 侧自行切主线程）。 */
    fun addActivatedListener(listener: () -> Unit) {
        activatedListeners.add(listener)
    }

    fun removeActivatedListener(listener: () -> Unit) {
        activatedListeners.remove(listener)
    }

    /**
     * 注入入口回调：LSPosed 已把模块装进自身进程（反射调用，勿直接调用）。
     */
    @JvmStatic
    fun markInjectedActivated() {
        injectedActivated = true
    }

    /**
     * Target of Xposed hook. Do not call this directly from UI code.
     */
    @JvmStatic
    fun isActivated(): Boolean = injectedActivated

    /**
     * Safe activation check for host UI. Uses reflection so the result always goes
     * through the (possibly hooked) method body.
     */
    @JvmStatic
    fun check(): Boolean {
        if (serviceEnabled || injectedActivated) return true
        return runCatching {
            val clazz = Class.forName(CLASS_NAME)
            val method = clazz.getDeclaredMethod(METHOD_NAME)
            method.isAccessible = true
            method.invoke(null) as Boolean
        }.getOrDefault(false)
    }
}
