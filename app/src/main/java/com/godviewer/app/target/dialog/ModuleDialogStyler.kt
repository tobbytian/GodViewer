package com.godviewer.app.target.dialog

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.godviewer.app.R
import com.godviewer.app.target.hook.ModuleRes.moduleRes

/**
 * 注入弹窗控件强制宿主色板，避免目标主题浅字/透明字叠在浅底上看不见。
 * 新拟物：输入框 = 凹陷（默认深/聚焦浅），中性按钮 = 默认凸起、按下凹陷；
 * 危险/主色按钮 = 强调色实底 + 同款 2dp 渐变环（与卡片/开关边一致）。
 */
internal object ModuleDialogStyler {

    fun normalizeTree(root: View) {
        when (root) {
            is EditText -> styleEditText(root)
            // CheckBox 继承 Button，必须先于 is Button 分支拦截：
            // 勾选框只保留系统方框本身，去掉主题/色板注入的背景卡片与按钮内边距
            is CheckBox -> styleCheckBox(root)
            // Switch 继承 Button/TextView，必须先拦下：换成应用同款 shadcn 开关
            // （灰关/蓝开轨道 + 白圆钮），否则会被 Button 分支画成中性按钮
            is Switch -> styleSwitch(root)
            is Spinner -> styleSpinner(root)
            is Button -> styleButton(root)
            is TextView -> root.setTextColor(ModuleDialogPalette.dialogText)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                normalizeTree(root.getChildAt(i))
            }
        }
    }

    private fun styleCheckBox(checkBox: CheckBox) {
        checkBox.backgroundTintList = null
        checkBox.background = null
        checkBox.setTextColor(ModuleDialogPalette.dialogText)
    }

    /**
     * 应用同款 shadcn 开关：44×24 轨道（关灰/开蓝，selector 自带状态）+
     * 20dp 白圆钮。先清掉目标主题注入的 thumb/track tint，否则会被 SRC_IN 重染。
     * 框架 Switch 按 drawable 固有尺寸画钮（不像 SwitchCompat 拉满帧高），无需垫 padding。
     */
    private fun styleSwitch(sw: Switch) {
        sw.backgroundTintList = null
        sw.thumbTintList = null
        sw.trackTintList = null
        sw.thumbDrawable = moduleRes.getDrawable(R.drawable.bg_switch_thumb_flat, null)
        sw.trackDrawable = moduleRes.getDrawable(R.drawable.bg_switch_track_flat, null)
        sw.setTextColor(ModuleDialogPalette.dialogText)
    }

    private fun styleEditText(editText: EditText) {
        // 先清掉主题（DeviceDefault / OEM ROM）注入的 backgroundTint：
        // View.setBackground 会把残留 tint 以 SRC_IN 重新染到新 Drawable 上，
        // 状态刷新（按压/焦点）时按 stateful tint 再次取色 → 控件「有时」整块变黑
        editText.backgroundTintList = null
        val metrics = editText.resources.displayMetrics
        val hPad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, metrics).toInt()
        val vPad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, metrics).toInt()
        // 新拟物输入框：默认深凹陷 / 聚焦浅凹陷（bg_text_input 状态选择器）；
        // margin/padding 四向并排的输入框换 2dp 细环变体，四框挨着不显边框厚
        editText.background = moduleRes.getDrawable(
            if (editText.id in COMPACT_INPUT_IDS) R.drawable.bg_text_input_compact
            else R.drawable.bg_text_input,
            null,
        )
        editText.setTextColor(ModuleDialogPalette.dialogText)
        editText.setHintTextColor(ModuleDialogPalette.dialogHint)
        editText.setPadding(hPad, vPad, hPad, vPad)
    }

    private fun styleButton(button: Button) {
        // 先清掉主题（DeviceDefault / OEM ROM）注入的 backgroundTint：
        // View.setBackground / refreshDrawableState 会把残留 tint 以 SRC_IN
        // 重新染到我们的 GradientDrawable 上 → 按钮「有时」整颗变黑填充
        button.backgroundTintList = null
        val label = button.text?.toString().orEmpty()
        val danger = looksDanger(label)
        val primary = !danger && looksPrimary(label)
        val metrics = button.resources.displayMetrics
        // 中性按钮 = 新拟物凸起、按下凹陷；危险/主色 = 强调色实底 + 同款 2dp 渐变环
        button.background = when {
            danger -> moduleRes.getDrawable(R.drawable.bg_button_danger, null)
            primary -> moduleRes.getDrawable(R.drawable.bg_button_primary, null)
            else -> moduleRes.getDrawable(R.drawable.bg_neu_button, null)
        }
        button.setTextColor(
            when {
                danger -> ModuleDialogPalette.dialogDangerText
                primary -> ModuleDialogPalette.dialogPrimaryText
                else -> ModuleDialogPalette.dialogButtonText
            },
        )
        button.minHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            44f,
            metrics,
        ).toInt()
        val hPad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14f, metrics).toInt()
        button.setPadding(hPad, button.paddingTop, hPad, button.paddingBottom)
        // minSdk 23 (LOLLIPOP+21) 恒成立：压平按钮阴影，避免目标主题 elevation 叠加
        button.elevation = 0f
        button.stateListAnimator = null
    }

    /** 下拉选择（宽/高模式等）：与输入框同语言的凹陷+chevron 底；目标主题的
     *  backgroundTint 先清掉（残留 tint 会 SRC_IN 染到新底上），选中项文字色在
     *  布局后统一刷一次（ArrayAdapter 复用该 TextView，后续换选不回退）。 */
    private fun styleSpinner(spinner: Spinner) {
        spinner.backgroundTintList = null
        spinner.background = moduleRes.getDrawable(R.drawable.bg_spinner, null)
        spinner.post {
            for (i in 0 until spinner.childCount) {
                (spinner.getChildAt(i) as? TextView)
                    ?.setTextColor(ModuleDialogPalette.dialogText)
            }
        }
    }

    /** margin/padding 四向并排输入框：细环 + 行内留缝（布局侧 4dp），避免边框相接 */
    private val COMPACT_INPUT_IDS = setOf(
        R.id.margin_left, R.id.margin_top, R.id.margin_right, R.id.margin_bottom,
        R.id.padding_left, R.id.padding_top, R.id.padding_right, R.id.padding_bottom,
    )

    private fun looksPrimary(label: String): Boolean {
        val t = label.lowercase()
        return t.contains("apply") ||
            t.contains("应用") ||
            t.contains("确定") ||
            t.contains("ok") ||
            t.contains("advanced") ||
            t.contains("高级")
    }

    private fun looksDanger(label: String): Boolean {
        val t = label.lowercase()
        return t.contains("delete") ||
            t.contains("删除") ||
            t.contains("hide") ||
            t.contains("隐藏")
    }
}
