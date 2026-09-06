package com.godviewer.app.host.diag

import android.content.Context
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.diag.CrashReportProtocol
import com.godviewer.app.shared.mirror.sanitizeMirrorPackageName
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 宿主落盘目标进程上报的闪退副本：
 * `files/godviewer/target-crashes/<pkg>/last-crash.txt`
 * 以及 `latest.txt` 指向最近一次（任意包）。
 */
object HostCrashStore {
    private const val TAG = "CrashStore"
    private const val ROOT = "godviewer/target-crashes"
    private const val FILE_LAST = "last-crash.txt"
    private const val FILE_LATEST = "latest.txt"
    private const val MAX_PACKAGES = 20

    data class CrashMeta(
        val packageName: String,
        val processName: String,
        val threadName: String,
        val summary: String,
        val timeMs: Long,
        val moduleVc: Int,
        val moduleVn: String,
        val stack: String,
        val ring: String,
    )

    fun save(context: Context, meta: CrashMeta): Boolean {
        return runCatching {
            val safePkg = sanitizeMirrorPackageName(meta.packageName) ?: meta.packageName
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(120)
                .ifBlank { "unknown" }
            val root = File(context.applicationContext.filesDir, ROOT)
            val dir = File(root, safePkg)
            if (!dir.exists()) dir.mkdirs()

            val body = formatBody(meta)
            File(dir, FILE_LAST).writeText(body)
            File(root, FILE_LATEST).writeText(body)

            // 简单 prefs 索引，便于列表
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_PKG, safePkg)
                .putLong(KEY_LAST_TIME, meta.timeMs)
                .putString(KEY_LAST_SUMMARY, meta.summary.take(400))
                .apply()

            pruneOld(root)
            GvLog.i(
                TAG,
                "saved target crash pkg=$safePkg summary=${meta.summary.take(120)}",
            )
            true
        }.onFailure {
            GvLog.w(TAG, "save crash failed", it)
        }.getOrDefault(false)
    }

    fun lastPackage(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_PKG, null)

    fun lastSummary(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SUMMARY, null)

    fun lastTimeMs(context: Context): Long =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_TIME, 0L)

    /** 最近一次任意目标的崩溃全文；无则 null */
    fun readLatest(context: Context): String? {
        val f = File(context.applicationContext.filesDir, "$ROOT/$FILE_LATEST")
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()
    }

    fun readForPackage(context: Context, packageName: String): String? {
        val safe = sanitizeMirrorPackageName(packageName) ?: return null
        val f = File(context.applicationContext.filesDir, "$ROOT/$safe/$FILE_LAST")
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()
    }

    fun hasAny(context: Context): Boolean =
        readLatest(context)?.isNotBlank() == true

    /** 删除全部目标闪退副本与索引（设置「清空日志」）。 */
    fun clearAll(context: Context): Boolean {
        return runCatching {
            val app = context.applicationContext
            val root = File(app.filesDir, ROOT)
            if (root.exists()) {
                root.deleteRecursively()
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            GvLog.i(TAG, "cleared all target crash copies")
            true
        }.onFailure {
            GvLog.w(TAG, "clear crash store failed", it)
        }.getOrDefault(false)
    }

    private fun formatBody(meta: CrashMeta): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
            .format(Date(meta.timeMs.coerceAtLeast(0L)))
        return buildString {
            appendLine("=== GodViewer Target Crash (host copy) ===")
            appendLine("time=$ts")
            appendLine("package=${meta.packageName}")
            appendLine("process=${meta.processName}")
            appendLine("thread=${meta.threadName}")
            appendLine("module_vc=${meta.moduleVc} module_vn=${meta.moduleVn}")
            appendLine("summary=${meta.summary}")
            appendLine("--- stack ---")
            appendLine(meta.stack.trimEnd())
            if (meta.ring.isNotBlank()) {
                appendLine()
                appendLine("--- diag ring (truncated) ---")
                appendLine(meta.ring.trimEnd())
            }
            appendLine()
            appendLine(
                "(Also on device: /data/data/${meta.packageName}/files/godviewer/last-crash.txt)",
            )
        }
    }

    private fun pruneOld(root: File) {
        runCatching {
            val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }
                ?: return
            dirs.drop(MAX_PACKAGES).forEach { d ->
                d.deleteRecursively()
            }
        }
    }

    private const val PREFS = "godviewer_host_crash"
    private const val KEY_LAST_PKG = "last_pkg"
    private const val KEY_LAST_TIME = "last_time"
    private const val KEY_LAST_SUMMARY = "last_summary"
}
