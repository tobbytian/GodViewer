package com.godviewer.app.util

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.godviewer.app.R
import com.godviewer.app.data.ViewRuleManager
import com.godviewer.app.hook.AnyHookZygote.Companion.moduleRes
import com.godviewer.app.hook.hookers.ActivityLifecycleHooker
import com.godviewer.app.ui.RuleManagerDialog

/**
 * 通知栏编辑模式开关（只运行在被注入的目标进程内）。
 *
 * 在目标进程发布一条常驻通知，通知上的"切换"按钮（或点击通知本体）通过
 * PendingIntent 广播，由本对象注册的动态 BroadcastReceiver 处理：
 * 取反 [EditMode] 状态、刷新通知并 Toast 反馈。
 * "撤销"按钮同样走广播：调用 [ViewRuleManager.undoLastOperation] 恢复上一步规则操作
 * （隐藏 / 应用修改 / 重置删除），被隐藏的视图会重新出现，从而可再次编辑或删除。
 * 接收器用 RECEIVER_EXPORTED 注册（自定义 action，无实际风险），确保广播可靠送达。
 */
object EditModeNotification {

    private const val TAG = "GodViewer.EditMode"

    const val ACTION_TOGGLE = "com.godviewer.app.action.TOGGLE_EDIT_MODE"
    const val ACTION_UNDO = "com.godviewer.app.action.UNDO_LAST_OPERATION"
    const val ACTION_MANAGE_RULES = "com.godviewer.app.action.MANAGE_RULES"

    private const val CHANNEL_ID = "godviewer_edit_mode"
    private const val NOTIFICATION_ID = 0x4756 // "GV"

    private var receiverRegistered = false

    /** Application.onCreate 时调用：注册切换接收器并发布通知 */
    fun init(app: Application) {
        runCatching { registerToggleReceiver(app) }
            .onFailure { Log.e(TAG, "register toggle receiver failed", it) }
        runCatching { post(app) }
            .onFailure { Log.e(TAG, "post notification failed", it) }
    }

    /** 发布（或刷新）编辑模式通知；目标应用无通知权限时跳过 */
    fun post(app: Application) {
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) {
            Log.d(TAG, "notifications disabled, skip post")
            return
        }
        val enabled = EditMode.isEnabled()
        val toggleIntent = PendingIntent.getBroadcast(
            app,
            0,
            Intent(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val undoIntent = PendingIntent.getBroadcast(
            app,
            1,
            Intent(ACTION_UNDO),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val manageRulesIntent = PendingIntent.getBroadcast(
            app,
            2,
            Intent(ACTION_MANAGE_RULES),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(moduleRes.getString(R.string.edit_mode))
            .setContentText(
                moduleRes.getString(if (enabled) R.string.edit_mode_on else R.string.edit_mode_off)
            )
            .setOngoing(true)
            .setContentIntent(toggleIntent)
            .addAction(
                android.R.drawable.ic_menu_edit,
                moduleRes.getString(R.string.edit_mode_enable),
                toggleIntent
            )
            .addAction(
                android.R.drawable.ic_menu_revert,
                moduleRes.getString(R.string.undo),
                undoIntent
            )
            .addAction(
                android.R.drawable.ic_menu_manage,
                moduleRes.getString(R.string.manage_rules),
                manageRulesIntent
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    moduleRes.getString(R.string.edit_mode),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, builder.build())
        Log.d(TAG, "notification posted, enabled=$enabled")
    }

    private fun registerToggleReceiver(app: Application) {
        if (receiverRegistered) {
            return
        }
        receiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null) {
                    return
                }
                when (intent?.action) {
                    // 撤销上一个规则操作：恢复规则数据并回放当前界面的视图
                    ACTION_UNDO -> {
                        val activity = ActivityLifecycleHooker.resumedActivity()
                        val undone = ViewRuleManager.undoLastOperation(activity)
                        Log.d(TAG, "undo received, undone=$undone")
                    }
                    // 打开规则管理弹窗：列出全部规则，可逐条删除
                    ACTION_MANAGE_RULES -> {
                        val activity = ActivityLifecycleHooker.resumedActivity()
                        if (activity != null) {
                            runCatching { RuleManagerDialog(activity).show() }
                                .onFailure { Log.e(TAG, "show rule manager failed", it) }
                        }
                    }
                    // 通知只负责"开启"编辑模式（已开启时重复点击无效果）；
                    // "关闭"由编辑弹窗的"退出编辑模式"按钮负责。setEnabled 内部会自动刷新通知
                    else -> {
                        Log.d(TAG, "toggle received")
                        EditMode.setEnabled(true)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE)
            addAction(ACTION_UNDO)
            addAction(ACTION_MANAGE_RULES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        Log.d(TAG, "notification receivers registered")
    }
}
