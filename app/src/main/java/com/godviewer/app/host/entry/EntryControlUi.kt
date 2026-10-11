package com.godviewer.app.host.entry

import android.content.Context
import com.godviewer.app.host.control.HostControlNotifier
import com.godviewer.app.host.prefs.HostPrefs

/**
 * 宿主进程通知形态总控（入口统一为本体通知）：
 * - 首页「本体通知」开关打开 → 常驻 [HostControlNotifier]（开启编辑 / 撤销 / 管理规则）
 * - 开关关闭 → 取消常驻控制通知
 */
object EntryControlUi {
    fun refresh(context: Context) {
        val app = context.applicationContext
        if (HostPrefs.isHostNotifyEnabled(app)) {
            HostControlNotifier.refresh(app)
        } else {
            HostControlNotifier.cancel(app)
        }
    }

    /** 首页开关：持久化「本体通知」开关并立即刷新通知形态。 */
    fun setHostNotification(context: Context, enabled: Boolean) {
        HostPrefs.setHostNotifyEnabled(context, enabled)
        refresh(context)
    }
}
