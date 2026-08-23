package com.godviewer.app.ui.host

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.godviewer.app.BuildConfig
import com.godviewer.app.MainActivity
import com.godviewer.app.R
import com.godviewer.app.databinding.FragmentSettingsBinding
import com.godviewer.app.util.AppLanguage
import com.godviewer.app.util.GITHUB_PAGE_URL
import com.godviewer.app.util.HostPrefs
import com.godviewer.app.util.LauncherIconHelper

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var suppressHideIconCallback = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        applyHideIcon(true, notificationGranted = granted)
    }

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
        binding.changelogRow.setOnClickListener {
            startActivity(
                Intent(ctx, SimpleTextActivity::class.java)
                    .putExtra(SimpleTextActivity.EXTRA_TITLE_RES, R.string.settings_changelog_title)
                    .putExtra(SimpleTextActivity.EXTRA_BODY_RES, R.string.changelog_body),
            )
        }
        binding.userAgreementRow.setOnClickListener {
            startActivity(
                Intent(ctx, SimpleTextActivity::class.java)
                    .putExtra(SimpleTextActivity.EXTRA_TITLE_RES, R.string.settings_user_agreement_title)
                    .putExtra(SimpleTextActivity.EXTRA_BODY_RES, R.string.user_agreement_body),
            )
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
                maybeRequestNotificationThenHide()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> setHideIconChecked(false) }
            .setOnCancelListener { setHideIconChecked(false) }
            .show()
    }

    private fun maybeRequestNotificationThenHide() {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            applyHideIcon(true, notificationGranted = true)
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            applyHideIcon(true, notificationGranted = true)
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun applyHideIcon(hidden: Boolean, notificationGranted: Boolean) {
        val ctx = requireContext()
        LauncherIconHelper.setHidden(ctx, hidden)
        if (hidden) {
            val msg = if (notificationGranted) {
                R.string.settings_hide_icon_hidden
            } else {
                R.string.settings_hide_icon_hidden_no_notification
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun checkUpdate() {
        val ctx = requireContext()
        Toast.makeText(ctx, R.string.settings_check_update_opening, Toast.LENGTH_SHORT).show()
        val releases = "$GITHUB_PAGE_URL/releases"
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releases)))
        }.onFailure {
            Toast.makeText(ctx, releases, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_OPEN_SETTINGS = "open_settings"
    }
}
