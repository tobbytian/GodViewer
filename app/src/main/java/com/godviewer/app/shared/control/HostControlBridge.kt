package com.godviewer.app.shared.control

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.godviewer.app.BuildConfig
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.model.DialogRule
import com.godviewer.app.shared.model.ViewRule
import com.godviewer.app.shared.resolveAppLabel
import com.google.gson.Gson

/**
 * Cross-process control bridge between the host app notification and injected targets.
 *
 * Flow:
 * - Target onResume → report foreground package to host (explicit broadcast)
 * - Host notification actions → command the last reported target package
 * - Target keeps a dynamic receiver and executes enable / undo / manage-rules
 */
object HostControlBridge {
    /** 日志 tag：`GodViewer.Bridge`（与宿主/目标的 Control.* 区分，跨进程命令链可搜） */
    private const val TAG = "Bridge"

    const val ACTION_TARGET_FOREGROUND =
        "${BuildConfig.PACKAGE_NAME}.ACTION_TARGET_FOREGROUND"
    const val ACTION_ENABLE_EDIT =
        "${BuildConfig.PACKAGE_NAME}.ACTION_ENABLE_EDIT"
    const val ACTION_UNDO =
        "${BuildConfig.PACKAGE_NAME}.ACTION_UNDO"
    const val ACTION_MANAGE_RULES =
        "${BuildConfig.PACKAGE_NAME}.ACTION_MANAGE_RULES"

    /**
     * 宿主规则管理页「删除该应用全部规则」：权威规则在目标应用私有目录，
     * 只能由目标进程执行删除（目标进程会连带推送空镜像，宿主镜像随之清除）。
     */
    const val ACTION_DELETE_ALL_RULES =
        "${BuildConfig.PACKAGE_NAME}.ACTION_DELETE_ALL_RULES"

    /**
     * 宿主规则页「删除单条 / 多选批量删除规则」：同删除全部，权威规则在目标应用
     * 私有目录，只能由目标进程执行删除；广播携带规则 JSON 数组（目标端仅取 key 定位）。
     */
    const val ACTION_DELETE_RULES =
        "${BuildConfig.PACKAGE_NAME}.ACTION_DELETE_RULES"

    /**
     * 宿主规则详情页「编辑规则」：宿主镜像可编辑，权威仍在目标私有目录；
     * 广播携带规则 JSON 数组（含 modified 全量值），目标按 key upsert 后回推镜像。
     */
    const val ACTION_UPDATE_RULES =
        "${BuildConfig.PACKAGE_NAME}.ACTION_UPDATE_RULES"

    /**
     * 宿主导入规则（合并 upsert）：视图规则 / 弹窗规则 JSON 至少携带其一，
     * 目标按 RuleKey / 指纹合并落盘。大批量由宿主分片多发，目标端幂等。
     */
    const val ACTION_IMPORT_RULES =
        "${BuildConfig.PACKAGE_NAME}.ACTION_IMPORT_RULES"

    /**
     * 宿主导出前请求目标补推镜像：镜像是 best-effort 广播，可能滞后或缺失，
     * 目标进程存活时收到后回推一次全量镜像。
     */
    const val ACTION_REQUEST_MIRROR_PUSH =
        "${BuildConfig.PACKAGE_NAME}.ACTION_REQUEST_MIRROR_PUSH"

    /** 宿主通知「关闭弹窗」：关闭目标当前 showing 的外来 Dialog，并生成弹窗规则 */
    const val ACTION_CLOSE_POPUPS =
        "${BuildConfig.PACKAGE_NAME}.ACTION_CLOSE_POPUPS"

    /**
     * 目标→宿主：检测到外来弹窗（DialogHooker show 时上报，目标端节流），
     * 宿主收到后弹通知询问是否关闭。EXTRA_LABEL 携带弹窗描述（首条文本或类名）。
     */
    const val ACTION_POPUP_DETECTED =
        "${BuildConfig.PACKAGE_NAME}.ACTION_POPUP_DETECTED"

