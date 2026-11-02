package com.godviewer.app.host.io

import android.content.Context
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.host.control.PendingCommandStore
import com.godviewer.app.shared.model.DialogRule
import com.godviewer.app.shared.model.ViewRule
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName

/**
 * 宿主侧规则导入/导出信封（`godviewer-rules-bundle`，新造宿主格式）。
 *
 * 与冻结的 `rules.json` schema 无关：单应用导出 = 1 条 app entry，
 * 批量导出 = 全部镜像包多条 entry —— 同一格式，导入端不分家。
 * v1 不含缩略图（目标列表本有 缓存 → 活视图 → 占位图 三级 fallback）。
 *
 * 数据源是只读镜像，可能滞后：导出前可调
 * [com.godviewer.app.shared.control.HostControlBridge.requestMirrorPush] 补推。
 */
data class RuleBundle(
    @SerializedName("format") val format: String,
    @SerializedName("format_version") val formatVersion: Int,
    @SerializedName("exported_at") val exportedAt: Long,
    @SerializedName("apps") val apps: List<AppEntry>,
) {
    data class AppEntry(
        @SerializedName("package") val packageName: String,
        @SerializedName("label") val label: String? = null,
        @SerializedName("view_rules") val viewRules: List<ViewRule> = emptyList(),
        @SerializedName("dialog_rules") val dialogRules: List<DialogRule> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = viewRules.isEmpty() && dialogRules.isEmpty()
    }

    companion object {
        const val FORMAT = "godviewer-rules-bundle"
        const val FORMAT_VERSION = 1

        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

        /** 从镜像构建单应用信封；该应用镜像为空（无可导出）时返回 null */
        fun forPackage(context: Context, packageName: String): RuleBundle? {
            val entry = entryFor(context, packageName) ?: return null
            return RuleBundle(FORMAT, FORMAT_VERSION, System.currentTimeMillis(), listOf(entry))
        }

        /** 从镜像构建全部应用的批量信封；镜像全空时返回 null */
        fun all(context: Context): RuleBundle? {
            val entries = RuleMirror.listPackages(context)
                .mapNotNull { entryFor(context, it.packageName) }
                .filterNot { it.isEmpty }
            if (entries.isEmpty()) return null
            return RuleBundle(FORMAT, FORMAT_VERSION, System.currentTimeMillis(), entries)
        }

        fun encode(bundle: RuleBundle): String = gson.toJson(bundle)

        /**
         * 解析并校验信封：损坏 / 格式不符返回失败原因（携带给调用方提示，
         * 绝不抛出、绝不让宿主崩溃）。
         */
        fun decode(json: String): Result<RuleBundle> = runCatching {
            require(json.isNotBlank()) { "empty content" }
            val bundle = runCatching { gson.fromJson(json, RuleBundle::class.java) }
                .getOrNull() ?: error("unparsable json")
            require(bundle.format == FORMAT) { "not a godviewer rules bundle" }
            require(bundle.formatVersion in 1..FORMAT_VERSION) {
                "unsupported format_version=${bundle.formatVersion}"
            }
            require(bundle.apps.isNotEmpty()) { "bundle has no apps" }
            bundle
        }

        /**
         * 从镜像 + 待同步队列构建单应用条目（镜像可能滞后于宿主侧编辑）：
         * `(镜像 − 待同步删除) 按 key 被待同步 upsert 覆盖`——离线编辑/删除
         * 未同步到目标时，导出的快照仍然是宿主所见的最准确状态。
         * 两者皆空时返回 null。
         */
        private fun entryFor(context: Context, packageName: String): AppEntry? {
            val viewRules = effectiveRules(
                mirror = RuleMirror.loadRules(context, packageName),
                deleteKeys = PendingCommandStore.pendingViewDeleteKeys(context, packageName),
                upserts = PendingCommandStore.pendingViewUpserts(context, packageName),
                keyOf = { it.key() },
            )
            val dialogRules = effectiveRules(
                mirror = RuleMirror.loadDialogRules(context, packageName),
                deleteKeys = PendingCommandStore.pendingDialogDeleteKeys(context, packageName),
                upserts = PendingCommandStore.pendingDialogUpserts(context, packageName),
                keyOf = { it.key() },
            )
            if (viewRules.isEmpty() && dialogRules.isEmpty()) return null
            return AppEntry(
                packageName = packageName,
                label = RuleMirror.loadAppLabel(context, packageName),
                viewRules = viewRules,
                dialogRules = dialogRules,
            )
        }

        /** `(镜像 − 删除 keys)` 以 key 映射，再按「后写胜」合入 upserts，保持镜像顺序在前 */
        private fun <T> effectiveRules(
            mirror: List<T>,
            deleteKeys: Set<*>,
            upserts: List<T>,
            keyOf: (T) -> Any?,
        ): List<T> {
            if (mirror.isEmpty() && upserts.isEmpty()) return emptyList()
            val byKey = LinkedHashMap<Any?, T>(mirror.size + upserts.size)
            for (rule in mirror) {
                val key = keyOf(rule)
                if (key in deleteKeys) continue
                byKey[key] = rule
            }
            for (rule in upserts) {
                byKey[keyOf(rule)] = rule
            }
            return byKey.values.toList()
        }
    }
}