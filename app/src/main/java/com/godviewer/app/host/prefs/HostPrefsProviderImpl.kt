package com.godviewer.app.host.prefs

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.godviewer.app.BuildConfig
import com.godviewer.app.host.prefs.HostPrefs

/**
 * 向被注入的目标进程暴露宿主设置（只读）。
 *
 * 现代 Android 下直接读模块私有 prefs（旧 XSharedPreferences 那条路）常失败，
 * 导致入口模式回退默认「目标通知」、两边通知叠在一起。ContentProvider 跨 UID 可读。
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
            else -> null
        }
    }

    override fun getType(uri: Uri): String? = when (uriMatcherPath(uri)) {
        PATH_ENTRY_MODE -> "vnd.android.cursor.item/vnd.$AUTHORITY.entry_mode"
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
        const val COLUMN_MODE = "mode"

        val ENTRY_MODE_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_ENTRY_MODE")

        private fun uriMatcherPath(uri: Uri): String? {
            if (uri.authority != AUTHORITY) return null
            return uri.pathSegments.firstOrNull()
        }
    }
}
