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

    /**
     * 首页「本体通知」开关：只有打开时才显示本体常驻控制通知（默认关闭，
     * 与需求「开关打开后才会显示本体通知」一致）。
     */
    fun isHostNotifyEnabled(context: Context): Boolean =
        prefs(context).getBoolean(HostPrefsNames.KEY_HOST_NOTIFY_ENABLED, false)

    fun setHostNotifyEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(HostPrefsNames.KEY_HOST_NOTIFY_ENABLED, enabled).apply()
    }

    /**
     * 每应用通知开关（规则管理页维护）：黑名单中的包在前台时不显示本体控制通知。
     * 仅首页开关打开时有意义。
     */
    fun isAppNotifyDisabled(context: Context, packageName: String): Boolean =
        prefs(context).getStringSet(HostPrefsNames.KEY_NOTIFY_DISABLED_PACKAGES, emptySet())
            ?.contains(packageName) == true

    fun setAppNotifyDisabled(context: Context, packageName: String, disabled: Boolean) {
        val prefs = prefs(context)
        // getStringSet 返回的集合不可直接修改，必须先拷贝
        val set = (prefs.getStringSet(HostPrefsNames.KEY_NOTIFY_DISABLED_PACKAGES, emptySet())
            ?: emptySet()).toMutableSet()
        val changed = if (disabled) set.add(packageName) else set.remove(packageName)
        if (!changed) return
        prefs.edit().putStringSet(HostPrefsNames.KEY_NOTIFY_DISABLED_PACKAGES, set).apply()
    }

    /**
     * 首页「强制弹窗拦截」开关（**默认开启**，内测期免配置；用户显式关过则尊重
     * 存储值）：打开后目标端才检测弹窗、发询问通知并按规则自动重放关闭；
     * 关闭则整体静默（手动关闭按钮不受影响）。
     * 目标进程查不到宿主 Provider（包可见性），状态经广播同步，见
     * [com.godviewer.app.shared.entry.PopupIntercept]。
     */
    fun isPopupInterceptEnabled(context: Context): Boolean =
        prefs(context).getBoolean(HostPrefsNames.KEY_POPUP_INTERCEPT_ENABLED, true)

    fun setPopupInterceptEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(HostPrefsNames.KEY_POPUP_INTERCEPT_ENABLED, enabled)
            .apply()
    }

    /** 弹窗检测开关（询问通知）；从未设置过则沿用旧的拦截开关值 */
    fun isPopupDetectEnabled(context: Context): Boolean {
        val p = prefs(context)
        return if (p.contains(HostPrefsNames.KEY_POPUP_DETECT_ENABLED)) {
            p.getBoolean(HostPrefsNames.KEY_POPUP_DETECT_ENABLED, false)
        } else {
            isPopupInterceptEnabled(context)
        }
    }

    fun setPopupDetectEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(HostPrefsNames.KEY_POPUP_DETECT_ENABLED, enabled)
            .apply()
    }

    /** 应用打开后检测/拦截弹窗的时间窗（秒），默认 [DEFAULT_POPUP_WINDOW_SECONDS] */
    fun getPopupWindowSeconds(context: Context): Int =
        prefs(context).getInt(HostPrefsNames.KEY_POPUP_WINDOW_SECONDS, DEFAULT_POPUP_WINDOW_SECONDS)
            .coerceIn(MIN_POPUP_WINDOW_SECONDS, MAX_POPUP_WINDOW_SECONDS)

    fun setPopupWindowSeconds(context: Context, seconds: Int) {
        prefs(context).edit()
            .putInt(
                HostPrefsNames.KEY_POPUP_WINDOW_SECONDS,
                seconds.coerceIn(MIN_POPUP_WINDOW_SECONDS, MAX_POPUP_WINDOW_SECONDS),
            )
            .apply()
    }

    /**
     * 每应用「弹窗检测通知」开关（单应用规则页维护）：单独设置过按该值，
     * 从未设置过则沿用全局 [isPopupDetectEnabled]（升级后行为不变）。
     */
    fun isPopupDetectEnabledFor(context: Context, packageName: String): Boolean {
        val p = prefs(context)
        val key = HostPrefsNames.KEY_POPUP_DETECT_PACKAGE_PREFIX + packageName
        return if (p.contains(key)) {
            p.getBoolean(key, false)
        } else {
            isPopupDetectEnabled(context)
        }
    }

    fun setPopupDetectEnabledFor(context: Context, packageName: String, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(HostPrefsNames.KEY_POPUP_DETECT_PACKAGE_PREFIX + packageName, enabled)
            .apply()
    }

    /** 每应用「检测时长」（秒）：单独设置过按该值，否则沿用全局时长 */
    fun getPopupWindowSecondsFor(context: Context, packageName: String): Int {
        val p = prefs(context)
        val key = HostPrefsNames.KEY_POPUP_WINDOW_PACKAGE_PREFIX + packageName
        val raw = if (p.contains(key)) {
            p.getInt(key, DEFAULT_POPUP_WINDOW_SECONDS)
        } else {
            getPopupWindowSeconds(context)
        }
        return raw.coerceIn(MIN_POPUP_WINDOW_SECONDS, MAX_POPUP_WINDOW_SECONDS)
    }

    fun setPopupWindowSecondsFor(context: Context, packageName: String, seconds: Int) {
        prefs(context).edit()
            .putInt(
                HostPrefsNames.KEY_POPUP_WINDOW_PACKAGE_PREFIX + packageName,
                seconds.coerceIn(MIN_POPUP_WINDOW_SECONDS, MAX_POPUP_WINDOW_SECONDS),
            )
            .apply()
    }

    /** 拉取过拦截开关状态的目标包（开关切换时定向推送最新状态给它们） */
    fun popupInterceptWatchers(context: Context): Set<String> =
        prefs(context).getStringSet(HostPrefsNames.KEY_POPUP_INTERCEPT_WATCHERS, emptySet())
            ?: emptySet()

    fun addPopupInterceptWatcher(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        val prefs = prefs(context)
        val set = (prefs.getStringSet(
            HostPrefsNames.KEY_POPUP_INTERCEPT_WATCHERS,
            emptySet(),
        ) ?: emptySet()).toMutableSet()
        if (!set.add(packageName)) return
        prefs.edit().putStringSet(HostPrefsNames.KEY_POPUP_INTERCEPT_WATCHERS, set).apply()
    }

    /** 首页统计卡「已拦截次数」累计：弹窗关闭（规则自动重放 + 手动），收到上报 +1 */
    fun addPopupInterceptCount(context: Context) {
        val p = prefs(context)
        val next = p.getInt(HostPrefsNames.KEY_INTERCEPT_COUNT_POPUP, 0) + 1
        p.edit().putInt(HostPrefsNames.KEY_INTERCEPT_COUNT_POPUP, next).apply()
    }

    /** 首页统计卡「已拦截次数」累计：控件点击被拦截（编辑模式触摸消费），收到上报 +1 */
    fun addControlInterceptCount(context: Context) {
        val p = prefs(context)
        val next = p.getInt(HostPrefsNames.KEY_INTERCEPT_COUNT_CONTROL, 0) + 1
        p.edit().putInt(HostPrefsNames.KEY_INTERCEPT_COUNT_CONTROL, next).apply()
    }

    /** 弹窗 + 控件累计拦截次数（首页统计卡展示；不随「拦截记录」清空而重置） */
    fun totalInterceptCount(context: Context): Int {
        val p = prefs(context)
        return p.getInt(HostPrefsNames.KEY_INTERCEPT_COUNT_POPUP, 0) +
            p.getInt(HostPrefsNames.KEY_INTERCEPT_COUNT_CONTROL, 0)
    }

    /** 入口统一为本体通知；委托 [EntryMode] 保持单一数据源（协议冻结的 Provider 列）。 */
    fun getEntryMode(context: Context): String = EntryMode.current(context)

    /** 弹窗拦截教程是否已自动展示过（只首次自动弹，之后从页面「使用说明」进入） */
    fun isPopupGuideShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POPUP_GUIDE_SHOWN, false)

    fun markPopupGuideShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_POPUP_GUIDE_SHOWN, true).apply()
    }

    private const val KEY_POPUP_GUIDE_SHOWN = "popup_guide_shown"

    const val DEFAULT_POPUP_WINDOW_SECONDS = 10
    const val MIN_POPUP_WINDOW_SECONDS = 1
    const val MAX_POPUP_WINDOW_SECONDS = 120
}
