package com.godviewer.app.target.ui

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ancestors
import androidx.core.view.children
import com.godviewer.app.R
import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.dialog.ModuleDialogUi
import com.godviewer.app.target.dispatch.ViewDispatcher
import com.godviewer.app.target.edit.ViewHitUtil
import com.godviewer.app.target.hook.ModuleRes
import com.godviewer.app.target.hook.ModuleRes.moduleRes
import com.godviewer.app.target.ui.adapter.ViewItemListAdapter

/**
 * 高级编辑弹窗的父/子层级导航。
 * 先 dismiss 当前层再开列表，避免双层半透明叠糊；负按钮「返回」与系统返回键/
 * 点外部都经 [onBack] 回到上一层（重新 show 原编辑页），而不是只关列表全退出。
 *
 * 使用系统 AlertDialog 的列表时，必须在 show 后对 decor 整树
 * [ModuleDialogUi.markIgnoreTree]，否则编辑模式会把右下角按钮当成目标控件。
 */
internal object AttrDialogHierarchy {
    private const val TAG = "UI"

    fun findChildren(itemView: View): List<View> {
        if (itemView !is ViewGroup || itemView.childCount <= 0) {
            return emptyList()
        }
        return itemView.children
            .filterNot { ViewHitUtil.isGodViewerUi(it) }
            .toList()
    }

    fun findAncestors(itemView: View): List<ViewGroup> {
        val result = ArrayList<ViewGroup>()
        for (a in itemView.ancestors) {
            if (a is ViewGroup && !ViewHitUtil.isGodViewerUi(a)) {
                result.add(a)
            }
        }
        return result
    }

    fun openPicker(
        anchor: View,
        dismissCurrent: () -> Unit,
        titleRes: Int,
        candidates: List<View>,
        onBack: () -> Unit = {},
    ) {
        if (candidates.isEmpty()) {
            // 静默 return 会让按钮看起来「点了没反应」：给明确提示
            GvLog.i(TAG, "open picker skip: empty title=$titleRes")
            Toast.makeText(
                anchor.context,
                moduleRes.getString(R.string.picker_empty),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        GvLog.i(TAG, "open picker begin n=${candidates.size} title=$titleRes")
        dismissCurrent()
        Handler(Looper.getMainLooper()).post {
            runCatching {
                if (!ModuleRes.isModuleResReady()) {
                    GvLog.w(TAG, "open hierarchy picker skip: moduleRes not ready")
                    return@runCatching
                }
                val wrapCtx = ModuleDialogUi.dialogContext(anchor)
                val picker = AlertDialog.Builder(wrapCtx)
                    .setTitle(moduleRes.getString(titleRes))
                    .setAdapter(ViewItemListAdapter(candidates, wrapCtx)) { d, which ->
                        d.dismiss()
                        val selected = candidates.getOrNull(which) ?: return@setAdapter
                        if (ViewHitUtil.isGodViewerUi(selected)) return@setAdapter
                        ViewDispatcher.dispatch(selected)
                    }
                    .setNegativeButton(moduleRes.getString(R.string.back)) { d, _ ->
                        d.dismiss()
                        GvLog.i(TAG, "picker back: button")
                        // 下一帧再回上一层，避免 dismiss 同帧开窗的 token 冲突
                        Handler(Looper.getMainLooper()).post { onBack() }
                    }
                    .create()
                // 系统返回键 / 点外部走 cancel()（不经过负按钮），同样回上一层而不是全关
                picker.setOnCancelListener {
                    GvLog.i(TAG, "picker back: cancel")
                    Handler(Looper.getMainLooper()).post { onBack() }
                }
                picker.setOnShowListener {
                    // buttonBar / 取消在 onShow 时已创建，再打一遍标
                    runCatching {
                        picker.window?.decorView?.let { ModuleDialogUi.markIgnoreTree(it) }
                    }
                }
                picker.show()
                GvLog.i(TAG, "picker shown")
                ModuleDialogUi.applyWindow(picker)
                runCatching {
                    picker.window?.decorView?.let { ModuleDialogUi.markIgnoreTree(it) }
                }
            }.onFailure {
                GvLog.e(TAG, "open hierarchy picker failed", it)
            }
        }
    }
}
