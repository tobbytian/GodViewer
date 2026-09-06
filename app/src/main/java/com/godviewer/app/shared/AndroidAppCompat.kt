package com.godviewer.app.shared

import android.app.Application

/**
 * `android.app.AndroidAppHelper` 的现代替代。
 *
 * 旧类是 XposedBridge（legacy bridge）注入的兼容类，libxposed API 102 模块进程里不存在；
 * 这里改用 `ActivityThread` 反射获取当前应用 / 进程名。首次成功后缓存 Application 实例。
 */
object AndroidAppCompat {
    @Volatile
    private var cachedApp: Application? = null

    fun currentApplication(): Application? {
        cachedApp?.let { return it }
        val app = runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val method = at.getDeclaredMethod("currentApplication")
            method.isAccessible = true
            method.invoke(null) as? Application
        }.getOrNull() ?: return null
        cachedApp = app
        return app
    }

    fun currentProcessName(): String? {
        return runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val method = at.getDeclaredMethod("currentProcessName")
            method.isAccessible = true
            method.invoke(null) as? String
        }.getOrNull()
    }
}
