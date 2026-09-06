package com.godviewer.app.target.util

import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import com.godviewer.app.IGNORE_HOOK
import com.godviewer.app.target.dispatch.ViewClickWrapper
import com.godviewer.app.target.edit.ViewHitUtil

/**
 * 读取已有 ListenerInfo，不存在则返回 null。
 * 切勿在空闲路径调用会创建 ListenerInfo 的 getListenerInfo()，
 * 否则整棵 View 树被写上 mListenerInfo，部分地图/Surface 手势会异常。
 */
fun View.listenerInfoOrNull(): Any? {
    return runCatching { getObjectField(this, "mListenerInfo") }.getOrNull()
}

fun View.replaceOnClickListener(
    listenerGeneratorCallback: (origin: View.OnClickListener?) -> View.OnClickListener?
) {
    val info = callMethod(this, "getListenerInfo")
    val originListener =
        getObjectField(info, "mOnClickListener") as View.OnClickListener?
    val newListener = listenerGeneratorCallback.invoke(originListener)
    setObjectField(info, "mOnClickListener", newListener)
}

fun View.getOnClickListener(): View.OnClickListener? {
    val info = listenerInfoOrNull() ?: return null
    return getObjectField(info, "mOnClickListener") as View.OnClickListener?
}

fun View.drawLayoutBounds(drawEnabled: Boolean, traversalChildren: Boolean) {
    val attachInfo = getObjectField(this, "mAttachInfo") ?: return
    setBooleanField(attachInfo, "mDebugLayout", drawEnabled)
    if (traversalChildren && this is ViewGroup) {
        for (child in children) {
            child.drawLayoutBounds(drawEnabled, true)
        }
    }
    this.invalidate()
}

/**
 * 遍历包装 / 还原点击监听。
 *
 * - [enabled]=true：包装为 [ViewClickWrapper]；[forceClickable] 时可强制 isClickable
 * - [enabled]=false：仅当已有 wrapper 时还原 listener + originClickable；
 *   **无 wrapper 的 View 直接跳过，不创建 ListenerInfo**
 */
fun View.setGlobalHookClick(
    enabled: Boolean,
    traversalChildren: Boolean = true,
    forceClickable: Boolean = false
) {
    if (tag == IGNORE_HOOK || ViewHitUtil.isGodViewerUi(this)) {
        return
    }
    // 淘宝 WaterMask 等穿透层：不 wrap、不强制 clickable，否则编辑模式点哪都是它
    if (ViewHitUtil.isPassThroughOverlay(this)) {
        if (this is ViewGroup && traversalChildren) {
            for (child in children) {
                child.setGlobalHookClick(enabled, traversalChildren, forceClickable)
            }
        }
        return
    }
    if (enabled) {
        replaceOnClickListener { origin ->
            if (origin is ViewClickWrapper) {
                origin
            } else {
                ViewClickWrapper(origin, isClickable, this)
            }
        }
        if (forceClickable) {
            isClickable = true
        }
    } else {
        // 空闲 / 退出编辑：只清理我们自己装过的 wrapper，绝不 getListenerInfo()
        val info = listenerInfoOrNull()
        if (info != null) {
            val origin = runCatching {
                getObjectField(info, "mOnClickListener") as View.OnClickListener?
            }.getOrNull()
            if (origin is ViewClickWrapper) {
                isClickable = origin.originClickable
                setObjectField(info, "mOnClickListener", origin.originListener)
            }
        }
    }
    if (this !is ViewGroup || !traversalChildren) {
        return
    }
    for (child in children) {
        child.setGlobalHookClick(enabled, traversalChildren, forceClickable)
    }
}
