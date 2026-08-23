package com.godviewer.app

import android.app.Application
import android.content.Context
import com.godviewer.app.util.AppLanguage

class GodViewerApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }
}
