package com.godviewer.app.host

import android.app.Application
import android.content.Context
import com.godviewer.app.BuildConfig
import com.godviewer.app.host.entry.EntryControlUi
import com.godviewer.app.host.prefs.HostPrefs
import com.godviewer.app.host.service.LspService
import com.godviewer.app.shared.AppLanguage
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.control.HostControlBridge
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
        // 宿主进程启动时清掉上一会话残留目标，再按首页开关刷新通知形态
        HostControlBridge.clearTargetState(this)
        // 入口统一本体通知：把历史 target/host 偏好迁移为 host（并广播给旧版目标进程）
        runCatching { EntryMode.set(this, EntryMode.HOST) }
        EntryControlUi.refresh(this)
        // 直读 LSPosed 的模块启用状态（service 绑定 → 首页「已激活」）
        LspService.register()
    }
}
