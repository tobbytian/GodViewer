package com.godviewer.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * 规则存储：JSON 文件保存在目标应用自己的数据目录
 * （files/godviewer/rules.json），读写都在被注入的目标进程内完成，
 * 无需跨进程 / 系统级权限，天然兼容 Android 16。
 */
internal class RuleStore(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val executor = Executors.newSingleThreadExecutor()

    private val rulesFile: File
        get() = File(File(context.filesDir, "godviewer"), "rules.json")

    @Synchronized
    fun load(): List<ViewRule> {
        return try {
            val file = rulesFile
            if (!file.exists()) {
                return emptyList()
            }
            val wrapper = gson.fromJson(file.readText(), RulesFile::class.java)
            wrapper?.rules ?: emptyList()
        } catch (e: Exception) {
            // 文件损坏时当作无规则处理，绝不能让目标应用崩溃
            emptyList()
        }
    }

    fun save(rules: List<ViewRule>) {
        executor.execute {
            try {
                val file = rulesFile
                val dir = file.parentFile
                if (dir != null && !dir.exists()) {
                    dir.mkdirs()
                }
                // 原子写：先写临时文件再 rename，避免写一半损坏 rules.json
                val tmp = File(dir, "rules.json.tmp")
                FileOutputStream(tmp).use { out ->
                    out.write(gson.toJson(RulesFile(SCHEMA_VERSION, rules)).toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                if (tmp.exists()) {
                    tmp.renameTo(file)
                }
                // 镜像同步到本体（失败不影响目标应用）
                val packageName = rules.firstOrNull()?.packageName
                    ?: context.packageName
                RuleMirror.pushFromTarget(context, packageName, rules)
            } catch (e: Exception) {
                // 持久化失败不影响目标应用运行
            }
        }
    }

    private data class RulesFile(val schemaVersion: Int = SCHEMA_VERSION, val rules: List<ViewRule> = emptyList())

    companion object {
        private const val SCHEMA_VERSION = 1
    }
}
