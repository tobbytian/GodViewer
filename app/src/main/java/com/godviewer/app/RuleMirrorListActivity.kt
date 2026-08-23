package com.godviewer.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.godviewer.app.ui.host.HostActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.databinding.ActivityRuleMirrorListBinding
import com.godviewer.app.databinding.ItemMirroredPackageBinding
import java.text.DateFormat
import java.util.Date

class RuleMirrorListActivity : HostActivity() {
    private val binding by lazy { ActivityRuleMirrorListBinding.inflate(layoutInflater) }
    private val adapter = PackageAdapter { item ->
        startActivity(
            Intent(this, RuleMirrorDetailActivity::class.java)
                .putExtra(RuleMirrorDetailActivity.EXTRA_PACKAGE, item.packageName)
                .putExtra(RuleMirrorDetailActivity.EXTRA_LABEL, item.label),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        val items = RuleMirror.listPackages(this)
        adapter.submit(items)
        binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private class PackageAdapter(
        private val onClick: (RuleMirror.MirroredPackage) -> Unit,
    ) : RecyclerView.Adapter<PackageAdapter.Holder>() {
        private val items = ArrayList<RuleMirror.MirroredPackage>()
        private val timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

        fun submit(list: List<RuleMirror.MirroredPackage>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemMirroredPackageBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(
            private val binding: ItemMirroredPackageBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: RuleMirror.MirroredPackage) {
                if (item.icon != null) {
                    binding.appIcon.setImageDrawable(item.icon)
                } else {
                    binding.appIcon.setImageResource(R.mipmap.ic_launcher)
                }
                // Prefer human label; package stays as secondary line
                binding.appLabel.text = item.label
                binding.appPackage.text = item.packageName
                binding.appMeta.text = binding.root.context.getString(
                    R.string.mirror_package_meta,
                    item.ruleCount,
                    timeFormat.format(Date(item.updatedAt)),
                )
                binding.root.setOnClickListener { onClick(item) }
            }
        }
    }
}
