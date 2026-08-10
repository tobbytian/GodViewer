package com.godviewer.app.data

import com.google.gson.annotations.SerializedName

/**
 * 一条持久化的视图修改规则（GodMode ViewRule 移植，精简为 GodViewer 支持的属性）。
 *
 * 标识字段（activityClass + viewClass + depth[]）用于应用重启后重新定位目标视图；
 * [original] 保存规则创建前的原始状态，供"重置"恢复；[modified] 与 changed* 标志
 * 表示用户修改后的状态与生效范围。
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
    @SerializedName("depth")
    val depth: List<Int>,
    @SerializedName("res_name")
    val resourceName: String?,
    @SerializedName("text")
    val text: String?,
    @SerializedName("description")
    val description: String?,
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
    @SerializedName("changed_image")
    var changedImage: Boolean = false,
    @SerializedName("timestamp")
    var timestamp: Long = 0L
) {
    /** 视图唯一键：同一 Activity 内 depth 路径 + 视图类名 */
    data class RuleKey(val activityClass: String, val depth: List<Int>, val viewClass: String)

    fun key(): RuleKey = RuleKey(activityClass, depth, viewClass)
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
    @SerializedName("max_lines") val maxLines: Int,
    @SerializedName("image_url") val imageUrl: String?,
    @SerializedName("scale_type") val scaleType: String?
)
