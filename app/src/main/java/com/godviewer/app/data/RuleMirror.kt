package com.godviewer.app.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import android.util.Log
import com.godviewer.app.BuildConfig
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Cross-process rule mirror: target apps push a JSON copy (+ optional icon/thumbs)
 * to the host app via an explicit broadcast; the host stores mirrors under its own
 * filesDir and can list them.
 *
 * Authoritative rules remain in each target app's private [RuleStore].
 */
object RuleMirror {
    private const val TAG = "GodViewer.Mirror"

    const val ACTION_MIRROR_RULES = "${BuildConfig.PACKAGE_NAME}.ACTION_MIRROR_RULES"
    const val EXTRA_PACKAGE = "package_name"
    const val EXTRA_JSON = "json"
    /** Lightweight shared token; not a secret, just blocks casual junk writes. */
    const val EXTRA_TOKEN = "token"
    const val MIRROR_TOKEN = "godviewer-rule-mirror-v1"

    private const val RECEIVER_CLASS = "com.godviewer.app.data.RuleMirrorReceiver"
    private const val MIRROR_DIR = "godviewer/mirror"
    private const val RULES_FILE = "rules.json"
    private const val ICON_FILE = "icon.png"
    private const val THUMB_DIR = "thumbs"

    /** Keep broadcast payload under Binder limits. */
    private const val MAX_JSON_BYTES = 700 * 1024
    private const val MAX_THUMB_BYTES_TOTAL = 400 * 1024
    private const val MAX_SINGLE_THUMB_BYTES = 80 * 1024

    private val gson: Gson = GsonBuilder().create()

    // ---------- Target process: push after local save ----------

    /**
     * Best-effort sync from the injected target process. Never throws into the target app.
     */
    fun pushFromTarget(context: Context, packageName: String, rules: List<ViewRule>) {
        runCatching {
            val safePkg = sanitizePackageName(packageName) ?: return
            val app = context.applicationContext
            val label = resolveLabel(app, safePkg)
            val iconB64 = encodeAppIcon(app, safePkg)
            val thumbs = collectThumbnails(rules)

            var payload = MirrorFile(
                schemaVersion = 1,
                packageName = safePkg,
                appLabel = label,
                updatedAt = System.currentTimeMillis(),
                appIconPngBase64 = iconB64,
                thumbnails = thumbs,
                rules = rules,
            )
            var json = gson.toJson(payload)
            // Drop heavy optional blobs if binder payload would be too large
            if (json.length > MAX_JSON_BYTES && !thumbs.isNullOrEmpty()) {
                payload = payload.copy(thumbnails = emptyMap())
                json = gson.toJson(payload)
            }
            if (json.length > MAX_JSON_BYTES && !iconB64.isNullOrBlank()) {
                payload = payload.copy(appIconPngBase64 = null)
                json = gson.toJson(payload)
            }
            if (json.length > MAX_JSON_BYTES) {
                Log.w(TAG, "mirror json still too large (${json.length}), skip push")
                return
            }

            val intent = Intent(ACTION_MIRROR_RULES).apply {
                component = ComponentName(BuildConfig.PACKAGE_NAME, RECEIVER_CLASS)
                putExtra(EXTRA_PACKAGE, safePkg)
                putExtra(EXTRA_JSON, json)
                putExtra(EXTRA_TOKEN, MIRROR_TOKEN)
            }
            app.sendBroadcast(intent)
            Log.d(TAG, "mirror push sent: $safePkg rules=${rules.size} label=$label")
        }.onFailure {
            Log.w(TAG, "mirror push failed", it)
        }
    }

    private fun resolveLabel(context: Context, packageName: String): String {
        return runCatching {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        }.getOrDefault(packageName)
    }

