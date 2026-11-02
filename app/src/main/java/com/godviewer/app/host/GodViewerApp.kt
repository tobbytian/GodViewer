package com.godviewer.app.host

import android.app.Application
import android.content.Context
import com.godviewer.app.BuildConfig
import com.godviewer.app.host.entry.EntryControlUi
import com.godviewer.app.host.prefs.HostPrefs
import com.godviewer.app.host.service.LspService
import com.godviewer.app.shared.AppLanguage
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.diag.DiagBuffer
import com.godviewer.app.shared.entry.EntryMode

class GodViewerApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        DiagBuffer.bindProcess(packageName, packageName)
        GvLog.i(
            "Host",
            "GodViewerApp.onCreate vc=${BuildConfig.VERSION_CODE} vn=${BuildConfig.VERSION_NAME} " +
                "entry=${EntryMode.current(this)} hostNotify=${HostPrefs.isHostNotifyEnabled(this)} " +
                "debug=${BuildConfig.DEBUG}",
        )
        // 不在此清空目标状态：通知按钮会冷启动宿主进程，清空会让随后的点击
        // 读到「尚无活动目标」；过期目标由 MainActivity.onResume 与卸载过滤处理
        // 入口统一本体通知：把历史 target/host 偏好迁移为 host（并广播给旧版目标进程）
        runCatching { EntryMode.set(this, EntryMode.HOST) }
        EntryControlUi.refresh(this)
        // 直读 LSPosed 的模块启用状态（service 绑定 → 首页「已激活」）
        LspService.register()
    }
}
