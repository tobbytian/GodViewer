package com.godviewer.app.target.ui

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
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
 * 先 dismiss 当前层再开列表，避免双层半透明叠糊。
 *
 * 使用系统 AlertDialog 的列表 + 取消时，必须在 show 后对 decor 整树
 * [ModuleDialogUi.markIgnoreTree]，否则编辑模式会把右下角「取消」当成目标控件。
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
    ) {
        if (candidates.isEmpty()) {
            return
        }
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
                    .setNegativeButton(moduleRes.getString(R.string.cancel)) { d, _ ->
                        d.dismiss()
                    }
                    .create()
                picker.setOnShowListener {
                    // buttonBar / 取消在 onShow 时已创建，再打一遍标
                    runCatching {
                        picker.window?.decorView?.let { ModuleDialogUi.markIgnoreTree(it) }
                    }
                }
                picker.show()
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
