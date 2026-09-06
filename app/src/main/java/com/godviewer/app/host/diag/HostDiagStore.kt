package com.godviewer.app.host.diag

import android.content.Context
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.mirror.sanitizeMirrorPackageName
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 宿主落盘目标进程推送的**全量调试日志**副本：
 * `files/godviewer/target-diags/<pkg>/last-diag.txt`
 * 以及 `latest.txt` 指向最近一次（任意包）。
 *
 * 与 [HostCrashStore] 分开：日常 debug 用这里，FATAL 用 crash store。
 */
object HostDiagStore {
    private const val TAG = "DiagStore"
    private const val ROOT = "godviewer/target-diags"
    private const val FILE_LAST = "last-diag.txt"
    private const val FILE_LATEST = "latest.txt"
    private const val MAX_PACKAGES = 20

    data class DiagMeta(
        val packageName: String,
        val processName: String,
        val stage: String,
        val timeMs: Long,
        val moduleVc: Int,
        val moduleVn: String,
        val lineCount: Int,
        val body: String,
    )

    fun save(context: Context, meta: DiagMeta): Boolean {
        return runCatching {
            val safePkg = sanitizeMirrorPackageName(meta.packageName) ?: meta.packageName
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(120)
                .ifBlank { "unknown" }
            val root = File(context.applicationContext.filesDir, ROOT)
            val dir = File(root, safePkg)
            if (!dir.exists()) dir.mkdirs()

            val text = formatBody(meta)
            File(dir, FILE_LAST).writeText(text)
            File(root, FILE_LATEST).writeText(text)

            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_PKG, safePkg)
                .putLong(KEY_LAST_TIME, meta.timeMs)
                .putString(KEY_LAST_STAGE, meta.stage.take(200))
                .putInt(KEY_LAST_LINES, meta.lineCount)
                .apply()

            pruneOld(root)
            GvLog.d(
                TAG,
                "saved target diag pkg=$safePkg stage=${meta.stage} lines=${meta.lineCount}",
            )
            true
        }.onFailure {
            GvLog.w(TAG, "save diag failed", it)
        }.getOrDefault(false)
    }

    fun lastPackage(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_PKG, null)

    fun lastStage(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_STAGE, null)

    fun lastTimeMs(context: Context): Long =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_TIME, 0L)

    fun lastLineCount(context: Context): Int =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_LINES, 0)

    fun readLatest(context: Context): String? {
        val f = File(context.applicationContext.filesDir, "$ROOT/$FILE_LATEST")
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()
    }

    fun hasAny(context: Context): Boolean =
        readLatest(context)?.isNotBlank() == true

    fun clearAll(context: Context): Boolean {
        return runCatching {
            val app = context.applicationContext
            val root = File(app.filesDir, ROOT)
            if (root.exists()) root.deleteRecursively()
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            GvLog.i(TAG, "cleared all target diag copies")
            true
        }.onFailure {
            GvLog.w(TAG, "clear diag store failed", it)
        }.getOrDefault(false)
    }

    private fun formatBody(meta: DiagMeta): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
            .format(Date(meta.timeMs.coerceAtLeast(0L)))
        return buildString {
            appendLine("=== GodViewer Target Diag (host copy, full debug ring) ===")
            appendLine("time=$ts")
            appendLine("package=${meta.packageName}")
            appendLine("process=${meta.processName}")
            appendLine("stage=${meta.stage}")
            appendLine("lines=${meta.lineCount}")
            appendLine("module_vc=${meta.moduleVc} module_vn=${meta.moduleVn}")
            appendLine("--- ring ---")
            appendLine(meta.body.trimEnd())
            appendLine()
            appendLine(
                "(Also on device: /data/data/${meta.packageName}/files/godviewer/diag-ring.txt)",
            )
        }
    }

    private fun pruneOld(root: File) {
        runCatching {
            val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }
                ?: return
            dirs.drop(MAX_PACKAGES).forEach { d -> d.deleteRecursively() }
        }
    }

    private const val PREFS = "godviewer_host_diag"
    private const val KEY_LAST_PKG = "last_pkg"
    private const val KEY_LAST_TIME = "last_time"
    private const val KEY_LAST_STAGE = "last_stage"
    private const val KEY_LAST_LINES = "last_lines"
}
