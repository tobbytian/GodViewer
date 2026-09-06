package com.godviewer.app.shared

/**
 * 宿主 SharedPreferences 文件名与键名（host / target 只读路径共用）。
 * 真正读写实现：host 侧 [com.godviewer.app.host.prefs.HostPrefs]，
 * shared 侧 [com.godviewer.app.shared.entry.EntryMode] / [AppLanguage] 可直接按同名键访问，
 * 避免 shared → host 编译依赖。
 */
object HostPrefsNames {
    const val PREFS_NAME = "godviewer_host_prefs"

    const val KEY_ENTRY_MODE = "entry_mode"
    const val KEY_APP_LANGUAGE = "app_language"
    const val KEY_HIDE_LAUNCHER_ICON = "hide_launcher_icon"
    const val KEY_AUTO_UPDATE = "auto_update"
    const val KEY_RULES_SYNC_TIP_DISMISSED = "rules_sync_tip_dismissed"
    const val KEY_UPDATE_SKIP_TAG = "update_skip_tag"
}
