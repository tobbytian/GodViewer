package com.godviewer.app.target.dialog

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import com.godviewer.app.IGNORE_HOOK
import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.hook.ModuleRes
import com.godviewer.app.target.hook.ModuleRes.moduleRes
import java.lang.ref.WeakReference

/**
 * 注入到目标进程的弹窗 UI 工具 — 薄 facade。
 *
 * 布局 XML 来自 moduleRes；Context 包系统 Dialog 主题，避免吃目标 AppTheme。
 * 实现拆到：
 * - [ModuleDialogPalette] 色板
 * - [ModuleDialogWindow] 窗口/高度/chrome
 * - [ModuleDialogStyler] 控件强制着色
 */
object ModuleDialogUi {

    private const val TAG = "UI"

    /** 由 ActivityLifecycle 写入，弹窗在 View.context 不是 Activity 时回退用 */
    @Volatile
    private var resumedActivityRef: WeakReference<Activity>? = null

    fun noteResumedActivity(activity: Activity?) {
        resumedActivityRef = activity?.let { WeakReference(it) }
    }

    fun lastResumedActivity(): Activity? {
        val a = resumedActivityRef?.get() ?: return null
        if (a.isFinishing) return null
        return a
    }

    /**
     * 用系统 DeviceDefault 浅色 Dialog 主题包装目标 Context。
     * 使用 framework 的 [ContextThemeWrapper]，避免在目标进程依赖 AppCompat。
     *
     * **必须尽量挂在 Activity 上**：部分控件 `view.context` 不是 Activity
     * （主题/插件 Context），否则 AlertDialog.show → BadTokenException。
     */
    fun wrap(base: Context): Context {
        val activity = activityOf(base)
        val tokenOwner = when {
            activity != null && !activity.isFinishing -> activity
            else -> lastResumedActivity() ?: base
        }
        return ContextThemeWrapper(
            tokenOwner,
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
        )
    }

    /**
     * 为注入弹窗解析带 window token 的 Context。
     * 优先：View 上溯 Activity → resumed Activity → view.context。
     */
    fun dialogContext(anchor: View): Context {
        val fromView = activityFromView(anchor)
        if (fromView != null && !fromView.isFinishing) {
            return wrap(fromView)
        }
        val resumed = lastResumedActivity()
        if (resumed != null) {
            GvLog.d(
                TAG,
                "dialogContext fallback resumed=${resumed.javaClass.simpleName} " +
                    "view=${anchor.javaClass.simpleName}",
            )
            return wrap(resumed)
        }
        GvLog.w(
            TAG,
            "dialogContext no activity view=${anchor.javaClass.name} " +
                "ctx=${anchor.context.javaClass.name}",
        )
        return wrap(anchor.context)
    }

    fun activityOf(context: Context): Activity? {
        var current: Context? = context
        var guard = 0
        while (current is ContextWrapper && guard++ < 24) {
            if (current is Activity) {
                return current
            }
            val base = current.baseContext
            // 部分插件 Context getBaseContext 返回自身，防死循环
            if (base === current) break
            current = base
        }
        return current as? Activity
    }

    fun activityFromView(view: View): Activity? {
        activityOf(view.context)?.let { return it }
        runCatching {
            if (view.isAttachedToWindow) {
                activityOf(view.rootView.context)?.let { return it }
            }
        }
        var parent = view.parent
        var guard = 0
        while (parent is ViewGroup && guard++ < 64) {
            activityOf(parent.context)?.let { return it }
            parent = parent.parent
        }
        return null
    }

    fun inflate(context: Context, @LayoutRes layoutId: Int): View {
        val layout = moduleRes.getLayout(layoutId)
        val view = LayoutInflater.from(context).inflate(layout, null, false)
        markIgnoreTree(view)
        normalizeTree(view)
        return view
    }

    /**
     * 展示后固定窗口外观：宿主同色圆角卡片、合理宽度、最大高度。
     *
     * @param contentRoot 业务 setContentView 的根视图。高级页必须传入。
     * @param preferMaxHeight true 时窗口与内容占满最大高度（高级编辑页）
     */
    fun applyWindow(
        dialog: Dialog,
        contentRoot: View? = null,
        preferMaxHeight: Boolean = false,
    ) {
        ModuleDialogWindow.apply(dialog, contentRoot, preferMaxHeight)
        // 系统 Alert 的 title / buttonBar / 取消按钮不在业务 layout 里，必须整窗打标，
        // 否则编辑模式下 setOnClickListener hook 会把「取消」当成目标控件打开属性编辑。
        runCatching {
            dialog.window?.decorView?.let { markIgnoreTree(it) }
            contentRoot?.let { markIgnoreTree(it) }
        }
        // buttonBar 有时在 show 后一帧才挂上
        dialog.window?.decorView?.post {
            runCatching { dialog.window?.decorView?.let { markIgnoreTree(it) } }
        }
    }

    /**
     * 将整棵 View 树标为模块 UI，编辑命中 / click 包装 / 强制可点均应跳过。
     * 用于业务 layout 与系统 AlertDialog chrome（含右下角取消）。
     */
    fun markIgnoreTree(root: View?) {
        if (root == null) return
        root.tag = IGNORE_HOOK
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                markIgnoreTree(root.getChildAt(i))
            }
        }
    }

    /** 注入树强制使用宿主色板。 */
    fun normalizeTree(root: View) {
        ModuleDialogStyler.normalizeTree(root)
    }
}
