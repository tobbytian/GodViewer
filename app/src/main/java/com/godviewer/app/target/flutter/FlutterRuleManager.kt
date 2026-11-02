package com.godviewer.app.target.flutter

import android.app.Activity
import android.content.Context
import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.rule.versionCode
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Flutter 语义规则：把某个语义节点「隐藏」持久化到
 * `files/godviewer/flutter_rules.json`，进程重启后随语义树重放（遮罩盖回）。
 *
 * 刻意不复用 `rules.json`：其 schema 已冻结（宿主/镜像/导入导出均依赖），
 * Flutter 规则独立成文件独立演进，待协议扩展后再并入镜像推送。
 *
 * 规则匹配键（尽力而为，自上而下）：
 * 1. `identifier` —— widget 显式语义命名，最稳定；
 * 2. `label + value` —— 语义文本组合（多数按钮/文本都有 label）。
 * 两者皆空的节点无法建立规则（见 [canCapture]）。
 */
object FlutterRuleManager {

    private const val TAG = "FlutterRule"
    private const val DIR = "godviewer"
    private const val FILE = "flutter_rules.json"
    private const val SCHEMA_VERSION = 1
    private const val ACTION_HIDE = "hide"

    class FlutterRule(
        val packageName: String,
        val activityClass: String,
        val matchVersionCode: Int,
        val identifier: String?,
        val label: String?,
        val value: String?,
        val kind: String,
        val action: String = ACTION_HIDE,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        /** 稳定唯一键（upsert / 遮罩回收用） */
        fun key(): String =
            listOfNotNull(activityClass, identifier ?: "", label ?: "", value ?: "", kind)
                .joinToString("|")

        override fun toString(): String =
            "FlutterRule(${key()})"
    }

    private data class RulesFile(
        val schemaVersion: Int = SCHEMA_VERSION,
        val rules: List<FlutterRule> = emptyList(),
    )

    @Volatile
    private var initialized = false
    private var appContext: Context? = null
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var rules: List<FlutterRule> = emptyList()

    fun init(application: Context) {
        if (initialized) return
        initialized = true
        appContext = application.applicationContext
        runCatching {
            rules = load()
            GvLog.d(TAG, "flutter rules loaded: ${rules.size}")
        }.onFailure {
            rules = emptyList()
            GvLog.e(TAG, "flutter rules load failed; continue empty", it)
        }
        // 启动即有规则 → 引擎捕获后需要自动开启语义树做遮罩重放
        FlutterBridge.refreshActivation()
    }

    fun hasRules(): Boolean = rules.isNotEmpty()

    fun allRules(): List<FlutterRule> = rules

    fun rulesForActivity(activityClass: String): List<FlutterRule> =
        rules.filter { it.activityClass == activityClass }

    /** 该节点是否具备建立规则的最小标识（identifier 或 label/value 至少其一） */
    fun canCapture(node: FlutterSemanticsNode): Boolean =
        !node.identifier.isNullOrBlank() ||
            !node.label.isNullOrBlank() ||
            !node.value.isNullOrBlank()

    /** 保存/更新一条隐藏规则（按 key upsert），立即生效 */
    fun saveHideRule(activity: Activity, node: FlutterSemanticsNode): FlutterRule? {
        if (!canCapture(node)) return null
        val rule = FlutterRule(
            packageName = activity.packageName,
            activityClass = activity.componentName.className,
            matchVersionCode = versionCode(activity),
            identifier = node.identifier?.takeIf { it.isNotBlank() },
            label = node.label?.takeIf { it.isNotBlank() },
            value = node.value?.takeIf { it.isNotBlank() },
            kind = node.kindHint(),
        )
        val index = rules.indexOfFirst { it.key() == rule.key() }
        rules = if (index >= 0) {
            rules.toMutableList().apply { set(index, rule) }
        } else {
            rules + rule
        }
        persist()
        FlutterMaskController.refresh()
        FlutterBridge.refreshActivation()
        GvLog.i(TAG, "flutter rule saved: $rule")
        return rule
    }

    /** 删除规则并立即撤下对应遮罩 */
    fun deleteRule(rule: FlutterRule) {
        val before = rules.size
        rules = rules.filterNot { it.key() == rule.key() }
        if (rules.size != before) {
            persist()
            FlutterMaskController.refresh()
            FlutterBridge.refreshActivation()
        }
        GvLog.i(TAG, "flutter rule deleted: ${rule.key()}")
    }

    /** 查找命中某节点的既有规则（编辑弹窗判断「恢复显示」可用性） */
    fun findRuleForNode(activityClass: String, node: FlutterSemanticsNode): FlutterRule? =
        rulesForActivity(activityClass).firstOrNull { rule ->
            matches(rule, node)
        }

    private fun matches(rule: FlutterRule, node: FlutterSemanticsNode): Boolean {
        val identifier = rule.identifier
        if (!identifier.isNullOrBlank()) {
            return node.identifier == identifier
        }
        val label = rule.label
        val value = rule.value
        if (label.isNullOrBlank() && value.isNullOrBlank()) return false
        return node.label == label && node.value == value &&
            (node.label != null || node.value != null)
    }

    private fun persist() {
        val context = appContext ?: return
        val snapshot = rules
        executor.execute {
            runCatching {
                val file = File(File(context.filesDir, DIR), FILE)
                val dir = file.parentFile
                if (dir != null && !dir.exists()) dir.mkdirs()
                // 原子写：先写临时文件再 rename（与 rules.json 一致），损坏即当空规则
                val tmp = File(dir, "$FILE.tmp")
                FileOutputStream(tmp).use { out ->
                    out.write(
                        gson.toJson(RulesFile(SCHEMA_VERSION, snapshot)).toByteArray(Charsets.UTF_8),
                    )
                    out.fd.sync()
                }
                if (tmp.exists()) tmp.renameTo(file)
            }.onFailure {
                // 持久化失败不影响目标应用运行，但必须留痕（对齐 RuleStore）
                GvLog.w(TAG, "flutter rules persist failed", it)
            }
        }
    }

    private fun load(): List<FlutterRule> {
        val context = appContext ?: return emptyList()
        val file = File(File(context.filesDir, DIR), FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            gson.fromJson(file.readText(), RulesFile::class.java)?.rules ?: emptyList()
        }.onFailure {
            GvLog.w(TAG, "flutter rules corrupted; treated as empty", it)
        }.getOrDefault(emptyList())
    }
}
