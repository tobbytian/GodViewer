package com.godviewer.app.host.control

import android.content.Context
import com.godviewer.app.shared.GvLog
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File

/**
 * 弹窗拦截记录：目标端每关闭一个弹窗（自动重放或手动）就上报一条，
 * 宿主只留最近 [MAX_ENTRIES] 条，存 `filesDir/godviewer/popup_log.json`。
 * 仅用于让用户确认功能在工作、排查误杀，不参与任何匹配逻辑。
 */
object PopupLogStore {
    private const val TAG = "PopupLog"
    private const val FILE_NAME = "popup_log.json"
    const val MAX_ENTRIES = 20

    data class Entry(
        @SerializedName("package_name") val packageName: String,
        @SerializedName("label") val label: String,
        @SerializedName("kind") val kind: String,
        @SerializedName("time") val time: Long,
    )

    private data class LogFile(
        @SerializedName("entries") val entries: List<Entry> = emptyList(),
    )

    private val gson = Gson()

    @Synchronized
    fun add(context: Context, entry: Entry) {
        val list = (listOf(entry) + load(context)).take(MAX_ENTRIES)
        save(context, list)
    }

    /** 最新的在前 */
    @Synchronized
    fun all(context: Context): List<Entry> = load(context)

    @Synchronized
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) =
        File(File(context.applicationContext.filesDir, "godviewer"), FILE_NAME)

    private fun load(context: Context): List<Entry> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            gson.fromJson(f.readText(), LogFile::class.java)?.entries.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, list: List<Entry>) {
        runCatching {
            val f = file(context)
            f.parentFile?.mkdirs()
            f.writeText(gson.toJson(LogFile(list)))
        }.onFailure { GvLog.w(TAG, "save popup log failed", it) }
    }
}
