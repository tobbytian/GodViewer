package com.godviewer.app.shared

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import java.security.MessageDigest

/**
 * 跨文件复用的纯工具（原散落于 RuleMirror / ViewRuleManager / 通知类）。
 * 仅含无副作用的小函数，便于单点维护。
 */

/** UTF-8 字节的 SHA-256 十六进制串；异常时退化为 hashCode（与原逻辑一致）。 */
fun sha256(input: String): String = try {
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
} catch (e: Exception) {
    input.hashCode().toString().replace("-", "n")
}

/** PendingIntent 不可变标志：Android M 及以上必须，否则崩溃。 */
fun immutableFlag(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PendingIntent.FLAG_IMMUTABLE
    } else {
        0
    }
}

/** 解析应用显示名；失败回退到包名（与原 RuleMirror/HostControlBridge 逻辑一致）。 */
fun resolveAppLabel(context: Context, packageName: String): String {
    return runCatching {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)
}
