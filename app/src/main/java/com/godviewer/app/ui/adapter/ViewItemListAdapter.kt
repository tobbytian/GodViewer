package com.godviewer.app.ui.adapter

import android.app.AlertDialog
import android.content.Context
import android.text.SpannableString
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.godviewer.app.IGNORE_HOOK
import com.godviewer.app.R
import com.godviewer.app.databinding.LayoutImageBinding
import com.godviewer.app.databinding.LayoutViewPreviewItemBinding
import com.godviewer.app.glide.GlideApp
import com.godviewer.app.util.ModuleDialogUi

class ViewItemListAdapter(
    private val views: List<View>,
    private val dialogContext: Context? = null,
) : BaseAdapter() {
    override fun getCount(): Int = views.size

    override fun getItem(position: Int): Any = views[position]

    override fun getItemId(position: Int): Long {
        return views[position].id.toLong()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = views[position]
        val inflateCtx = dialogContext ?: parent?.context ?: view.context
        val itemView: View = if (convertView == null) {
            ModuleDialogUi.inflate(inflateCtx, R.layout.layout_view_preview_item)
        } else {
            convertView
        }
        itemView.tag = IGNORE_HOOK
        val binding: LayoutViewPreviewItemBinding = LayoutViewPreviewItemBinding.bind(itemView)
        GlideApp
            .with(view)
            .load(view)
            .skipMemoryCache(true)
            .into(binding.viewImage)
        binding.viewImage.setOnClickListener {
            showViewImageDialog(view, inflateCtx)
        }
        binding.viewName.text = SpannableString(view::class.java.name)
        return itemView
    }

    private fun showViewImageDialog(view: View, dialogContext: Context) {
        val itemView = ModuleDialogUi.inflate(dialogContext, R.layout.layout_image)
        val binding = LayoutImageBinding.bind(itemView)

        val dialog = AlertDialog.Builder(ModuleDialogUi.wrap(dialogContext))
            .setTitle(view::class.java.name)
            .setView(itemView)
            .create()
        dialog.show()
        ModuleDialogUi.applyWindow(dialog, itemView)

        GlideApp.with(view).load(view).into(binding.previewImage)
    }
}
