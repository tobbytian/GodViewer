package com.godviewer.app.util

/**
 * Legacy target-app notification entry.
 *
 * Edit-mode controls now live in the host app notification
 * ([HostControlNotifier] + [TargetControlReceiver]). Kept as a no-op facade
 * so older call sites stay harmless if any remain.
 */
@Deprecated("Host notification is the single control entry")
object EditModeNotification {
    fun init(app: android.app.Application) {
        TargetControlReceiver.init(app)
    }

    fun post(@Suppress("UNUSED_PARAMETER") app: android.app.Application) {
        // no-op: target apps no longer post edit-mode notifications
    }
}
