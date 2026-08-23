package com.godviewer.app.ui.host

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.godviewer.app.R
import com.godviewer.app.RuleMirrorDetailActivity
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.databinding.FragmentRulesBinding
import com.godviewer.app.databinding.ItemMirroredPackageBinding
import com.godviewer.app.util.HostPrefs
import java.text.DateFormat
import java.util.Date

class RulesFragment : Fragment() {
    private var _binding: FragmentRulesBinding? = null
    private val binding get() = _binding!!
    private var syncTipDialog: AlertDialog? = null

    private val adapter = PackageAdapter { item ->
        startActivity(
            Intent(requireContext(), RuleMirrorDetailActivity::class.java)
                .putExtra(RuleMirrorDetailActivity.EXTRA_PACKAGE, item.packageName)
                .putExtra(RuleMirrorDetailActivity.EXTRA_LABEL, item.label),
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        val items = RuleMirror.listPackages(requireContext())
        adapter.submit(items)
        binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        maybeShowSyncTip()
    }

    override fun onPause() {
        syncTipDialog?.dismiss()
        syncTipDialog = null
        super.onPause()
    }

    override fun onDestroyView() {
        syncTipDialog?.dismiss()
        syncTipDialog = null
        super.onDestroyView()
        _binding = null
    }

    private fun maybeShowSyncTip() {
        val ctx = context ?: return
        if (HostPrefs.isRulesSyncTipDismissed(ctx)) return
        if (syncTipDialog?.isShowing == true) return
        syncTipDialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.rules_sync_tip_title)
            .setMessage(R.string.rules_sync_tip_message)
            .setPositiveButton(R.string.rules_sync_tip_got_it, null)
            .setNeutralButton(R.string.rules_sync_tip_dont_show) { _, _ ->
                HostPrefs.setRulesSyncTipDismissed(ctx, true)
            }
            .setOnDismissListener { syncTipDialog = null }
            .show()
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
