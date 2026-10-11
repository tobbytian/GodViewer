package com.godviewer.app.host.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.godviewer.app.BuildConfig
import com.godviewer.app.R
import com.godviewer.app.host.LauncherIconHelper
import com.godviewer.app.host.MainActivity
import com.godviewer.app.host.UpdateChecker
import com.godviewer.app.host.prefs.HostPrefs
import com.godviewer.app.shared.AppLanguage
import com.godviewer.app.shared.TUTORIAL_URL
import com.godviewer.app.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var suppressHideIconCallback = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        refreshLanguageValue()
        binding.languageRow.setOnClickListener { showLanguagePicker() }

        // 教程：从底部导航移到设置页，点击打开酷安图文教程
        binding.tutorialRow.setOnClickListener { openTutorial() }

        suppressHideIconCallback = true
        binding.hideIconSwitch.isChecked = LauncherIconHelper.isHidden(ctx)
        suppressHideIconCallback = false
        binding.hideIconSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressHideIconCallback) return@setOnCheckedChangeListener
            if (checked) {
                confirmHideIcon()
            } else {
                LauncherIconHelper.setHidden(ctx, false)
                Toast.makeText(ctx, R.string.settings_hide_icon_shown, Toast.LENGTH_SHORT).show()
            }
        }

        binding.autoUpdateSwitch.setOnCheckedChangeListener(null)
        binding.autoUpdateSwitch.isChecked = HostPrefs.isAutoUpdateEnabled(ctx)
        binding.autoUpdateSwitch.setOnCheckedChangeListener { _, checked ->
            HostPrefs.setAutoUpdateEnabled(ctx, checked)
            val msg = if (checked) {
                R.string.settings_auto_update_on
            } else {
                R.string.settings_auto_update_off
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }

        binding.checkUpdateSubtitle.text = getString(
            R.string.settings_check_update_subtitle_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
        binding.checkUpdateRow.setOnClickListener { checkUpdate() }
        // 更新日志/用户协议/诊断日志合并进二级页「关于与诊断」
        binding.infoRow.setOnClickListener {
            startActivity(Intent(ctx, SettingsInfoActivity::class.java))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refreshLanguageValue() {
        val mode = AppLanguage.current(requireContext())
        binding.languageValue.setText(AppLanguage.labelRes(mode))
    }

    /** 打开图文教程（酷安）；无浏览器时降级为 Toast 展示链接 */
    private fun openTutorial() {
        val ctx = requireContext()
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TUTORIAL_URL)))
        }.onFailure {
            Toast.makeText(ctx, TUTORIAL_URL, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLanguagePicker() {
        val ctx = requireContext()
        val modes = arrayOf(AppLanguage.SYSTEM, AppLanguage.ZH, AppLanguage.EN)
        val labels = arrayOf(
            getString(R.string.settings_language_system),
            getString(R.string.settings_language_zh),
            getString(R.string.settings_language_en),
        )
        val current = AppLanguage.current(ctx)
        val checked = modes.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.settings_language_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val selected = modes[which]
                if (selected != current) {
                    AppLanguage.set(ctx, selected)
                    dialog.dismiss()
                    recreateHost()
                } else {
                    dialog.dismiss()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun recreateHost() {
        val activity = activity ?: return
        val intent = Intent(activity, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EXTRA_OPEN_SETTINGS, true)
        }
        activity.startActivity(intent)
        activity.finish()
        activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun setHideIconChecked(checked: Boolean) {
        val switch = _binding?.hideIconSwitch ?: return
        suppressHideIconCallback = true
        switch.isChecked = checked
        suppressHideIconCallback = false
    }

    private fun confirmHideIcon() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.settings_hide_icon_title)
            .setMessage(R.string.settings_hide_icon_confirm)
            .setPositiveButton(R.string.settings_hide_icon_confirm_ok) { _, _ ->
                applyHideIcon(true)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> setHideIconChecked(false) }
            .setOnCancelListener { setHideIconChecked(false) }
            .show()
    }

    private fun applyHideIcon(hidden: Boolean) {
        val ctx = requireContext()
        LauncherIconHelper.setHidden(ctx, hidden)
        if (hidden) {
            Toast.makeText(ctx, R.string.settings_hide_icon_hidden, Toast.LENGTH_LONG).show()
        }
    }

    private fun checkUpdate() {
        UpdateChecker.checkManual(requireContext())
    }

    companion object {
        const val EXTRA_OPEN_SETTINGS = "open_settings"
    }
}
