package com.godviewer.app.host.ui

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.godviewer.app.R
import com.godviewer.app.host.control.PendingCommandStore
import com.godviewer.app.host.io.RuleBundle
import com.godviewer.app.shared.px

/**
 * 导入/导出的共用选项面板与导入流程（规则 Tab 与单应用详情页共用，
 * 保持两处交互一致）。SAF 文件选择器由各屏自持 launcher，这里只发回调。
 *
 * 导入三段式：[parseImport] 解析分组 → [showImportPreview] 预览确认
 * （按应用勾选、可选覆盖模式）→ [commitImport] 入队下发。未安装应用的
 * 规则不再静默丢弃，在预览里明确列出跳过明细。
 */
object RuleTransferUi {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 等待待同步队列的首轮确认（[PendingCommandStore] 内部 1500ms 回读镜像）
     * 之后再弹结果 Toast：全部落地 = 已同步；仍有排队 = 明确告知
     * 「等待打开目标应用同步」。
     */
    private const val SYNC_CHECK_DELAY_MS = 1_800L

    /** 导入预览：可导入（已安装且有内容）与被跳过（未安装）的两组 */
    data class ImportPreview(
        val entries: List<RuleBundle.AppEntry>,
        val skipped: List<RuleBundle.AppEntry>,
    ) {
        val totalRules: Int
            get() = entries.sumOf { it.viewRules.size + it.dialogRules.size }

        val skippedRules: Int
            get() = skipped.sumOf { it.viewRules.size + it.dialogRules.size }
    }