    private fun encodeAppIcon(context: Context, packageName: String): String? {
        return runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = drawableToBitmap(drawable, 96)
            bitmapToBase64Png(bitmap, quality = 90)
        }.getOrNull()
    }

    private fun collectThumbnails(rules: List<ViewRule>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var total = 0
        for (rule in rules) {
            val bmp = ViewRuleManager.thumbnailFor(rule) ?: continue
            val key = thumbnailKey(rule)
            val b64 = runCatching { bitmapToBase64Png(bmp, quality = 85) }.getOrNull() ?: continue
            val bytes = b64.length
            if (bytes > MAX_SINGLE_THUMB_BYTES) continue
            if (total + bytes > MAX_THUMB_BYTES_TOTAL) break
            out[key] = b64
            total += bytes
        }
        return out
    }

    fun thumbnailKey(rule: ViewRule): String {
        val raw = "${rule.activityClass}|${rule.viewClass}|${rule.depth.joinToString(",")}"
        return sha256(raw).take(16)
    }

    // ---------- Host process: receive & read ----------

    fun writeMirror(context: Context, packageName: String, json: String): Boolean {
        val safePkg = sanitizePackageName(packageName) ?: return false
        if (json.isBlank() || json.length > 2 * 1024 * 1024) {
            return false
        }
        val parsed = runCatching {
            gson.fromJson(json, MirrorFile::class.java)
        }.getOrNull() ?: return false
        val pkg = sanitizePackageName(parsed.packageName ?: safePkg) ?: safePkg
        val rules = parsed.rules ?: emptyList()

        return runCatching {
            val dir = packageDir(context, pkg)
            if (rules.isEmpty()) {
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
                Log.d(TAG, "mirror cleared: $pkg")
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
            val file = File(dir, RULES_FILE)
            val tmp = File(dir, "$RULES_FILE.tmp")
            val outJson = gson.toJson(stored)
            FileOutputStream(tmp).use { out ->
                out.write(outJson.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }

            // Icon
            decodeBase64ToFile(parsed.appIconPngBase64, File(dir, ICON_FILE))

            // Thumbnails
            val thumbDir = File(dir, THUMB_DIR)
            if (!parsed.thumbnails.isNullOrEmpty()) {
                if (!thumbDir.exists()) thumbDir.mkdirs()
                // Remove stale thumbs not in the new set
                val keep = parsed.thumbnails.keys
                thumbDir.listFiles()?.forEach { f ->
                    val name = f.name.removeSuffix(".png")
                    if (name !in keep) f.delete()
                }
                parsed.thumbnails.forEach { (key, b64) ->
                    if (key.matches(Regex("^[a-f0-9]{8,64}$"))) {
                        decodeBase64ToFile(b64, File(thumbDir, "$key.png"))
                    }
                }
            }

            Log.d(TAG, "mirror written: $pkg rules=${rules.size} label=${stored.appLabel}")
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
                val pkg = sanitizePackageName(dir.name) ?: return@mapNotNull null
                val file = File(dir, RULES_FILE)
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
        val safePkg = sanitizePackageName(packageName) ?: return emptyList()
        val file = File(packageDir(context, safePkg), RULES_FILE)
        if (!file.exists()) {
            return emptyList()
        }
        return runCatching {
            gson.fromJson(file.readText(), MirrorFile::class.java)?.rules.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun loadAppLabel(context: Context, packageName: String): String {
        val safePkg = sanitizePackageName(packageName) ?: return packageName
        runCatching {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(safePkg, 0)
            return pm.getApplicationLabel(ai).toString()
        }
        val file = File(packageDir(context, safePkg), RULES_FILE)
        if (file.exists()) {
            val mirror = runCatching {
                gson.fromJson(file.readText(), MirrorFile::class.java)
            }.getOrNull()
            mirror?.appLabel?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return safePkg
    }

    fun loadAppIcon(context: Context, packageName: String): Drawable? {
        val safePkg = sanitizePackageName(packageName) ?: return null
        runCatching {
            return context.packageManager.getApplicationIcon(safePkg)
        }
        return loadIconDrawable(context, packageDir(context, safePkg))
    }

    fun loadThumbnail(context: Context, packageName: String, rule: ViewRule): Bitmap? {
        val safePkg = sanitizePackageName(packageName) ?: return null
        val key = thumbnailKey(rule)
        val file = File(File(packageDir(context, safePkg), THUMB_DIR), "$key.png")
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun packageCount(context: Context): Int = listPackages(context).size

    private fun mirrorRoot(context: Context): File =
        File(context.applicationContext.filesDir, MIRROR_DIR)

    private fun packageDir(context: Context, packageName: String): File =
        File(mirrorRoot(context), packageName)

    private fun loadIconDrawable(context: Context, dir: File): Drawable? {
        val file = File(dir, ICON_FILE)
        if (!file.exists()) return null
        val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: return null
        return BitmapDrawable(context.resources, bmp)
    }

    private fun decodeBase64ToFile(b64: String?, file: File) {
        if (b64.isNullOrBlank()) return
        runCatching {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            if (bytes.isEmpty() || bytes.size > 512 * 1024) return
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun drawableToBitmap(drawable: Drawable, max: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return scaleDown(drawable.bitmap, max)
        }
        val w = (drawable.intrinsicWidth.takeIf { it > 0 } ?: max).coerceAtMost(max)
        val h = (drawable.intrinsicHeight.takeIf { it > 0 } ?: max).coerceAtMost(max)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun scaleDown(bitmap: Bitmap, max: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= max && height <= max) return bitmap
        val scale = max.toFloat() / maxOf(width, height)
        val newWidth = (width * scale).toInt().coerceAtLeast(1)
        val newHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun bitmapToBase64Png(bitmap: Bitmap, quality: Int): String {
        val scaled = scaleDown(bitmap, 128)
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, quality, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun sha256(input: String): String = try {
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    } catch (e: Exception) {
        input.hashCode().toString().replace("-", "n")
    }

    fun sanitizePackageName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (raw.length > 200) return null
        if (!PACKAGE_REGEX.matches(raw)) return null
        if (raw.contains("..")) return null
        return raw
    }

    private val PACKAGE_REGEX =
        Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$|^[A-Za-z][A-Za-z0-9_]+$")

    data class MirrorFile(
        @SerializedName("schema_version") val schemaVersion: Int = 1,
        @SerializedName("package_name") val packageName: String? = null,
        @SerializedName("app_label") val appLabel: String? = null,
        @SerializedName("updated_at") val updatedAt: Long = 0L,
        @SerializedName("app_icon_png_base64") val appIconPngBase64: String? = null,
        @SerializedName("thumbnails") val thumbnails: Map<String, String>? = null,
        @SerializedName("rules") val rules: List<ViewRule>? = emptyList(),
    )

    data class MirroredPackage(
        val packageName: String,
        val label: String,
        val ruleCount: Int,
        val updatedAt: Long,
        val icon: Drawable?,
        val isSystem: Boolean,
    )
}
