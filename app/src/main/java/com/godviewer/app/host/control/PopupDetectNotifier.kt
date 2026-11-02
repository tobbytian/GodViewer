package com.godviewer.app.host.control

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.godviewer.app.R
import com.godviewer.app.data.HostControlReceiver
import com.godviewer.app.host.prefs.HostPrefs
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.immutableFlag
import com.godviewer.app.shared.resolveAppLabel

/**
 * 「检测到弹窗」询问通知（独立于常驻控制通知，固定 id 覆盖式更新）：
 * 目标端 [com.godviewer.app.target.hook.hookers.DialogHooker] 检测到外来弹窗并上报，
 * 宿主弹此通知「是否关闭？」——[关闭] 下发 ACTION_CLOSE_POPUPS 定向关闭该应用弹窗，
 * [取消] / 点通知体仅收起通知。
 *
 * 不受首页「本体通知」总开关约束（属独立功能），但尊重系统通知权限与
 * 每应用通知抑制开关。
 */
object PopupDetectNotifier {
    private const val TAG = "PopupNotify"
    private const val CHANNEL_ID = "godviewer_popup_detect"
    private const val NOTIFICATION_ID = 41002

    fun show(context: Context, packageName: String, dialogLabel: String) {
        runCatching {
            val app = context.applicationContext
            if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) {
                GvLog.d(TAG, "notifications disabled, skip popup detect notify")
                return
            }
            // 复用每应用通知抑制：被单独关闭的应用不再打扰
            if (HostPrefs.isAppNotifyDisabled(app, packageName)) {
                GvLog.d(TAG, "app notify disabled, skip pkg=$packageName")
                return
            }
            ensureChannel(app)

            val appLabel = resolveAppLabel(app, packageName)
            val closePending = actionPending(
                app,
                HostControlNotifier.ACTION_CLOSE_POPUPS,
                11,
                packageName,
            )
            val dismissPending = actionPending(
                app,
                HostControlNotifier.ACTION_POPUP_NOTIFY_DISMISS,
                12,
                packageName,
            )

            val title = app.getString(R.string.popup_detected_title)
            val text = app.getString(
                R.string.popup_detected_text,
                appLabel,
                dialogLabel,
            )

            val builder = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_godviewer)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                // 点通知体 = 直接确认关闭并拦截（receiver 内会收通知 + Toast 回执）；
                // 不想关就点「取消」按钮
                .setContentIntent(closePending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .addAction(
                    R.drawable.ic_action_close_24,
                    app.getString(R.string.close_popups),
                    closePending,
                )
                .addAction(
                    R.drawable.ic_action_undo_24,
                    app.getString(R.string.cancel),
                    dismissPending,
                )

            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, builder.build())
            GvLog.i(TAG, "popup detect notify posted pkg=$packageName label=$dialogLabel")
        }.onFailure {
            GvLog.w(TAG, "popup detect notify failed", it)
        }
    }

    fun cancel(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context.applicationContext)
                .cancel(NOTIFICATION_ID)
        }
        GvLog.d(TAG, "popup detect notify canceled")
    }

    /** 关闭动作携带目标包名（定向 dispatchToPackage，不依赖「最近目标」状态） */
    private fun actionPending(
        context: Context,
        action: String,
        requestCode: Int,
        packageName: String,
    ): PendingIntent {
        val intent = Intent(context, HostControlReceiver::class.java).apply {
            this.action = action
            putExtra(HostControlBridge.EXTRA_PACKAGE, packageName)
            putExtra(HostControlBridge.EXTRA_TOKEN, HostControlBridge.CONTROL_TOKEN)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.popup_detect_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.popup_detect_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}