    /**
     * 目标→宿主：一个弹窗已被关闭（规则自动重放或手动关闭），写入宿主的拦截记录。
     * EXTRA_LABEL 携带弹窗描述，[EXTRA_KIND] 区分自动/手动。
     */
    const val ACTION_POPUP_INTERCEPTED =
        "${BuildConfig.PACKAGE_NAME}.ACTION_POPUP_INTERCEPTED"

    /**
     * 目标→宿主：一次控件点击被拦截（编辑模式下触摸被模块消费、原点击未执行），
     * 宿主累计到首页统计卡「已拦截次数」。仅计数，不写拦截记录。
     */
    const val ACTION_CONTROL_INTERCEPTED =
        "${BuildConfig.PACKAGE_NAME}.ACTION_CONTROL_INTERCEPTED"

    const val KIND_AUTO = "auto"
    const val KIND_MANUAL = "manual"

    /**
     * 宿主规则详情页「删除弹窗规则」：权威 dialog_rules.json 在目标应用私有目录，
     * 只能由目标进程执行删除；广播携带 DialogRule JSON 数组（目标端按 key 定位）。
     */
    const val ACTION_DELETE_DIALOG_RULES =
        "${BuildConfig.PACKAGE_NAME}.ACTION_DELETE_DIALOG_RULES"

    /**
     * 目标→宿主：拉取「强制弹窗拦截」开关状态（目标端 Provider 查询被包可见性
     * 挡住，改为广播同步）。宿主回发 [ACTION_POPUP_INTERCEPT_STATE]。
     */
    const val ACTION_POPUP_INTERCEPT_REQUEST =
        "${BuildConfig.PACKAGE_NAME}.ACTION_POPUP_INTERCEPT_REQUEST"

    /** 宿主→目标：下发开关状态，[EXTRA_ENABLED] 携带 true/false */
    const val ACTION_POPUP_INTERCEPT_STATE =
        "${BuildConfig.PACKAGE_NAME}.ACTION_POPUP_INTERCEPT_STATE"

    const val EXTRA_PACKAGE = "package_name"
    const val EXTRA_LABEL = "app_label"
    const val EXTRA_EDIT_ENABLED = "edit_enabled"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_RULES_JSON = "rules_json"
    const val EXTRA_DIALOG_RULES_JSON = "dialog_rules_json"
    const val EXTRA_KIND = "popup_kind"
    const val EXTRA_ENABLED = "popup_enabled"
    const val EXTRA_WINDOW_SECONDS = "popup_window_seconds"

    /**
     * 覆盖导入：置 true 时目标端先清空该应用全部规则再 upsert（恢复备份用）。
     * 只放在**首个分片**上（分片按序投递、目标端幂等），旧目标端忽略此 extra
     * 表现为普通合并，无兼容破坏。
     */
    const val EXTRA_OVERWRITE = "overwrite_import"
    const val CONTROL_TOKEN = "godviewer-host-control-v1"

    /** 控制广播单片 JSON 载荷软上限（镜像协议 700KB 先例，留 Intent 开销余量） */
    private const val MAX_PAYLOAD_BYTES = 500 * 1024

    private const val HOST_RECEIVER = "com.godviewer.app.data.HostControlReceiver"
    private const val PREFS = "godviewer_host_control"
    private const val KEY_PACKAGE = "fg_package"
    private const val KEY_LABEL = "fg_label"
    private const val KEY_EDIT_ENABLED = "fg_edit_enabled"
    private const val KEY_UPDATED_AT = "fg_updated_at"

    data class TargetState(
        val packageName: String,
        val label: String,
        val editEnabled: Boolean,
        val updatedAt: Long,
    )

    // ---------- Target process ----------

