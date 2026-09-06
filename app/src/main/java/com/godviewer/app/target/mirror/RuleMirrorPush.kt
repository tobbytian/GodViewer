package com.godviewer.app.target.mirror

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.godviewer.app.BuildConfig
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.mirror.MirrorFile
import com.godviewer.app.shared.mirror.RuleMirrorCodec
import com.godviewer.app.shared.mirror.RuleMirrorProtocol
import com.godviewer.app.shared.mirror.mirrorThumbnailKey
import com.godviewer.app.shared.mirror.sanitizeMirrorPackageName
import com.godviewer.app.shared.model.ViewRule
import com.godviewer.app.shared.resolveAppLabel
import com.godviewer.app.target.rule.RuleStore
import com.godviewer.app.target.rule.ViewRuleManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * 目标进程：本地 [RuleStore.save] 成功后 best-effort 推送镜像广播。
 *
 * 第二阶段迁包时预期落入 `target`（inject）侧。
 */
internal object RuleMirrorPush {
    private const val TAG = "Mirror"
    private val gson: Gson = GsonBuilder().create()

    /**
     * Best-effort sync from the injected target process. Never throws into the target app.
     */
    fun pushFromTarget(context: Context, packageName: String, rules: List<ViewRule>) {
        runCatching {
            val safePkg = sanitizeMirrorPackageName(packageName) ?: return
            val app = context.applicationContext
            val label = resolveAppLabel(app, safePkg)
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
            if (json.toByteArray(Charsets.UTF_8).size > RuleMirrorProtocol.MAX_JSON_BYTES &&
                thumbs.isNotEmpty()
            ) {
                payload = payload.copy(thumbnails = emptyMap())
                json = gson.toJson(payload)
            }
            if (json.toByteArray(Charsets.UTF_8).size > RuleMirrorProtocol.MAX_JSON_BYTES &&
                !iconB64.isNullOrBlank()
            ) {
                payload = payload.copy(appIconPngBase64 = null)
                json = gson.toJson(payload)
            }
            if (json.toByteArray(Charsets.UTF_8).size > RuleMirrorProtocol.MAX_JSON_BYTES) {
                GvLog.w(TAG, "mirror json still too large (${json.length}), skip push")
                return
            }

            val intent = Intent(RuleMirrorProtocol.ACTION_MIRROR_RULES).apply {
                component = ComponentName(
                    BuildConfig.PACKAGE_NAME,
                    RuleMirrorProtocol.RECEIVER_CLASS,
                )
                putExtra(RuleMirrorProtocol.EXTRA_PACKAGE, safePkg)
                putExtra(RuleMirrorProtocol.EXTRA_JSON, json)
                putExtra(RuleMirrorProtocol.EXTRA_TOKEN, RuleMirrorProtocol.MIRROR_TOKEN)
            }
            app.sendBroadcast(intent)
            GvLog.d(TAG, "mirror push sent: $safePkg rules=${rules.size} label=$label")
        }.onFailure {
            GvLog.w(TAG, "mirror push failed", it)
        }
    }

    private fun encodeAppIcon(context: Context, packageName: String): String? {
        return runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = RuleMirrorCodec.drawableToBitmap(drawable, 96)
            RuleMirrorCodec.bitmapToBase64Png(bitmap, quality = 90)
        }.getOrNull()
    }

    private fun collectThumbnails(rules: List<ViewRule>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var total = 0
        for (rule in rules) {
            val bmp = ViewRuleManager.thumbnailFor(rule) ?: continue
            val key = mirrorThumbnailKey(rule)
            val b64 = runCatching {
                RuleMirrorCodec.bitmapToBase64Png(bmp, quality = 85)
            }.getOrNull() ?: continue
            val bytes = b64.length
            if (bytes > RuleMirrorProtocol.MAX_SINGLE_THUMB_BYTES) continue
            if (total + bytes > RuleMirrorProtocol.MAX_THUMB_BYTES_TOTAL) break
            out[key] = b64
            total += bytes
        }
        return out
    }
}
