package com.godviewer.app.shared.diag

import com.godviewer.app.BuildConfig

/**
 * 目标进程 → 宿主：闪退栈 best-effort 上报（软 token，非加密）。
 * 权威仍在目标 files/godviewer/last-crash.txt；宿主副本便于无 adb 时反馈。
 */
object CrashReportProtocol {
    const val ACTION_TARGET_CRASH =
        "${BuildConfig.PACKAGE_NAME}.ACTION_TARGET_CRASH"

    const val EXTRA_TOKEN = "token"
    const val EXTRA_PACKAGE = "package_name"
    const val EXTRA_PROCESS = "process_name"
    const val EXTRA_THREAD = "thread_name"
    const val EXTRA_SUMMARY = "summary"
    const val EXTRA_STACK = "stack"
    const val EXTRA_RING = "ring"
    const val EXTRA_TIME = "time_ms"
    const val EXTRA_MODULE_VC = "module_vc"
    const val EXTRA_MODULE_VN = "module_vn"

    /** 与 HostControl 同族软 token，只挡随手 junk */
    const val CRASH_TOKEN = "godviewer-target-crash-v1"

    /** Binder 安全：栈 + ring 合计不宜过大 */
    const val MAX_STACK_CHARS = 24_000
    const val MAX_RING_CHARS = 12_000
}
