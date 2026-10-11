package com.godviewer.app.target.edit

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import com.godviewer.app.shared.GvLog

/**
 * 目标应用进程通知：**已废弃**。
 *
 * 入口统一为本体通知（宿主 `HostControlNotifier` + 首页开关），目标应用不再
 * 发布任何常驻通知，也不会注册通知按钮接收器（开启 / 撤销 / 管理规则改由宿主
 * 通知下发，见 `target.control.TargetControlReceiver`）。
 *
 * 保留本对象只有两个作用：
 * 1. 调用点不变（ApplicationHooker / EditMode / TargetControlReceiver）；
 * 2. 清掉旧版本遗留在通知栏的编辑模式通知。
 */
object EditModeNotification {

    private const val TAG = "EditNotif"

    /** 旧版本的目标编辑模式通知 ID（"GV"），仅用于清理残留。 */
    private const val NOTIFICATION_ID = 0x4756

    @Volatile
    private var appRef: Application? = null

    fun init(app: Application) {
        appRef = app
        cancel(app)
    }

    fun refresh(app: Application? = appRef) {
        val application = app ?: appRef ?: return
        cancel(application)
    }

    fun cancel(app: Application) {
        runCatching {
            NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
            GvLog.d(TAG, "target notification cancelled (entry unified to host notification)")
        }
    }
}
