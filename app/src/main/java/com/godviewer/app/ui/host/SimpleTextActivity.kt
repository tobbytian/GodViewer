package com.godviewer.app.ui.host

import android.os.Bundle
import com.godviewer.app.databinding.ActivitySimpleTextBinding

/** Host-only read-only text page (changelog / user agreement). */
class SimpleTextActivity : HostActivity() {
    private val binding by lazy { ActivitySimpleTextBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        val titleRes = intent.getIntExtra(EXTRA_TITLE_RES, 0)
        val bodyRes = intent.getIntExtra(EXTRA_BODY_RES, 0)
        if (titleRes != 0) {
            binding.toolbar.setTitle(titleRes)
        }
        if (bodyRes != 0) {
            binding.bodyText.setText(bodyRes)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    companion object {
        const val EXTRA_TITLE_RES = "title_res"
        const val EXTRA_BODY_RES = "body_res"
    }
}
