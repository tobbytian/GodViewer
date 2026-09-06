package com.godviewer.app.shared.entry

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.godviewer.app.BuildConfig
import com.godviewer.app.R
import com.godviewer.app.shared.AndroidAppCompat
import com.godviewer.app.shared.HostPrefsNames
import com.godviewer.app.shared.control.HostControlBridge

/**
 * 编辑模式功能入口：目标应用通知（默认）或上帝视角本体通知。
 *
 * - 宿主：直接读写 [HostPrefsNames] 同名 SharedPreferences（不依赖 host 包）
 * - 目标：内存 / 本地缓存 / Provider → 默认 target
 *   （API 102 迁移：旧 XSharedPreferences 兜底已移除——现代 Android 上基本读不到，
 *   Provider 是实际主路径）
 *
 * Provider URI / 列名在此用字面常量，**不引用** `data.HostPrefsProvider` 类，
 * 避免目标进程解析 EntryMode 时连带加载 host ContentProvider 实现。
 *
 * 宿主 UI 刷新（通知形态）由 host 侧 [com.godviewer.app.host.entry.EntryControlUi] 在
 * [set] 之后自行调用，避免 shared → host 依赖。
 */
object EntryMode {
    const val TARGET = "target"
    const val HOST = "host"

    const val ACTION_ENTRY_MODE_CHANGED =
        "${BuildConfig.PACKAGE_NAME}.ACTION_ENTRY_MODE_CHANGED"
    const val EXTRA_MODE = "entry_mode"

    private const val TAG = "GodViewer.Entry"
    private const val TARGET_CACHE_PREFS = "godviewer_entry_cache"
    private const val KEY_CACHED_MODE = "mode"

    /** 与 Manifest / HostPrefsProviderImpl 冻结一致；勿改 authority。 */
    private const val HOSTPREFS_AUTHORITY = "${BuildConfig.PACKAGE_NAME}.hostprefs"
    private const val HOSTPREFS_PATH_ENTRY_MODE = "entry_mode"
    private const val HOSTPREFS_COLUMN_MODE = "mode"
    private val ENTRY_MODE_URI: Uri =
        Uri.parse("content://$HOSTPREFS_AUTHORITY/$HOSTPREFS_PATH_ENTRY_MODE")

    @Volatile
    private var memoryCache: String? = null

    fun current(context: Context): String {
        val mode = hostPrefs(context).getString(HostPrefsNames.KEY_ENTRY_MODE, TARGET) ?: TARGET
        return normalize(mode)
    }

    /** 宿主进程：是否本体入口 */
    fun isHostEntry(context: Context): Boolean = current(context) == HOST

    fun isTargetEntry(context: Context): Boolean = !isHostEntry(context)

    /**
     * 持久化并推送给最近目标。不刷新宿主通知 UI——调用方（设置页）需再调 EntryControlUi。
     */
    fun set(context: Context, mode: String) {
        val normalized = normalize(mode)
        // commit：目标进程尽快读到最新值
        hostPrefs(context).edit().putString(HostPrefsNames.KEY_ENTRY_MODE, normalized).commit()
        val lastTarget = HostControlBridge.currentTarget(context)?.packageName
        if (!lastTarget.isNullOrBlank()) {
            broadcastChanged(context, normalized, targetPackage = lastTarget)
        } else {
            broadcastChanged(context, normalized, targetPackage = null)
        }
    }

    fun labelRes(mode: String): Int = when (mode) {
        HOST -> R.string.settings_entry_mode_host
        else -> R.string.settings_entry_mode_target
    }

    /** 目标进程：宿主 push 写入内存 + 本地缓存 */
    fun applyFromHost(context: Context, mode: String?) {
        val normalized = normalize(mode)
        memoryCache = normalized
        runCatching {
            context.applicationContext
                .getSharedPreferences(TARGET_CACHE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CACHED_MODE, normalized)
                .commit()
        }
        Log.d(TAG, "cache entry mode from host=$normalized")
    }

    /**
     * 目标进程当前入口。
     * 1) 内存 2) 目标本地缓存 3) Provider 4) 默认 target
     */
    fun currentFromModule(context: Context? = null): String {
        memoryCache?.let { return it }
        val app = context?.applicationContext
            ?: runCatching { AndroidAppCompat.currentApplication() }.getOrNull()
        readLocalCache(app)?.let {
            memoryCache = it
            return it
        }
        readViaProvider(app)?.let {
            memoryCache = it
            return it
        }
        return TARGET
    }

    /** 目标进程：是否本体入口（可读缓存/Provider） */
    fun isHostEntryInTarget(context: Context? = null): Boolean =
        currentFromModule(context) == HOST

    /**
     * 是否展示目标应用通知。
     * host → 绝不展示；target → 展示。
     */
    fun shouldShowTargetNotification(context: Context? = null): Boolean =
        !isHostEntryInTarget(context)

    /**
     * 是否已从宿主/缓存确认过模式。
     * 未确认时不要急着 post，先等宿主 push，避免本体入口下闪出目标通知。
     */
    fun hasConfirmedMode(context: Context? = null): Boolean {
        if (memoryCache != null) return true
        val app = context?.applicationContext
            ?: runCatching { AndroidAppCompat.currentApplication() }.getOrNull()
        return readLocalCache(app) != null
    }

    fun pushToTarget(context: Context, targetPackage: String) {
        if (targetPackage.isBlank() || targetPackage == BuildConfig.PACKAGE_NAME) return
        broadcastChanged(context, current(context), targetPackage = targetPackage)
    }

    private fun hostPrefs(context: Context) =
        context.getSharedPreferences(HostPrefsNames.PREFS_NAME, Context.MODE_PRIVATE)

    private fun readLocalCache(context: Context?): String? {
        if (context == null) return null
        return runCatching {
            val raw = context.applicationContext
                .getSharedPreferences(TARGET_CACHE_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CACHED_MODE, null)
            if (raw == HOST || raw == TARGET) raw else null
        }.getOrNull()
    }

    private fun readViaProvider(context: Context?): String? {
        if (context == null) return null
        return runCatching {
            context.contentResolver.query(
                ENTRY_MODE_URI,
                arrayOf(HOSTPREFS_COLUMN_MODE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val idx = cursor.getColumnIndex(HOSTPREFS_COLUMN_MODE)
                val raw = if (idx >= 0) cursor.getString(idx) else cursor.getString(0)
                normalize(raw)
            }
        }.onFailure {
            Log.d(TAG, "provider read entry mode failed", it)
        }.getOrNull()
    }

    private fun normalize(raw: String?): String =
        if (raw == HOST) HOST else TARGET

    private fun broadcastChanged(context: Context, mode: String, targetPackage: String?) {
        runCatching {
            val intent = Intent(ACTION_ENTRY_MODE_CHANGED).apply {
                putExtra(EXTRA_MODE, mode)
                putExtra(HostControlBridge.EXTRA_TOKEN, HostControlBridge.CONTROL_TOKEN)
                if (!targetPackage.isNullOrBlank()) {
                    setPackage(targetPackage)
                }
            }
            context.applicationContext.sendBroadcast(intent)
            Log.d(TAG, "broadcast entry mode=$mode pkg=${targetPackage ?: "*"}")
        }.onFailure {
            Log.w(TAG, "broadcast entry mode failed", it)
        }
    }
}
