package com.godviewer.app.target.hook

import android.os.Build
import androidx.annotation.RequiresApi
import com.godviewer.app.BuildConfig
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.AndroidAppCompat
import com.godviewer.app.shared.diag.DiagBuffer
import com.godviewer.app.target.diag.TargetCrashProbe
import com.godviewer.app.target.hook.hookers.ActivityLifecycleHooker
import com.godviewer.app.target.hook.hookers.ApplicationHooker
import com.godviewer.app.target.hook.hookers.PupupWindowHooker
import com.godviewer.app.target.hook.hookers.TextViewHooker
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * libxposed API 102 模块入口（`META-INF/xposed/java_init.list`，无参构造由框架反射创建）。
 *
 * 职责：注入框架日志 sink、创建模块资源、按包路由；
 * 具体 Hook 见 `hookers/`（编辑模式相关 Hook 仍是懒安装）。
 *
 * 自身进程：仅装激活探针（`ModuleStatus.isActivated` → true）；
 * 目标进程：Framework 类 Hook 每进程装一次（旧 handleLoadPackage 每包触发会重复装，这里收紧）。
 */
class GodViewerModule : XposedModule() {

    private val hookers = listOf(
        ApplicationHooker(),
        TextViewHooker(),
        PupupWindowHooker(),
        ActivityLifecycleHooker(),
    )

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        GvHook.base = this
        GvLog.frameworkSink = GvLog.FrameworkSink { priority, tag, msg, tr ->
            log(priority, tag, msg, tr)
        }
        runCatching { ModuleRes.ensureCreated(getModuleApplicationInfo()) }
            .onFailure { GvLog.e(TAG, "module res init failed", it) }
        GvLog.i(
            TAG,
            "event=module_loaded process=${param.processName} api=${getApiVersion()} " +
                "framework=${getFrameworkName()}/${getFrameworkVersion()} " +
                "vc=${BuildConfig.VERSION_CODE} vn=${BuildConfig.VERSION_NAME}",
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // 旧 handleLoadPackage 的等价时机：部分实现下自身进程只回调这里，探测装一次即可
        if (param.packageName == BuildConfig.PACKAGE_NAME) {
            installActivationProbe(param.getDefaultClassLoader())
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val packageName = param.packageName
        val processName = AndroidAppCompat.currentProcessName()
        DiagBuffer.bindProcess(packageName, processName)

        // 仅模块自身进程：只装激活探针，用 app ClassLoader + 类名字符串（ModuleStatus 是
        // 不同 ClassLoader 下的另一个 Class）。不装业务 hook。
        if (packageName == BuildConfig.PACKAGE_NAME) {
            installActivationProbe(param.classLoader)
            return
        }

        // Framework 类（Application/Activity/View/PopupWindow）Hook 每进程一次即可；
        // 进程内后续加载的其它包不再重复装。
        if (!param.isFirstPackage) {
            GvLog.i(TAG, "event=package_ready_skip package=$packageName reason=not_first_package")
            return
        }

        GvLog.i(
            TAG,
            "event=package_ready package=$packageName process=$processName " +
                "moduleRes=${ModuleRes.isModuleResReady()} " +
                "vc=${BuildConfig.VERSION_CODE} vn=${BuildConfig.VERSION_NAME}",
        )
        // 早期闪退探针：Application.onCreate 之前死掉也能在 LSPosed 日志留痕
        runCatching { TargetCrashProbe.installEarlyHandler() }
            .onFailure { GvLog.e(TAG, "installEarlyHandler failed", it) }
        for (hooker in hookers) {
            runCatching { hooker.onHook() }
                .onFailure { GvLog.e(TAG, "hooker failed: ${hooker.javaClass.simpleName}", it) }
        }
        GvLog.i(TAG, "event=package_ready_done package=$packageName (idle: no touch hooks until edit on)")
    }

    private val probeInstalled = AtomicBoolean(false)

    /**
     * 自身进程激活探测，双保险：
     * 1) 经 app ClassLoader 反射调 `ModuleStatus.markInjectedActivated()` —— 不依赖 hook 是否生效；
     * 2) hook `isActivated`（static @JvmStatic + 实例方法全部替换）。
     * onPackageLoaded / onPackageReady 谁先来谁装，失败后允许下个时机重试。
     */
    private fun installActivationProbe(classLoader: ClassLoader) {
        if (!probeInstalled.compareAndSet(false, true)) {
            return
        }
        runCatching {
            val clazz = classLoader.loadClass("com.godviewer.app.util.ModuleStatus")
            GvLog.i(TAG, "activation probe cl=${classLoader.javaClass.name} cls=${clazz.name}")
            runCatching {
                clazz.getDeclaredMethod("markInjectedActivated")
                    .apply { isAccessible = true }
                    .invoke(null)
            }.onFailure { GvLog.e(TAG, "markInjectedActivated failed", it) }
            var hooked = 0
            var c: Class<*>? = clazz
            while (c != null) {
                for (method in c.declaredMethods) {
                    // Kotlin object 可能同时暴露 static(@JvmStatic) 与实例 isActivated()，全部替换
                    if (method.name != "isActivated") continue
                    method.isAccessible = true
                    val handle = hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept { true }
                    if (handle != null) hooked++
                }
                c = c.superclass
            }
            GvLog.i(TAG, "host activation probe installed methods=$hooked")
        }.onFailure {
            probeInstalled.set(false)
            GvLog.e(TAG, "host activation probe failed", it)
        }
    }

    companion object {
        private const val TAG = "Hook"
    }
}
