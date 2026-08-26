package com.godviewer.app.util

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.godviewer.app.data.ViewRuleManager
import com.godviewer.app.hook.hookers.ActivityLifecycleHooker
import com.godviewer.app.ui.RuleManagerDialog

/**
 * Target-process command receiver for host notification actions.
 * No notification is posted from the target app.
 */
object TargetControlReceiver {
    private const val TAG = "GodViewer.Control"
    private var registered = false

    fun init(app: Application) {
        if (registered) return
        registered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null || intent == null) return
                val token = intent.getStringExtra(HostControlBridge.EXTRA_TOKEN)
                if (token != HostControlBridge.CONTROL_TOKEN) {
                    Log.w(TAG, "reject command: bad token")
                    return
                }
                when (intent.action) {
                    HostControlBridge.ACTION_ENABLE_EDIT -> {
                        Log.d(TAG, "enable edit received")
                        EditMode.setEnabled(true)
                        EditModeTouchInterceptor.refreshActivity(
                            activity = ActivityLifecycleHooker.resumedActivity(),
                            forceClickable = true,
                        )
                    }
                    HostControlBridge.ACTION_UNDO -> {
                        val activity = ActivityLifecycleHooker.resumedActivity()
                        val undone = ViewRuleManager.undoLastOperation(activity)
                        Log.d(TAG, "undo received, undone=$undone")
                    }
                    HostControlBridge.ACTION_MANAGE_RULES -> {
                        val activity = ActivityLifecycleHooker.resumedActivity()
                        if (activity == null) {
                            Log.d(TAG, "manage rules: no resumed activity")
                            return
                        }
                        // Dialog must show on main thread after returning to target UI.
                        Handler(Looper.getMainLooper()).post {
                            runCatching { RuleManagerDialog(activity).show() }
                                .onFailure { Log.e(TAG, "show rule manager failed", it) }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(HostControlBridge.ACTION_ENABLE_EDIT)
            addAction(HostControlBridge.ACTION_UNDO)
            addAction(HostControlBridge.ACTION_MANAGE_RULES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        Log.d(TAG, "target control receiver registered")
    }
}
