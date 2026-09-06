package com.godviewer.app.host.ui

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.godviewer.app.shared.AppLanguage

/** Base for host-only activities so language override applies consistently. */
open class HostActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }
}
