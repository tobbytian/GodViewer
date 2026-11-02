package com.godviewer.app.shared.diag

import android.os.Build
import android.os.Process
import com.godviewer.app.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 进程内**全量调试**环形缓冲：GvLog 全级别写入（不只闪退）。
 * 目标：落盘 diag-ring / 推宿主；宿主：设置页复制反馈。
 *
 * 不做加密；体量有上限，过旧行会被挤掉。
 */
object DiagBuffer {
    private const val MAX_LINES = 480
    private const val MAX_LINE_CHARS = 900

    private val lines = ConcurrentLinkedDeque<String>()
    private val timeFmt = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    @Volatile
    private var packageName: String = "?"

    @Volatile
    private var processName: String = "?"

    @Volatile
    private var lastCrashSummary: String? = null

    fun bindProcess(pkg: String?, process: String?) {
        if (!pkg.isNullOrBlank()) packageName = pkg
        if (!process.isNullOrBlank()) processName = process
    }

    fun packageName(): String = packageName

    fun processName(): String = processName

    fun lastCrashSummary(): String? = lastCrashSummary

    fun append(level: String, tag: String, msg: String, tr: Throwable? = null) {
        val ts = timeFmt.get()?.format(Date()) ?: "?"
        val base = "$ts $level/$tag $msg"
        offer(base)
        if (tr != null) {
            offer("$ts $level/$tag ${tr.javaClass.name}: ${tr.message}")
            tr.stackTrace.take(18).forEach { el ->
                offer("    at $el")
            }
            tr.cause?.let { cause ->
                offer("$ts $level/$tag Caused by: ${cause.javaClass.name}: ${cause.message}")
                cause.stackTrace.take(8).forEach { el ->
                    offer("    at $el")
                }
            }
        }
    }

    fun markCrash(threadName: String?, tr: Throwable) {
        val summary = buildString {
            append(tr.javaClass.name)
            append(": ")
            append(tr.message ?: "")
            append(" @ ")
            append(threadName ?: "?")
        }
        lastCrashSummary = summary.take(400)
        append("E", "Crash", "uncaught on thread=${threadName ?: "?"}", tr)
    }

    fun snapshot(headerExtra: Map<String, String> = emptyMap()): String {
        val body = synchronized(lines) { lines.toList() }.joinToString("\n")
        return buildHeader(headerExtra) + body
    }

    /**
     * 总长超过 [maxChars] 时**丢最旧行、保最新行**的快照（与 `String.take` 相反）。
     * 诊断价值集中在最新行：ring 饱合后 `take()` 会把事故窗口整段砍掉。
     * 未超限时输出与 [snapshot] 逐字节一致。
     */
    fun snapshotCapped(headerExtra: Map<String, String> = emptyMap(), maxChars: Int): String {
        val header = buildHeader(headerExtra)
        val budget = maxChars - header.length
        // header 独占上限（实际 ~500 字符，max 为 28_000，不会发生）：宁可截断 header 也守住上限
        if (budget <= 0) return header.take(maxChars)
        val kept = ArrayList<String>()
        var used = 0
        synchronized(lines) {
            val iter = lines.descendingIterator()
            while (iter.hasNext()) {
                val line = iter.next()
                val cost = line.length + 1 // 预留换行符
                if (used + cost > budget) break
                used += cost
                kept.add(line)
            }
        }
        kept.reverse()
        return header + kept.joinToString("\n")
    }

    private fun buildHeader(headerExtra: Map<String, String>): String = buildString {
        appendLine("=== GodViewer Diag ===")
        appendLine("module=${BuildConfig.PACKAGE_NAME} vc=${BuildConfig.VERSION_CODE} vn=${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG}")
        appendLine("pkg=$packageName process=$processName pid=${Process.myPid()} uid=${Process.myUid()}")
        appendLine(
            "device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}",
        )
        headerExtra.forEach { (k, v) -> appendLine("$k=$v") }
        lastCrashSummary?.let { appendLine("lastCrash=$it") }
        appendLine("--- recent ---")
    }

    fun clear() {
        synchronized(lines) { lines.clear() }
        lastCrashSummary = null
    }

    fun size(): Int = synchronized(lines) { lines.size }

    private fun offer(raw: String) {
        val line = if (raw.length <= MAX_LINE_CHARS) raw else raw.take(MAX_LINE_CHARS) + "…"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) {
                lines.pollFirst()
            }
        }
    }
}
