package com.godviewer.app.ui.adapter

import android.app.AlertDialog
import android.text.SpannableString
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.godviewer.app.IGNORE_HOOK
import com.godviewer.app.R
import com.godviewer.app.databinding.LayoutImageBinding
import com.godviewer.app.databinding.LayoutViewPreviewItemBinding
import com.godviewer.app.glide.GlideApp
import com.godviewer.app.hook.AnyHookZygote

class ViewItemListAdapter(private val views: List<View>) : BaseAdapter() {
    override fun getCount(): Int = views.size

    override fun getItem(position: Int): Any = views[position]

    override fun getItemId(position: Int): Long {
        return views[position].id.toLong()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = views[position]
        val itemView: View = if (convertView == null) {
            val layout = AnyHookZygote.moduleRes.getLayout(R.layout.layout_view_preview_item)
            val inflater = LayoutInflater.from(view.context)
            inflater.inflate(layout, null, false)
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
            showViewImageDialog(view)
        }
        binding.viewName.text = SpannableString(view::class.java.name)
        return itemView
    }

    private fun showViewImageDialog(view: View) {
        val layout = AnyHookZygote.moduleRes.getLayout(R.layout.layout_image)
        val inflater = LayoutInflater.from(view.context)
        val itemView = inflater.inflate(layout, null, false)
        val binding = LayoutImageBinding.bind(itemView)

        val dialog = AlertDialog.Builder(view.context)
            .setTitle(view::class.java.name)
            .setView(itemView)
            .create()
        dialog.show()

        GlideApp.with(view).load(view).into(binding.previewImage)
    }
}
