package com.godviewer.app.host.mirror

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.shared.GvLog

/**
 * Host-side receiver: target processes push mirrored rules.json here via explicit Intent.
 */
open class RuleMirrorReceiverImpl : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            return
        }
        if (intent.action != RuleMirror.ACTION_MIRROR_RULES) {
            return
        }
        val token = intent.getStringExtra(RuleMirror.EXTRA_TOKEN)
        if (token != RuleMirror.MIRROR_TOKEN) {
            GvLog.w(TAG, "reject mirror: bad token")
            return
        }
        val packageName = intent.getStringExtra(RuleMirror.EXTRA_PACKAGE)
        val json = intent.getStringExtra(RuleMirror.EXTRA_JSON)
        if (packageName.isNullOrBlank() || json.isNullOrBlank()) {
            GvLog.w(TAG, "reject mirror: missing extras")
            return
        }
        val ok = RuleMirror.writeMirror(context.applicationContext, packageName, json)
        GvLog.d(TAG, "mirror receive pkg=$packageName ok=$ok")
    }

    companion object {
        private const val TAG = "GodViewer.Mirror"
    }
}
