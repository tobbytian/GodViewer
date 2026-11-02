package com.godviewer.app.target.popup

import android.app.Dialog
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.model.DialogRule
import com.godviewer.app.target.dialog.ModuleDialogUi
import com.godviewer.app.target.hook.hookers.DialogHooker
import com.godviewer.app.target.rule.DialogFingerprint
import com.godviewer.app.target.rule.DialogRuleStore

/**
 * 一键关闭目标应用当前 showing 的外来弹窗（Dialog/AlertDialog）。
 *
 * **关闭即保存**：每个被关掉的弹窗先生成 [DialogRule]（versionCode + Activity +
 * 文本指纹）落盘，之后同类弹窗再现时由 [DialogHooker] 自动重放关闭。
 * 模块自身对话框（[ModuleDialogUi.isModuleDialog]）必须跳过。
 *
 * 必须在主线程调用（广播接收分支会 post 到主 looper）。
 */
object PopupDismiss {
    private const val TAG = "Popup"

    /** @return 实际关闭并保存规则的弹窗数 */
    fun dismissAll(): Int {
        var closed = 0
        for (dialog in DialogHooker.trackedDialogs()) {
            runCatching {
                if (!dialog.isShowing) return@runCatching
                if (ModuleDialogUi.isModuleDialog(dialog)) return@runCatching
                val context = dialog.context ?: return@runCatching
                val texts = DialogFingerprint.textsOf(dialog)
                val activityClass =
                    ModuleDialogUi.activityOf(context)?.componentName?.className.orEmpty()
                val rule = DialogRule(
                    packageName = context.packageName,
                    matchVersionCode = DialogFingerprint.versionCodeOf(context),
                    activityClass = activityClass,
                    dialogClass = dialog.javaClass.name,
                    texts = texts,
                    timestamp = System.currentTimeMillis(),
                )
                // 先存规则再关：dismiss 后 decorView 可能已分离，指纹需在此之前采集
                if (texts.isEmpty()) {
                    // 没有文本指纹的规则无法可靠匹配，只关不存
                    GvLog.w(TAG, "empty fingerprint, dismiss without saving: ${rule.dialogClass}")
                } else {
                    DialogRuleStore.save(rule)
                }
                dialog.dismiss()
                closed++
                HostControlBridge.reportPopupIntercepted(
                    context,
                    context.packageName,
                    rule.title(),
                    HostControlBridge.KIND_MANUAL,
                )
                GvLog.i(TAG, "popup dismissed: ${rule.dialogClass} texts=${texts.size}")
            }.onFailure {
                GvLog.w(TAG, "dismiss one popup failed", it)
            }
        }
        GvLog.d(TAG, "dismissAll done closed=$closed")
        return closed
    }
}