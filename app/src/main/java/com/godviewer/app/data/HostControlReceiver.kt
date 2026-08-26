package com.godviewer.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.godviewer.app.R
import com.godviewer.app.util.HostControlBridge
import com.godviewer.app.util.HostControlNotifier

/**
 * Host-side control receiver:
 * - target foreground / edit-state reports
 * - notification action buttons (enable / undo / manage rules)
 */
class HostControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val app = context.applicationContext
        val action = intent.action ?: return

        when (action) {
            HostControlBridge.ACTION_TARGET_FOREGROUND -> {
                val token = intent.getStringExtra(HostControlBridge.EXTRA_TOKEN)
                if (token != HostControlBridge.CONTROL_TOKEN) {
                    Log.w(TAG, "reject foreground: bad token")
                    return
                }
                val pkg = intent.getStringExtra(HostControlBridge.EXTRA_PACKAGE).orEmpty()
                if (pkg.isBlank()) return
                val label = intent.getStringExtra(HostControlBridge.EXTRA_LABEL).orEmpty()
                val editEnabled = intent.getBooleanExtra(HostControlBridge.EXTRA_EDIT_ENABLED, false)
                HostControlBridge.saveTargetState(app, pkg, label, editEnabled)
                HostControlNotifier.refresh(app)
                Log.d(TAG, "foreground pkg=$pkg edit=$editEnabled")
            }

            HostControlNotifier.ACTION_ENABLE,
            HostControlNotifier.ACTION_UNDO,
            HostControlNotifier.ACTION_MANAGE_RULES,
            -> {
                val targetAction = when (action) {
                    HostControlNotifier.ACTION_ENABLE -> HostControlBridge.ACTION_ENABLE_EDIT
                    HostControlNotifier.ACTION_UNDO -> HostControlBridge.ACTION_UNDO
                    else -> HostControlBridge.ACTION_MANAGE_RULES
                }
                val ok = HostControlBridge.dispatchToTarget(app, targetAction)
                if (!ok) {
                    Toast.makeText(app, R.string.host_control_no_target, Toast.LENGTH_SHORT).show()
                }
            }

            else -> Unit
        }
    }

    companion object {
        private const val TAG = "GodViewer.Control"
    }
}
