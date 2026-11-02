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
     *
     * 登记返回的 Context：模块所有注入对话框都经此创建，
     * [isModuleDialog] 据此在关闭外来弹窗时排除自身 UI。
     */
    fun wrap(base: Context): Context {
        val activity = activityOf(base)
        val tokenOwner = when {
            activity != null && !activity.isFinishing -> activity
            else -> {
                // BadToken 的前兆：token 不在真实 Activity 上，记下回退来源
                val fallback = lastResumedActivity() ?: base
                GvLog.d(
                    TAG,
                    "wrap tokenOwner fallback=${fallback.javaClass.name} " +
                        "base=${base.javaClass.name}",
                )
                fallback
            }
        }
        val wrapped = ContextThemeWrapper(
            tokenOwner,
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
        )
        moduleContexts.add(wrapped)
        return wrapped
    }

    /** 模块注入对话框的 Context 登记表（weak，避免泄漏已关闭对话框） */
    private val moduleContexts: MutableSet<Context> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    /**
     * 是否为模块自身的对话框：Context 在 [wrap] 登记表中，
     * 或 decor 树已打 [IGNORE_HOOK] 标（兜底，applyWindow 之后才可判）。
     * 关闭外来弹窗 / 自动重放时必须跳过自身 UI。
     */
    fun isModuleDialog(dialog: Dialog): Boolean {
        runCatching {
            val ctx = dialog.context
            if (ctx != null && moduleContexts.contains(ctx)) return true
            // AlertDialog 内部可能再包一层 ContextThemeWrapper，向上找 base 链
            var current: Context? = ctx
            var guard = 0
            while (current is ContextWrapper && guard++ < 8) {
                if (moduleContexts.contains(current)) return true
                val base = current.baseContext
                if (base === current) break
                current = base
            }
            val decor = dialog.window?.decorView
            if (decor != null && hasIgnoreTag(decor)) return true
        }
        return false
    }

    private fun hasIgnoreTag(view: View): Boolean {
        if (view.tag == IGNORE_HOOK) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (hasIgnoreTag(view.getChildAt(i))) return true
            }
        }
        return false
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
        // 同时整树跑一遍色板归一化：系统按钮（如层级选择弹窗的「取消」）由
        // AlertController 按主题 buttonBarButtonStyle 创建，不走业务 inflate，
        // 在 DeviceDefault 被 OEM 覆盖的 ROM 上会呈现原生黑底按钮。
        runCatching {
            dialog.window?.decorView?.let {
                markIgnoreTree(it)
                ModuleDialogStyler.normalizeTree(it)
            }
            contentRoot?.let { markIgnoreTree(it) }
        }
        // buttonBar 有时在 show 后一帧才挂上
        dialog.window?.decorView?.post {
            runCatching {
                dialog.window?.decorView?.let {
                    markIgnoreTree(it)
                    ModuleDialogStyler.normalizeTree(it)
                }
            }
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
