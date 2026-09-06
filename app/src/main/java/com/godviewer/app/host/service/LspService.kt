package com.godviewer.app.host.service

import android.os.Build
import com.godviewer.app.shared.GvLog
import com.godviewer.app.util.ModuleStatus
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 通过 libxposed/service 直读 LSPosed 的模块启用状态。
 *
 * 库内置的 [io.github.libxposed.service.XposedProvider] 负责接收 LSPosed 管理器下发的
 * service binder —— 管理器**只会对已启用模块**下发，绑定成功即「已激活」，不再要求把
 * GodViewer 自己勾进作用域。绑定回调可能运行在 Binder 线程，UI 侧自行切主线程。
 */
object LspService {
    private const val TAG = "LspService"

    @Volatile
    private var registered = false

    fun register() {
        if (registered) return
        // service 库 minSdk 26（Android 8.0）；能跑 LSPosed 的设备必然满足
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        registered = true
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    runCatching {
                        val scope = runCatching { service.scope }.getOrNull().orEmpty()
                        GvLog.i(
                            TAG,
                            "service bound api=${service.apiVersion} " +
                                "framework=${service.frameworkName}/${service.frameworkVersion} " +
                                "scope=${scope.joinToString(",")}",
                        )
                        ModuleStatus.setServiceEnabled(true, scope.size)
                    }.onFailure { GvLog.e(TAG, "onServiceBind handle failed", it) }
                }

                override fun onServiceDied(service: XposedService) {
                    GvLog.w(TAG, "service died")
                }
            })
        }.onFailure { GvLog.e(TAG, "register listener failed", it) }
    }
}
