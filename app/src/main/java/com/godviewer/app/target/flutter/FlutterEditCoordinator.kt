package com.godviewer.app.target.flutter

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.dialog.ModuleDialogUi
import com.godviewer.app.target.edit.ViewHitUtil
import com.godviewer.app.target.hook.ModuleRes
import com.godviewer.app.target.hook.ModuleRes.moduleRes

/**
 * Flutter 编辑流：编辑模式下的触摸 → 语义 hitTest → 节点操作弹窗（隐藏/恢复）。
 *
 * 由 [com.godviewer.app.target.edit.EditModeTouchInterceptor] 在触摸命中 Flutter 表面时
 * 优先接入（原生 View 树对 Flutter 而言只有一整块 Surface，原生 hitTest 无意义）。
 */
object FlutterEditCoordinator {

    private const val TAG = "FlutterEdit"

    /**
     * 编辑模式点按处理。
     * @return true = 已按 Flutter 节点处理（消费本次触摸）
     */
    fun handleEditTap(activity: Activity, rawX: Float, rawY: Float): Boolean {
        val flutterView = FlutterBridge.flutterView() ?: return false
        if (!ViewHitUtil.pointInView(flutterView, rawX, rawY)) return false
        val loc = IntArray(2)
        if (!FlutterBridge.flutterViewLocation(loc)) return false
        val node = FlutterBridge.hitTest(rawX, rawY, loc) ?: run {
            GvLog.d(TAG, "flutter hitTest miss at $rawX,$rawY (nodes=${FlutterBridge.updateCount})")
            return false
        }
        GvLog.d(TAG, "flutter tap -> $node")
        showNodeDialog(activity, node)
        return true
    }

    private fun showNodeDialog(activity: Activity, node: FlutterSemanticsNode) {
        runCatching {
            val context = ModuleDialogUi.wrap(activity)
            val panel = buildPanel(context, node)
            val existing = FlutterRuleManager.findRuleForNode(
                activity.componentName.className,
                node,
            )
            val builder = android.app.AlertDialog.Builder(context)
                .setView(panel)
                .setNegativeButton(str("cancel", "取消"), null)
            if (existing != null) {
                builder.setPositiveButton(str("flutter_action_restore", "恢复显示")) { _, _ ->
                    FlutterRuleManager.deleteRule(existing)
                }
            } else {
                builder.setPositiveButton(str("flutter_action_hide", "隐藏此控件")) { _, _ ->
                    val rule = FlutterRuleManager.saveHideRule(activity, node)
                    if (rule == null) {
                        Toast.makeText(
                            activity,
                            str("flutter_no_semantics", "该控件没有语义标识，无法建立规则"),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            // 可触发节点：远程语义 TAP（等价真实点击，App 自己处理）
            if (node.hasTapAction) {
                builder.setNeutralButton(str("flutter_action_tap", "点击它")) { _, _ ->
                    FlutterBridge.dispatchTap(node.id)
                }
            }
            val dialog = builder.create()
            dialog.show()
            ModuleDialogUi.applyWindow(dialog, panel)
        }.onFailure {
            GvLog.e(TAG, "flutter node dialog failed", it)
        }
    }

    private fun buildPanel(context: android.content.Context, node: FlutterSemanticsNode): View {
        val dp = context.resources.displayMetrics.density
        fun dip(v: Int) = (v * dp + 0.5f).toInt()

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(20), dip(16), dip(20), dip(8))
        }

        val title = TextView(context).apply {
            text = node.displayText() ?: str("flutter_node_title", "Flutter 控件")
            setTextColor(PALETTE_TEXT)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }
        panel.addView(title)

        panel.addView(
            memoLine(
                context,
                listOfNotNull(
                    line(str("flutter_field_kind", "类别"), node.kindHint()),
                    line(str("flutter_field_label", "文本"), node.label),
                    line(str("flutter_field_value", "值"), node.value),
                    line(str("flutter_field_identifier", "标识"), node.identifier),
                    line(str("flutter_field_rect", "区域"), rectText(node)),
                ).joinToString("\n"),
            ),
        )
        return panel
    }

    private fun rectText(node: FlutterSemanticsNode): String =
        "[${node.left.toInt()}, ${node.top.toInt()}, ${node.right.toInt()}, ${node.bottom.toInt()}]"

    private fun line(label: String, value: String?): String? =
        value?.takeIf { it.isNotBlank() }?.let { "$label: $it" }

    private fun memoLine(context: android.content.Context, text: String): View {
        val dp = context.resources.displayMetrics.density
        fun dip(v: Int) = (v * dp + 0.5f).toInt()
        return TextView(context).apply {
            this.text = text
            setTextColor(PALETTE_HINT)
            textSize = 13f
            setLineSpacing(0f, 1.15f)
            background = GradientDrawable().apply {
                setColor(PALETTE_INPUT_BG)
                cornerRadius = 8f * dp
            }
            setPadding(dip(12), dip(10), dip(12), dip(10))
        }
    }

    /** moduleRes 字符串按名解析；缺资源时退回内置中文，保证弹窗永不空白 */
    private fun str(name: String, fallback: String): String = runCatching {
        val id = moduleRes.getIdentifier(name, "string", null)
        if (id != 0) moduleRes.getString(id) else null
    }.getOrNull() ?: fallback

    private val PALETTE_TEXT: Int
        get() = runCatching {
            ModuleRes.moduleRes.getColor(
                moduleRes.getIdentifier("home_title_text", "color", null),
                null,
            )
        }.getOrDefault(Color.parseColor("#E6E8EC"))

    private val PALETTE_HINT: Int
        get() = runCatching {
            ModuleRes.moduleRes.getColor(
                moduleRes.getIdentifier("home_subtitle_text", "color", null),
                null,
            )
        }.getOrDefault(Color.parseColor("#9AA3AD"))

    private val PALETTE_INPUT_BG: Int
        get() = runCatching {
            ModuleRes.moduleRes.getColor(
                moduleRes.getIdentifier("home_link_chip_background", "color", null),
                null,
            )
        }.getOrDefault(Color.parseColor("#1AFFFFFF"))

    // 保留给后续节点高亮（与原生 SelectedViewHighlight 对齐）
    @Suppress("unused")
    private fun dipOf(context: android.content.Context, v: Int): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()
}
