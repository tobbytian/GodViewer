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
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.entry.EntryMode
import com.godviewer.app.shared.immutableFlag

/**
 * 本体入口的常驻控制通知：
 * 点击通知体 /「开启」= 目标未开编辑则开启，已开则仅刷新状态（不跳转 GodViewer）；
 * 另有撤销、规则管理操作。
 */
object HostControlNotifier {
    private const val TAG = "Control"
    private const val CHANNEL_ID = "godviewer_host_control"
    private const val NOTIFICATION_ID = 41001

    /** 点击通知体或「开启」：未开编辑则开启，已开则 no-op 刷新 */
    const val ACTION_ENABLE = "com.godviewer.app.action.HOST_ENABLE_EDIT"
    const val ACTION_UNDO = "com.godviewer.app.action.HOST_UNDO"
    const val ACTION_MANAGE_RULES = "com.godviewer.app.action.HOST_MANAGE_RULES"

    fun refresh(context: Context) {
        show(context)
    }

    fun show(context: Context) {
        runCatching {
            val app = context.applicationContext
            // 仅「本体通知」入口模式才发宿主控制通知
            if (!EntryMode.isHostEntry(app)) {
                cancel(app)
                return
            }
            if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) {
                GvLog.d(TAG, "notifications disabled, skip host control notify")
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

            // 与目标通知一致：点通知体 = 开启/刷新编辑，不打开宿主 Activity
            val enablePending = actionPending(app, ACTION_ENABLE, 1)
            val undoPending = actionPending(app, ACTION_UNDO, 2)
            val managePending = actionPending(app, ACTION_MANAGE_RULES, 3)

            val builder = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_godviewer)
                .setContentTitle(app.getString(R.string.host_control_notification_title))
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                .setContentIntent(enablePending)
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
            GvLog.d(TAG, "host control notification posted target=${target?.packageName}")
        }.onFailure {
            GvLog.w(TAG, "host control notify failed", it)
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
}
