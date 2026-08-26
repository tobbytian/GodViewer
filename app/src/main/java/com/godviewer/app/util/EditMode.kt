package com.godviewer.app.util

import android.app.Application
import com.godviewer.app.hook.hookers.ActivityLifecycleHooker

/**
 * 编辑模式开关（只运行在被注入的目标进程内）。
 *
 * 状态保存在内存中、只影响本次运行：**每次启动目标应用默认关闭编辑模式**，
 * 通过宿主应用通知栏的「开启」按钮（或长按空白）打开；「关闭」由编辑弹窗的
 * "退出编辑模式"按钮负责。重启目标应用后回到默认关闭。
 */
object EditMode {

    private var app: Application? = null

    /** 内存标志：点击路径直接读它，零开销 */
    @Volatile
    private var enabled: Boolean = false

    /** Application.onCreate 时调用：记录上下文并默认关闭编辑模式 */
    fun init(app: Application) {
        this.app = app
        enabled = false
    }

    /** 编辑模式是否开启 */
    fun isEnabled(): Boolean = enabled

    /** 更新内存标志（仅本次运行生效），并同步报告给宿主通知 */
    fun setEnabled(value: Boolean) {
        val changed = enabled != value
        enabled = value
        if (changed && value) {
            // 开启时立刻强制重挂当前界面，提升非 clickable 视图的可点性
            EditModeTouchInterceptor.refreshActivity(
                activity = ActivityLifecycleHooker.resumedActivity(),
                forceClickable = true,
            )
        } else if (changed && !value) {
            EditModeTouchInterceptor.refreshActivity(
                activity = ActivityLifecycleHooker.resumedActivity(),
                forceClickable = false,
            )
        }
        app?.let { a ->
            runCatching {
                HostControlBridge.reportForeground(
                    context = a,
                    packageName = a.packageName,
                    editEnabled = enabled,
                )
            }
        }
    }
}
