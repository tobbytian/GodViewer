package com.godviewer.app.target.rule

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import com.godviewer.app.shared.ViewSnapshot
import com.godviewer.app.shared.model.ViewRule
import com.godviewer.app.shared.sha256
import java.io.File
import java.io.FileOutputStream

/**
 * 目标进程规则缩略图：内存缓存 + files/godviewer/thumbnails/ 落盘。
 *
 * 在 [ViewRuleManager.createRule] 时截取（视图仍可见）；列表与镜像推送只读缓存。
 * 第二阶段迁包时预期落入 `target`。
 */
internal object ViewRuleThumbnails {

    private var appContext: Context? = null
    private val thumbnails = HashMap<ViewRule.RuleKey, Bitmap>()

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 规则对应的缩略图：内存缓存 → 磁盘文件 → null。
     */
    fun thumbnailFor(rule: ViewRule): Bitmap? {
        thumbnails[rule.key()]?.let { return it }
        val file = thumbnailFile(rule.key()) ?: return null
        if (!file.exists()) {
            return null
        }
        return runCatching {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                thumbnails[rule.key()] = bitmap
            }
            bitmap
        }.getOrNull()
    }

    /**
     * 截取并持久化缩略图（视图须已布局且当前可见）。已有缩略图时跳过。
     */
    fun capture(view: View, rule: ViewRule) {
        if (thumbnailFor(rule) != null) {
            return
        }
        if (view.visibility == View.GONE || !view.isLaidOut || view.width <= 0 || view.height <= 0) {
            return
        }
        runCatching {
            val bitmap = ViewSnapshot.capture(view, maxEdge = 256) ?: return@runCatching
            thumbnails[rule.key()] = bitmap
            saveThumbnailToFile(rule.key(), bitmap)
        }
    }

    fun remove(rule: ViewRule) {
        val key = rule.key()
        thumbnails.remove(key)
        thumbnailFile(key)?.delete()
    }

    private val thumbnailDir: File?
        get() = appContext?.let { File(File(it.filesDir, "godviewer"), "thumbnails") }

    private fun thumbnailFile(key: ViewRule.RuleKey): File? {
        val dir = thumbnailDir ?: return null
        return File(dir, thumbnailName(key))
    }

    /** 规则键 → 文件名：键内容 SHA-256 前 16 位十六进制 + .png */
    private fun thumbnailName(key: ViewRule.RuleKey): String {
        val raw = "${key.activityClass}|${key.viewClass}|${key.depth.joinToString(",")}"
        return sha256(raw).take(16) + ".png"
    }

    private fun saveThumbnailToFile(key: ViewRule.RuleKey, bitmap: Bitmap) {
        val file = thumbnailFile(key) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }
}
