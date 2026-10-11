package com.godviewer.app.shared.entry

import android.content.Context
import android.content.Intent
import android.util.Log
import com.godviewer.app.BuildConfig
import com.godviewer.app.R
import com.godviewer.app.shared.HostPrefsNames
import com.godviewer.app.shared.control.HostControlBridge

/**
 * 编辑模式入口：**统一为本体通知**（历史「目标应用通知 / 本体通知」双入口已废弃）。
 *
 * 目标应用进程不再发布自己的编辑模式通知，通知只由宿主
 * `host.control.HostControlNotifier` 发布；宿主首页的「本体通知」开关
 * （[HostPrefsNames.KEY_HOST_NOTIFY_ENABLED]）决定是否显示。
 *
 * 本类保留历史常量与入口 API（旧宿主 / 旧目标进程的广播协议兼容），
 * 但所有解析结果恒为 [HOST]。
 */
object EntryMode {
    const val HOST = "host"

    /** 历史值：仅供旧广播 / 旧偏好兼容读取，不再产生该入口。 */
    const val TARGET = "target"

    const val ACTION_ENTRY_MODE_CHANGED =
        "${BuildConfig.PACKAGE_NAME}.ACTION_ENTRY_MODE_CHANGED"
    const val EXTRA_MODE = "entry_mode"

    private const val TAG = "GodViewer.Entry"
    private const val TARGET_CACHE_PREFS = "godviewer_entry_cache"
    private const val KEY_CACHED_MODE = "mode"

    /** 宿主进程当前入口（恒本体通知）。 */
    fun current(context: Context): String = HOST

    /** 宿主进程：是否本体入口（统一后恒为 true）。 */
    fun isHostEntry(context: Context): Boolean = true

    fun isTargetEntry(context: Context): Boolean = false

    /**
     * 持久化入口（恒写入 [HOST]，供协议冻结的 `data.HostPrefsProvider` 读取）
     * 并广播给最近目标，使旧版本目标进程也不再发布自己的通知。
     */
    fun set(context: Context, mode: String?) {
        runCatching {
            // commit：目标进程尽快读到最新值
            context.getSharedPreferences(HostPrefsNames.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(HostPrefsNames.KEY_ENTRY_MODE, HOST)
                .commit()
        }
        val lastTarget = runCatching {
            HostControlBridge.currentTarget(context)?.packageName
        }.getOrNull()
        broadcastChanged(context, HOST, lastTarget)
    }

    /** 历史入口文案；统一后无选择项，宿主 UI 已不再展示。 */
    fun labelRes(mode: String?): Int = R.string.settings_entry_mode_host

    /**
     * 目标进程：历史入口同步（旧宿主版本会下发 mode）。
     * 统一本体通知后目标进程不再发布通知，这里只保留本地缓存与日志以兼容旧宿主。
     */
    fun applyFromHost(context: Context?, mode: String?) {
        val app = context?.applicationContext ?: return
        runCatching {
            app.getSharedPreferences(TARGET_CACHE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CACHED_MODE, HOST)
                .commit()
        }
        Log.d(TAG, "entry mode from host ignored=$mode, unified to=$HOST")
    }

    /** 目标进程当前入口：恒本体通知。 */
    fun currentFromModule(context: Context? = null): String = HOST

    /** 目标进程：是否本体入口（统一后恒为 true）。 */
    fun isHostEntryInTarget(context: Context? = null): Boolean = true

    /** 目标应用通知已废弃：永不展示。 */
    fun shouldShowTargetNotification(context: Context? = null): Boolean = false

    /** 入口模式恒定，视为始终已确认（无需等待宿主 push）。 */
    fun hasConfirmedMode(context: Context? = null): Boolean = true

    fun pushToTarget(context: Context, targetPackage: String) {
        if (targetPackage.isBlank() || targetPackage == BuildConfig.PACKAGE_NAME) return
        broadcastChanged(context, HOST, targetPackage)
    }

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
