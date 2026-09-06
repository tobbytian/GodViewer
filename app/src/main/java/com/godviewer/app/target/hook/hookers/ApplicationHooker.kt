package com.godviewer.app.target.hook.hookers

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import com.godviewer.app.shared.APP_FIELD_SHOW_BOUNDS
import com.godviewer.app.shared.AndroidAppCompat
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.target.control.TargetControlReceiver
import com.godviewer.app.target.diag.TargetCrashProbe
import com.godviewer.app.target.edit.EditMode
import com.godviewer.app.target.edit.EditModeNotification
import com.godviewer.app.target.edit.EditModeTouchInterceptor
import com.godviewer.app.target.edit.SelectedViewHighlight
import com.godviewer.app.target.hook.GvHook
import com.godviewer.app.target.hook.GvMethodHook
import com.godviewer.app.target.hook.IHooker
import com.godviewer.app.target.hook.MethodHookParam
import com.godviewer.app.target.hook.ModuleRes
import com.godviewer.app.target.rule.ViewRuleManager
import com.godviewer.app.target.util.drawLayoutBounds
import com.godviewer.app.target.util.getInjectedField
import com.godviewer.app.target.util.setGlobalHookClick
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @author hhvvg
 *
 * Hooks application.
 *
 * 未开编辑：不 hook 触摸、不遍历 View、不改 clickable；只做规则加载与通知入口。
 * onCreate 路径全部 runCatching，并尽早安装 [TargetCrashProbe]
 * （全量 GvLog ring + 闪退探针，推宿主便于无 adb 反馈）。
 */
