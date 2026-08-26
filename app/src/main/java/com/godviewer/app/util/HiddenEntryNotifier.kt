package com.godviewer.app.util

/**
 * Compatibility facade: desktop-icon hide used to own a dedicated reopen notification.
 * That entry is merged into [HostControlNotifier] (open app + edit controls).
 */
object HiddenEntryNotifier {
    fun refresh(context: android.content.Context) {
        HostControlNotifier.refresh(context)
    }

    fun show(context: android.content.Context) {
        HostControlNotifier.show(context)
    }

    fun cancel(context: android.content.Context) {
        // Keep host control notification even when launcher icon is shown.
        HostControlNotifier.refresh(context)
    }
}
