package com.godviewer.app.target.util

import android.app.Application
import java.util.Collections
import java.util.WeakHashMap

/**
 * API 102 迁移：旧 `XposedHelpers.setAdditionalInstanceField` 改为进程内 WeakHashMap。
 * 键是 Application（进程存活期间始终强可达），无泄漏风险。
 */
private val additionalFields: MutableMap<Any, MutableMap<String, Any?>> =
    Collections.synchronizedMap(WeakHashMap())

fun Application.injectField(name: String, value: Any?) {
    additionalFields.getOrPut(this) { HashMap() }[name] = value
}

fun <T> Application.getInjectedField(name: String, defaultValue: T? = null): T? {
    val value = additionalFields[this]?.get(name)
    @Suppress("UNCHECKED_CAST")
    return (value as? T) ?: defaultValue
}
