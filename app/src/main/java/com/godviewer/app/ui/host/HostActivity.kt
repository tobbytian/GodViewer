package com.godviewer.app.ui.host

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.godviewer.app.util.AppLanguage

/** Base for host-only activities so language override applies consistently. */
open class HostActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }
}
