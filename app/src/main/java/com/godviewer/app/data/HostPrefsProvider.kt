package com.godviewer.app.data

import android.net.Uri
import com.godviewer.app.BuildConfig

/**
 * Protocol-frozen exported component FQCN + authority.
 * Logic lives in [com.godviewer.app.host.prefs.HostPrefsProviderImpl].
 *
 * Companion 常量用字面量，避免其它模块仅读 URI 时强依赖 Impl 类初始化。
 * EntryMode 已改为自有 URI 常量，不再 import 本类。
 */
class HostPrefsProvider : com.godviewer.app.host.prefs.HostPrefsProviderImpl() {
    companion object {
        const val AUTHORITY = "${BuildConfig.PACKAGE_NAME}.hostprefs"
        const val PATH_ENTRY_MODE = "entry_mode"
        const val COLUMN_MODE = "mode"
        val ENTRY_MODE_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_ENTRY_MODE")
    }
}
