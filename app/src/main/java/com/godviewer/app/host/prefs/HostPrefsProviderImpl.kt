package com.godviewer.app.host.prefs

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.godviewer.app.BuildConfig
import com.godviewer.app.host.prefs.HostPrefs
import com.godviewer.app.shared.HostPrefsNames

/**
 * 宿主设置只读出口（authority 协议冻结）。
 *
 * 注意：**目标进程查不到它** —— Android 11+ 包可见性要求查询方清单声明
 * `<queries><provider authorities=…>`，而查询方是目标应用（清单不可控），
 * query 拿不到 Provider 时静默返回 null（真机已验证该现象）。开关类状态已改
 * 走广播同步（见 [com.godviewer.app.shared.entry.PopupIntercept]）；本 Provider
 * 保留供宿主自身与 adb shell 调试读取。
 */
open class HostPrefsProviderImpl : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        return when (uriMatcherPath(uri)) {
            PATH_ENTRY_MODE -> {
                val mode = HostPrefs.getEntryMode(ctx)
                MatrixCursor(arrayOf(COLUMN_MODE)).apply {
                    addRow(arrayOf(mode))
                }
            }
            PATH_POPUP_INTERCEPT -> {
                // 增量 path：目标端查「强制弹窗拦截」开关（默认关闭）
                val enabled = HostPrefs.isPopupInterceptEnabled(ctx)
                MatrixCursor(arrayOf(HostPrefsNames.COLUMN_POPUP_INTERCEPT)).apply {
                    addRow(arrayOf(if (enabled) 1 else 0))
                }
            }
            else -> null
        }
    }

    override fun getType(uri: Uri): String? = when (uriMatcherPath(uri)) {
        PATH_ENTRY_MODE -> "vnd.android.cursor.item/vnd.$AUTHORITY.entry_mode"
        PATH_POPUP_INTERCEPT -> "vnd.android.cursor.item/vnd.$AUTHORITY.popup_intercept"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = "${BuildConfig.PACKAGE_NAME}.hostprefs"
        const val PATH_ENTRY_MODE = "entry_mode"
        const val PATH_POPUP_INTERCEPT = "popup_intercept"
        const val COLUMN_MODE = "mode"

        val ENTRY_MODE_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_ENTRY_MODE")

        private fun uriMatcherPath(uri: Uri): String? {
            if (uri.authority != AUTHORITY) return null
            return uri.pathSegments.firstOrNull()
        }
    }
}
