package com.godviewer.app.host.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.godviewer.app.databinding.DialogConfirmDeleteBinding

/**
 * 宿主统一的删除确认对话框：白色圆角面板 + 圆形危险图标 + 中性/危险双 pill 按钮，
 * 替代分散在各页面的原生 AlertDialog，保证删除类交互风格一致。
 */
object ConfirmDialog {

    private const val MAX_WIDTH_DP = 340
    private const val HORIZONTAL_MARGIN_DP = 48

    fun showDelete(
        context: Context,
        title: CharSequence,
        message: CharSequence,
        onConfirm: () -> Unit,
    ) {
        val binding = DialogConfirmDeleteBinding.inflate(LayoutInflater.from(context))
        binding.confirmTitle.text = title
        binding.confirmMessage.text = message
        val dialog = AlertDialog.Builder(context)
            .setView(binding.root)
            .setCancelable(true)
            .create()
        binding.confirmCancel.setOnClickListener { dialog.dismiss() }
        binding.confirmDelete.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        dialog.show()
        val window = dialog.window ?: return
        window.setBackgroundDrawableResource(android.R.color.transparent)
        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        window.setLayout(
            minOf(
                screenWidth - (HORIZONTAL_MARGIN_DP * density).toInt(),
                (MAX_WIDTH_DP * density).toInt(),
            ),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
    }
}