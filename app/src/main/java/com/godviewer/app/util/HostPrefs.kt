package com.godviewer.app.util

import android.content.Context

/** Lightweight host-app preferences (settings page). */
object HostPrefs {
    private const val PREFS = "godviewer_host_prefs"
    private const val KEY_HIDE_LAUNCHER_ICON = "hide_launcher_icon"
    private const val KEY_AUTO_UPDATE = "auto_update"
    private const val KEY_RULES_SYNC_TIP_DISMISSED = "rules_sync_tip_dismissed"
    private const val KEY_APP_LANGUAGE = "app_language"

    /**
     * Prefer the given [context] directly.
     * During Application.attachBaseContext, applicationContext may still be null.
     */
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isLauncherIconHidden(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_LAUNCHER_ICON, false)

    fun setLauncherIconHidden(context: Context, hidden: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_LAUNCHER_ICON, hidden).apply()
    }

    fun isAutoUpdateEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_UPDATE, false)

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply()
    }

    fun isRulesSyncTipDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RULES_SYNC_TIP_DISMISSED, false)

    fun setRulesSyncTipDismissed(context: Context, dismissed: Boolean) {
        prefs(context).edit().putBoolean(KEY_RULES_SYNC_TIP_DISMISSED, dismissed).apply()
    }

    fun getAppLanguage(context: Context): String =
        prefs(context).getString(KEY_APP_LANGUAGE, "system") ?: "system"

    fun setAppLanguage(context: Context, language: String) {
        prefs(context).edit().putString(KEY_APP_LANGUAGE, language).apply()
    }
}
