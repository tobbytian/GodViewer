package com.godviewer.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.godviewer.app.MainActivity
import com.godviewer.app.R
import com.godviewer.app.data.HostControlReceiver

/**
 * Single host-owned persistent notification:
 * open GodViewer + control the last foreground injected target
 * (enable edit mode / undo / manage rules).
 *
 * Replaces both the old target-app edit-mode notification and the hide-icon-only entry.
 */
object HostControlNotifier {
    private const val TAG = "GodViewer.Control"
    private const val CHANNEL_ID = "godviewer_host_control"
    private const val NOTIFICATION_ID = 41001

    const val ACTION_ENABLE = "com.godviewer.app.action.HOST_ENABLE_EDIT"
    const val ACTION_UNDO = "com.godviewer.app.action.HOST_UNDO"
    const val ACTION_MANAGE_RULES = "com.godviewer.app.action.HOST_MANAGE_RULES"

    fun refresh(context: Context) {
        show(context)
    }

    fun show(context: Context) {
        runCatching {
            val app = context.applicationContext
            if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) {
                Log.d(TAG, "notifications disabled, skip host control notify")
                return
            }
            ensureChannel(app)

            val target = HostControlBridge.currentTarget(app)
            val contentText = if (target == null) {
                app.getString(R.string.host_control_no_target_text)
            } else {
                val state = app.getString(
                    if (target.editEnabled) R.string.edit_mode_on else R.string.edit_mode_off,
                )
                app.getString(R.string.host_control_target_text, target.label, state)
            }

            val openApp = Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPending = PendingIntent.getActivity(
                app,
                0,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
            )

            val enablePending = actionPending(app, ACTION_ENABLE, 1)
            val undoPending = actionPending(app, ACTION_UNDO, 2)
            val managePending = actionPending(app, ACTION_MANAGE_RULES, 3)

            val builder = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_godviewer)
                .setContentTitle(app.getString(R.string.host_control_notification_title))
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                .setContentIntent(openPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(
                    android.R.drawable.ic_menu_edit,
                    app.getString(R.string.edit_mode_enable),
                    enablePending,
                )
                .addAction(
                    android.R.drawable.ic_menu_revert,
                    app.getString(R.string.undo),
                    undoPending,
                )
                .addAction(
                    android.R.drawable.ic_menu_manage,
                    app.getString(R.string.manage_rules),
                    managePending,
                )

            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, builder.build())
            Log.d(TAG, "host control notification posted target=${target?.packageName}")
        }.onFailure {
            Log.w(TAG, "host control notify failed", it)
        }
    }

    fun cancel(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
        }
    }

    private fun actionPending(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, HostControlReceiver::class.java).apply {
            this.action = action
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
            context.getString(R.string.host_control_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.host_control_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun immutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }
}
