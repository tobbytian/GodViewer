package com.godviewer.app.host.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.godviewer.app.R
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.host.RuleMirrorDetailActivity
import com.godviewer.app.host.control.PendingCommandStore
import com.godviewer.app.host.io.RuleBundle
import com.godviewer.app.host.io.RuleTransfer
import com.godviewer.app.host.prefs.HostPrefs
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.mirror.MirroredPackage
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.godviewer.app.databinding.FragmentRulesBinding
import com.godviewer.app.databinding.ItemMirroredPackageBinding

class RulesFragment : Fragment() {
    private var _binding: FragmentRulesBinding? = null
    private val binding get() = _binding!!
    private var syncTipDialog: AlertDialog? = null

    /** 导入：SAF 选 JSON → 解析 → 预览确认（可覆盖模式）→ 逐应用入队下发 */
    private val importDocLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val ctx = context ?: return@registerForActivityResult
        val act = activity ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        val json = RuleTransfer.readText(ctx, uri)
        if (json == null) {
            Toast.makeText(
                ctx,
                ctx.getString(R.string.import_invalid, "read failed"),
                Toast.LENGTH_LONG,
            ).show()
            return@registerForActivityResult
        }
        RuleTransferUi.startImportFlow(act, json) { _, _ -> refreshList() }
    }

    /** 批量导出：SAF 建文件 → 结果返回时读最新镜像写出（用户选路径期间可到达补推） */
    private val exportDocLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(),
    ) { uri ->
        val ctx = context ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        val bundle = RuleBundle.all(ctx)
        if (bundle == null) {
            Toast.makeText(ctx, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val ok = RuleTransfer.writeText(ctx, uri, RuleBundle.encode(bundle))
        Toast.makeText(
            ctx,
            if (ok) {
                ctx.getString(
                    R.string.export_done,
                    bundle.apps.size,
                    bundle.apps.sumOf { it.viewRules.size + it.dialogRules.size },
                )
            } else {
                ctx.getString(R.string.export_save_failed)
            },
            Toast.LENGTH_LONG,
        ).show()
    }

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
        binding.importButton.setOnClickListener { onImportClicked() }
        binding.exportAllButton.setOnClickListener { onExportClicked() }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        maybeShowSyncTip()
        // 顺手清理已同步的待同步命令（目标可能在后台已回推确认）
        runCatching { PendingCommandStore.pruneSynced(requireContext()) }
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

    private fun refreshList() {
        val binding = _binding ?: return
        val ctx = context ?: return
        val items = RuleMirror.listPackages(ctx)
        adapter.submit(items)
        binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onImportClicked() {
        val ctx = context ?: return
        RuleTransferUi.showImportOptions(
            activity = requireActivity(),
            onPickFile = { importDocLauncher.launch(arrayOf("*/*")) },
            onPaste = {
                val json = RuleTransfer.pasteFromClipboard(ctx)
                if (json == null) {
                    Toast.makeText(ctx, R.string.import_clipboard_empty, Toast.LENGTH_SHORT)
                        .show()
                } else {
                    RuleTransferUi.startImportFlow(requireActivity(), json) { _, _ -> refreshList() }
                }
            },
        )
    }

    private fun onExportClicked() {
        val ctx = context ?: return
        // 打开选项即请求各目标补推镜像：用户选路径/确认期间若目标存活，导出更完整
        RuleMirror.listPackages(ctx).forEach {
            HostControlBridge.requestMirrorPush(ctx, it.packageName)
        }
        RuleTransferUi.showExportOptions(
            activity = requireActivity(),
            onPickFile = {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                exportDocLauncher.launch("godviewer-rules-$stamp.json")
            },
            onShare = { json ->
                val ok = RuleTransfer.shareJson(
                    ctx,
                    json,
                    getString(R.string.rules_export_title),
                )
                if (!ok) {
                    Toast.makeText(ctx, R.string.export_save_failed, Toast.LENGTH_SHORT).show()
                }
            },
            onCopy = { json ->
                val ok = RuleTransfer.copyToClipboard(ctx, json)
                Toast.makeText(
                    ctx,
                    if (ok) R.string.export_copy_done else R.string.export_save_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            },
            buildJson = { buildExportJson() },
        )
    }

    private fun buildExportJson(): String? {
        val ctx = context ?: return null
        val bundle = RuleBundle.all(ctx) ?: return null
        return RuleBundle.encode(bundle)
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
        private val onClick: (MirroredPackage) -> Unit,
    ) : RecyclerView.Adapter<PackageAdapter.Holder>() {
        private val items = ArrayList<MirroredPackage>()
        private val timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

        fun submit(list: List<MirroredPackage>) {
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
            fun bind(item: MirroredPackage) {
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
