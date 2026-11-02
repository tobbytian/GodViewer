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

    /**
     * 首页「强制弹窗拦截」开关（默认关闭）：打开后目标端才检测弹窗、
     * 询问通知与规则自动重放；关闭则功能整体静默。
     */
    const val KEY_POPUP_INTERCEPT_ENABLED = "popup_intercept_enabled"

    /** 「弹窗检测」开关（询问通知）；缺省时沿用旧的拦截开关值，升级后行为不变 */
    const val KEY_POPUP_DETECT_ENABLED = "popup_detect_enabled"

    /** 应用打开后检测/拦截弹窗的时间窗（秒） */
    const val KEY_POPUP_WINDOW_SECONDS = "popup_window_seconds"

    /**
     * 每应用「弹窗检测通知」开关（前缀 + 包名，Boolean）：
     * 单独设置过按该值，否则沿用全局 [KEY_POPUP_DETECT_ENABLED]。
     */
    const val KEY_POPUP_DETECT_PACKAGE_PREFIX = "popup_detect_enabled_"

    /**
     * 每应用「检测时长」（前缀 + 包名，Int 秒）：
     * 单独设置过按该值，否则沿用全局 [KEY_POPUP_WINDOW_SECONDS]。
     */
    const val KEY_POPUP_WINDOW_PACKAGE_PREFIX = "popup_window_seconds_"

    /**
     * 拉取过拦截开关状态的目标包集合（StringSet，仅宿主读写）：
     * 开关切换时把最新开关状态定向推送给这些包（目标端动态接收器）。
     */
    const val KEY_POPUP_INTERCEPT_WATCHERS = "popup_intercept_watchers"

    /**
     * 首页统计卡「已拦截次数」累计（仅宿主读写）：
     * 弹窗关闭（规则自动重放 + 手动）与控件点击被拦截（编辑模式触摸消费）各计一份。
     */
    const val KEY_INTERCEPT_COUNT_POPUP = "intercept_count_popup"
    const val KEY_INTERCEPT_COUNT_CONTROL = "intercept_count_control"

    /**
     * 开关状态的 Provider URI（authority 协议冻结）。**目标端因 Android 11+
     * 包可见性查不到此 path（query 静默返回 null）**，状态同步已改为广播
     * （ACTION_POPUP_INTERCEPT_REQUEST/STATE）；此 path 保留供 adb/shell 调试。
     */
    const val POPUP_INTERCEPT_URI = "content://com.godviewer.app.hostprefs/popup_intercept"

    /** [POPUP_INTERCEPT_URI] 返回列名：1 = 开，0 = 关 */
    const val COLUMN_POPUP_INTERCEPT = "enabled"
}
