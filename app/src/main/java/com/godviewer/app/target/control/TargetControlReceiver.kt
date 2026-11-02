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
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.shared.entry.EntryMode
import com.godviewer.app.shared.entry.PopupIntercept
import com.godviewer.app.shared.model.DialogRule
import com.godviewer.app.shared.model.ViewRule
import com.godviewer.app.target.edit.EditMode
import com.godviewer.app.target.edit.EditModeNotification
import com.godviewer.app.target.hook.hookers.ActivityLifecycleHooker
import com.godviewer.app.target.popup.PopupDismiss
import com.godviewer.app.target.rule.DialogRuleStore
import com.godviewer.app.target.rule.ViewRuleManager
import com.godviewer.app.target.ui.RuleManagerDialog
import com.google.gson.Gson

/**
 * Target-process receiver:
 * - host notification commands (enable / undo / manage rules)
 * - host entry-mode sync (cache mode + cancel/post target notification)
 */
object TargetControlReceiver {
    /** 区分宿主/目标/通知三个进程侧的 Control 日志（原同名 tag 在 logcat 撞车） */
    private const val TAG = "Control.Target"
    private var registered = false
    private var appRef: Application? = null

    /** 镜像补推的后台执行器（图标/缩略图编码不进主线程） */
    private val mirrorExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

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
                        // 空规则落盘会连带推送空镜像，宿主镜像目录随之清除。
                        // 「全部规则」语义连带清空弹窗规则（dialog_rules.json）
                        val removed = ViewRuleManager.deleteAllRules(
                            ActivityLifecycleHooker.liveActivities(),
                        )
                        val removedDialogs = DialogRuleStore.deleteAll()
                        GvLog.i(
                            TAG,
                            "delete all rules received, removed=$removed dialogs=$removedDialogs",
                        )
                    }
                    HostControlBridge.ACTION_CLOSE_POPUPS -> {
                        // 强制弹窗可能正遮挡界面，关闭动作必须走主线程且不依赖编辑模式
                        Handler(Looper.getMainLooper()).post {
                            runCatching {
                                val closed = PopupDismiss.dismissAll()
                                GvLog.i(TAG, "close popups received, closed=$closed")
                            }.onFailure {
                                GvLog.e(TAG, "close popups failed", it)
                            }
                        }
                    }
                    HostControlBridge.ACTION_DELETE_DIALOG_RULES -> {
                        // 弹窗规则权威同样在目标私有目录；按 key 定位删除后回推镜像
                        val json = intent.getStringExtra(HostControlBridge.EXTRA_DIALOG_RULES_JSON)
                        val incoming = runCatching {
                            json?.let { Gson().fromJson(it, Array<DialogRule>::class.java) }
                                ?.toList()
                        }.getOrNull()
                        if (incoming.isNullOrEmpty()) {
                            GvLog.w(TAG, "delete dialog rules: missing/bad json")
                            return
                        }
                        val removed = DialogRuleStore.delete(incoming.map { it.key() })
                        GvLog.i(TAG, "delete dialog rules received, removed=$removed")
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
                    HostControlBridge.ACTION_UPDATE_RULES,
                    HostControlBridge.ACTION_IMPORT_RULES -> {
                        // 宿主编辑/导入下发：目标按 key/指纹 upsert（幂等，可重复下发），
                        // 落盘自动回推镜像；只接受 package_name 与自身一致的规则，
                        // 防止信封分错应用后污染权威数据。
                        // 覆盖导入（EXTRA_OVERWRITE，仅首片携带）：先清空全部规则再
                        // 合并本批，单事务语义；重发幂等（重跑结果一致）
                        val ownPkg = context.packageName
                        val viewJson =
                            intent.getStringExtra(HostControlBridge.EXTRA_RULES_JSON)
                        val incomingView = runCatching {
                            viewJson?.let { Gson().fromJson(it, Array<ViewRule>::class.java) }
                                ?.toList()
                        }.getOrNull().orEmpty().filter { it.packageName == ownPkg }
                        val dialogJson =
                            intent.getStringExtra(HostControlBridge.EXTRA_DIALOG_RULES_JSON)
                        val incomingDialog = runCatching {
                            dialogJson?.let { Gson().fromJson(it, Array<DialogRule>::class.java) }
                                ?.toList()
                        }.getOrNull().orEmpty().filter { it.packageName == ownPkg }
                        if (incomingView.isEmpty() && incomingDialog.isEmpty()) {
                            GvLog.w(TAG, "upsert: missing/bad json or pkg mismatch pkg=$ownPkg")
                            return
                        }
                        if (intent.getBooleanExtra(HostControlBridge.EXTRA_OVERWRITE, false)) {
                            val removedView = ViewRuleManager.deleteAllRules(
                                ActivityLifecycleHooker.liveActivities(),
                            )
                            val removedDialogs = DialogRuleStore.deleteAll()
                            GvLog.i(
                                TAG,
                                "overwrite import: cleared view=$removedView " +
                                    "dialogs=$removedDialogs pkg=$ownPkg",
                            )
                        }
                        if (incomingView.isNotEmpty()) {
                            val applied = ViewRuleManager.upsertRules(
                                incomingView,
                                ActivityLifecycleHooker.liveActivities(),
                            )
                            GvLog.i(TAG, "upsert rules applied=$applied pkg=$ownPkg")
                        }
                        if (incomingDialog.isNotEmpty()) {
                            val applied = DialogRuleStore.upsert(incomingDialog)
                            GvLog.i(TAG, "upsert dialog rules applied=$applied pkg=$ownPkg")
                        }
                    }
                    HostControlBridge.ACTION_REQUEST_MIRROR_PUSH -> {
                        // 导出/刷新前宿主请求补推：镜像 best-effort 可能滞后；
                        // 图标/缩略图编码走后台线程，不阻塞主线程
                        val appCtx = appRef ?: context.applicationContext as? Application
                        if (appCtx == null) return
                        GvLog.d(TAG, "mirror push requested")
                        mirrorExecutor.execute {
                            runCatching {
                                RuleMirror.pushFromTarget(
                                    appCtx,
                                    appCtx.packageName,
                                    ViewRuleManager.allRules(),
                                )
                            }.onFailure {
                                GvLog.w(TAG, "mirror push on request failed", it)
                            }
                        }
                    }
                    HostControlBridge.ACTION_POPUP_INTERCEPT_STATE -> {
                        // 宿主回发/推送的「强制弹窗拦截」开关状态
                        // （目标端查不到宿主 Provider，状态同步只走广播）
                        val appCtx = appRef ?: context.applicationContext as? Application
                        if (appCtx == null) return
                        val enabled =
                            intent.getBooleanExtra(HostControlBridge.EXTRA_ENABLED, false)
                        val windowSeconds = intent.getIntExtra(
                            HostControlBridge.EXTRA_WINDOW_SECONDS,
                            PopupIntercept.DEFAULT_WINDOW_SECONDS,
                        )
                        PopupIntercept.onStateReceived(appCtx, enabled, windowSeconds)
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
            addAction(HostControlBridge.ACTION_CLOSE_POPUPS)
            addAction(HostControlBridge.ACTION_DELETE_DIALOG_RULES)
            addAction(HostControlBridge.ACTION_UPDATE_RULES)
            addAction(HostControlBridge.ACTION_IMPORT_RULES)
            addAction(HostControlBridge.ACTION_REQUEST_MIRROR_PUSH)
            addAction(HostControlBridge.ACTION_POPUP_INTERCEPT_STATE)
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
