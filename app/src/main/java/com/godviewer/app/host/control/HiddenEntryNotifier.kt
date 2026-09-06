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
import com.godviewer.app.host.LauncherIconHelper
import com.godviewer.app.host.MainActivity
import com.godviewer.app.shared.entry.EntryMode
import com.godviewer.app.shared.immutableFlag

/**
 * 隐藏桌面图标后的重新打开入口（仅「目标应用通知」入口模式下使用）。
 * 「本体通知」模式下由 [HostControlNotifier] 兼顾打开应用，不再单独发此通知。
 *
 * 仅运行在宿主进程；由 [com.godviewer.app.host.entry.EntryControlUi] 调度。
 */
object HiddenEntryNotifier {
    private const val CHANNEL_ID = "godviewer_hidden_entry"
    private const val NOTIFICATION_ID = 41002

    fun refresh(context: Context) {
        if (EntryMode.isHostEntry(context)) {
            cancel(context)
            return
        }
        if (LauncherIconHelper.isHidden(context)) {
            show(context)
        } else {
            cancel(context)
        }
    }

    fun show(context: Context) {
        runCatching {
            val app = context.applicationContext
            if (EntryMode.isHostEntry(app)) {
                cancel(app)
                return
            }
            ensureChannel(app)
            val launch = Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                app,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
            )
            val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_godviewer)
                .setContentTitle(app.getString(R.string.hidden_entry_notification_title))
                .setContentText(app.getString(R.string.hidden_entry_notification_text))
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
        }
    }

    fun cancel(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.hidden_entry_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.hidden_entry_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
