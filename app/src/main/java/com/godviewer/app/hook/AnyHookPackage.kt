package com.godviewer.app.hook

import android.util.Log
import com.godviewer.app.BuildConfig
import com.godviewer.app.hook.hookers.ActivityLifecycleHooker
import com.godviewer.app.hook.hookers.ApplicationHooker
import com.godviewer.app.hook.hookers.PupupWindowHooker
import com.godviewer.app.hook.hookers.TextViewHooker
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Class for package hook.
 *
 * @author hhvvg
 */
class AnyHookPackage : IXposedHookLoadPackage {
    private val hookers: ArrayList<IHooker> = arrayListOf(
        ApplicationHooker(),
        TextViewHooker(),
        PupupWindowHooker(),
        ActivityLifecycleHooker(),
    )

    override fun handleLoadPackage(p0: XC_LoadPackage.LoadPackageParam?) {
        if (p0 == null) {
            return
        }
        val packageName = p0.packageName
        // Only install the activation probe in the module's own process.
        // Must use app ClassLoader + class name string: ModuleClassLoader's
        // ModuleStatus is a different Class object from the UI's ModuleStatus.
        if (packageName == BuildConfig.PACKAGE_NAME) {
            runCatching {
                // App ClassLoader + hookAllMethods: Kotlin object may expose both
                // static (@JvmStatic) and instance isActivated(); hook every one.
                val clazz = XposedHelpers.findClass(
                    "com.godviewer.app.util.ModuleStatus",
                    p0.classLoader,
                )
                XposedBridge.hookAllMethods(
                    clazz,
                    "isActivated",
                    XC_MethodReplacement.returnConstant(true),
                )
                Log.d(TAG, "host activation probe installed")
            }.onFailure {
                Log.e(TAG, "host activation probe failed", it)
            }
            return
        }
        hookers.forEach {
            it.onHook(param = p0)
        }
    }

    companion object {
        private const val TAG = "GodViewer.Hook"
    }
}
