package com.godviewer.app.host.prefs

import android.content.Context
import com.godviewer.app.shared.HostPrefsNames
import com.godviewer.app.shared.entry.EntryMode

/**
 * Lightweight host-app preferences (settings page).
 * 键名集中在 [HostPrefsNames]，与 shared 侧 EntryMode/AppLanguage 对齐。
 */
object HostPrefs {
    const val PREFS_NAME = HostPrefsNames.PREFS_NAME
    const val KEY_ENTRY_MODE = HostPrefsNames.KEY_ENTRY_MODE

    /**
     * Prefer the given [context] directly.
     * During Application.attachBaseContext, applicationContext may still be null.
     */
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isLauncherIconHidden(context: Context): Boolean =
        prefs(context).getBoolean(HostPrefsNames.KEY_HIDE_LAUNCHER_ICON, false)

    fun setLauncherIconHidden(context: Context, hidden: Boolean) {
        prefs(context).edit().putBoolean(HostPrefsNames.KEY_HIDE_LAUNCHER_ICON, hidden).apply()
    }

    /** 默认开启：打开应用时在线检查更新 */
    fun isAutoUpdateEnabled(context: Context): Boolean =
        prefs(context).getBoolean(HostPrefsNames.KEY_AUTO_UPDATE, true)

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(HostPrefsNames.KEY_AUTO_UPDATE, enabled).apply()
    }

    /** 「不再提示」记下的 release tag（如 v3.4.0）；仅跳过该版本 */
    fun getUpdateSkipTag(context: Context): String? =
        prefs(context).getString(HostPrefsNames.KEY_UPDATE_SKIP_TAG, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun setUpdateSkipTag(context: Context, tag: String?) {
        val edit = prefs(context).edit()
        if (tag.isNullOrBlank()) {
            edit.remove(HostPrefsNames.KEY_UPDATE_SKIP_TAG)
        } else {
            edit.putString(HostPrefsNames.KEY_UPDATE_SKIP_TAG, tag.trim())
        }
        edit.apply()
    }

    fun isRulesSyncTipDismissed(context: Context): Boolean =
        prefs(context).getBoolean(HostPrefsNames.KEY_RULES_SYNC_TIP_DISMISSED, false)

    fun setRulesSyncTipDismissed(context: Context, dismissed: Boolean) {
        prefs(context).edit()
            .putBoolean(HostPrefsNames.KEY_RULES_SYNC_TIP_DISMISSED, dismissed)
            .apply()
    }

    fun getAppLanguage(context: Context): String =
        prefs(context).getString(HostPrefsNames.KEY_APP_LANGUAGE, "system") ?: "system"

    fun setAppLanguage(context: Context, language: String) {
        prefs(context).edit().putString(HostPrefsNames.KEY_APP_LANGUAGE, language).apply()
    }

    /** 默认目标应用通知入口；委托 [EntryMode] 保持单一数据源 */
    fun getEntryMode(context: Context): String = EntryMode.current(context)
}
