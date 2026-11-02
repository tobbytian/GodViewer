package com.godviewer.app.shared.model

import com.google.gson.annotations.SerializedName

/**
 * 一条"关闭弹窗"规则：用户在目标应用内一键关闭某个 Dialog 后生成，
 * 存目标应用私有目录 `files/godviewer/dialog_rules.json`（独立于冻结的 rules.json schema）。
 *
 * 匹配思路：版本相等时按覆盖率 ≥ [TEXT_MATCH_THRESHOLD] 命中；版本不等（目标应用
 * 已升级）以更高阈值降级匹配（见 [matches]）；**不要求同一 Activity**（同一弹窗在
 * 不同页面弹出也拦截，持久优先）；规则无文本时回退 dialogClass（调用方保证）
 * + activityClass 均非空时一致。
 */
data class DialogRule(
    @SerializedName("package_name")
    val packageName: String,
    // 创建规则时目标应用的版本号，匹配时严格相等
    @SerializedName("match_version_code")
    val matchVersionCode: Int,
    // 弹窗弹出时的前台 Activity（弹窗多次出现在不同 Activity 时不匹配）
    @SerializedName("activity_class")
    val activityClass: String,
    @SerializedName("dialog_class")
    val dialogClass: String,
    // 弹窗内 TextView 文本指纹（已排除 Button、规范化、去重、截断）
    @SerializedName("texts")
    val texts: List<String>,
    @SerializedName("timestamp")
    val timestamp: Long = 0L,
) {
    /** 视则唯一键：同一应用 + 版本 + Activity + 弹窗类 + 文本指纹 */
    data class Key(
        val packageName: String,
        val matchVersionCode: Int,
        val activityClass: String,
        val dialogClass: String,
        val texts: List<String>,
    )

    fun key(): Key = Key(packageName, matchVersionCode, activityClass, dialogClass, texts)

    /** 列表/通知里显示的标题：首条真实文本，其次内容描述，最后弹窗类短名 */
    fun title(): String = titleOf(texts, dialogClass)

    /**
     * 候选弹窗是否命中本规则。
     *
     * 版本不再硬卡：目标应用升级后旧规则静默失效正是「拦截不稳定」的主因之一。
     * 版本相等沿用 [TEXT_MATCH_THRESHOLD]；版本不等时以更高的
     * [CROSS_VERSION_TEXT_THRESHOLD] 降级匹配——指纹是弹窗正文文本，跨版本
     * 通常稳定（如「发现新版本」），阈值提高防误伤；正文变了的自然匹配不上。
     *
     * @param versionCode 候选弹窗所属应用当前版本号
     * @param activityClass 弹出时前台 Activity 类名（可为空串）
     * @param candidateTexts 候选弹窗的文本指纹
     */
    fun matches(
        versionCode: Int,
        activityClass: String,
        candidateTexts: List<String>,
    ): Boolean {
        if (texts.isEmpty()) {
            // 无文本指纹的规则会命中同类的所有弹窗（如任意 AlertDialog），
            // 误伤用户主动打开的界面；宁漏勿滥，一律不匹配
            return false
        }
        // 有文本指纹：覆盖率说了算，不再卡 Activity —— 同一弹窗换个页面弹出也要拦截
        if (candidateTexts.isEmpty()) return false
        val threshold = if (matchVersionCode == versionCode) {
            TEXT_MATCH_THRESHOLD
        } else {
            CROSS_VERSION_TEXT_THRESHOLD
        }
        // 数字归一化后再比：倒计时「跳过 3s」、日期、数量等动态文本不应拉低覆盖率
        val candidates = candidateTexts.mapTo(HashSet()) { normalize(it) }
        val hit = texts.count { normalize(it) in candidates }
        return hit.toDouble() / texts.size >= threshold
    }

    /** 与 [other] 是否为同一弹窗的指纹（数字归一化后文本集相同），供保存时去重 */
    fun sameFingerprintAs(other: DialogRule): Boolean {
        return packageName == other.packageName &&
            matchVersionCode == other.matchVersionCode &&
            dialogClass == other.dialogClass &&
            texts.mapTo(HashSet()) { normalize(it) } == other.texts.mapTo(HashSet()) { normalize(it) }
    }

    companion object {
        /** 文本指纹覆盖率阈值（≥ 60% 判为同类弹窗） */
        private const val TEXT_MATCH_THRESHOLD = 0.6

        /** 跨版本降级匹配阈值（目标应用已升级，规则版本号不等时用更严阈值） */
        private const val CROSS_VERSION_TEXT_THRESHOLD = 0.8

        private val DIGITS = Regex("[0-9]+")

        /** 结构指纹（纯图片弹窗）以 id: / desc: 开头，标题优先取真实文本 */
        fun titleOf(texts: List<String>, dialogClass: String): String {
            texts.firstOrNull { it.isNotBlank() && !it.startsWith("id:") && !it.startsWith("desc:") }
                ?.let { return it }
            texts.firstOrNull { it.startsWith("desc:") }?.removePrefix("desc:")
                ?.takeIf { it.isNotBlank() }?.let { return it }
            return dialogClass.substringAfterLast('.')
        }

        private fun normalize(text: String): String = DIGITS.replace(text, "#")
    }
}