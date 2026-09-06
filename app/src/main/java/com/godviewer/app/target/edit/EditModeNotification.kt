package com.godviewer.app.target.edit

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.godviewer.app.R
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.entry.EntryMode
import com.godviewer.app.target.hook.ModuleRes
import com.godviewer.app.target.hook.hookers.ActivityLifecycleHooker
import com.godviewer.app.target.rule.ViewRuleManager
import com.godviewer.app.target.ui.RuleManagerDialog

/**
 * 目标应用进程内的编辑模式通知入口（默认入口）。
 *
 * 仅在入口=目标通知时发布；本体入口下必须 cancel。
 * 模式未确认前不 post，等宿主 push，避免本体入口下闪出目标通知。
 *
 * **moduleRes 未就绪时绝不 post**（部分进程/框架路径 zygote init 可能未跑到），
 * 否则 lateinit 会在 Application.onCreate 的延迟任务里直接闪退目标 App。
 */
object EditModeNotification {

    private const val TAG = "EditNotif"

    const val ACTION_TOGGLE = "com.godviewer.app.action.TOGGLE_EDIT_MODE"
    const val ACTION_UNDO = "com.godviewer.app.action.UNDO_LAST_OPERATION"
    const val ACTION_MANAGE_RULES = "com.godviewer.app.action.MANAGE_RULES"

    private const val CHANNEL_ID = "godviewer_edit_mode"
    private const val NOTIFICATION_ID = 0x4756 // "GV"
    private const val CONFIRM_WAIT_MS = 600L

    private var receiverRegistered = false
    private var appRef: Application? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingConfirm: Runnable? = null

    fun init(app: Application) {
        appRef = app
        runCatching { registerToggleReceiver(app) }
            .onFailure { GvLog.e(TAG, "register toggle receiver failed", it) }
        runCatching { refresh(app) }
            .onFailure { GvLog.e(TAG, "init refresh failed", it) }
    }

    fun refresh(app: Application? = appRef) {
        val application = app ?: appRef ?: return
        runCatching {
            // 已确认 host → 立刻撤掉
            if (EntryMode.isHostEntryInTarget(application)) {
                cancelPendingConfirm()
                cancel(application)
                GvLog.d(TAG, "host entry: target notification cancelled")
                return
            }
            // 已确认 target → 发通知
            if (EntryMode.hasConfirmedMode(application)) {
                cancelPendingConfirm()
                post(application)
                return
            }
            // 尚未确认：先 cancel 任何残留，等宿主 push；超时再按默认 target 发
            cancel(application)
            scheduleConfirmFallback(application)
            GvLog.d(TAG, "entry mode unconfirmed: wait host sync before post")
        }.onFailure {
            GvLog.e(TAG, "refresh failed", it)
        }
    }

    fun post(app: Application) {
        runCatching {
            if (!EntryMode.shouldShowTargetNotification(app)) {
                cancel(app)
                return
            }
            if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) {
                GvLog.d(TAG, "notifications disabled, skip post")
                return
            }
            if (!ModuleRes.isModuleResReady()) {
                // 绝不能碰 lateinit moduleRes：会 UninitializedPropertyAccessException 闪退
                GvLog.w(
                    TAG,
                    "moduleRes not ready, skip target notification " +
                        "(zygote init missing or failed for this process)",
                )
                return
            }
            val res = ModuleRes.moduleRes
            val enabled = EditMode.isEnabled()
            val toggleIntent = PendingIntent.getBroadcast(
                app,
                0,
                Intent(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val undoIntent = PendingIntent.getBroadcast(
                app,
                1,
                Intent(ACTION_UNDO),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val manageRulesIntent = PendingIntent.getBroadcast(
                app,
                2,
                Intent(ACTION_MANAGE_RULES),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle(res.getString(R.string.edit_mode))
                .setContentText(
                    res.getString(if (enabled) R.string.edit_mode_on else R.string.edit_mode_off),
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(toggleIntent)
                .addAction(
                    android.R.drawable.ic_menu_edit,
                    res.getString(R.string.edit_mode_enable),
                    toggleIntent,
                )
                .addAction(
                    android.R.drawable.ic_menu_revert,
                    res.getString(R.string.undo),
                    undoIntent,
                )
                .addAction(
                    android.R.drawable.ic_menu_manage,
                    res.getString(R.string.manage_rules),
                    manageRulesIntent,
                )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        res.getString(R.string.edit_mode),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, builder.build())
            GvLog.d(TAG, "notification posted, enabled=$enabled")
        }.onFailure {
            GvLog.e(TAG, "post notification failed", it)
        }
    }

    fun cancel(app: Application) {
        runCatching {
            NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
        }
    }

    private fun scheduleConfirmFallback(app: Application) {
        cancelPendingConfirm()
        val task = Runnable {
            pendingConfirm = null
            runCatching {
                // 超时仍无宿主确认：按默认目标入口发（兼容宿主未存活）
                if (!EntryMode.isHostEntryInTarget(app)) {
                    GvLog.d(TAG, "confirm timeout: post target notification by default")
                    post(app)
                } else {
                    cancel(app)
                }
            }.onFailure {
                GvLog.e(TAG, "confirm fallback failed", it)
            }
        }
        pendingConfirm = task
        mainHandler.postDelayed(task, CONFIRM_WAIT_MS)
    }

    private fun cancelPendingConfirm() {
        pendingConfirm?.let { mainHandler.removeCallbacks(it) }
        pendingConfirm = null
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
                runCatching {
                    when (intent?.action) {
                        ACTION_UNDO -> {
                            val activity = ActivityLifecycleHooker.resumedActivity()
                            val undone = ViewRuleManager.undoLastOperation(activity)
                            GvLog.d(TAG, "undo received, undone=$undone")
                        }
                        ACTION_MANAGE_RULES -> {
                            val activity = ActivityLifecycleHooker.resumedActivity()
                            if (activity != null) {
                                runCatching { RuleManagerDialog(activity).show() }
                                    .onFailure { GvLog.e(TAG, "show rule manager failed", it) }
                            }
                        }
                        else -> {
                            GvLog.i(TAG, "toggle received -> enable edit")
                            EditMode.setEnabled(true)
                        }
                    }
                }.onFailure {
                    GvLog.e(TAG, "notification action failed action=${intent?.action}", it)
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
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        GvLog.d(TAG, "notification receivers registered")
    }
}
