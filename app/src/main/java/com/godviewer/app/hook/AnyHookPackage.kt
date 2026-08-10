package com.godviewer.app.hook

import com.godviewer.app.BuildConfig
import com.godviewer.app.hook.hookers.ActivityLifecycleHooker
import com.godviewer.app.hook.hookers.ApplicationHooker
import com.godviewer.app.hook.hookers.PupupWindowHooker
import com.godviewer.app.hook.hookers.TextViewHooker
import de.robv.android.xposed.IXposedHookLoadPackage
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
        // Don't hook itself
        val packageName = p0.packageName
        if (packageName == BuildConfig.PACKAGE_NAME) {
            return
        }
        hookers.forEach {
            it.onHook(param = p0)
        }
    }
}
