package com.godviewer.app.shared

/**
 * 宿主 SharedPreferences 文件名与键名（host / target 只读路径共用）。
 * 真正读写实现：host 侧 [com.godviewer.app.host.prefs.HostPrefs]，
 * shared 侧 [com.godviewer.app.shared.entry.EntryMode] / [AppLanguage] 可直接按同名键访问，
 * 避免 shared → host 编译依赖。
 */
object HostPrefsNames {
    const val PREFS_NAME = "godviewer_host_prefs"

    /** 历史入口模式键（协议冻结）：现存值恒为 "host"，仅供旧数据兼容读取。 */
    const val KEY_ENTRY_MODE = "entry_mode"

    /** 首页「本体通知」开关：关闭时不显示常驻控制通知（默认关闭）。 */
    const val KEY_HOST_NOTIFY_ENABLED = "host_notify_enabled"

    /**
     * 每应用通知黑名单（StringSet）：其中的包在前台时不显示本体控制通知。
     * 仅在首页开关打开时有意义；由规则管理页的每应用开关维护。
     */
    const val KEY_NOTIFY_DISABLED_PACKAGES = "notify_disabled_packages"
    const val KEY_APP_LANGUAGE = "app_language"
    const val KEY_HIDE_LAUNCHER_ICON = "hide_launcher_icon"
    const val KEY_AUTO_UPDATE = "auto_update"
    const val KEY_RULES_SYNC_TIP_DISMISSED = "rules_sync_tip_dismissed"
    const val KEY_UPDATE_SKIP_TAG = "update_skip_tag"
}