    /** 导出选项：存为文件（构建推迟到 SAF 返回时，读最新镜像）/ 系统分享 / 剪贴板 */
    fun showExportOptions(
        activity: Activity,
        onPickFile: () -> Unit,
        onShare: (json: String) -> Unit,
        onCopy: (json: String) -> Unit,
        buildJson: () -> String?,
    ) {
        val options = arrayOf(
            activity.getString(R.string.rules_export_file) to
                activity.getString(R.string.rules_export_file_hint),
            activity.getString(R.string.rules_export_share) to
                activity.getString(R.string.rules_export_share_hint),
            activity.getString(R.string.rules_export_copy) to
                activity.getString(R.string.rules_export_copy_hint),
        )
        showOptionList(activity, R.string.rules_export_title, options) { which ->
            when (which) {
                0 -> onPickFile()
                1 -> buildJson()?.let(onShare)
                    ?: Toast.makeText(activity, R.string.export_empty, Toast.LENGTH_SHORT).show()
                2 -> buildJson()?.let(onCopy)
                    ?: Toast.makeText(activity, R.string.export_empty, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 导入选项：从文件选择 / 从剪贴板粘贴 */
    fun showImportOptions(
        activity: Activity,
        onPickFile: () -> Unit,
        onPaste: () -> Unit,
    ) {
        val options = arrayOf(
            activity.getString(R.string.rules_import_file) to
                activity.getString(R.string.rules_import_file_hint),
            activity.getString(R.string.rules_import_clipboard) to
                activity.getString(R.string.rules_import_clipboard_hint),
        )
        showOptionList(activity, R.string.rules_import_title, options) { which ->
            if (which == 0) onPickFile() else onPaste()
        }
    }

    /**
     * 选项面板（导入/导出共用）：自定义行 = 主文案 + 说明副文案，行间 1dp 发丝线
     * （取代裸 setItems 的纯文字列表）。点行先关面板再回调，SAF/分享不受影响。
     */
    private fun showOptionList(
        activity: Activity,
        titleRes: Int,
        options: Array<Pair<String, String>>,
        onSelect: (Int) -> Unit,
    ) {
        val content = activity.layoutInflater.inflate(R.layout.dialog_transfer_options, null)
        val container = content.findViewById<LinearLayout>(R.id.option_rows)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .create()
        options.forEachIndexed { index, (label, hint) ->
            if (index > 0) container.addView(makeDivider(activity))
            val row = activity.layoutInflater
                .inflate(R.layout.item_transfer_option, container, false)
            row.findViewById<TextView>(R.id.row_title).text = label
            row.findViewById<TextView>(R.id.row_subtitle).text = hint
            row.setOnClickListener {
                dialog.dismiss()
                onSelect(index)
            }
            container.addView(row)
        }
        dialog.show()
    }

    /** 内容区发丝线：1dp，左右内缩 20dp 与文字对齐 */
    private fun makeDivider(context: Context): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.px(),
            ).apply {
                marginStart = 20.px()
                marginEnd = 20.px()
            }
            setBackgroundColor(ContextCompat.getColor(context, R.color.home_divider))
        }

    /**
     * 解析信封并按已安装/未安装分组。失败（损坏/无可用 entry）时 Toast 说明并
     * 返回 null；调用方直接结束导入。
     *
     * @param onlyPackage 定向单应用导入（单应用详情页），null = 全部
     */
    fun parseImport(
        context: Context,
        json: String,
        onlyPackage: String? = null,
    ): ImportPreview? {
        val bundle = RuleBundle.decode(json).getOrElse { err ->
            Toast.makeText(
                context,
                context.getString(R.string.import_invalid, err.message ?: "parse error"),
                Toast.LENGTH_LONG,
            ).show()
            return null
        }
        val pm = context.packageManager
        val inScope = bundle.apps.filter { entry ->
            onlyPackage == null || entry.packageName == onlyPackage
        }
        val (installed, notInstalled) = inScope.partition { entry ->
            runCatching { pm.getApplicationInfo(entry.packageName, 0) }.isSuccess
        }
        val entries = installed.filterNot { it.isEmpty }
        if (entries.isEmpty()) {
            // 有内容但目标未安装：提示明细而不是一句「无可用应用」
            if (notInstalled.any { !it.isEmpty }) {
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.import_none_not_installed,
                        notInstalled.count { !it.isEmpty },
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                Toast.makeText(context, R.string.import_none, Toast.LENGTH_LONG).show()
            }
            return null
        }
        return ImportPreview(entries = entries, skipped = notInstalled.filterNot { it.isEmpty })
    }

    /**
     * 预览确认对话框：应用逐行 = 名称 + 规则条数 + 右侧勾选框（默认全选，
     * 只导入勾选的应用，行间发丝线）；未安装跳过单独一节（无勾选、灰字行），
     * 覆盖模式为面板内 CheckBox。标题随勾选数变化，全不勾时禁用导入按钮。
     */
    fun showImportPreview(
        activity: Activity,
        preview: ImportPreview,
        onConfirm: (overwrite: Boolean, selected: List<RuleBundle.AppEntry>) -> Unit,
    ) {
        val content = activity.layoutInflater.inflate(R.layout.dialog_import_preview, null)
        val appRows = content.findViewById<LinearLayout>(R.id.preview_app_rows)
        val skippedRows = content.findViewById<LinearLayout>(R.id.preview_skipped_rows)
        val skippedHeader = content.findViewById<TextView>(R.id.preview_skipped_header)
        val skippedDivider = content.findViewById<View>(R.id.preview_divider)
        val overwrite = content.findViewById<CheckBox>(R.id.preview_overwrite)
        val boxes = ArrayList<CheckBox>(preview.entries.size)

        fun addRow(
            container: LinearLayout,
            entry: RuleBundle.AppEntry,
            checkable: Boolean,
            muted: Boolean,
            index: Int,
        ) {
            if (index > 0) container.addView(makeDivider(activity))
            val row = activity.layoutInflater
                .inflate(R.layout.item_import_preview_app, container, false)
            val name = entry.label?.takeIf { it.isNotBlank() } ?: entry.packageName
            row.findViewById<TextView>(R.id.row_title).text = name
            row.findViewById<TextView>(R.id.row_counts).text = activity.getString(
                R.string.import_preview_counts,
                entry.viewRules.size,
                entry.dialogRules.size,
            )
            val check = row.findViewById<CheckBox>(R.id.row_check)
            if (checkable) {
                boxes += check
                // 点整行文字也能切换勾选；点勾选框本身由子 View 消费，不会双重切换
                row.setOnClickListener { check.isChecked = !check.isChecked }
            } else {
                check.visibility = View.GONE
            }
            if (muted) {
                row.findViewById<TextView>(R.id.row_title).setTextColor(
                    ContextCompat.getColor(activity, R.color.home_subtitle_text),
                )
            }
            container.addView(row)
        }

        preview.entries.forEachIndexed { i, e ->
            addRow(appRows, e, checkable = true, muted = false, index = i)
        }
        if (preview.skipped.isNotEmpty()) {
            skippedDivider.visibility = View.VISIBLE
            skippedHeader.visibility = View.VISIBLE
            preview.skipped.forEachIndexed { i, e ->
                addRow(skippedRows, e, checkable = false, muted = true, index = i)
            }
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.import_preview_title, preview.entries.size))
            .setView(content)
            .setPositiveButton(R.string.rules_import) { _, _ ->
                val selected = preview.entries.filterIndexed { i, _ -> boxes[i].isChecked }
                onConfirm(overwrite.isChecked, selected)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        // 标题显示「已勾选 / 应用总数」，全不勾时禁用导入按钮
        fun syncSelection() {
            val checked = boxes.count { it.isChecked }
            dialog.setTitle(
                activity.getString(R.string.import_preview_title_selected, checked, boxes.size),
            )
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = checked > 0
        }
        boxes.forEach { box ->
            box.setOnCheckedChangeListener { _, _ -> syncSelection() }
        }
        syncSelection()
    }

    /**
     * 执行导入：逐应用入队下发（待同步队列保证目标离线也能生效）。
     * 结果 Toast 延迟到首轮镜像确认之后：全部同步完成提示「已同步」；
     * 仍有命令排队则明确提示「等待打开目标应用后同步」。
     */
    fun commitImport(
        context: Context,
        preview: ImportPreview,
        overwrite: Boolean,
        onDone: ((apps: Int, rules: Int) -> Unit)? = null,
    ) {
        val appCtx = context.applicationContext
        val totalRules = preview.totalRules
        val submittedPkgs = ArrayList<String>(preview.entries.size)
        for (entry in preview.entries) {
            PendingCommandStore.submitImport(
                appCtx,
                entry.packageName,
                entry.viewRules,
                entry.dialogRules,
                overwrite,
            )
            submittedPkgs.add(entry.packageName)
        }
        onDone?.invoke(preview.entries.size, totalRules)
        // 队列内部 ~1.5s 回读镜像确认；等它跑完再按真实状态提示（用 application
        // context 弹 Toast，页面关闭也不影响）
        mainHandler.postDelayed({
            val pendingApps = submittedPkgs.count { pkg ->
                PendingCommandStore.pendingCount(appCtx, pkg) > 0
            }
            val text = if (pendingApps == 0) {
                appCtx.getString(R.string.import_done, totalRules, submittedPkgs.size)
            } else {
                appCtx.getString(
                    R.string.import_waiting,
                    totalRules,
                    submittedPkgs.size,
                    pendingApps,
                )
            }
            Toast.makeText(appCtx, text, Toast.LENGTH_LONG).show()
        }, SYNC_CHECK_DELAY_MS)
    }

    /** 一站式导入流程：解析 → 预览确认 → 提交（失败已 Toast，静默返回） */
    fun startImportFlow(
        activity: Activity,
        json: String,
        onlyPackage: String? = null,
        onDone: ((apps: Int, rules: Int) -> Unit)? = null,
    ) {
        val preview = parseImport(activity, json, onlyPackage) ?: return
        showImportPreview(activity, preview) { overwrite, selected ->
            if (selected.isEmpty()) return@showImportPreview
            commitImport(activity, preview.copy(entries = selected), overwrite, onDone)
        }
    }
}
