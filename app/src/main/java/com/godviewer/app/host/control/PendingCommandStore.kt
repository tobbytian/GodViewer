package com.godviewer.app.host.control

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.model.DialogRule
import com.godviewer.app.shared.model.ViewRule
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.FileOutputStream

/**
 * 宿主待同步队列：UPDATE/IMPORT/DELETE 广播对死进程静默丢弃（README bug #6 的根因），
 * 命令先入队再下发：
 *
 * - 目标存活 → 立即送达；[VERIFY_DELAY_MS] 后按镜像**内容全等**确认出队；
 * - 目标不存活 → 命令留队，等它上报前台（`ACTION_TARGET_FOREGROUND`）
 *   时由 [HostControlReceiverImpl] 调 [flush] 自动重发 —— 用户打开应用即生效。
 *
 * 目标端 upsert/删除均幂等，重发安全；同 key 的 UPDATE 后写覆盖先写（last-wins），
 * 多命令按 FIFO 顺序重放，与用户操作时序一致。
 *
 * 确认语义按 action 区分：
 * - 编辑/导入：镜像里按 key 找到的规则与下发的**全等**（目标 upsert 原样落盘，
 *   不重写 timestamp）；
 * - 删除单条/批量：镜像里这些 key 已不存在；
 * - 删除全部：镜像视图规则与弹窗规则均为空（空镜像 = 目标端清空后回推）。
 *
 * 存储：`filesDir/godviewer/pending/<pkg>.json`（每包一个文件，FIFO）。
 */
object PendingCommandStore {
    private const val TAG = "Pending"
    private const val PENDING_DIR = "godviewer/pending"

    /** 下发后等待镜像回推确认的时间（ms），与各页删除确认节奏一致 */
    private const val VERIFY_DELAY_MS = 1_500L

    data class PendingCommand(
        @SerializedName("action") val action: String,
        @SerializedName("rules") val rules: List<ViewRule> = emptyList(),
        @SerializedName("dialog_rules") val dialogRules: List<DialogRule> = emptyList(),
        @SerializedName("overwrite") val overwrite: Boolean = false,
        @SerializedName("created_at") val createdAt: Long = 0L,
    )

    private data class PendingFile(
        @SerializedName("commands") val commands: List<PendingCommand> = emptyList(),
    )

    private val gson = GsonBuilder().create()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---------- 提交（宿主编辑 / 导入入口调用） ----------

    /** 提交「编辑/更新视图规则」：入队 + 立即下发 + 延迟确认出队 */
    fun submitUpdate(
        context: Context,
        packageName: String,
        rules: List<ViewRule>,
    ): Boolean {
        if (rules.isEmpty()) return false
        return submit(
            context,
            packageName,
            PendingCommand(action = HostControlBridge.ACTION_UPDATE_RULES, rules = rules),
        )
    }

    /** 提交「导入规则」：入队 + 立即下发 + 延迟确认出队。
     *  @param overwrite true = 覆盖模式（目标端先清空该应用全部规则再合并） */
    fun submitImport(
        context: Context,
        packageName: String,
        viewRules: List<ViewRule>,
        dialogRules: List<DialogRule>,
        overwrite: Boolean = false,
    ): Boolean {
        if (viewRules.isEmpty() && dialogRules.isEmpty()) return false
        return submit(
            context,
            packageName,
            PendingCommand(
                action = HostControlBridge.ACTION_IMPORT_RULES,
                rules = viewRules,
                dialogRules = dialogRules,
                overwrite = overwrite,
            ),
        )
    }

    /** 提交「删除单条/批量视图规则」：入队 + 立即下发 + 延迟确认出队 */
    fun submitDeleteRules(
        context: Context,
        packageName: String,
        rules: List<ViewRule>,
    ): Boolean {
        if (rules.isEmpty()) return false
        return submit(
            context,
            packageName,
            PendingCommand(action = HostControlBridge.ACTION_DELETE_RULES, rules = rules),
        )
    }

    /** 提交「删除弹窗规则」：入队 + 立即下发 + 延迟确认出队 */
    fun submitDeleteDialogRules(
        context: Context,
        packageName: String,
        rules: List<DialogRule>,
    ): Boolean {
        if (rules.isEmpty()) return false
        return submit(
            context,
            packageName,
            PendingCommand(
                action = HostControlBridge.ACTION_DELETE_DIALOG_RULES,
                dialogRules = rules,
            ),
        )
    }

    /** 提交「删除该应用全部规则」（无载荷，靠镜像清空确认） */
    fun submitDeleteAll(context: Context, packageName: String): Boolean =
        submit(
            context,
            packageName,
            PendingCommand(action = HostControlBridge.ACTION_DELETE_ALL_RULES),
        )

    private fun submit(context: Context, packageName: String, command: PendingCommand): Boolean {
        val app = context.applicationContext
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return false
        enqueue(app, pkg, command)
        val sent = dispatch(app, pkg, command)
        scheduleVerify(app, pkg)
        GvLog.d(TAG, "submit action=${command.action} pkg=$pkg sent=$sent")
        return sent
    }

