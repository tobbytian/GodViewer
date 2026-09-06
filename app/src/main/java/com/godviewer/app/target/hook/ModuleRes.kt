package com.godviewer.app.target.hook

import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import com.godviewer.app.shared.GvLog

/**
 * 模块资源（注入弹窗 / 通知 UI 用）。
 *
 * API 102 迁移：没有 zygote 回调，改为 [GodViewerModule.onModuleLoaded] 里用
 * `getModuleApplicationInfo()` 拿到模块 APK 路径后惰性创建；不再依赖旧 `XModuleResources`。
 * 创建失败只置 false，访问方先用 [isModuleResReady] 判断并跳过 UI，绝不拖垮目标 App。
 */
object ModuleRes {
    private const val TAG = "ModuleRes"

    @JvmStatic
    lateinit var moduleRes: Resources
        private set

    @JvmStatic
    fun isModuleResReady(): Boolean = this::moduleRes.isInitialized

    /** 用模块自身 APK 构建独立 Resources；系统 metrics 兜底（弹窗 dp/密度正确）。 */
    @JvmStatic
    @Synchronized
    fun ensureCreated(appInfo: ApplicationInfo?): Boolean {
        if (isModuleResReady()) return true
        val apkPath = appInfo?.sourceDir
        if (apkPath.isNullOrBlank()) {
            GvLog.e(TAG, "module res create failed: no sourceDir")
            return false
        }
        return runCatching {
            val ctor = AssetManager::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            val assets = ctor.newInstance() as AssetManager
            AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply {
                isAccessible = true
            }.invoke(assets, apkPath)
            val system = Resources.getSystem()
            moduleRes = Resources(assets, system.displayMetrics, system.configuration)
            GvLog.i(TAG, "module res created apk=$apkPath")
            true
        }.onFailure {
            GvLog.e(TAG, "module res create failed apk=$apkPath", it)
        }.getOrDefault(false)
    }
}
