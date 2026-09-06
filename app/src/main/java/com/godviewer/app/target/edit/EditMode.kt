package com.godviewer.app.target.edit

import android.app.Application
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.target.diag.TargetCrashProbe
import com.godviewer.app.target.hook.hookers.ActivityLifecycleHooker

/**
 * 编辑模式开关（只运行在被注入的目标进程内）。
 *
 * **每次启动默认关闭。** 仅通知栏「开启」后进入编辑；弹窗「退出编辑模式」关闭。
 *
 * 未开启时模块不得改写目标触摸 / clickable / ListenerInfo
 * （仅勾选 LSPosed 作用域也应可正常使用地图等应用）。
 */
object EditMode {

    private var app: Application? = null

    @Volatile
    private var enabled: Boolean = false

    fun init(app: Application) {
        this.app = app
        enabled = false
    }

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        runCatching {
            val changed = enabled != value
            enabled = value
            if (changed) {
                GvLog.i(TAG, "edit mode -> $value pkg=${app?.packageName}")
                if (value) {
                    // 首次开启才安装 dispatchTouchEvent / setOnClickListener / Popup hook
                    runCatching { EditModeTouchInterceptor.installIfNeeded() }
                        .onFailure { GvLog.e(TAG, "install edit hooks failed", it) }
                } else {
                    // 退出编辑：去掉选中蓝框，避免残留在目标界面
                    runCatching { SelectedViewHighlight.clear() }
                }
                runCatching {
                    EditModeTouchInterceptor.refreshActivity(
                        activity = ActivityLifecycleHooker.resumedActivity(),
                        forceClickable = value,
                    )
                }.onFailure { GvLog.e(TAG, "refreshActivity failed", it) }
            }
            app?.let { a ->
                runCatching { EditModeNotification.refresh(a) }
                runCatching {
                    HostControlBridge.reportForeground(
                        context = a,
                        packageName = a.packageName,
                        editEnabled = enabled,
                    )
                }
                // 开关编辑是关键节点：强制推一次全量 debug ring
                runCatching {
                    TargetCrashProbe.pushDiagSnapshot(
                        a,
                        stage = "editMode=$enabled",
                        force = true,
                    )
                }
            }
        }.onFailure {
            GvLog.e(TAG, "setEnabled($value) failed", it)
        }
    }

    private const val TAG = "EditMode"
}