    // ---------- flush（目标上报前台时调用） ----------

    /** 重发该包全部待同步命令（目标刚上报前台，其接收器已存活）并延迟确认 */
    fun flush(context: Context, packageName: String) {
        val app = context.applicationContext
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        val commands = load(app, pkg)
        if (commands.isEmpty()) return
        var sent = false
        for (command in commands) {
            if (dispatch(app, pkg, command)) sent = true
        }
        GvLog.i(TAG, "flush pkg=$pkg commands=${commands.size} sent=$sent")
        if (sent) scheduleVerify(app, pkg)
    }

    // ---------- 确认出队 ----------

    /** 检查全部有待同步命令的包，把镜像里已全等落地的命令移除（幂等，可随时调） */
    fun pruneSynced(context: Context) {
        val app = context.applicationContext
        val root = File(app.filesDir, PENDING_DIR)
        val files = root.listFiles() ?: return
        for (file in files) {
            val pkg = file.name.removeSuffix(".json")
            if (pkg.isBlank()) continue
            pruneFor(app, pkg)
        }
    }

    /** 某包当前待同步命令数（0 = 已全部同步，供 UI 提示） */
    fun pendingCount(context: Context, packageName: String): Int =
        load(context.applicationContext, packageName.trim()).size

    /** 该包待同步命令里要删除的弹窗规则 key，列表据此标「待同步」 */
    fun pendingDialogDeleteKeys(context: Context, packageName: String): Set<DialogRule.Key> =
        load(context.applicationContext, packageName.trim())
            .filter { it.action == HostControlBridge.ACTION_DELETE_DIALOG_RULES }
            .flatMapTo(HashSet()) { cmd -> cmd.dialogRules.map { it.key() } }

    /** 该包待同步命令里尚未落地的新增/更新弹窗规则（编辑后目标未在线时仍显示在列表里） */
    fun pendingDialogUpserts(context: Context, packageName: String): List<DialogRule> =
        load(context.applicationContext, packageName.trim())
            .filter { it.action == HostControlBridge.ACTION_IMPORT_RULES }
            .flatMap { it.dialogRules }

    /** 该包待同步命令里的视图规则新增/更新（UPDATE/IMPORT 合集，导出合并用） */
    fun pendingViewUpserts(context: Context, packageName: String): List<ViewRule> =
        load(context.applicationContext, packageName.trim())
            .filter {
                it.action == HostControlBridge.ACTION_UPDATE_RULES ||
                    it.action == HostControlBridge.ACTION_IMPORT_RULES
            }
            .flatMap { it.rules }

    /** 该包待同步命令里要删除的视图规则 key */
    fun pendingViewDeleteKeys(context: Context, packageName: String): Set<ViewRule.RuleKey> =
        load(context.applicationContext, packageName.trim())
            .filter { it.action == HostControlBridge.ACTION_DELETE_RULES }
            .flatMapTo(HashSet()) { cmd -> cmd.rules.map { it.key() } }

    /** 该包待同步命令里的视图规则编辑（UPDATE/IMPORT），key 对应的镜像行尚未更新 */
    fun pendingViewUpdateKeys(context: Context, packageName: String): Set<ViewRule.RuleKey> =
        load(context.applicationContext, packageName.trim())
            .filter {
                it.action == HostControlBridge.ACTION_UPDATE_RULES ||
                    it.action == HostControlBridge.ACTION_IMPORT_RULES
            }
            .flatMapTo(HashSet()) { cmd -> cmd.rules.map { it.key() } }

    private fun scheduleVerify(context: Context, packageName: String) {
        val app = context.applicationContext
        val pkg = packageName
        mainHandler.postDelayed({ pruneFor(app, pkg) }, VERIFY_DELAY_MS)
    }

    private fun pruneFor(context: Context, packageName: String) {
        val commands = load(context, packageName)
        if (commands.isEmpty()) return
        val remaining = commands.filterNot { isSynced(context, packageName, it) }
        if (remaining.size != commands.size) {
            save(context, packageName, remaining)
            GvLog.d(
                TAG,
                "pruned pkg=$packageName done=${commands.size - remaining.size} " +
                    "left=${remaining.size}",
            )
        }
    }