    /** 目标端检测到外来弹窗时上报宿主（显式广播，best-effort） */
    fun reportPopupDetected(context: Context, packageName: String, dialogLabel: String) {
        runCatching {
            val app = context.applicationContext
            val intent = Intent(ACTION_POPUP_DETECTED).apply {
                component = ComponentName(BuildConfig.PACKAGE_NAME, HOST_RECEIVER)
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_LABEL, dialogLabel.take(80))
                putExtra(EXTRA_TOKEN, CONTROL_TOKEN)
            }
            app.sendBroadcast(intent)
            GvLog.d(TAG, "reportPopupDetected pkg=$packageName label=$dialogLabel")
        }.onFailure {
            GvLog.w(TAG, "reportPopupDetected failed", it)
        }
    }

    /** 目标→宿主：弹窗已被关闭，供宿主写拦截记录（best-effort） */
    fun reportPopupIntercepted(
        context: Context,
        packageName: String,
        dialogLabel: String,
        kind: String,
    ) {
        runCatching {
            val intent = Intent(ACTION_POPUP_INTERCEPTED).apply {
                component = ComponentName(BuildConfig.PACKAGE_NAME, HOST_RECEIVER)
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_LABEL, dialogLabel.take(80))
                putExtra(EXTRA_KIND, kind)
                putExtra(EXTRA_TOKEN, CONTROL_TOKEN)
            }
            context.applicationContext.sendBroadcast(intent)
        }.onFailure {
            GvLog.w(TAG, "reportPopupIntercepted failed", it)
        }
    }

    /** 目标→宿主：一次控件点击被拦截，供宿主累计首页「已拦截次数」（best-effort） */
    fun reportControlIntercepted(context: Context, packageName: String) {
        runCatching {
            val intent = Intent(ACTION_CONTROL_INTERCEPTED).apply {
                component = ComponentName(BuildConfig.PACKAGE_NAME, HOST_RECEIVER)
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_TOKEN, CONTROL_TOKEN)
            }
            context.applicationContext.sendBroadcast(intent)
        }.onFailure {
            GvLog.w(TAG, "reportControlIntercepted failed", it)
        }
    }

    /** 目标→宿主：请求当前「强制弹窗拦截」开关状态（启动/过期时拉取，best-effort） */
    fun requestPopupInterceptState(context: Context, packageName: String) {
        runCatching {
            val app = context.applicationContext
            val intent = Intent(ACTION_POPUP_INTERCEPT_REQUEST).apply {
                component = ComponentName(BuildConfig.PACKAGE_NAME, HOST_RECEIVER)
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_TOKEN, CONTROL_TOKEN)
            }
            app.sendBroadcast(intent)
            GvLog.d(TAG, "requestPopupInterceptState pkg=$packageName")
        }.onFailure {
            GvLog.w(TAG, "requestPopupInterceptState failed", it)
        }
    }

    fun reportForeground(context: Context, packageName: String, editEnabled: Boolean) {
        runCatching {
            val app = context.applicationContext
            val label = resolveLabel(app, packageName)
            val intent = Intent(ACTION_TARGET_FOREGROUND).apply {
                component = ComponentName(BuildConfig.PACKAGE_NAME, HOST_RECEIVER)
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_EDIT_ENABLED, editEnabled)
                putExtra(EXTRA_TOKEN, CONTROL_TOKEN)
            }
            app.sendBroadcast(intent)
            GvLog.d(TAG, "reportForeground pkg=$packageName edit=$editEnabled")
        }.onFailure {
            GvLog.w(TAG, "reportForeground failed", it)
        }
    }

    // ---------- Host process ----------

    fun saveTargetState(
        context: Context,
        packageName: String,
        label: String,
        editEnabled: Boolean,
    ) {
        val safePkg = packageName.trim()
        if (safePkg.isEmpty() || safePkg == BuildConfig.PACKAGE_NAME) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PACKAGE, safePkg)
            .putString(KEY_LABEL, label.ifBlank { safePkg })
            .putBoolean(KEY_EDIT_ENABLED, editEnabled)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun currentTarget(context: Context): TargetState? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pkg = prefs.getString(KEY_PACKAGE, null)?.trim().orEmpty()
        if (pkg.isEmpty()) return null
        return TargetState(
            packageName = pkg,
            label = prefs.getString(KEY_LABEL, pkg).orEmpty().ifBlank { pkg },
            editEnabled = prefs.getBoolean(KEY_EDIT_ENABLED, false),
            updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L),
        )
    }

    /** Clear remembered target (e.g. when host UI is opened again). */
    fun clearTargetState(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PACKAGE)
            .remove(KEY_LABEL)
            .remove(KEY_EDIT_ENABLED)
            .remove(KEY_UPDATED_AT)
            .apply()
        GvLog.d(TAG, "target state cleared")
    }

    /** 下发命令给「最近上报的目标应用」。 */
    fun dispatchToTarget(context: Context, action: String): Boolean {
        val target = currentTarget(context) ?: run {
            GvLog.d(TAG, "dispatch skip: no target for $action")
            return false
        }
        return dispatchToPackage(context, target.packageName, action)
    }

    /**
     * 下发命令给指定包：宿主 UI 直接操作某个应用时使用，不依赖「最近目标」
     * （进入宿主界面会清空该状态）。
     *
     * 目标进程存活时其动态接收器会收到；进程不在则广播静默丢弃，
     * 调用方需自行确认结果（例如比对镜像是否已更新）。
     */
    /**
     * 宿主→目标：下发「强制弹窗拦截」开关状态（回发 REQUEST 的应答 +
     * 开关切换时向 watchers 主动推送）。同 [dispatchToPackage] 的定向
     * package 广播，目标进程不存活时静默丢弃（其缓存里仍有上次状态）。
     */
    fun dispatchPopupInterceptState(
        context: Context,
        targetPackage: String,
        enabled: Boolean,
        windowSeconds: Int,
    ): Boolean {
        val pkg = targetPackage.trim()
        if (pkg.isEmpty() || pkg == BuildConfig.PACKAGE_NAME) {
            GvLog.d(TAG, "dispatch skip: bad package '$targetPackage' for intercept state")
            return false
        }
        return runCatching {
            val intent = Intent(ACTION_POPUP_INTERCEPT_STATE).apply {
                setPackage(pkg)
                putExtra(EXTRA_TOKEN, CONTROL_TOKEN)
                putExtra(EXTRA_PACKAGE, pkg)
                putExtra(EXTRA_ENABLED, enabled)
                putExtra(EXTRA_WINDOW_SECONDS, windowSeconds)
            }
            context.applicationContext.sendBroadcast(intent)
            GvLog.d(TAG, "dispatch intercept state=$enabled -> $pkg")
            true
        }.onFailure {
            GvLog.w(TAG, "dispatch intercept state failed pkg=$pkg", it)
        }.getOrDefault(false)
    }

    fun dispatchToPackage(context: Context, targetPackage: String, action: String): Boolean {
        val pkg = targetPackage.trim()
        if (pkg.isEmpty() || pkg == BuildConfig.PACKAGE_NAME) {
            GvLog.d(TAG, "dispatch skip: bad package '$targetPackage' for $action")
            return false
        }
        return runCatching {
            val intent = Intent(action).apply {
                setPackage(pkg)
                putExtra(EXTRA_TOKEN, CONTROL_TOKEN)
                putExtra(EXTRA_PACKAGE, pkg)
            }
            context.applicationContext.sendBroadcast(intent)
            GvLog.d(TAG, "dispatch $action -> $pkg")
            true
        }.onFailure {
            GvLog.w(TAG, "dispatch failed action=$action pkg=$pkg", it)
        }.getOrDefault(false)
    }

    /**
     * 下发「删除规则（单条或多条）」给指定包：extras 携带规则 JSON 数组。
     * 同 [dispatchToPackage]，目标进程不存活时广播被静默丢弃，
     * 调用方需回读镜像按 rule key 确认结果。
     */
    fun dispatchRulesToPackage(
        context: Context,
        targetPackage: String,
        rules: List<ViewRule>,
    ): Boolean {
        if (rules.isEmpty()) {
            GvLog.d(TAG, "dispatch skip: empty rules for $ACTION_DELETE_RULES")
            return false
        }
        val pkg = targetPackage.trim()
        if (pkg.isEmpty() || pkg == BuildConfig.PACKAGE_NAME) {
            GvLog.d(TAG, "dispatch skip: bad package '$targetPackage' for $ACTION_DELETE_RULES")
            return false
        }
        return runCatching {
            val intent = Intent(ACTION_DELETE_RULES).apply {
                setPackage(pkg)
                putExtra(EXTRA_TOKEN, CONTROL_TOKEN)
                putExtra(EXTRA_PACKAGE, pkg)
                putExtra(EXTRA_RULES_JSON, Gson().toJson(rules))
            }
            context.applicationContext.sendBroadcast(intent)
            GvLog.d(TAG, "dispatch $ACTION_DELETE_RULES -> $pkg count=${rules.size}")
            true
        }.onFailure {
            GvLog.w(TAG, "dispatch failed action=$ACTION_DELETE_RULES pkg=$pkg", it)
        }.getOrDefault(false)
    }

    /**
     * 下发「删除弹窗规则（单条或多条）」给指定包：extras 携带 DialogRule JSON 数组。
     * 同 [dispatchToPackage]，目标进程不存活时广播被静默丢弃，
     * 调用方需回读镜像按 key 确认结果。
     */
    fun dispatchDialogRulesToPackage(
        context: Context,
        targetPackage: String,
        rules: List<DialogRule>,
    ): Boolean {
        if (rules.isEmpty()) {
            GvLog.d(TAG, "dispatch skip: empty dialog rules for $ACTION_DELETE_DIALOG_RULES")
            return false
        }
        val pkg = targetPackage.trim()
        if (pkg.isEmpty() || pkg == BuildConfig.PACKAGE_NAME) {
            GvLog.d(TAG, "dispatch skip: bad package '$targetPackage' for $ACTION_DELETE_DIALOG_RULES")
            return false
        }
        return runCatching {
            val intent = Intent(ACTION_DELETE_DIALOG_RULES).apply {
                setPackage(pkg)
                putExtra(EXTRA_TOKEN, CONTROL_TOKEN)
                putExtra(EXTRA_PACKAGE, pkg)
                putExtra(EXTRA_DIALOG_RULES_JSON, Gson().toJson(rules))
            }
            context.applicationContext.sendBroadcast(intent)
            GvLog.d(TAG, "dispatch $ACTION_DELETE_DIALOG_RULES -> $pkg count=${rules.size}")
            true
        }.onFailure {
            GvLog.w(TAG, "dispatch failed action=$ACTION_DELETE_DIALOG_RULES pkg=$pkg", it)
        }.getOrDefault(false)
    }

    /**
     * 下发「编辑/更新视图规则（upsert）」给指定包：extras 携带完整规则 JSON 数组
     * （含 modified 全量值，目标端原样落盘不重写 timestamp，便于回读全等确认）。
     * 载荷超限自动分片多发；目标进程不存活时广播被静默丢弃，
     * 调用方应配合 [com.godviewer.app.host.control.PendingCommandStore] 排队重发。
     */
    fun dispatchUpdateRules(
        context: Context,
        targetPackage: String,
        rules: List<ViewRule>,
    ): Boolean = dispatchChunked(
        context,
        targetPackage,
        ACTION_UPDATE_RULES,
        rules,
        emptyList(),
    )

    /**
     * 下发「导入规则」给指定包：视图规则 / 弹窗规则至少其一，
     * 分片多次广播；目标进程不存活时静默丢弃，调用方应配合待同步队列排队重发。
     *
     * @param overwrite true = 目标端先清空该应用全部规则再合并（覆盖导入）
     */
    fun dispatchImportRules(
        context: Context,
        targetPackage: String,
        viewRules: List<ViewRule>,
        dialogRules: List<DialogRule>,
        overwrite: Boolean = false,
    ): Boolean {
        if (viewRules.isEmpty() && dialogRules.isEmpty()) {
            GvLog.d(TAG, "dispatch skip: empty import for $ACTION_IMPORT_RULES")
            return false
        }
        return dispatchChunked(
            context,
            targetPackage,
            ACTION_IMPORT_RULES,
            viewRules,
            dialogRules,
            overwriteFirstChunk = overwrite,
        )
    }

    /** 请求指定包回推一次全量镜像（导出/刷新前调用，best-effort） */
    fun requestMirrorPush(context: Context, targetPackage: String): Boolean =
        dispatchToPackage(context, targetPackage, ACTION_REQUEST_MIRROR_PUSH)

    /**
     * 分片通用发送：视图规则按 ~500KB 上限分片各发一条广播
     * （镜像协议先例 700KB，留 Intent 开销余量），弹窗规则另自分片；
     * 每片独立、目标端幂等合并，丢一片只影响该片。
     * [overwriteFirstChunk] 为 true 时仅在发出的第一片带 [EXTRA_OVERWRITE]
     * （分片按序投递，目标端看到该标记先清空再合并）。
     */
    private fun dispatchChunked(
        context: Context,
        targetPackage: String,
        action: String,
        viewRules: List<ViewRule>,
        dialogRules: List<DialogRule>,
        overwriteFirstChunk: Boolean = false,
    ): Boolean {
        val pkg = targetPackage.trim()
        if (pkg.isEmpty() || pkg == BuildConfig.PACKAGE_NAME) {
            GvLog.d(TAG, "dispatch skip: bad package '$targetPackage' for $action")
            return false
        }
        val gson = Gson()
        var sent = false
        runCatching {
            var overwritePending = overwriteFirstChunk
            val viewChunks = chunkBySize(viewRules, gson)
            val dialogChunks = chunkBySize(dialogRules, gson)
            for (chunk in viewChunks) {
                val intent = Intent(action).apply {
                    setPackage(pkg)
                    putExtra(EXTRA_TOKEN, CONTROL_TOKEN)
                    putExtra(EXTRA_PACKAGE, pkg)
                    putExtra(EXTRA_RULES_JSON, gson.toJson(chunk))
                    if (overwritePending) {
                        putExtra(EXTRA_OVERWRITE, true)
                        overwritePending = false
                    }
                }
                context.applicationContext.sendBroadcast(intent)
                sent = true
            }
            for (chunk in dialogChunks) {
                val intent = Intent(action).apply {
                    setPackage(pkg)
                    putExtra(EXTRA_TOKEN, CONTROL_TOKEN)
                    putExtra(EXTRA_PACKAGE, pkg)
                    putExtra(EXTRA_DIALOG_RULES_JSON, gson.toJson(chunk))
                    if (overwritePending) {
                        putExtra(EXTRA_OVERWRITE, true)
                        overwritePending = false
                    }
                }
                context.applicationContext.sendBroadcast(intent)
                sent = true
            }
            if (sent) {
                GvLog.d(
                    TAG,
                    "dispatch $action -> $pkg " +
                        "viewChunks=${viewChunks.size} dialogChunks=${dialogChunks.size} " +
                        "overwrite=$overwriteFirstChunk",
                )
            }
        }.onFailure {
            GvLog.w(TAG, "dispatch failed action=$action pkg=$pkg", it)
        }
        return sent
    }

    /** 按序列化字节数把规则列表切成多片（单片不超过 [MAX_PAYLOAD_BYTES]） */
    private fun <T> chunkBySize(items: List<T>, gson: Gson): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        val chunks = mutableListOf<List<T>>()
        var buf = mutableListOf<T>()
        var bufBytes = 0L
        for (item in items) {
            val size = runCatching { gson.toJson(item).toByteArray(Charsets.UTF_8).size }
                .getOrDefault(0)
            if (buf.isNotEmpty() && bufBytes + size > MAX_PAYLOAD_BYTES) {
                chunks += buf
                buf = mutableListOf()
                bufBytes = 0L
            }
            buf += item
            bufBytes += size
        }
        if (buf.isNotEmpty()) chunks += buf
        return chunks
    }

    private fun resolveLabel(context: Context, packageName: String): String {
        return resolveAppLabel(context, packageName)
    }
}
