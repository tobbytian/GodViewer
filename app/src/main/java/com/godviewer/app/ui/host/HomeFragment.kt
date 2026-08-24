package com.godviewer.app.ui.host

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.godviewer.app.BuildConfig
import com.godviewer.app.R
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.databinding.FragmentHomeBinding
import com.godviewer.app.util.GITHUB_PAGE_URL
import com.godviewer.app.util.ModuleStatus
import com.google.android.material.card.MaterialCardView


class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

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
        binding.statusVersion.text = getString(
            R.string.status_version_format,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
        binding.infoAndroid.text = buildAndroidLine()
        binding.infoDevice.text = buildDeviceLine()
        binding.infoAbi.text = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "—" }

        binding.githubButton.setOnClickListener { openUrl(GITHUB_PAGE_URL) }
        binding.donateButton.setOnClickListener {
            startActivity(Intent(requireContext(), DonateActivity::class.java))
        }

    }

    override fun onResume() {
        super.onResume()
        refreshActivationStatus()
        binding.infoMirrored.text = RuleMirror.packageCount(requireContext()).toString()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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
