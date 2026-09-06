package com.godviewer.app.shared.mirror

import android.graphics.drawable.Drawable
import com.godviewer.app.BuildConfig
import com.godviewer.app.shared.model.ViewRule
import com.godviewer.app.shared.sha256
import com.google.gson.annotations.SerializedName

/**
 * 规则镜像的跨进程协议与 DTO（host / target 共用）。
 *
 * 权威规则仍在目标进程 RuleStore；镜像只是 best-effort 副本。
 * action / token / 目录布局 / JSON 字段名对外冻结，改动需兼容层。
 */
object RuleMirrorProtocol {
    const val ACTION_MIRROR_RULES = "${BuildConfig.PACKAGE_NAME}.ACTION_MIRROR_RULES"
    const val EXTRA_PACKAGE = "package_name"
    const val EXTRA_JSON = "json"
    /** Lightweight shared token; not a secret, just blocks casual junk writes. */
    const val EXTRA_TOKEN = "token"
    const val MIRROR_TOKEN = "godviewer-rule-mirror-v1"

    /** Manifest 组件全名；显式广播必须与之一致（协议冻结）。 */
    const val RECEIVER_CLASS = "com.godviewer.app.data.RuleMirrorReceiver"

    const val MIRROR_DIR = "godviewer/mirror"
    const val RULES_FILE = "rules.json"
    const val ICON_FILE = "icon.png"
    const val THUMB_DIR = "thumbs"

    /** Keep broadcast payload under Binder limits (UTF-8 bytes, not chars). */
    const val MAX_JSON_BYTES = 700 * 1024
    const val MAX_THUMB_BYTES_TOTAL = 400 * 1024
    const val MAX_SINGLE_THUMB_BYTES = 80 * 1024
}

/**
 * 广播 JSON / 落盘 JSON 的镜像文件结构。
 * 落盘时会去掉 base64 大字段，icon/thumbs 另存文件。
 */
data class MirrorFile(
    @SerializedName("schema_version") val schemaVersion: Int = 1,
    @SerializedName("package_name") val packageName: String? = null,
    @SerializedName("app_label") val appLabel: String? = null,
    @SerializedName("updated_at") val updatedAt: Long = 0L,
    @SerializedName("app_icon_png_base64") val appIconPngBase64: String? = null,
    @SerializedName("thumbnails") val thumbnails: Map<String, String>? = null,
    @SerializedName("rules") val rules: List<ViewRule>? = emptyList(),
)

/** 宿主镜像包列表行。 */
data class MirroredPackage(
    val packageName: String,
    val label: String,
    val ruleCount: Int,
    val updatedAt: Long,
    val icon: Drawable?,
    val isSystem: Boolean,
)

/** 包名路径安全校验（防 `../` 写穿 mirror 目录）。 */
fun sanitizeMirrorPackageName(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    if (raw.length > 200) return null
    if (!PACKAGE_NAME_REGEX.matches(raw)) return null
    if (raw.contains("..")) return null
    return raw
}

/** @see sanitizeMirrorPackageName */
fun sanitizePackageName(raw: String?): String? = sanitizeMirrorPackageName(raw)

/** 规则 → 缩略图文件名键（与目标侧 thumbnail 命名算法一致的短 hash）。 */
fun mirrorThumbnailKey(rule: ViewRule): String {
    val raw = "${rule.activityClass}|${rule.viewClass}|${rule.depth.joinToString(",")}"
    return sha256(raw).take(16)
}

private val PACKAGE_NAME_REGEX =
    Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$|^[A-Za-z][A-Za-z0-9_]+$")
