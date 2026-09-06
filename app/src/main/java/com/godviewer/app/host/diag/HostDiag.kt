package com.godviewer.app.host.diag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.godviewer.app.BuildConfig
import com.godviewer.app.host.prefs.HostPrefs
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.diag.DiagBuffer
import com.godviewer.app.shared.entry.EntryMode
import com.godviewer.app.util.ModuleStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 宿主进程诊断文本：设置页「复制诊断信息」用。
 *
 * 主体是**全量调试日志**（宿主 ring + 最近一次目标推送的 GvLog ring），
 * 闪退副本仅作附录。
 */
object HostDiag {
    fun buildReport(context: Context): String {
        val app = context.applicationContext
        DiagBuffer.bindProcess(app.packageName, app.packageName)
        val target = HostControlBridge.currentTarget(app)
        val diagPkg = HostDiagStore.lastPackage(app)
        val diagTime = HostDiagStore.lastTimeMs(app)
        val diagTimeStr = if (diagTime > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(diagTime))
        } else {
            "none"
        }
        val crashPkg = HostCrashStore.lastPackage(app)
        val crashTime = HostCrashStore.lastTimeMs(app)
        val crashTimeStr = if (crashTime > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(crashTime))
        } else {
            "none"
        }
        val extra = linkedMapOf(
            "kind" to "host-feedback-full-debug",
            "activated" to ModuleStatus.isActivated().toString(),
            "entryMode" to EntryMode.current(app),
            "hideIcon" to HostPrefs.isLauncherIconHidden(app).toString(),
            "autoUpdate" to HostPrefs.isAutoUpdateEnabled(app).toString(),
            "lastTarget" to (target?.let { "${it.packageName} edit=${it.editEnabled}" } ?: "none"),
            "lastTargetDiagPkg" to (diagPkg ?: "none"),
            "lastTargetDiagTime" to diagTimeStr,
            "lastTargetDiagStage" to (HostDiagStore.lastStage(app) ?: "none"),
            "lastTargetDiagLines" to HostDiagStore.lastLineCount(app).toString(),
            "lastCrashPkg" to (crashPkg ?: "none"),
            "lastCrashTime" to crashTimeStr,
            "lastCrashSummary" to (HostCrashStore.lastSummary(app) ?: "none"),
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to "${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})",
        )
        val hostRing = DiagBuffer.snapshot(headerExtra = extra)
        val targetDiag = HostDiagStore.readLatest(app)
        val crashBody = HostCrashStore.readLatest(app)
        return buildString {
            appendLine(hostRing)
            appendLine()
            if (!targetDiag.isNullOrBlank()) {
                appendLine("######## LAST TARGET FULL DEBUG LOG (host copy) ########")
                appendLine(targetDiag.trimEnd())
                appendLine("######## END TARGET FULL DEBUG LOG ########")
            } else {
                appendLine("######## LAST TARGET FULL DEBUG LOG ########")
                appendLine("(none yet — open a scoped app so the module can push GvLog ring,")
                appendLine(" then copy again. Pushed on boot / key stages / resume, not only on crash.)")
                appendLine("######## END TARGET FULL DEBUG LOG ########")
            }
            appendLine()
            if (!crashBody.isNullOrBlank()) {
                appendLine("######## LAST TARGET CRASH (appendix) ########")
                appendLine(crashBody.trimEnd())
                appendLine("######## END TARGET CRASH ########")
            } else {
                appendLine("######## LAST TARGET CRASH (appendix) ########")
                appendLine("(none)")
                appendLine("######## END TARGET CRASH ########")
            }
            appendLine()
            appendLine("--- optional adb ---")
            appendLine("adb logcat -d | findstr /i \"GodViewer AndroidRuntime FATAL\"")
            appendLine("adb shell run-as <target.pkg> cat files/godviewer/diag-ring.txt")
            appendLine("adb shell run-as <target.pkg> cat files/godviewer/last-crash.txt")
            appendLine("module ${BuildConfig.PACKAGE_NAME} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) debug=${BuildConfig.DEBUG}")
        }
    }

    fun copyToClipboard(context: Context): Boolean {
        return runCatching {
            val text = buildReport(context)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("GodViewer diag", text))
            true
        }.getOrDefault(false)
    }

    /** 清空宿主 ring + 目标 debug / 闪退副本。不影响目标 rules。 */
    fun clearLogs(context: Context): Boolean {
        return runCatching {
            val app = context.applicationContext
            DiagBuffer.bindProcess(app.packageName, app.packageName)
            DiagBuffer.clear()
            val diagOk = HostDiagStore.clearAll(app)
            val crashOk = HostCrashStore.clearAll(app)
            DiagBuffer.append(
                "I",
                "HostDiag",
                "full debug logs cleared diagStoreOk=$diagOk crashStoreOk=$crashOk",
            )
            true
        }.getOrDefault(false)
    }
}
