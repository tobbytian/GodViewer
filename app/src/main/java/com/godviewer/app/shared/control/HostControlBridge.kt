package com.godviewer.app.shared.control

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.godviewer.app.BuildConfig
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
    private const val TAG = "GodViewer.Control"

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
     * 私有目录，只能由目标进程执行；广播携带规则 JSON 数组（目标端仅取 key 定位）。
     */
    const val ACTION_DELETE_RULES =
        "${BuildConfig.PACKAGE_NAME}.ACTION_DELETE_RULES"

    const val EXTRA_PACKAGE = "package_name"
    const val EXTRA_LABEL = "app_label"
    const val EXTRA_EDIT_ENABLED = "edit_enabled"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_RULES_JSON = "rules_json"
    const val CONTROL_TOKEN = "godviewer-host-control-v1"

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
            Log.d(TAG, "reportForeground pkg=$packageName edit=$editEnabled")
        }.onFailure {
            Log.w(TAG, "reportForeground failed", it)
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
        Log.d(TAG, "target state cleared")
    }

    /** 下发命令给「最近上报的目标应用」。 */
    fun dispatchToTarget(context: Context, action: String): Boolean {
        val target = currentTarget(context) ?: run {
            Log.d(TAG, "dispatch skip: no target for $action")
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
    fun dispatchToPackage(context: Context, targetPackage: String, action: String): Boolean {
        val pkg = targetPackage.trim()
        if (pkg.isEmpty() || pkg == BuildConfig.PACKAGE_NAME) {
            Log.d(TAG, "dispatch skip: bad package '$targetPackage' for $action")
            return false
        }
        return runCatching {
            val intent = Intent(action).apply {
                setPackage(pkg)
                putExtra(EXTRA_TOKEN, CONTROL_TOKEN)
                putExtra(EXTRA_PACKAGE, pkg)
            }
            context.applicationContext.sendBroadcast(intent)
            Log.d(TAG, "dispatch $action -> $pkg")
            true
        }.onFailure {
            Log.w(TAG, "dispatch failed action=$action pkg=$pkg", it)
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
            Log.d(TAG, "dispatch skip: empty rules for $ACTION_DELETE_RULES")
            return false
        }
        val pkg = targetPackage.trim()
        if (pkg.isEmpty() || pkg == BuildConfig.PACKAGE_NAME) {
            Log.d(TAG, "dispatch skip: bad package '$targetPackage' for $ACTION_DELETE_RULES")
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
            Log.d(TAG, "dispatch $ACTION_DELETE_RULES -> $pkg count=${rules.size}")
            true
        }.onFailure {
            Log.w(TAG, "dispatch failed action=$ACTION_DELETE_RULES pkg=$pkg", it)
        }.getOrDefault(false)
    }

    private fun resolveLabel(context: Context, packageName: String): String {
        return resolveAppLabel(context, packageName)
    }
}
