package com.godviewer.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.godviewer.app.BuildConfig

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

    const val EXTRA_PACKAGE = "package_name"
    const val EXTRA_LABEL = "app_label"
    const val EXTRA_EDIT_ENABLED = "edit_enabled"
    const val EXTRA_TOKEN = "token"
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

    fun dispatchToTarget(context: Context, action: String): Boolean {
        val target = currentTarget(context) ?: run {
            Log.d(TAG, "dispatch skip: no target for $action")
            return false
        }
        return runCatching {
            val intent = Intent(action).apply {
                setPackage(target.packageName)
                putExtra(EXTRA_TOKEN, CONTROL_TOKEN)
                putExtra(EXTRA_PACKAGE, target.packageName)
            }
            context.applicationContext.sendBroadcast(intent)
            Log.d(TAG, "dispatch $action -> ${target.packageName}")
            true
        }.onFailure {
            Log.w(TAG, "dispatch failed action=$action", it)
        }.getOrDefault(false)
    }

    private fun resolveLabel(context: Context, packageName: String): String {
        return runCatching {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    }
