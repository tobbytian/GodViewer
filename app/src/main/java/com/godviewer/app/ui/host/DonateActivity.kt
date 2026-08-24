package com.godviewer.app.ui.host

import android.os.Bundle
import com.godviewer.app.databinding.ActivityDonateBinding

/** Host-only donate page with payment QR codes. */
class DonateActivity : HostActivity() {
    private val binding by lazy { ActivityDonateBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }
}
