package com.godviewer.app.target.flutter

import com.godviewer.app.shared.GvLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Flutter 语义树 wire-format 解析（`FlutterJNI.updateSemantics` 的 ByteBuffer）。
 *
 * 布局逐字段核对自两处（2026-10-31）：
 * - 引擎 master `AccessibilityBridge.SemanticsNode#updateWith`；
 * - 目标 APK（农夫山泉 3.2.3，Dart VM 3.13.1 / 2026-08）R8 混淆后的同一方法反编译体
 *   （`io.flutter.view.c#a`，两者完全一致），并经真机完整 buffer 离线解码验证。
 *
 * 每节点（LITTLE_ENDIAN）：
 * ```
 * id:i, flags:J, actions:i, maxValueLength:i, currentValueLength:i,
 * textSelectionBase:i, textSelectionExtent:i, platformViewId:i,
 * scrollChildren:i, scrollIndex:i, traversalParent:i,
 * scrollPosition:f, scrollExtentMax:f, scrollExtentMin:f, role:i,
 * identifier:s, label:s+attrs, value:s+attrs, increasedValue:s+attrs,
 * decreasedValue:s+attrs, hint:s+attrs, tooltip:s, linkUrl:s, locale:s,
 * minValue:s, maxValue:s, headingLevel:i, textDirection:i,
 * rect:4f, transform:16f, hitTestTransform:16f,
 * traversalChildCount:i + ids, hitTestChildCount:i + ids, customActionCount:i + ids
 * ```
 * `s` = int 字符串表下标（-1 = null）；`attrs` = 数量 int（-1 = 无）+ 每项
 * {start:i, end:i, type:i}（type 0=SPELL_OUT / 1=LOCALE 各再跟 1 个 int，其余无附加）。
 *
 * 解析失败（引擎代次不匹配等）返回 null：调用方保持功能关闭；
 * 首次失败会带偏移上下文与完整 buffer 转储便于对账，绝不向目标 App 抛异常。
 */
internal object FlutterSemanticsParser {

    private const val TAG = "FlutterSemantics"
    private const val MAX_NODES = 4096
    private const val MAX_CUSTOM_ACTIONS = 256
    private const val MAX_CHILDREN = 1024
    private const val MAX_ATTRS = 64
    private const val HEX_DUMP_BYTES = 80

    private var failureLogged = false

    /**
     * 解析一次语义更新。[raw] 为引擎传入的节点序列；不修改传入 buffer 的
     * position / order（引擎桥随后还会消费它）。
     *
     * @return id → 节点（仅本次 buffer 中出现的节点）；布局不匹配返回 null
     */
    fun parse(
        raw: ByteBuffer,
        strings: Array<String>,
        attrArgs: Array<ByteBuffer>,
    ): Map<Int, FlutterSemanticsNode>? {
        return runCatching {
            val buffer = raw.duplicate()
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(0)
            parseNodes(buffer, strings)
        }.onFailure {
            logFailure("parse threw: ${it.javaClass.simpleName}: ${it.message}", raw, strings)
        }.getOrNull()
    }

    private fun parseNodes(
        buffer: ByteBuffer,
        strings: Array<String>,
    ): HashMap<Int, FlutterSemanticsNode> {
        val out = HashMap<Int, FlutterSemanticsNode>(128)
        while (buffer.hasRemaining()) {
            if (out.size >= MAX_NODES) {
                parseFail(buffer, "too many nodes (> $MAX_NODES)")
            }
            val id = buffer.getInt()
            if (id < 0) {
                parseFail(buffer, "bad node id $id")
            }
            val nodeStartPos = buffer.position()
            val node = try {
                parseNodeBody(buffer, strings, id)
            } catch (t: Throwable) {
                throw IllegalStateException(
                    "node#${out.size} id=$id @body=$nodeStartPos read=${buffer.position()}: ${t.message}",
                )
            }
            out[id] = node
        }
        return out
    }

