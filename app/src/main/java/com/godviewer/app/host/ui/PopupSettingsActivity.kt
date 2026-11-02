package com.godviewer.app.host.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.godviewer.app.R
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.databinding.ActivityPopupSettingsBinding
import com.godviewer.app.host.prefs.HostPrefs
import com.godviewer.app.shared.control.HostControlBridge

/**
 * 二级页「弹窗检测设置」（单应用规则页入口，标题栏返回）：
 * 每应用的弹窗检测通知开关、检测时长，以及该应用的弹窗规则
 * （过滤模式，点按编辑、溢出菜单手动新建）。
 */
class PopupSettingsActivity : HostActivity() {
    private val binding by lazy { ActivityPopupSettingsBinding.inflate(layoutInflater) }
    private lateinit var packageName: String

    /** 程序化设置检测开关时抑制回调 */
    private var suppressDetectCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.popupDetectSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressDetectCallback) return@setOnCheckedChangeListener
            HostPrefs.setPopupDetectEnabledFor(this, packageName, checked)
            Toast.makeText(
                this,
                if (checked) R.string.popup_detect_on else R.string.popup_detect_off,
                Toast.LENGTH_SHORT,
            ).show()
        }
        binding.popupWindowRow.setOnClickListener { showWindowDialog() }
        binding.popupRulesRow.setOnClickListener {
            startActivity(
                Intent(this, PopupRulesActivity::class.java)
                    .putExtra(PopupRulesActivity.EXTRA_PACKAGE, packageName),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** 检测开关状态 / 检测时长文案 / 弹窗规则计数（从规则页返回时重新统计） */
    private fun refresh() {
        suppressDetectCallback = true
        binding.popupDetectSwitch.isChecked =
            HostPrefs.isPopupDetectEnabledFor(this, packageName)
        suppressDetectCallback = false
        binding.popupWindowValue.text = getString(
            R.string.popup_window_value,
            HostPrefs.getPopupWindowSecondsFor(this, packageName),
        )
        binding.popupRulesValue.text = getString(
            R.string.popup_rules_count,
            RuleMirror.loadDialogRules(this, packageName).size,
        )
    }

    /** 每应用检测时长输入：保存后立即推给该应用（进程不在则靠本地缓存 + 下次拉取） */
    private fun showWindowDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            // 新拟物输入框：凹陷底（随后 setPadding 20dp 覆盖 drawable 内边距）
            background = getDrawable(R.drawable.bg_text_input)
            setText(
                HostPrefs.getPopupWindowSecondsFor(this@PopupSettingsActivity, packageName)
                    .toString(),
            )
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        input.setPadding(pad, pad, pad, pad)
        AlertDialog.Builder(this)
            .setTitle(R.string.popup_window_title)
            .setMessage(
                getString(
                    R.string.popup_window_dialog_message,
                    HostPrefs.MIN_POPUP_WINDOW_SECONDS,
                    HostPrefs.MAX_POPUP_WINDOW_SECONDS,
                ),
            )
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.popup_save) { _, _ ->
                val seconds = input.text.toString().trim().toIntOrNull()
                if (seconds == null) {
                    Toast.makeText(this, R.string.popup_window_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                HostPrefs.setPopupWindowSecondsFor(this, packageName, seconds)
                HostControlBridge.dispatchPopupInterceptState(
                    this,
                    packageName,
                    HostPrefs.isPopupInterceptEnabled(this),
                    HostPrefs.getPopupWindowSecondsFor(this, packageName),
                )
                refresh()
            }
            .show()
    }

    companion object {
        const val EXTRA_PACKAGE = "extra_package"
    }
}