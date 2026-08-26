package com.godviewer.app.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Toggle the desktop launcher entry via activity-alias.
 * MainActivity itself stays exported (no LAUNCHER) so notifications / explicit intents still open it.
 */
object LauncherIconHelper {
    private const val TAG = "GodViewer.Launcher"
    const val ALIAS_CLASS = "com.godviewer.app.LauncherAlias"

    fun setHidden(context: Context, hidden: Boolean) {
        runCatching {
            val pm = context.packageManager
            val component = ComponentName(context, ALIAS_CLASS)
            val state = if (hidden) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            pm.setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP,
            )
            HostPrefs.setLauncherIconHidden(context, hidden)
            HostControlNotifier.refresh(context)
            Log.d(TAG, "launcher icon hidden=$hidden")
        }.onFailure {
            Log.w(TAG, "failed to toggle launcher icon", it)
        }
    }

    fun isHidden(context: Context): Boolean {
        return runCatching {
            val pm = context.packageManager
            val component = ComponentName(context, ALIAS_CLASS)
            when (pm.getComponentEnabledSetting(component)) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> false
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> HostPrefs.isLauncherIconHidden(context)
                else -> HostPrefs.isLauncherIconHidden(context)
            }
        }.getOrDefault(HostPrefs.isLauncherIconHidden(context))
    }
}
