package com.godviewer.app.target.hook

/**
 * @author hhvvg
 *
 * Hook interface for implementing customized hook process.
 *
 * API 102 迁移：由 [GodViewerModule.onPackageReady] 触发；Hooker 只装 Framework 类 Hook，
 * 编辑模式相关的触摸/点击/Popup Hook 由 EditModeTouchInterceptor 懒安装。
 */
interface IHooker {

    /**
     * Method with hook process.
     */
    fun onHook()
}