    /**
     * 内容确认（按 action 区分语义，见类注释）。删除确认只看 key 是否消失——
     * 即使目标端把同 key 规则改过再删，key 消失即达成；删除全部要求镜像双向清空。
     */
    private fun isSynced(context: Context, packageName: String, command: PendingCommand): Boolean {
        return runCatching {
            when (command.action) {
                HostControlBridge.ACTION_DELETE_ALL_RULES ->
                    RuleMirror.loadRules(context, packageName).isEmpty() &&
                        RuleMirror.loadDialogRules(context, packageName).isEmpty()

                HostControlBridge.ACTION_DELETE_RULES -> {
                    val mirrorKeys = RuleMirror.loadRules(context, packageName)
                        .mapTo(HashSet()) { it.key() }
                    command.rules.none { it.key() in mirrorKeys }
                }

                HostControlBridge.ACTION_DELETE_DIALOG_RULES -> {
                    val mirrorKeys = RuleMirror.loadDialogRules(context, packageName)
                        .mapTo(HashSet()) { it.key() }
                    command.dialogRules.none { it.key() in mirrorKeys }
                }

                HostControlBridge.ACTION_UPDATE_RULES,
                HostControlBridge.ACTION_IMPORT_RULES,
                -> {
                    if (command.rules.isNotEmpty()) {
                        val byKey = RuleMirror.loadRules(context, packageName)
                            .associateBy { it.key() }
                        if (command.rules.any { byKey[it.key()] != it }) return false
                    }
                    if (command.dialogRules.isNotEmpty()) {
                        val mirrorDialogs = RuleMirror.loadDialogRules(context, packageName)
                            .mapTo(HashSet()) { it.key() }
                        if (command.dialogRules.any { it.key() !in mirrorDialogs }) return false
                    }
                    // 覆盖导入要求镜像 key 集与下发 key 集全等（先清空再合并，
                    // 镜像里不应残留旧 key、也不应缺新 key）
                    if (command.overwrite) {
                        val mirrorViewKeys = RuleMirror.loadRules(context, packageName)
                            .mapTo(HashSet()) { it.key() }
                        val wantViewKeys = command.rules.mapTo(HashSet()) { it.key() }
                        if (mirrorViewKeys != wantViewKeys) return false
                        val mirrorDialogKeys = RuleMirror.loadDialogRules(context, packageName)
                            .mapTo(HashSet()) { it.key() }
                        val wantDialogKeys = command.dialogRules.mapTo(HashSet()) { it.key() }
                        if (mirrorDialogKeys != wantDialogKeys) return false
                    }
                    true
                }

                else -> false
            }
        }.getOrDefault(false)
    }

    // ---------- 队列读写 ----------

    private fun dispatch(context: Context, packageName: String, command: PendingCommand): Boolean =
        when (command.action) {
            HostControlBridge.ACTION_UPDATE_RULES ->
                HostControlBridge.dispatchUpdateRules(context, packageName, command.rules)
            HostControlBridge.ACTION_IMPORT_RULES ->
                HostControlBridge.dispatchImportRules(
                    context,
                    packageName,
                    command.rules,
                    command.dialogRules,
                    command.overwrite,
                )
            HostControlBridge.ACTION_DELETE_RULES ->
                HostControlBridge.dispatchRulesToPackage(context, packageName, command.rules)
            HostControlBridge.ACTION_DELETE_DIALOG_RULES ->
                HostControlBridge.dispatchDialogRulesToPackage(
                    context,
                    packageName,
                    command.dialogRules,
                )
            HostControlBridge.ACTION_DELETE_ALL_RULES ->
                HostControlBridge.dispatchToPackage(
                    context,
                    packageName,
                    HostControlBridge.ACTION_DELETE_ALL_RULES,
                )
            else -> {
                GvLog.w(TAG, "dispatch unknown action=${command.action}")
                false
            }
        }

    private fun enqueue(context: Context, packageName: String, command: PendingCommand) {
        val list = load(context, packageName).toMutableList()
        // 同 key 的在途 UPDATE 已过期：last-wins，剥掉重复 key 后再追加
        if (command.action == HostControlBridge.ACTION_UPDATE_RULES) {
            val keys = command.rules.map { it.key() }.toSet()
            val stripped = list.mapNotNull { existing ->
                if (existing.action != HostControlBridge.ACTION_UPDATE_RULES) {
                    existing
                } else {
                    existing.copy(rules = existing.rules.filterNot { it.key() in keys })
                        .takeIf { it.rules.isNotEmpty() || it.dialogRules.isNotEmpty() }
                }
            }
            list.clear()
            list.addAll(stripped)
        }
        list.add(command.copy(createdAt = System.currentTimeMillis()))
        save(context, packageName, list)
    }

    private fun load(context: Context, packageName: String): List<PendingCommand> {
        if (packageName.isEmpty()) return emptyList()
        val file = pendingFile(context, packageName)
        if (!file.exists()) return emptyList()
        return runCatching {
            gson.fromJson(file.readText(), PendingFile::class.java)?.commands.orEmpty()
        }.getOrElse {
            // 文件损坏当作无待同步命令（丢一条广播可接受，绝不让宿主崩溃）
            emptyList()
        }
    }

    private fun save(context: Context, packageName: String, commands: List<PendingCommand>) {
        if (packageName.isEmpty()) return
        val file = pendingFile(context, packageName)
        if (commands.isEmpty()) {
            runCatching { file.delete() }
            return
        }
        runCatching {
            val dir = file.parentFile
            if (dir != null && !dir.exists()) dir.mkdirs()
            val tmp = File(dir, "${file.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(gson.toJson(PendingFile(commands)).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure {
            GvLog.w(TAG, "save failed pkg=$packageName", it)
        }
    }

    private fun pendingFile(context: Context, packageName: String): File =
        File(File(context.filesDir, PENDING_DIR), "$packageName.json")
}