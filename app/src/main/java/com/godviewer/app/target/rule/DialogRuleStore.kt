package com.godviewer.app.target.rule

import android.app.Application
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.shared.AndroidAppCompat
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.model.DialogRule
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * 弹窗规则存储：JSON 文件保存在目标应用自己的数据目录
 * （files/godviewer/dialog_rules.json），独立于冻结的 rules.json schema。
 *
 * 读写都在被注入的目标进程内完成；[save] / [deleteAll] 成功后 best-effort
 * 回推镜像（与 [RuleStore] 同模式）。损坏时当作无规则，绝不能让目标应用崩溃。
 */
object DialogRuleStore {
    private const val TAG = "DlgRule"
    private const val FILE_NAME = "dialog_rules.json"
    private const val SCHEMA_VERSION = 1

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var initialized = false

    @Volatile
    private var appContext: android.content.Context? = null

    @Volatile
    private var rules: List<DialogRule> = emptyList()

    fun init(application: Application) {
        if (initialized) return
        initialized = true
        runCatching {
            appContext = application.applicationContext
            rules = load()
            GvLog.d(TAG, "dialog rules loaded: ${rules.size}")
        }.onFailure {
            rules = emptyList()
            GvLog.e(TAG, "DialogRuleStore.init failed; continue empty", it)
        }
    }

    fun all(): List<DialogRule> = rules

    /** 查找命中候选弹窗的规则（versionCode + activity + 文本指纹，宁漏勿滥） */
    fun findMatch(
        packageName: String,
        versionCode: Int,
        activityClass: String,
        dialogClass: String,
        texts: List<String>,
    ): DialogRule? {
        return rules.firstOrNull { rule ->
            rule.packageName == packageName &&
                rule.dialogClass == dialogClass &&
                rule.matches(versionCode, activityClass, texts)
        }
    }

    /**
     * 保存（按归一化指纹去重）并回推镜像；返回是否新增/更新。
     * 先与磁盘合并再改：多进程应用里各进程内存副本互不可见，直接覆盖会丢规则。
     */
    @Synchronized
    fun save(rule: DialogRule): Boolean {
        val base = mergeWithDisk()
        val index = base.indexOfFirst { it.sameFingerprintAs(rule) }
        rules = if (index >= 0) {
            base.toMutableList().apply { set(index, rule) }
        } else {
            base + rule
        }
        persist()
        GvLog.i(TAG, "dialog rule saved: ${rule.dialogClass} texts=${rule.texts.size}")
        return true
    }

    /**
     * 批量按指纹 upsert（宿主导入下发）：与 [save] 同语义的批量版——按归一化指纹
     * 替换或新增；先与磁盘合并再改（多进程应用各进程内存副本互不可见）。
     * 幂等：重复导入同一批规则结果不变。返回实际合并条数。
     */
    @Synchronized
    fun upsert(incoming: List<DialogRule>): Int {
        if (incoming.isEmpty()) return 0
        val list = mergeWithDisk().toMutableList()
        for (rule in incoming) {
            val index = list.indexOfFirst { it.sameFingerprintAs(rule) }
            if (index >= 0) {
                list[index] = rule
            } else {
                list.add(rule)
            }
        }
        rules = list
        persist()
        GvLog.i(TAG, "dialog rules upserted count=${incoming.size}")
        return incoming.size
    }

    /** 批量按 key 删除（宿主下发）；返回实际删除条数 */
    @Synchronized
    fun delete(keys: Collection<DialogRule.Key>): Int {
        if (keys.isEmpty()) return 0
        val keySet = keys.toSet()
        val base = mergeWithDisk()
        val remaining = base.filterNot { it.key() in keySet }
        val removed = base.size - remaining.size
        if (removed == 0) return 0
        rules = remaining
        persist()
        GvLog.i(TAG, "dialog rules deleted count=$removed")
        return removed
    }

    /** 清空（随「删除该应用全部规则」连带执行）；返回删除条数 */
    @Synchronized
    fun deleteAll(): Int {
        val removed = mergeWithDisk().size
        if (removed == 0) return 0
        rules = emptyList()
        persist()
        GvLog.i(TAG, "all dialog rules deleted count=$removed")
        return removed
    }

    private fun context(): android.content.Context? =
        appContext ?: AndroidAppCompat.currentApplication()?.applicationContext

    private fun rulesFile(ctx: android.content.Context): File =
        File(File(ctx.filesDir, "godviewer"), FILE_NAME)

    /** 磁盘规则 ∪ 内存里尚未落盘的规则（按 key） */
    private fun mergeWithDisk(): List<DialogRule> {
        val disk = load()
        if (disk.isEmpty()) return rules
        val diskKeys = disk.mapTo(HashSet()) { it.key() }
        return disk + rules.filter { it.key() !in diskKeys }
    }

    private fun load(): List<DialogRule> {
        return runCatching {
            val ctx = context() ?: return emptyList()
            val file = rulesFile(ctx)
            if (!file.exists()) return emptyList()
            gson.fromJson(file.readText(), DialogRulesFile::class.java)?.rules ?: emptyList()
        }.getOrElse {
            // 文件损坏时当作无规则处理，绝不能让目标应用崩溃
            emptyList()
        }
    }

    /**
     * 规则文件同步原子写（关闭弹窗后进程可能立刻被杀，异步写会丢规则），
     * 镜像回推仍异步 best-effort。
     */
    private fun persist() {
        val ctx = context() ?: run {
            GvLog.w(TAG, "persist skipped: no context, rule kept in memory only")
            return
        }
        val snapshot = rules
        runCatching {
            val file = rulesFile(ctx)
            val dir = file.parentFile
            if (dir != null && !dir.exists()) dir.mkdirs()
            val tmp = File(dir, "$FILE_NAME.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(
                    gson.toJson(DialogRulesFile(SCHEMA_VERSION, snapshot))
                        .toByteArray(Charsets.UTF_8),
                )
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure {
            GvLog.w(TAG, "dialog rules persist failed", it)
        }
        executor.execute {
            runCatching {
                RuleMirror.pushFromTarget(
                    ctx,
                    ctx.packageName,
                    com.godviewer.app.target.rule.ViewRuleManager.allRules(),
                )
            }.onFailure {
                GvLog.w(TAG, "dialog rules mirror push failed", it)
            }
        }
    }

    private data class DialogRulesFile(
        val schemaVersion: Int = SCHEMA_VERSION,
        val rules: List<DialogRule> = emptyList(),
    )
}