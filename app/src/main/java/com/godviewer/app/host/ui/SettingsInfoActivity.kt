package com.godviewer.app.host.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.godviewer.app.R
import com.godviewer.app.databinding.ActivitySettingsInfoBinding
import com.godviewer.app.host.diag.HostDiag

/** 设置二级页：更新日志 / 用户协议 / 诊断日志（设置页「关于与诊断」进入）。 */
class SettingsInfoActivity : HostActivity() {
    private val binding by lazy { ActivitySettingsInfoBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.changelogRow.setOnClickListener {
            startActivity(
                Intent(this, SimpleTextActivity::class.java)
                    .putExtra(SimpleTextActivity.EXTRA_TITLE_RES, R.string.settings_changelog_title)
                    .putExtra(SimpleTextActivity.EXTRA_BODY_RES, R.string.changelog_body),
            )
        }
        binding.userAgreementRow.setOnClickListener {
            startActivity(
                Intent(this, SimpleTextActivity::class.java)
                    .putExtra(SimpleTextActivity.EXTRA_TITLE_RES, R.string.settings_user_agreement_title)
                    .putExtra(SimpleTextActivity.EXTRA_BODY_RES, R.string.user_agreement_body),
            )
        }
        binding.copyDiagRow.setOnClickListener {
            val ok = HostDiag.copyToClipboard(this)
            Toast.makeText(
                this,
                if (ok) R.string.settings_copy_diag_done else R.string.settings_copy_diag_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
        binding.clearDiagRow.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_clear_diag_confirm_title)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.settings_clear_diag_confirm) { _, _ ->
                    val ok = HostDiag.clearLogs(this)
                    Toast.makeText(
                        this,
                        if (ok) R.string.settings_clear_diag_done else R.string.settings_clear_diag_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                .show()
        }
    }
}