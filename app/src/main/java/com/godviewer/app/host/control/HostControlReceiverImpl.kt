package com.godviewer.app.host.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.godviewer.app.R
import com.godviewer.app.host.control.HostControlNotifier
import com.godviewer.app.host.diag.HostCrashStore
import com.godviewer.app.host.diag.HostDiagStore
import com.godviewer.app.host.prefs.HostPrefs
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.diag.CrashReportProtocol
import com.godviewer.app.shared.diag.DiagReportProtocol

/**
 * Host-side control receiver:
 * - target foreground / edit-state reports
 * - notification body / actions（开启仅在未开时下发；已开只刷新通知）
 * - target **full debug ring** + crash dump（best-effort，供设置页复制）
 */
open class HostControlReceiverImpl : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val app = context.applicationContext
        val action = intent.action ?: return

        when (action) {
            DiagReportProtocol.ACTION_TARGET_DIAG -> {
                val token = intent.getStringExtra(DiagReportProtocol.EXTRA_TOKEN)
                if (token != DiagReportProtocol.DIAG_TOKEN) {
                    GvLog.w(TAG, "reject target diag: bad token")
                    return
                }
                val pkg = intent.getStringExtra(DiagReportProtocol.EXTRA_PACKAGE).orEmpty()
                if (pkg.isBlank()) return
                val body = intent.getStringExtra(DiagReportProtocol.EXTRA_BODY).orEmpty()
                if (body.isBlank()) return
                val meta = HostDiagStore.DiagMeta(
                    packageName = pkg,
                    processName = intent.getStringExtra(DiagReportProtocol.EXTRA_PROCESS)
                        .orEmpty().ifBlank { pkg },
                    stage = intent.getStringExtra(DiagReportProtocol.EXTRA_STAGE)
                        .orEmpty().ifBlank { "diag" },
                    timeMs = intent.getLongExtra(
                        DiagReportProtocol.EXTRA_TIME,
                        System.currentTimeMillis(),
                    ),
                    moduleVc = intent.getIntExtra(DiagReportProtocol.EXTRA_MODULE_VC, 0),
                    moduleVn = intent.getStringExtra(DiagReportProtocol.EXTRA_MODULE_VN).orEmpty(),
                    lineCount = intent.getIntExtra(DiagReportProtocol.EXTRA_LINE_COUNT, 0),
                    body = body,
                )
                val ok = HostDiagStore.save(app, meta)
                GvLog.d(
                    TAG,
                    "target diag received pkg=$pkg ok=$ok stage=${meta.stage} lines=${meta.lineCount}",
                )
            }

            CrashReportProtocol.ACTION_TARGET_CRASH -> {
                val token = intent.getStringExtra(CrashReportProtocol.EXTRA_TOKEN)
                if (token != CrashReportProtocol.CRASH_TOKEN) {
                    GvLog.w(TAG, "reject crash report: bad token")
                    return
                }
                val pkg = intent.getStringExtra(CrashReportProtocol.EXTRA_PACKAGE).orEmpty()
                if (pkg.isBlank()) return
                val meta = HostCrashStore.CrashMeta(
                    packageName = pkg,
                    processName = intent.getStringExtra(CrashReportProtocol.EXTRA_PROCESS)
                        .orEmpty().ifBlank { pkg },
                    threadName = intent.getStringExtra(CrashReportProtocol.EXTRA_THREAD)
                        .orEmpty().ifBlank { "?" },
                    summary = intent.getStringExtra(CrashReportProtocol.EXTRA_SUMMARY).orEmpty(),
                    timeMs = intent.getLongExtra(
                        CrashReportProtocol.EXTRA_TIME,
                        System.currentTimeMillis(),
                    ),
                    moduleVc = intent.getIntExtra(CrashReportProtocol.EXTRA_MODULE_VC, 0),
                    moduleVn = intent.getStringExtra(CrashReportProtocol.EXTRA_MODULE_VN).orEmpty(),
                    stack = intent.getStringExtra(CrashReportProtocol.EXTRA_STACK).orEmpty(),
                    ring = intent.getStringExtra(CrashReportProtocol.EXTRA_RING).orEmpty(),
                )
                val ok = HostCrashStore.save(app, meta)
                GvLog.i(
                    TAG,
                    "target crash received pkg=$pkg ok=$ok summary=${meta.summary.take(160)}",
                )
            }

            HostControlBridge.ACTION_TARGET_FOREGROUND -> {
                val token = intent.getStringExtra(HostControlBridge.EXTRA_TOKEN)
                if (token != HostControlBridge.CONTROL_TOKEN) {
                    GvLog.w(TAG, "reject foreground: bad token")
                    return
                }
                val pkg = intent.getStringExtra(HostControlBridge.EXTRA_PACKAGE).orEmpty()
                if (pkg.isBlank()) return
                val label = intent.getStringExtra(HostControlBridge.EXTRA_LABEL).orEmpty()
                val editEnabled = intent.getBooleanExtra(HostControlBridge.EXTRA_EDIT_ENABLED, false)
                HostControlBridge.saveTargetState(app, pkg, label, editEnabled)
                // 目标不再发布自己的编辑模式通知，这里只需按开关刷新本体控制通知
                HostControlNotifier.refresh(app)
                // 目标刚上报前台 = 其动态接收器已存活：重发此前被死进程丢弃的
                // 编辑/导入命令（upsert 幂等），确认后自动出队 —— 离线导入打开即生效
                PendingCommandStore.flush(app, pkg)
                GvLog.d(TAG, "foreground pkg=$pkg edit=$editEnabled")
            }

            HostControlNotifier.ACTION_ENABLE -> {
                // 与目标通知点击一致：未开 → 开启；已开 → 不操作，只刷新文案
                val target = HostControlBridge.currentTarget(app)
                if (target == null) {
                    Toast.makeText(app, R.string.host_control_no_target, Toast.LENGTH_SHORT).show()
                    HostControlNotifier.refresh(app)
                    return
                }
                // 宿主缓存的 editEnabled 可能过期（目标进程被杀后未回报），
                // 一律下发：目标端 setEnabled(true) 幂等，已开则只刷新
                val ok = HostControlBridge.dispatchToTarget(app, HostControlBridge.ACTION_ENABLE_EDIT)
                GvLog.i(TAG, "enable click: dispatch open edit pkg=${target.packageName} ok=$ok")
                if (!ok) {
                    Toast.makeText(app, R.string.host_control_no_target, Toast.LENGTH_SHORT).show()
                }
                HostControlNotifier.refresh(app)
            }

            HostControlNotifier.ACTION_UNDO,
            HostControlNotifier.ACTION_MANAGE_RULES,
            -> {
                val targetAction = when (action) {
                    HostControlNotifier.ACTION_UNDO -> HostControlBridge.ACTION_UNDO
                    else -> HostControlBridge.ACTION_MANAGE_RULES
                }
                val ok = HostControlBridge.dispatchToTarget(app, targetAction)
                if (!ok) {
                    Toast.makeText(app, R.string.host_control_no_target, Toast.LENGTH_SHORT).show()
                }
            }

            HostControlNotifier.ACTION_CLOSE_POPUPS -> {
                // 「检测到弹窗」通知携带目标包名（定向关闭，不依赖「最近目标」）；
                // 常驻通知的「关闭弹窗」按钮已移除，包名为空仅旧版残留 PendingIntent 才会走到，
                // 回退到最近目标
                val pkg = intent.getStringExtra(HostControlBridge.EXTRA_PACKAGE).orEmpty()
                val ok = if (pkg.isNotBlank()) {
                    HostControlBridge.dispatchToPackage(
                        app,
                        pkg,
                        HostControlBridge.ACTION_CLOSE_POPUPS,
                    )
                } else {
                    HostControlBridge.dispatchToTarget(app, HostControlBridge.ACTION_CLOSE_POPUPS)
                }
                // 动作通知用完即收 + Toast 回执（无目标时仍提示失败原因）
                GvLog.i(TAG, "close popups click pkg=$pkg dispatch=$ok")
                PopupDetectNotifier.cancel(app)
                if (ok) {
                    Toast.makeText(app, R.string.close_popups_toast, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(app, R.string.host_control_no_target, Toast.LENGTH_SHORT).show()
                }
            }

            HostControlNotifier.ACTION_POPUP_NOTIFY_DISMISS -> {
                val pkg = intent.getStringExtra(HostControlBridge.EXTRA_PACKAGE).orEmpty()
                GvLog.d(TAG, "popup detect canceled by user pkg=$pkg")
                PopupDetectNotifier.cancel(app)
                Toast.makeText(app, R.string.cancel_popups_toast, Toast.LENGTH_SHORT).show()
            }

            HostControlBridge.ACTION_POPUP_DETECTED -> {
                val token = intent.getStringExtra(HostControlBridge.EXTRA_TOKEN)
                if (token != HostControlBridge.CONTROL_TOKEN) {
                    GvLog.w(TAG, "reject popup detect: bad token")
                    return
                }
                val pkg = intent.getStringExtra(HostControlBridge.EXTRA_PACKAGE).orEmpty()
                if (pkg.isBlank()) return
                val label = intent.getStringExtra(HostControlBridge.EXTRA_LABEL).orEmpty()
                    .ifBlank { "?" }
                // 目标端不再查开关（Provider 被包可见性挡住），这里是唯一闸门；
                // 检测通知按每应用设置判定（单应用规则页维护，未设置沿用全局）
                if (!HostPrefs.isPopupDetectEnabledFor(app, pkg)) {
                    GvLog.d(TAG, "popup detect off, drop detect pkg=$pkg")
                    return
                }
                PopupDetectNotifier.show(app, pkg, label)
            }

            HostControlBridge.ACTION_POPUP_INTERCEPTED -> {
                val token = intent.getStringExtra(HostControlBridge.EXTRA_TOKEN)
                if (token != HostControlBridge.CONTROL_TOKEN) {
                    GvLog.w(TAG, "reject popup intercepted: bad token")
                    return
                }
                val pkg = intent.getStringExtra(HostControlBridge.EXTRA_PACKAGE).orEmpty()
                if (pkg.isBlank()) return
                PopupLogStore.add(
                    app,
                    PopupLogStore.Entry(
                        packageName = pkg,
                        label = intent.getStringExtra(HostControlBridge.EXTRA_LABEL).orEmpty()
                            .ifBlank { "?" },
                        kind = intent.getStringExtra(HostControlBridge.EXTRA_KIND)
                            ?: HostControlBridge.KIND_MANUAL,
                        time = System.currentTimeMillis(),
                    ),
                )
                // 首页统计卡「已拦截次数」累计（弹窗部分）；记录只留最近 20 条，计数不封顶
                HostPrefs.addPopupInterceptCount(app)
            }

            HostControlBridge.ACTION_CONTROL_INTERCEPTED -> {
                val token = intent.getStringExtra(HostControlBridge.EXTRA_TOKEN)
                if (token != HostControlBridge.CONTROL_TOKEN) {
                    GvLog.w(TAG, "reject control intercepted: bad token")
                    return
                }
                val pkg = intent.getStringExtra(HostControlBridge.EXTRA_PACKAGE).orEmpty()
                if (pkg.isBlank()) return
                // 首页统计卡「已拦截次数」累计（控件部分：编辑模式点击被消费）
                HostPrefs.addControlInterceptCount(app)
                GvLog.d(TAG, "control intercepted pkg=$pkg")
            }

            HostControlBridge.ACTION_POPUP_INTERCEPT_REQUEST -> {
                val token = intent.getStringExtra(HostControlBridge.EXTRA_TOKEN)
                if (token != HostControlBridge.CONTROL_TOKEN) {
                    GvLog.w(TAG, "reject popup intercept request: bad token")
                    return
                }
                val pkg = intent.getStringExtra(HostControlBridge.EXTRA_PACKAGE).orEmpty()
                if (pkg.isBlank()) return
                // 记下拉取过的包：开关切换时向它们主动推送最新开关状态
                HostPrefs.addPopupInterceptWatcher(app, pkg)
                val enabled = HostPrefs.isPopupInterceptEnabled(app)
                HostControlBridge.dispatchPopupInterceptState(
                    app, pkg, enabled, HostPrefs.getPopupWindowSecondsFor(app, pkg),
                )
                GvLog.d(TAG, "popup intercept requested pkg=$pkg -> $enabled")
            }

            else -> Unit
        }
    }

    companion object {
        /** 区分宿主/目标/通知三个进程侧的 Control 日志（原同名 tag 在 logcat 撞车） */
        private const val TAG = "Control.Host"
    }
}
