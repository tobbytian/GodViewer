package com.godviewer.app.shared.diag

import com.godviewer.app.BuildConfig

/**
 * 目标进程 → 宿主：全量调试 ring（GvLog）best-effort 上报。
 * 不是只收闪退；闪退仍走 [CrashReportProtocol]。
 */
object DiagReportProtocol {
    const val ACTION_TARGET_DIAG =
        "${BuildConfig.PACKAGE_NAME}.ACTION_TARGET_DIAG"

    const val EXTRA_TOKEN = "token"
    const val EXTRA_PACKAGE = "package_name"
    const val EXTRA_PROCESS = "process_name"
    const val EXTRA_STAGE = "stage"
    const val EXTRA_BODY = "body"
    const val EXTRA_TIME = "time_ms"
    const val EXTRA_MODULE_VC = "module_vc"
    const val EXTRA_MODULE_VN = "module_vn"
    const val EXTRA_LINE_COUNT = "line_count"

    /** 软 token，挡随手 junk */
    const val DIAG_TOKEN = "godviewer-target-diag-v1"

    /** Binder 安全上限（UTF-16 字符近似） */
    const val MAX_BODY_CHARS = 28_000
}
