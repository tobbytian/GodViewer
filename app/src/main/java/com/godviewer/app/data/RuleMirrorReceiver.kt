package com.godviewer.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Host-side receiver: target processes push mirrored rules.json here via explicit Intent.
 */
class RuleMirrorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            return
        }
        if (intent.action != RuleMirror.ACTION_MIRROR_RULES) {
            return
        }
        val token = intent.getStringExtra(RuleMirror.EXTRA_TOKEN)
        if (token != RuleMirror.MIRROR_TOKEN) {
            Log.w(TAG, "reject mirror: bad token")
            return
        }
        val packageName = intent.getStringExtra(RuleMirror.EXTRA_PACKAGE)
        val json = intent.getStringExtra(RuleMirror.EXTRA_JSON)
        if (packageName.isNullOrBlank() || json.isNullOrBlank()) {
            Log.w(TAG, "reject mirror: missing extras")
            return
        }
        val ok = RuleMirror.writeMirror(context.applicationContext, packageName, json)
        Log.d(TAG, "mirror receive pkg=$packageName ok=$ok")
    }

    companion object {
        private const val TAG = "GodViewer.Mirror"
    }
}
