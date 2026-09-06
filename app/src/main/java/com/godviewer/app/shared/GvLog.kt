package com.godviewer.app.shared

import android.util.Log
import com.godviewer.app.shared.diag.DiagBuffer

/**
 * 模块日志：同时写 logcat、libxposed 框架日志与 [DiagBuffer]（**全量 debug，不只闪退**）。
 *
 * LSPosed / logcat 搜 `GodViewer`。目标进程会把 ring 落盘 `diag-ring.txt`，
 * 并 best-effort 推到宿主；设置页可复制全量调试日志 / 清空。
 *
 * API 102 迁移：框架日志经 [frameworkSink]（GodViewerModule.onModuleLoaded 注入 `module.log`），
 * 不再使用旧 `XposedBridge.log`；宿主进程 sink 为空，仅 logcat + DiagBuffer。
 */
object GvLog {
    private const val PREFIX = "GodViewer"

    /** libxposed 框架日志出口；target 进程由 GodViewerModule 注入。 */
    @Volatile
    internal var frameworkSink: FrameworkSink? = null

    fun interface FrameworkSink {
        fun log(priority: Int, tag: String?, msg: String, tr: Throwable?)
    }

    fun d(tag: String, msg: String) {
        runCatching { Log.d(fullTag(tag), msg) }
        runCatching { frameworkSink?.log(Log.DEBUG, fullTag(tag), msg, null) }
        runCatching { DiagBuffer.append("D", fullTag(tag), msg) }
    }

    fun i(tag: String, msg: String) {
        runCatching { Log.i(fullTag(tag), msg) }
        runCatching { frameworkSink?.log(Log.INFO, fullTag(tag), msg, null) }
        runCatching { DiagBuffer.append("I", fullTag(tag), msg) }
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        runCatching { Log.w(fullTag(tag), msg, tr) }
        runCatching { frameworkSink?.log(Log.WARN, fullTag(tag), msg, tr) }
        runCatching { DiagBuffer.append("W", fullTag(tag), msg, tr) }
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        runCatching { Log.e(fullTag(tag), msg, tr) }
        runCatching { frameworkSink?.log(Log.ERROR, fullTag(tag), msg, tr) }
        runCatching { DiagBuffer.append("E", fullTag(tag), msg, tr) }
    }

    private fun fullTag(tag: String): String {
        return if (tag.startsWith(PREFIX)) tag else "$PREFIX.$tag"
    }
}
