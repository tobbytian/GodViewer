package com.godviewer.app.target.dialog

import androidx.annotation.ColorRes
import com.godviewer.app.R
import com.godviewer.app.target.hook.ModuleRes
import com.godviewer.app.target.hook.ModuleRes.moduleRes

/**
 * 注入弹窗色板：与宿主 home_* / md error 对齐，经 moduleRes 读取。
 */
internal object ModuleDialogPalette {
    val dialogBg get() = color(R.color.home_page_background)
    /** 弹窗面板约 90% 不透明，略透出目标应用界面 */
    val dialogPanelBg get() = withAlpha(dialogBg, alpha = 0.90f)
    val dialogText get() = color(R.color.home_title_text)
    val dialogHint get() = color(R.color.home_subtitle_text)
    val dialogButtonBg get() = color(R.color.home_card_background)
    val dialogButtonText get() = color(R.color.home_title_text)
    val dialogPrimaryBg get() = color(R.color.home_activated_card)
    val dialogPrimaryText get() = color(R.color.home_activated_on_card)
    val dialogDangerBg get() = color(R.color.md_theme_light_errorContainer)
    val dialogDangerText get() = color(R.color.md_theme_light_onErrorContainer)
    val dialogInputBg get() = color(R.color.home_link_chip_background)
    val dialogDivider get() = color(R.color.home_divider)

    fun color(@ColorRes id: Int): Int {
        // minSdk 23 (M) 起 Resources.getColor(id, theme) 恒可用，theme=null 与旧 getColor(id) 等价
        return moduleRes.getColor(id, null)
    }

    fun withAlpha(argb: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (argb and 0x00FFFFFF) or (a shl 24)
    }
}
