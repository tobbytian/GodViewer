package com.godviewer.app.host.io

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.godviewer.app.BuildConfig
import com.godviewer.app.shared.GvLog
import java.io.File

/**
 * 规则导入导出的传输通道：剪贴板复制/粘贴、SAF 读写、系统分享（FileProvider）。
 * 全部 runCatching 防御——传输失败只返回 false/ null，绝不让宿主崩溃。
 */
object RuleTransfer {
    private const val TAG = "RuleTransfer"

    const val FILE_PROVIDER_AUTHORITY = "${BuildConfig.PACKAGE_NAME}.fileprovider"
    private const val SHARE_DIR = "godviewer-share"
    private const val CLIP_LABEL = "godviewer-rules"

    // ---------- 剪贴板 ----------

    fun copyToClipboard(context: Context, json: String): Boolean = runCatching {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, json))
        true
    }.getOrElse {
        GvLog.w(TAG, "copy to clipboard failed", it)
        false
    }

    /** 读剪贴板文本；空/不可读返回 null */
    fun pasteFromClipboard(context: Context): String? = runCatching {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    // ---------- SAF 文件 ----------

    /** 从 Uri 读文本（导入）；失败返回 null */
    fun readText(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 写文本到 Uri（导出，SAF CREATE_DOCUMENT 返回的目标） */
    fun writeText(context: Context, uri: Uri, json: String): Boolean = runCatching {
        // "wt" 截断写：目标文件已存在时不能残留旧内容
        val ok = context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.flush()
            true
        } ?: false
        ok
    }.getOrElse {
        GvLog.w(TAG, "write to $uri failed", it)
        false
    }

    // ---------- 系统分享 ----------

    /**
     * ACTION_SEND 分享 JSON：落 cache 临时文件经 FileProvider 授权流式分享
     * （超大 JSON 塞 EXTRA_TEXT 会被部分应用截断）。返回是否成功拉起选择器。
     */
    fun shareJson(context: Context, json: String, chooserTitle: String): Boolean {
        return runCatching {
            val dir = File(context.cacheDir, SHARE_DIR)
            if (!dir.exists()) dir.mkdirs()
            // 清理上次分享的残留，避免 cache 无限增长
            dir.listFiles()?.forEach { if (it.name != "keep") runCatching { it.delete() } }
            val file = File(dir, "godviewer-rules-${System.currentTimeMillis()}.json")
            file.writeText(json, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, chooserTitle)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrElse {
            GvLog.w(TAG, "share failed", it)
            false
        }
    }
}