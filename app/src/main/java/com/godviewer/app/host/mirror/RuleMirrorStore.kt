package com.godviewer.app.host.mirror

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.mirror.MirrorFile
import com.godviewer.app.shared.mirror.MirroredPackage
import com.godviewer.app.shared.mirror.RuleMirrorCodec
import com.godviewer.app.shared.mirror.RuleMirrorProtocol
import com.godviewer.app.shared.mirror.mirrorThumbnailKey
import com.godviewer.app.shared.mirror.sanitizeMirrorPackageName
import com.godviewer.app.shared.model.ViewRule
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileOutputStream

/**
 * 宿主进程：接收镜像 JSON 后落盘，并提供列表/详情读取。
 *
 * 第二阶段迁包时预期落入 `host` 侧。
 */
internal object RuleMirrorStore {
    private const val TAG = "Mirror"
    private val gson: Gson = GsonBuilder().create()

    fun writeMirror(context: Context, packageName: String, json: String): Boolean {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return false
        if (json.isBlank() || json.length > 2 * 1024 * 1024) {
            return false
        }
        val parsed = runCatching {
            gson.fromJson(json, MirrorFile::class.java)
        }.getOrNull() ?: return false
        val pkg = sanitizeMirrorPackageName(parsed.packageName ?: safePkg) ?: return false
        val rules = parsed.rules ?: emptyList()

        return runCatching {
            val dir = packageDir(context, pkg)
            if (rules.isEmpty()) {
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
                GvLog.d(TAG, "mirror cleared: $pkg")
                return@runCatching true
            }
            if (!dir.exists()) {
                dir.mkdirs()
            }

            // Persist JSON without heavy base64 blobs (blobs go to files)
            val stored = MirrorFile(
                schemaVersion = 1,
                packageName = pkg,
                appLabel = parsed.appLabel?.takeIf { it.isNotBlank() },
                updatedAt = if (parsed.updatedAt > 0L) parsed.updatedAt else System.currentTimeMillis(),
                appIconPngBase64 = null,
                thumbnails = null,
                rules = rules,
            )
            val file = File(dir, RuleMirrorProtocol.RULES_FILE)
            val tmp = File(dir, "${RuleMirrorProtocol.RULES_FILE}.tmp")
            val outJson = gson.toJson(stored)
            FileOutputStream(tmp).use { out ->
                out.write(outJson.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }

            RuleMirrorCodec.decodeBase64ToFile(
                parsed.appIconPngBase64,
                File(dir, RuleMirrorProtocol.ICON_FILE),
            )

            val thumbDir = File(dir, RuleMirrorProtocol.THUMB_DIR)
            if (!parsed.thumbnails.isNullOrEmpty()) {
                if (!thumbDir.exists()) thumbDir.mkdirs()
                val keep = parsed.thumbnails.keys
                thumbDir.listFiles()?.forEach { f ->
                    val name = f.name.removeSuffix(".png")
                    if (name !in keep) f.delete()
                }
                parsed.thumbnails.forEach { (key, b64) ->
                    if (key.matches(Regex("^[a-f0-9]{8,64}$"))) {
                        RuleMirrorCodec.decodeBase64ToFile(b64, File(thumbDir, "$key.png"))
                    }
                }
            }

            GvLog.d(TAG, "mirror written: $pkg rules=${rules.size} label=${stored.appLabel}")
            true
        }.getOrDefault(false)
    }

    fun listPackages(context: Context): List<MirroredPackage> {
        val root = mirrorRoot(context)
        if (!root.exists()) {
            return emptyList()
        }
        val pm = context.packageManager
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val pkg = sanitizeMirrorPackageName(dir.name) ?: return@mapNotNull null
                val file = File(dir, RuleMirrorProtocol.RULES_FILE)
                if (!file.exists()) {
                    return@mapNotNull null
                }
                val mirror = runCatching {
                    gson.fromJson(file.readText(), MirrorFile::class.java)
                }.getOrNull()
                val rules = mirror?.rules.orEmpty()
                if (rules.isEmpty()) {
                    return@mapNotNull null
                }
                val pmLabel = runCatching {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(ai).toString()
                }.getOrNull()
                val label = pmLabel
                    ?: mirror?.appLabel?.takeIf { it.isNotBlank() }
                    ?: pkg
                val pmIcon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
                val fileIcon = loadIconDrawable(context, dir)
                val icon = pmIcon ?: fileIcon
                val isSystem = runCatching {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                }.getOrDefault(false)
                MirroredPackage(
                    packageName = pkg,
                    label = label,
                    ruleCount = rules.size,
                    updatedAt = mirror?.updatedAt
                        ?: rules.maxOfOrNull { it.timestamp }
                        ?: file.lastModified(),
                    icon = icon,
                    isSystem = isSystem,
                )
            }
            ?.sortedByDescending { it.updatedAt }
            .orEmpty()
    }

    fun loadRules(context: Context, packageName: String): List<ViewRule> {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return emptyList()
        val file = File(packageDir(context, safePkg), RuleMirrorProtocol.RULES_FILE)
        if (!file.exists()) {
            return emptyList()
        }
        return runCatching {
            gson.fromJson(file.readText(), MirrorFile::class.java)?.rules.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun loadAppLabel(context: Context, packageName: String): String {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return packageName
        val fromPm = runCatching {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(safePkg, 0)
            pm.getApplicationLabel(ai).toString()
        }.getOrNull()
        if (!fromPm.isNullOrBlank()) return fromPm
        val file = File(packageDir(context, safePkg), RuleMirrorProtocol.RULES_FILE)
        if (file.exists()) {
            val mirror = runCatching {
                gson.fromJson(file.readText(), MirrorFile::class.java)
            }.getOrNull()
            mirror?.appLabel?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return safePkg
    }

    fun loadAppIcon(context: Context, packageName: String): Drawable? {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return null
        runCatching {
            return context.packageManager.getApplicationIcon(safePkg)
        }
        return loadIconDrawable(context, packageDir(context, safePkg))
    }

    fun loadThumbnail(context: Context, packageName: String, rule: ViewRule): Bitmap? {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return null
        val key = mirrorThumbnailKey(rule)
        val file = File(
            File(packageDir(context, safePkg), RuleMirrorProtocol.THUMB_DIR),
            "$key.png",
        )
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun packageCount(context: Context): Int = listPackages(context).size

    private fun mirrorRoot(context: Context): File =
        File(context.applicationContext.filesDir, RuleMirrorProtocol.MIRROR_DIR)

    private fun packageDir(context: Context, packageName: String): File =
        File(mirrorRoot(context), packageName)

    private fun loadIconDrawable(context: Context, dir: File): Drawable? {
        val file = File(dir, RuleMirrorProtocol.ICON_FILE)
        if (!file.exists()) return null
        val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: return null
        return BitmapDrawable(context.resources, bmp)
    }
}
