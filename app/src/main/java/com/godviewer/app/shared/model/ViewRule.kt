package com.godviewer.app.shared.model

import com.google.gson.annotations.SerializedName

/**
 * 一条持久化的视图修改规则（GodMode ViewRule 移植，精简为 GodViewer 支持的属性）。
 *
 * 标识字段（activityClass + viewClass + depth[]）用于应用重启后重新定位目标视图；
 * [original] 保存规则创建前的原始状态，供"重置"恢复；[modified] 与 changed* 标志
 * 表示用户修改后的状态与生效范围。
 *
 * repeatable 规则（列表项批量生效）：[itemRootClass] 非空时，[depth] 的语义变为
 * "自 item 根（RecyclerView/ListView 直接子项）到目标视图的 childIndex 路径"，
 * 规则会对该 Activity 内所有结构/文本匹配的同类项生效（见 RepeatableRuleReconciler）。
 * repeatable / itemRootClass / textMatchMode 为增量字段（schemaVersion 不变）：
 * 旧版本 Gson 解析时忽略，缺省时 false / null / null，行为与旧规则完全一致。
 */
data class ViewRule(
    // ---------- 标识 ----------
    @SerializedName("package_name")
    val packageName: String,
    // 创建规则时目标应用的版本号，供匹配时参考
    @SerializedName("match_version_code")
    val matchVersionCode: Int,
    @SerializedName("activity_class")
    val activityClass: String,
    @SerializedName("view_class")
    val viewClass: String,
    // 自 decorView 向下的逐层 childIndex 路径
    // （repeatable 规则时为自 item 根向下的路径，见类注释）
    @SerializedName("depth")
    val depth: List<Int>,
    @SerializedName("res_name")
    val resourceName: String?,
    @SerializedName("text")
    val text: String?,
    @SerializedName("description")
    val description: String?,
    // ---------- repeatable（列表项规则，增量字段） ----------
    /** 是否为列表项 repeatable 规则：对 Activity 内所有匹配的同类项生效 */
    @SerializedName("repeatable")
    val repeatable: Boolean = false,
    /** item 根（RecyclerView/ListView 直接子项）的运行时类名；非空即 repeatable */
    @SerializedName("item_root_class")
    val itemRootClass: String? = null,
    /** [text] 的匹配方式（[MATCH_MODE_EXACT] 等），null 等价 exact */
    @SerializedName("text_match_mode")
    var textMatchMode: String? = null,
    // ---------- 状态 ----------
    @SerializedName("original")
    var original: ViewAttrSnapshot,
    @SerializedName("modified")
    var modified: ViewAttrSnapshot,
    // ---------- 生效范围 ----------
    @SerializedName("changed_size")
    var changedSize: Boolean = false,
    @SerializedName("changed_margin")
    var changedMargin: Boolean = false,
    @SerializedName("changed_padding")
    var changedPadding: Boolean = false,
    @SerializedName("changed_visibility")
    var changedVisibility: Boolean = false,
    @SerializedName("changed_text")
    var changedText: Boolean = false,
    @SerializedName("changed_hint")
    var changedHint: Boolean = false,
    @SerializedName("changed_image")
    var changedImage: Boolean = false,
    @SerializedName("timestamp")
    var timestamp: Long = 0L
) {
    /** 视图唯一键：同一 Activity 内 depth 路径 + 视图类名 */
    data class RuleKey(val activityClass: String, val depth: List<Int>, val viewClass: String)

    fun key(): RuleKey = RuleKey(activityClass, depth, viewClass)

    companion object {
        /** 文本匹配方式：精确相等（默认，textMatchMode 为 null 时同此） */
        const val MATCH_MODE_EXACT = "exact"
        /** 文本匹配方式：包含 */
        const val MATCH_MODE_CONTAINS = "contains"
        /** 文本匹配方式：前缀 */
        const val MATCH_MODE_PREFIX = "prefix"
        /** 文本匹配方式：后缀 */
        const val MATCH_MODE_SUFFIX = "suffix"
        /** 文本匹配方式：正则（解析失败回落精确匹配） */
        const val MATCH_MODE_REGEX = "regex"

        /** 全部合法匹配方式，供 UI 枚举 */
        val MATCH_MODES = listOf(
            MATCH_MODE_EXACT,
            MATCH_MODE_CONTAINS,
            MATCH_MODE_PREFIX,
            MATCH_MODE_SUFFIX,
            MATCH_MODE_REGEX,
        )
    }
}

/**
 * 视图属性快照（像素值）。
 *
 * 图片 URL 无法从 ImageView 反查，因此 [imageUrl] 只在 modified 中有意义，
 * "重置"时无法恢复原图，仅能恢复 scaleType（已知限制）。
 */
data class ViewAttrSnapshot(
    @SerializedName("width") val width: Int,
    @SerializedName("height") val height: Int,
    @SerializedName("margin_left") val marginLeft: Int,
    @SerializedName("margin_top") val marginTop: Int,
    @SerializedName("margin_right") val marginRight: Int,
    @SerializedName("margin_bottom") val marginBottom: Int,
    @SerializedName("padding_left") val paddingLeft: Int,
    @SerializedName("padding_top") val paddingTop: Int,
    @SerializedName("padding_right") val paddingRight: Int,
    @SerializedName("padding_bottom") val paddingBottom: Int,
    @SerializedName("visibility") val visibility: Int,
    @SerializedName("text") val text: String?,
    @SerializedName("hint") val hint: String?,
    @SerializedName("max_lines") val maxLines: Int,
    @SerializedName("image_url") val imageUrl: String?,
    @SerializedName("scale_type") val scaleType: String?
)
