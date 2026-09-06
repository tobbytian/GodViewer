package com.godviewer.app.host.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.godviewer.app.R
import com.godviewer.app.host.control.HostControlNotifier
import com.godviewer.app.host.diag.HostCrashStore
import com.godviewer.app.host.diag.HostDiagStore
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.diag.CrashReportProtocol
import com.godviewer.app.shared.diag.DiagReportProtocol
import com.godviewer.app.shared.entry.EntryMode

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
                // 把当前入口模式推回目标，避免目标进程读不到 prefs 仍发自己的通知
                EntryMode.pushToTarget(app, pkg)
                // 仅本体入口展示控制通知；目标入口下 refresh 内部会 cancel
                HostControlNotifier.refresh(app)
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
                if (target.editEnabled) {
                    GvLog.d(TAG, "enable click: already on pkg=${target.packageName}, refresh only")
                    HostControlNotifier.refresh(app)
                    return
                }
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

            else -> Unit
        }
    }

    companion object {
        private const val TAG = "Control"
    }
}
