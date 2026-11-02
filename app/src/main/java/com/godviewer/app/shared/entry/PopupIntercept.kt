package com.godviewer.app.shared.entry

import android.content.Context
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.control.HostControlBridge

/**
 * 「强制弹窗拦截」开关的目标端缓存（首页开关存宿主 HostPrefs）。
 *
 * **不能**经 Provider 查询：Android 11+ 包可见性要求查询方清单声明
 * `<queries><provider authorities=…>`，而查询方是目标应用（其清单不可控），
 * `ContentResolver.query` 拿不到 Provider 时静默返回 null → 旧实现
 * `?: false` 把开关永远读成「关」（真机日志 `dialog tracked (intercept off)`
 * 即此根因）。改走**定向广播**（本应用命令通道已验证双向可达）：
 *
 * - [bootstrap]：进程启动、接收器就绪后拉一次（REQUEST → 宿主回 STATE）；
 * - [isEnabled]：状态过期（> [REFRESH_MS]）时再拉一次（异步，本次用缓存值）；
 * - 宿主开关切换时主动推送给曾拉取过的存活目标（HostPrefs watchers）；
 * - 状态持久化到**目标自身** SharedPreferences：重启先用上次值，后台刷新。
 *
 * 仅供「规则重放」闸门使用；检测上报不查开关——宿主收到后以最新开关二次把关。
 */
object PopupIntercept {
    private const val TAG = "PopupIntercept"
    private const val PREFS = "godviewer_popup_intercept"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_WINDOW_SECONDS = "window_seconds"
    private const val CACHE_FILE = "popup_intercept.cache"

    const val DEFAULT_WINDOW_SECONDS = 10

    /** 拉取最小间隔：过期才发 REQUEST，避免每次闸门调用都打广播 */
    private const val REFRESH_MS = 60_000L

    /** 回前台刷新的最小间隔（比 [REFRESH_MS] 短，尽快拿到开关新值） */
    private const val FOREGROUND_REFRESH_MS = 30_000L

    @Volatile
    private var loaded = false

    /** 缓存未同步时的默认值：内测期默认开启（宿主同步过的显式值优先） */
    @Volatile
    private var enabled = true

    @Volatile
    private var updatedAt = 0L

    @Volatile
    private var windowSeconds = DEFAULT_WINDOW_SECONDS

    /** 目标进程启动时刻（elapsedRealtime）；Application.onCreate 里记录 */
    @Volatile
    private var processStartedAt = android.os.SystemClock.elapsedRealtime()

    /** 进程启动时调用，作为「应用开启」的计时起点 */
    fun markAppStart() {
        processStartedAt = android.os.SystemClock.elapsedRealtime()
    }

    /** 当前是否还在「应用开启后 N 秒」的检测窗口内（N 由宿主设置，经广播同步） */
    fun withinOpenWindow(context: Context): Boolean {
        ensureLoaded(context)
        val elapsed = android.os.SystemClock.elapsedRealtime() - processStartedAt
        return elapsed <= windowSeconds * 1000L
    }

    /** 开关状态（缓存未同步时默认开启，内测期免配置）。过期时顺手向宿主拉取最新值，本次返回缓存值 */
    fun isEnabled(context: Context): Boolean {
        ensureLoaded(context)
        if (System.currentTimeMillis() - updatedAt > REFRESH_MS) requestState(context)
        return enabled
    }

    /** 进程启动调用：载入本地缓存并立刻向宿主拉一次最新状态 */
    fun bootstrap(context: Context) {
        ensureLoaded(context)
        GvLog.d(TAG, "bootstrap cachedEnabled=$enabled, requesting state")
        requestState(context)
    }

    /**
     * 应用回到前台时调用：距上次同步超过 [FOREGROUND_REFRESH_MS] 就拉一次最新状态。
     * 否则刚打开开关、目标端缓存仍是旧值（60s 过期检查在 [isEnabled] 里，窗口内
     * 不触发），规则重放闸门会用过期开关拒绝拦截。
     */
    fun refreshOnForeground(context: Context) {
        ensureLoaded(context)
        if (System.currentTimeMillis() - updatedAt > FOREGROUND_REFRESH_MS) {
            requestState(context)
        }
    }

    /** 宿主回发/推送的开关状态落地（内存 + 目标自身 prefs） */
    fun onStateReceived(context: Context, value: Boolean, windowSec: Int) {
        enabled = value
        windowSeconds = windowSec.coerceIn(1, 120)
        updatedAt = System.currentTimeMillis()
        loaded = true
        runCatching {
            val file = cacheFile(context)
            file.parentFile?.mkdirs()
            val props = java.util.Properties().apply {
                setProperty(KEY_ENABLED, value.toString())
                setProperty(KEY_UPDATED_AT, updatedAt.toString())
                setProperty(KEY_WINDOW_SECONDS, windowSeconds.toString())
            }
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { props.store(it, null) }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure {
            GvLog.w(TAG, "persist state failed", it)
        }
        GvLog.d(TAG, "state synced enabled=$value")
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val file = cacheFile(context)
                if (file.exists()) {
                    val props = java.util.Properties()
                    file.inputStream().use { props.load(it) }
                    enabled = props.getProperty(KEY_ENABLED) == "true"
                    updatedAt = props.getProperty(KEY_UPDATED_AT)?.toLongOrNull() ?: 0L
                    windowSeconds = props.getProperty(KEY_WINDOW_SECONDS)?.toIntOrNull()
                        ?.coerceIn(1, 120) ?: DEFAULT_WINDOW_SECONDS
                } else {
                    // 旧版本只写 SharedPreferences：迁移期间读一次作兜底
                    // （无任何记录时默认开启——内测期免配置，宿主同步后以同步值为准）
                    val p = prefs(context)
                    enabled = p.getBoolean(KEY_ENABLED, true)
                    updatedAt = p.getLong(KEY_UPDATED_AT, 0L)
                    windowSeconds = p.getInt(KEY_WINDOW_SECONDS, DEFAULT_WINDOW_SECONDS)
                }
            }
            loaded = true
            // 首次读缓存的值：排查"闸门为什么是关的"第一现场
            GvLog.d(TAG, "cache loaded enabled=$enabled updatedAt=$updatedAt")
        }
    }

    /** 向宿主拉取状态；发出即推进 updatedAt 节流（宿主无应答也不会广播风暴） */
    private fun requestState(context: Context) {
        updatedAt = System.currentTimeMillis()
        GvLog.d(TAG, "request state -> host pkg=${context.packageName}")
        HostControlBridge.requestPopupInterceptState(context, context.packageName)
    }

    /**
     * 缓存放模块自己的文件而不是 SharedPreferences：部分应用（如酷我）把 SP 换成
     * MMKV，Application.onCreate 里读 SP 拿到的是空值，每次冷启动都当作「开关关闭」。
     */
    private fun cacheFile(context: Context): java.io.File =
        java.io.File(java.io.File(context.applicationContext.filesDir, "godviewer"), CACHE_FILE)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}