package com.godviewer.app.host.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.godviewer.app.R
import com.godviewer.app.databinding.ActivityPopupLogBinding
import com.godviewer.app.databinding.ItemPopupRuleBinding
import com.godviewer.app.host.control.PopupLogStore
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.resolveAppLabel
import java.text.DateFormat
import java.util.Date

/** 弹窗拦截记录：最近关闭的弹窗（自动重放 / 手动），用来确认功能在工作、排查误杀。 */
class PopupLogActivity : HostActivity() {
    private val binding by lazy { ActivityPopupLogBinding.inflate(layoutInflater) }
    private val adapter = Adapter()
    private val timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_popup_log)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear_log) {
                PopupLogStore.clear(this)
                reload()
                true
            } else {
                false
            }
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val entries = PopupLogStore.all(this)
        adapter.submit(entries)
        binding.emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.toolbar.menu.findItem(R.id.action_clear_log)?.isEnabled = entries.isNotEmpty()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = ArrayList<PopupLogStore.Entry>()

        fun submit(list: List<PopupLogStore.Entry>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val root = ItemPopupRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false).root
            return object : RecyclerView.ViewHolder(root) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val entry = items[position]
            val b = ItemPopupRuleBinding.bind(holder.itemView)
            b.ruleTitle.text = entry.label
            val kind = getString(
                if (entry.kind == HostControlBridge.KIND_AUTO) R.string.popup_log_kind_auto
                else R.string.popup_log_kind_manual,
            )
            b.ruleSubtitle.text = getString(
                R.string.popup_log_subtitle,
                resolveAppLabel(this@PopupLogActivity, entry.packageName),
                kind,
                timeFormat.format(Date(entry.time)),
            )
            b.root.isClickable = false
        }

        override fun getItemCount() = items.size
    }
}
