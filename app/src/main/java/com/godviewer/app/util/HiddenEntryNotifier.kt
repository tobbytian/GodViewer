package com.godviewer.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.godviewer.app.MainActivity
import com.godviewer.app.R

/**
 * Persistent notification used as a reopen entry after the desktop launcher icon is hidden.
 * LSPosed module list may not launch apps without a LAUNCHER component; this is the reliable path.
 */
object HiddenEntryNotifier {
    private const val CHANNEL_ID = "godviewer_hidden_entry"
    private const val NOTIFICATION_ID = 41001

    fun refresh(context: Context) {
        if (LauncherIconHelper.isHidden(context)) {
            show(context)
        } else {
            cancel(context)
        }
    }

    fun show(context: Context) {
        runCatching {
            val app = context.applicationContext
            ensureChannel(app)
            val launch = Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                app,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutableFlag(),
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
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
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

    private fun pendingImmutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }
}
