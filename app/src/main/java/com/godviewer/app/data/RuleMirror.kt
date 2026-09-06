package com.godviewer.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.godviewer.app.shared.mirror.MirrorFile
import com.godviewer.app.shared.mirror.MirroredPackage
import com.godviewer.app.shared.mirror.RuleMirrorProtocol
import com.godviewer.app.shared.mirror.mirrorThumbnailKey
import com.godviewer.app.shared.mirror.sanitizeMirrorPackageName
import com.godviewer.app.shared.model.ViewRule

/**
 * 规则镜像对外薄 facade（保持历史 call site：`RuleMirror.*`）。
 *
 * 实现已按职责拆到：
 * - [RuleMirrorProtocol] / [MirrorFile] / [MirroredPackage] — 协议与 DTO
 * - target [com.godviewer.app.target.mirror.RuleMirrorPush] — 目标进程推送
 * - host [com.godviewer.app.host.mirror.RuleMirrorStore] — 宿主读写
 *
 * 方法体内再引用 host/target 实现类，避免本 facade 类加载时
 * 在目标进程过早解析宿主 UI/Store 依赖图。
 *
 * 权威规则仍在各目标应用私有 RuleStore。
 */
object RuleMirror {
    const val ACTION_MIRROR_RULES = RuleMirrorProtocol.ACTION_MIRROR_RULES
    const val EXTRA_PACKAGE = RuleMirrorProtocol.EXTRA_PACKAGE
    const val EXTRA_JSON = RuleMirrorProtocol.EXTRA_JSON
    const val EXTRA_TOKEN = RuleMirrorProtocol.EXTRA_TOKEN
    const val MIRROR_TOKEN = RuleMirrorProtocol.MIRROR_TOKEN

    fun pushFromTarget(context: Context, packageName: String, rules: List<ViewRule>) {
        com.godviewer.app.target.mirror.RuleMirrorPush.pushFromTarget(context, packageName, rules)
    }

    fun writeMirror(context: Context, packageName: String, json: String): Boolean {
        return com.godviewer.app.host.mirror.RuleMirrorStore.writeMirror(context, packageName, json)
    }

    fun listPackages(context: Context): List<MirroredPackage> {
        return com.godviewer.app.host.mirror.RuleMirrorStore.listPackages(context)
    }

    fun loadRules(context: Context, packageName: String): List<ViewRule> {
        return com.godviewer.app.host.mirror.RuleMirrorStore.loadRules(context, packageName)
    }

    fun loadAppLabel(context: Context, packageName: String): String {
        return com.godviewer.app.host.mirror.RuleMirrorStore.loadAppLabel(context, packageName)
    }

    fun loadAppIcon(context: Context, packageName: String): Drawable? {
        return com.godviewer.app.host.mirror.RuleMirrorStore.loadAppIcon(context, packageName)
    }

    fun loadThumbnail(context: Context, packageName: String, rule: ViewRule): Bitmap? {
        return com.godviewer.app.host.mirror.RuleMirrorStore.loadThumbnail(context, packageName, rule)
    }

    fun packageCount(context: Context): Int {
        return com.godviewer.app.host.mirror.RuleMirrorStore.packageCount(context)
    }

    fun thumbnailKey(rule: ViewRule): String = mirrorThumbnailKey(rule)

    fun sanitizePackageName(raw: String?): String? = sanitizeMirrorPackageName(raw)
}
