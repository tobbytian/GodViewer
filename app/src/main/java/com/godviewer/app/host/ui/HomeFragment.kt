package com.godviewer.app.host.ui

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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.godviewer.app.BuildConfig
import com.godviewer.app.R
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.host.entry.EntryControlUi
import com.godviewer.app.host.prefs.HostPrefs
import com.godviewer.app.shared.GITHUB_PAGE_URL
import com.godviewer.app.shared.TELEGRAM_GROUP_URL
import com.godviewer.app.util.ModuleStatus
import com.google.android.material.card.MaterialCardView
import com.godviewer.app.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    /** 设置开关状态时抑制回调，避免程序化赋值触发通知刷新 */
    private var suppressNotifyCallback = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val ctx = context ?: return@registerForActivityResult
        if (granted) {
            EntryControlUi.refresh(ctx)
            Toast.makeText(ctx, R.string.home_host_notify_on, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, R.string.home_host_notify_need_permission, Toast.LENGTH_LONG).show()
        }
    }

    /** libxposed/service 绑定可能在 Binder 线程回调，切主线程后刷新激活卡片。 */
    private val activatedListener: () -> Unit = {
        activity?.runOnUiThread {
            if (_binding != null && isAdded) {
                refreshActivationStatus()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ModuleStatus.addActivatedListener(activatedListener)
        binding.statusVersion.text = getString(
            R.string.status_version_format,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
        binding.infoAndroid.text = buildAndroidLine()
        binding.infoDevice.text = buildDeviceLine()
        binding.infoAbi.text = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "—" }

        binding.githubButton.setOnClickListener { openUrl(GITHUB_PAGE_URL) }
        binding.telegramButton.setOnClickListener { openUrl(TELEGRAM_GROUP_URL) }
        binding.donateButton.setOnClickListener {
            startActivity(Intent(requireContext(), DonateActivity::class.java))
        }

        refreshHostNotifySwitch()
        binding.hostNotifySwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressNotifyCallback) return@setOnCheckedChangeListener
            onHostNotifyToggled(checked)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshActivationStatus()
        refreshHostNotifySwitch()
        binding.infoMirrored.text = RuleMirror.packageCount(requireContext()).toString()
    }

    override fun onDestroyView() {
        ModuleStatus.removeActivatedListener(activatedListener)
        super.onDestroyView()
        _binding = null
    }

    /** 按偏好同步开关状态（程序化赋值不触发通知刷新） */
    private fun refreshHostNotifySwitch() {
        suppressNotifyCallback = true
        binding.hostNotifySwitch.isChecked = HostPrefs.isHostNotifyEnabled(requireContext())
        suppressNotifyCallback = false
    }

    /**
     * 首页「本体通知」开关：打开后才显示本体常驻控制通知；
     * Android 13+ 无通知权限时先申请（否则通知发不出来）。
     */
    private fun onHostNotifyToggled(enabled: Boolean) {
        val ctx = requireContext()
        EntryControlUi.setHostNotification(ctx, enabled)
        if (!enabled) {
            Toast.makeText(ctx, R.string.home_host_notify_off, Toast.LENGTH_SHORT).show()
            return
        }
        val needPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        Toast.makeText(ctx, R.string.home_host_notify_on, Toast.LENGTH_SHORT).show()
    }

    private fun refreshActivationStatus() {
        val ctx = requireContext()
        val activated = ModuleStatus.check()
        val card: MaterialCardView = binding.statusCard
        if (activated) {
            card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.home_activated_card))
            binding.statusIcon.setImageResource(R.drawable.ic_check_circle_24)
            binding.statusIcon.setColorFilter(
                ContextCompat.getColor(ctx, R.color.home_activated_on_card),
            )
            binding.statusTitle.setText(R.string.status_activated)
            binding.statusTitle.setTextColor(
                ContextCompat.getColor(ctx, R.color.home_activated_on_card),
            )
            binding.statusVersion.setTextColor(
                ContextCompat.getColor(ctx, R.color.home_activated_on_card),
            )
            binding.statusHint.setText(R.string.status_activated_hint)
            binding.statusHint.setTextColor(
                ContextCompat.getColor(ctx, R.color.home_activated_on_card),
            )
        } else {
            card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.home_deactivated_card))
            binding.statusIcon.setImageResource(R.drawable.ic_warning_24)
            binding.statusIcon.setColorFilter(
                ContextCompat.getColor(ctx, R.color.home_deactivated_on_card),
            )
            binding.statusTitle.setText(R.string.status_not_activated)
            binding.statusTitle.setTextColor(
                ContextCompat.getColor(ctx, R.color.home_deactivated_on_card),
            )
            binding.statusVersion.setTextColor(
                ContextCompat.getColor(ctx, R.color.home_deactivated_on_card),
            )
            binding.statusHint.setText(R.string.status_not_activated_hint)
            binding.statusHint.setTextColor(
                ContextCompat.getColor(ctx, R.color.home_deactivated_on_card),
            )
        }
    }

    private fun buildAndroidLine(): String {
        val release = Build.VERSION.RELEASE ?: "?"
        val sdk = Build.VERSION.SDK_INT
        val codename = Build.VERSION.CODENAME
        return if (!codename.isNullOrBlank() && codename != "REL") {
            "$release (API $sdk, $codename)"
        } else {
            "$release (API $sdk)"
        }
    }

    private fun buildDeviceLine(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val device = Build.DEVICE.orEmpty().trim()
        val brandModel = listOf(manufacturer, model)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifBlank { model.ifBlank { "—" } }
        return if (device.isNotEmpty() && !brandModel.contains(device, ignoreCase = true)) {
            "$brandModel ($device)"
        } else {
            brandModel
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(requireContext(), url, Toast.LENGTH_SHORT).show()
        }
    }
}