class ApplicationHooker : IHooker {
    override fun onHook() {
        GvHook.findAndHookMethod(
            Application::class.java,
            "onCreate",
            object : GvMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val app = AndroidAppCompat.currentApplication() ?: return
                        // Application.onCreate 可能被触发多次（部分加固框架二次初始化
                        // Application / 框架链路重复回调）。初始化 + ActivityLifecycleCallbacks
                        // 只做一次：重复注册会让编辑模式下每次全局布局都做 N 遍全树遍历，
                        // 复杂应用（酷我等动画多的页面）会明显卡顿。
                        if (!appInitialized.compareAndSet(false, true)) return
                        GvLog.i(
                            TAG,
                            "Application.onCreate pkg=${app.packageName} " +
                                "moduleRes=${ModuleRes.isModuleResReady()}",
                        )
                        // 最先装崩溃探针，后续任一步异常也能落盘
                        runCatching { TargetCrashProbe.install(app) }
                            .onFailure { GvLog.e(TAG, "TargetCrashProbe.install failed", it) }

                        runCatching { ViewRuleManager.init(app) }
                            .onFailure { GvLog.e(TAG, "ViewRuleManager.init failed", it) }
                        runCatching { EditMode.init(app) }
                            .onFailure { GvLog.e(TAG, "EditMode.init failed", it) }
                        // 不在此处安装触摸/click hook
                        runCatching { TargetControlReceiver.init(app) }
                            .onFailure { GvLog.e(TAG, "TargetControlReceiver.init failed", it) }
                        runCatching { EditModeNotification.init(app) }
                            .onFailure { GvLog.e(TAG, "EditModeNotification.init failed", it) }
                        runCatching {
                            HostControlBridge.reportForeground(
                                context = app,
                                packageName = app.packageName,
                                editEnabled = false,
                            )
                        }.onFailure { GvLog.w(TAG, "reportForeground failed", it) }
                        runCatching {
                            // 公开 API 注册（内部自带同步）；不要反射改 mActivityLifecycleCallbacks，
                            // Android 14+ / 部分 OEM 上字段注入会弄坏回调列表导致闪退
                            app.registerActivityLifecycleCallbacks(ActivityCallback())
                        }.onFailure { GvLog.e(TAG, "register ActivityLifecycleCallbacks failed", it) }

                        TargetCrashProbe.noteStage(
                            app,
                            "Application.onCreate done",
                            "edit=false touchHook=off moduleRes=${ModuleRes.isModuleResReady()}",
                        )
                    }.onFailure { GvLog.e(TAG, "Application.onCreate hook failed", it) }
                }
            },
        )
    }

    private class ActivityCallback : Application.ActivityLifecycleCallbacks {
        override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
            runCatching {
                val contentView = activity.window?.decorView as? ViewGroup ?: return
                var lastGlobalWrap = 0L
                contentView.viewTreeObserver.addOnGlobalLayoutListener {
                    // 未开编辑：GlobalLayout 极频繁（地图），完全不碰 View 树
                    if (!EditMode.isEnabled()) return@addOnGlobalLayoutListener
                    // 全树遍历很贵（每个 View 反射 + 建 ListenerInfo）：每帧都做会卡。
                    // 节流到 ≥150ms 一次；新出现视图最迟 150ms 内被包装，点选不受影响
                    //（点选走 dispatchTouchEvent hook）。
                    val now = SystemClock.uptimeMillis()
                    if (now - lastGlobalWrap < GLOBAL_LAYOUT_MIN_INTERVAL_MS) {
                        return@addOnGlobalLayoutListener
                    }
                    lastGlobalWrap = now
                    val app =
                        AndroidAppCompat.currentApplication() ?: return@addOnGlobalLayoutListener
                    val showBounds = app.getInjectedField(APP_FIELD_SHOW_BOUNDS, false) ?: false
                    // 关闭状态无需每帧重刷（新建 View 的 mDebugLayout 默认就是 false）；
                    // 开启时才补标新视图
                    if (showBounds) {
                        runCatching { contentView.drawLayoutBounds(true, true) }
                    }
                    runCatching {
                        contentView.setGlobalHookClick(
                            enabled = true,
                            traversalChildren = true,
                            forceClickable = true,
                        )
                    }
                }
            }.onFailure { GvLog.e(TAG, "onActivityPostCreated failed", it) }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}

        override fun onActivityResumed(activity: Activity) {
            runCatching {
                val app = AndroidAppCompat.currentApplication() ?: return
                val editOn = EditMode.isEnabled()
                GvLog.d(
                    TAG,
                    "onResume ${activity.javaClass.name} edit=$editOn touchPath=${if (editOn) "active" else "idle"}",
                )
                if (editOn) {
                    val showBounds = app.getInjectedField(APP_FIELD_SHOW_BOUNDS, false) ?: false
                    val decor = activity.window?.decorView as? ViewGroup
                    if (decor != null) {
                        runCatching { decor.drawLayoutBounds(showBounds, true) }
                    }
                    runCatching { EditModeTouchInterceptor.refreshActivity(activity) }
                    runCatching { SelectedViewHighlight.update() }
                } else {
                    runCatching { SelectedViewHighlight.clear() }
                }
                runCatching { EditModeNotification.refresh(app) }
                runCatching {
                    HostControlBridge.reportForeground(
                        context = app,
                        packageName = app.packageName,
                        editEnabled = editOn,
                    )
                }
                // 全量 debug ring 推宿主（有间隔，不刷屏）
                runCatching {
                    TargetCrashProbe.pushDiagSnapshot(
                        app,
                        stage = "resume:${activity.javaClass.simpleName}:edit=$editOn",
                        force = false,
                    )
                }
            }.onFailure { GvLog.e(TAG, "onActivityResumed failed", it) }
        }

        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            runCatching { SelectedViewHighlight.clearIfActivity(activity) }
        }
    }

    companion object {
        private const val TAG = "App"

        /** 编辑模式全局布局重刷的最小间隔（ms）：全树遍历太贵，不能每帧做。 */
        private const val GLOBAL_LAYOUT_MIN_INTERVAL_MS = 150L

        /** Application.onCreate 初始化只做一次（进程级）。 */
        private val appInitialized = AtomicBoolean(false)
    }
}