    private fun parseNodeBody(
        buffer: ByteBuffer,
        strings: Array<String>,
        id: Int,
    ): FlutterSemanticsNode {
        val node = FlutterSemanticsNode()
        node.id = id
        node.flags = buffer.getLong()
        node.actions = buffer.getInt()
        buffer.getInt() // maxValueLength
        buffer.getInt() // currentValueLength
        buffer.getInt() // textSelectionBase
        buffer.getInt() // textSelectionExtent
        node.platformViewId = buffer.getInt()
        buffer.getInt() // scrollChildren
        buffer.getInt() // scrollIndex
        buffer.getInt() // traversalParent
        buffer.getFloat() // scrollPosition
        buffer.getFloat() // scrollExtentMax
        buffer.getFloat() // scrollExtentMin
        node.role = buffer.getInt()

        // identifier / label / value 合法为 null（-1），只有越界才会在 readString 内抛出
        node.identifier = readString(buffer, strings)
        node.label = readString(buffer, strings)
        skipAttributes(buffer)
        node.value = readString(buffer, strings)
        skipAttributes(buffer)
        readString(buffer, strings); skipAttributes(buffer) // increasedValue
        readString(buffer, strings); skipAttributes(buffer) // decreasedValue
        node.hint = readString(buffer, strings)
        skipAttributes(buffer)
        node.tooltip = readString(buffer, strings)
        readString(buffer, strings) // linkUrl
        readString(buffer, strings) // locale
        readString(buffer, strings) // minValue
        readString(buffer, strings) // maxValue
        buffer.getInt() // headingLevel
        buffer.getInt() // textDirection

        node.left = buffer.getFloat()
        node.top = buffer.getFloat()
        node.right = buffer.getFloat()
        node.bottom = buffer.getFloat()
        node.transform = readMatrix(buffer)
        readMatrix(buffer) // hitTestTransform：MVP 仅消费对齐（命中用 traversal 全局变换）

        readChildren(buffer, node.childrenTraversal)
        readChildren(buffer, node.childrenHitTest)

        val actionCount = buffer.getInt()
        if (actionCount < 0 || actionCount > MAX_CUSTOM_ACTIONS) {
            throw IllegalStateException("custom action count $actionCount @${buffer.position() - 4}")
        }
        repeat(actionCount) { buffer.getInt() } // 自定义动作 id（MVP 不消费）
        return node
    }

    private fun readString(buffer: ByteBuffer, strings: Array<String>): String? {
        val idx = buffer.getInt()
        if (idx == -1) return null
        if (idx < 0 || idx >= strings.size) {
            throw IndexOutOfBoundsException("string idx $idx/${strings.size} @${buffer.position() - 4}")
        }
        return strings[idx]
    }

    private fun skipAttributes(buffer: ByteBuffer) {
        val count = buffer.getInt()
        if (count == -1) return
        if (count < 0 || count > MAX_ATTRS) {
            throw IllegalStateException("attr count $count @${buffer.position() - 4}")
        }
        repeat(count) {
            buffer.getInt() // start
            buffer.getInt() // end
            when (buffer.getInt()) { // type：0=SPELL_OUT（尾随 size 占位）/ 1=LOCALE（尾随 args 下标）
                0, 1 -> buffer.getInt()
                else -> Unit
            }
        }
    }

    private fun readMatrix(buffer: ByteBuffer): FloatArray {
        val m = FloatArray(16)
        for (i in 0 until 16) m[i] = buffer.getFloat()
        return m
    }

    private fun readChildren(buffer: ByteBuffer, out: ArrayList<Int>) {
        val count = buffer.getInt()
        if (count < 0 || count > MAX_CHILDREN) {
            throw IllegalStateException("child count $count @${buffer.position() - 4}")
        }
        repeat(count) {
            val child = buffer.getInt()
            if (child < 0) throw IllegalStateException("bad child id $child @${buffer.position() - 4}")
            out.add(child)
        }
    }

    /** 结构性失败：抛出带偏移上下文的异常，由 [parse] 统一落日志 */
    private fun parseFail(buffer: ByteBuffer, reason: String): Nothing {
        throw IllegalStateException("$reason @${buffer.position()}")
    }

    private fun logFailure(reason: String, buffer: ByteBuffer, strings: Array<String>): Nothing? {
        if (!failureLogged) {
            failureLogged = true
            val dup = buffer.duplicate()
            dup.order(ByteOrder.LITTLE_ENDIAN)
            val n = minOf(HEX_DUMP_BYTES, dup.capacity())
            val hex = StringBuilder(n * 2)
            for (i in 0 until n) hex.append("%02x".format(dup.get(i)))
            GvLog.w(
                TAG,
                "semantics parse failed: $reason strings=${strings.size} " +
                    "cap=${buffer.capacity()} pos=${buffer.position()} lim=${buffer.limit()} hex=$hex",
            )
            // 完整转储（base64 分块）+ 字符串表，供离线逐字节对账
            dumpBufferBase64(buffer)
            dumpStrings(strings)
        }
        return null
    }

    private fun dumpBufferBase64(buffer: ByteBuffer) {
        val dup = buffer.duplicate()
        dup.position(0)
        val bytes = ByteArray(dup.remaining())
        dup.get(bytes)
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val chunkSize = 1800
        var i = 0
        var part = 0
        while (i < b64.length) {
            val end = minOf(i + chunkSize, b64.length)
            GvLog.w(TAG, "DUMP64[$part]=${b64.substring(i, end)}")
            i = end
            part++
        }
    }

    private fun dumpStrings(strings: Array<String>) {
        for ((i, s) in strings.withIndex()) {
            GvLog.w(TAG, "STR[$i]=${if (s.length > 120) s.take(120) + "…" else s}")
        }
    }
}
