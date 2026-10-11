package com.godviewer.app.target.control

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.entry.EntryMode
import com.godviewer.app.shared.model.ViewRule
import com.godviewer.app.target.edit.EditMode
import com.godviewer.app.target.edit.EditModeNotification
import com.godviewer.app.target.hook.hookers.ActivityLifecycleHooker
import com.godviewer.app.target.rule.ViewRuleManager
import com.godviewer.app.target.ui.RuleManagerDialog
import com.google.gson.Gson

/**
 * Target-process receiver:
 * - host notification commands (enable / undo / manage rules)
 * - host entry-mode sync (cache mode + cancel/post target notification)
 */
object TargetControlReceiver {
    private const val TAG = "Control"
    private var registered = false
    private var appRef: Application? = null

    fun init(app: Application) {
        appRef = app
        if (registered) return
        registered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null || intent == null) return
                val token = intent.getStringExtra(HostControlBridge.EXTRA_TOKEN)
                if (token != HostControlBridge.CONTROL_TOKEN) {
                    GvLog.w(TAG, "reject command: bad token")
                    return
                }
                when (intent.action) {
                    HostControlBridge.ACTION_ENABLE_EDIT -> {
                        GvLog.i(TAG, "enable edit received")
                        // setEnabled 内部会 refreshActivity / unwrap 或 wrap
                        EditMode.setEnabled(true)
                    }
                    HostControlBridge.ACTION_UNDO -> {
                        val activity = ActivityLifecycleHooker.resumedActivity()
                        val undone = ViewRuleManager.undoLastOperation(activity)
                        GvLog.d(TAG, "undo received, undone=$undone")
                    }
                    HostControlBridge.ACTION_MANAGE_RULES -> {
                        val activity = ActivityLifecycleHooker.resumedActivity()
                        if (activity == null) {
                            GvLog.d(TAG, "manage rules: no resumed activity")
                            return
                        }
                        Handler(Looper.getMainLooper()).post {
                            runCatching { RuleManagerDialog(activity).show() }
                                .onFailure { GvLog.e(TAG, "show rule manager failed", it) }
                        }
                    }
                    HostControlBridge.ACTION_DELETE_ALL_RULES -> {
                        // 权威规则在目标应用私有目录，只能在本进程删除；
                        // 空规则落盘会连带推送空镜像，宿主镜像目录随之清除
                        val removed = ViewRuleManager.deleteAllRules(
                            ActivityLifecycleHooker.liveActivities(),
                        )
                        GvLog.i(TAG, "delete all rules received, removed=$removed")
                    }
                    HostControlBridge.ACTION_DELETE_RULES -> {
                        // 宿主镜像可能滞后，仅信任 key：按 key 定位权威规则后批量删除，
                        // 落盘会自动回推镜像供宿主回读校验
                        val json = intent.getStringExtra(HostControlBridge.EXTRA_RULES_JSON)
                        val incoming = runCatching {
                            json?.let { Gson().fromJson(it, Array<ViewRule>::class.java) }
                                ?.toList()
                        }.getOrNull()
                        if (incoming.isNullOrEmpty()) {
                            GvLog.w(TAG, "delete rules: missing/bad rules json")
                            return
                        }
                        val keys = incoming.map { it.key() }.toSet()
                        val matched = ViewRuleManager.allRules().filter { it.key() in keys }
                        if (matched.isEmpty()) {
                            GvLog.d(TAG, "delete rules: none matched count=${keys.size}")
                            return
                        }
                        val removed = ViewRuleManager.deleteRules(
                            matched,
                            ActivityLifecycleHooker.liveActivities(),
                        )
                        GvLog.i(TAG, "delete rules received, removed=$removed")
                    }
                    EntryMode.ACTION_ENTRY_MODE_CHANGED -> {
                        val appCtx = appRef ?: context.applicationContext as? Application
                        if (appCtx == null) return
                        // 直接用 extras 写入本地缓存，不依赖目标再读宿主 prefs
                        val mode = intent.getStringExtra(EntryMode.EXTRA_MODE)
                        EntryMode.applyFromHost(appCtx, mode)
                        GvLog.d(TAG, "entry mode synced mode=$mode")
                        Handler(Looper.getMainLooper()).post {
                            runCatching { EditModeNotification.refresh(appCtx) }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(HostControlBridge.ACTION_ENABLE_EDIT)
            addAction(HostControlBridge.ACTION_UNDO)
            addAction(HostControlBridge.ACTION_MANAGE_RULES)
            addAction(HostControlBridge.ACTION_DELETE_ALL_RULES)
            addAction(HostControlBridge.ACTION_DELETE_RULES)
            addAction(EntryMode.ACTION_ENTRY_MODE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        GvLog.d(TAG, "target control receiver registered")
    }
}
