package com.godviewer.app

import android.app.Application
import android.content.Context
import com.godviewer.app.util.AppLanguage
import com.godviewer.app.util.HostControlBridge
import com.godviewer.app.util.HostControlNotifier

class GodViewerApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // Host process start: don't keep a previous session's target on the notification.
        HostControlBridge.clearTargetState(this)
        HostControlNotifier.refresh(this)
    }
}
