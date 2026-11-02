package com.godviewer.app.target.flutter

import android.graphics.Rect
import android.opengl.Matrix

/**
 * Flutter 语义树单个节点的 GodViewer 侧模型。
 *
 * flag / action 位定义与引擎 `AccessibilityBridge.Flag` / `.Action` 枚举一致
 * （已与引擎 master 及目标 APK 反编译体核对），按位测试而非引入引擎类。
 */
class FlutterSemanticsNode {

    var id: Int = 0

    /** 语义标志位（long；bit 定义见下方常量） */
    var flags: Long = 0L

    /** 语义动作位（TAP=1<<0、LONG_PRESS=1<<1 …） */
    var actions: Int = 0
    var role: Int = 0
    var platformViewId: Int = -1

    /** 引擎语义 identifier（widget 显式命名；多数节点为空） */
    var identifier: String? = null
    var label: String? = null
    var value: String? = null
    var hint: String? = null
    var tooltip: String? = null

    /** 本地坐标矩形（Flutter 逻辑像素；DPR 烘在 transform 里） */
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f

    /** 本节点 4x4 变换（列主序，与引擎一致） */
    var transform: FloatArray? = null

    val childrenTraversal = ArrayList<Int>()
    val childrenHitTest = ArrayList<Int>()

    /** 由 [FlutterBridge] 回填：traversal 父节点 id（根为 -1） */
    var parentId: Int = -1

    /** 由 [FlutterBridge] 回填：自根合成的全局变换（根 = 自身 transform） */
    var globalTransform: FloatArray? = null

    fun hasFlag(bit: Int): Boolean = (flags ushr bit) and 1L == 1L

    fun hasAction(bit: Int): Boolean = (actions ushr bit) and 1 == 1

    val isButton: Boolean get() = hasFlag(FLAG_IS_BUTTON)
    val isTextField: Boolean get() = hasFlag(FLAG_IS_TEXT_FIELD)
    val isImage: Boolean get() = hasFlag(FLAG_IS_IMAGE)
    val isHidden: Boolean get() = hasFlag(FLAG_IS_HIDDEN)
    val scopesRoute: Boolean get() = hasFlag(FLAG_SCOPES_ROUTE)
    val namesRoute: Boolean get() = hasFlag(FLAG_NAMES_ROUTE)
    val hasTapAction: Boolean get() = hasAction(ACTION_TAP)

    /** 语义显示文本（label 优先，其次 value / tooltip / identifier） */
    fun displayText(): String? = label?.takeIf { it.isNotBlank() }
        ?: value?.takeIf { it.isNotBlank() }
        ?: tooltip?.takeIf { it.isNotBlank() }
        ?: identifier?.takeIf { it.isNotBlank() }

    /** 粗粒度类别提示（规则存储与编辑弹窗展示用） */
    fun kindHint(): String = when {
        isButton -> KIND_BUTTON
        isTextField -> KIND_TEXT_FIELD
        isImage -> KIND_IMAGE
        else -> KIND_VIEW
    }

    /**
     * 计算节点在屏幕上的像素矩形。
     *
     * 与引擎桥一致：全局变换映射矩形（含透视除法）后，加 FlutterView 的屏幕偏移；
     * DPR 已由根节点 transform 烘进全局矩阵，无需再乘密度。
     *
     * @param flutterViewLoc FlutterView 的 getLocationOnScreen 结果
     * @param out 输出矩形
     * @return false 表示缺全局变换（尚未被桥遍历过），out 内容未定义
     */
    fun screenRect(flutterViewLoc: IntArray, out: Rect): Boolean {
        val m = globalTransform ?: return false
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var i = 0
        while (i < 4) {
            vec[0] = if (i == 0 || i == 3) left else right
            vec[1] = if (i < 2) top else bottom
            vec[2] = 0f
            vec[3] = 1f
            Matrix.multiplyMV(vec, 0, m, 0, vec, 0)
            val w = vec[3]
            val x = if (w != 0f && w.isFinite()) vec[0] / w else vec[0]
            val y = if (w != 0f && w.isFinite()) vec[1] / w else vec[1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            i++
        }
        if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite()) {
            return false
        }
        out.set(
            (minX + 0.5f).toInt() + flutterViewLoc[0],
            (minY + 0.5f).toInt() + flutterViewLoc[1],
            (maxX + 0.5f).toInt() + flutterViewLoc[0],
            (maxY + 0.5f).toInt() + flutterViewLoc[1],
        )
        return true
    }

    override fun toString(): String =
        "#$id ${kindHint()} text=${displayText() ?: "-"} id=${identifier ?: "-"} " +
            "rect=[$left,$top,$right,$bottom]"

    companion object {
        // Flag 位（engine AccessibilityBridge.Flag，2026-10 与 master 及目标 APK 核对）
        const val FLAG_IS_BUTTON = 3
        const val FLAG_IS_TEXT_FIELD = 4
        const val FLAG_IS_FOCUSED = 5
        const val FLAG_IS_ENABLED = 7
        const val FLAG_SCOPES_ROUTE = 11
        const val FLAG_NAMES_ROUTE = 12
        const val FLAG_IS_HIDDEN = 13
        const val FLAG_IS_IMAGE = 14

        // Action 位（engine AccessibilityBridge.Action）
        const val ACTION_TAP = 0
        const val ACTION_LONG_PRESS = 1

        const val KIND_BUTTON = "Button"
        const val KIND_TEXT_FIELD = "TextField"
        const val KIND_IMAGE = "Image"
        const val KIND_VIEW = "View"

        private val vec = FloatArray(4)
    }
}
