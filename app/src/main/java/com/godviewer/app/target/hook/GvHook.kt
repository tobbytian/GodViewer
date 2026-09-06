package com.godviewer.app.target.hook

import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.util.findMethodBestMatch
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * 旧 `XC_MethodHook` → libxposed API 102 `Chain` 模型的项目内迁移层（**临时兼容方案**）。
 *
 * 保留 before/after + `param.args` / `param.setResult` 语义，让 Hooker 逻辑零改动；
 * 待在设备上稳定后，可逐步下沉为原生 `hook(method).intercept { chain -> ... }`。
 *
 * 安装失败不抛出（目标 App 不能被模块拖垮）；Hook 体异常由框架按
 * module.prop `exceptionMode=protective` 吞掉并记录。
 */
object GvHook {
    private const val TAG = "GvHook"

    /** 框架句柄，由 [GodViewerModule.onModuleLoaded] 注入。 */
    @Volatile
    internal var base: XposedInterface? = null

    fun hookMethod(method: Executable, callback: GvMethodHook): XposedInterface.HookHandle? {
        val xb = base ?: run {
            GvLog.e(TAG, "hook skipped (framework not attached): ${method.declaringClass.name}#${method.name}")
            return null
        }
        return runCatching {
            xb.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain -> dispatch(chain, callback) }
        }.onFailure {
            GvLog.e(TAG, "hook install failed: ${method.declaringClass.name}#${method.name}", it)
        }.getOrNull()
    }

    fun findAndHookMethod(
        clazz: Class<*>,
        methodName: String,
        callback: GvMethodHook,
        vararg parameterTypes: Class<*>,
    ): XposedInterface.HookHandle? {
        val method = runCatching { findMethodBestMatch(clazz, methodName, *parameterTypes) }
            .onFailure { GvLog.e(TAG, "method not found: ${clazz.name}#$methodName", it) }
            .getOrNull() ?: return null
        return hookMethod(method, callback)
    }

    private fun dispatch(chain: XposedInterface.Chain, callback: GvMethodHook): Any? {
        val param = MethodHookParam(chain)
        callback.beforeHookedMethod(param)
        if (!param.earlyReturn) {
            try {
                param.value = chain.proceed(param.args)
            } catch (t: Throwable) {
                param.throwable = t
                callback.afterHookedMethod(param)
                if (param.earlyReturn) return param.value
                throw t
            }
        }
        callback.afterHookedMethod(param)
        return param.value
    }
}

/** 与旧 XC_MethodHook 对齐的回调基类。 */
abstract class GvMethodHook {
    open fun beforeHookedMethod(param: MethodHookParam) {}

    open fun afterHookedMethod(param: MethodHookParam) {}
}

/** 与旧 XC_MethodHook.MethodHookParam 对齐的最小参数对象。 */
class MethodHookParam internal constructor(chain: XposedInterface.Chain) {
    val thisObject: Any? = chain.thisObject
    val args: Array<Any?> = chain.args.toTypedArray<Any?>()

    /** 原方法抛出的异常（after 阶段可读）。 */
    var throwable: Throwable? = null
        internal set

    internal var value: Any? = null
    internal var earlyReturn = false

    /** before 阶段调用 = 跳过原方法直接返回；after 阶段调用 = 替换返回值。 */
    fun setResult(value: Any?) {
        this.value = value
        earlyReturn = true
    }
}
