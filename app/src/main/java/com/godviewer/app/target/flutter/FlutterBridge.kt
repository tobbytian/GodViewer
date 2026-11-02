package com.godviewer.app.target.flutter

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeProvider
import com.godviewer.app.shared.AndroidAppCompat
import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.edit.EditMode
import com.godviewer.app.target.flutter.FlutterRuleManager.FlutterRule
import com.godviewer.app.target.hook.GvHook
import com.godviewer.app.target.hook.GvMethodHook
import com.godviewer.app.target.hook.MethodHookParam
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Flutter 引擎桥（运行在被注入的目标进程内）。
 *
 * 原理：任何 Flutter App 的 release 包，`io.flutter.embedding.engine.FlutterJNI` 类名与
 * JNI 注册方法名必然保留（引擎 C++ 按名解析，R8 无法改名）。利用这组锚点：
 *
 * 1. hook `setAccessibilityDelegate`（FlutterView 初始化语义桥时无条件调用）→
 *    捕获 FlutterJNI 实例，并经 delegate → AccessibilityNodeProvider(语义桥) → View
 *    的**类型扫描**定位 FlutterView（不寻址任何被 R8 改名的类）；
 * 2. hook `setPlatformMessageHandler` 作为兜底实例捕获点（引擎初始化早期调用）；
 * 3. hook `updateSemantics`（native→Java 回调）→ 自行解析语义树（见 [FlutterSemanticsParser]）；
 * 4. 反射调用保留名方法 `setSemanticsEnabled(boolean)` 强制开启语义树 —— 不依赖系统无障碍服务。
 *
 * 非 Flutter App：`FlutterJNI` 类不存在，[ensureInstalled] 静默返回 false，零开销。
 */
object FlutterBridge {

    private const val TAG = "FlutterBridge"
    private const val JNI_CLASS = "io.flutter.embedding.engine.FlutterJNI"

    /** 引擎生成的节点 id 下界（≥ 此值为 platform view 节点，由 embedder 管理，跳过） */
    private const val ENGINE_NODE_ID_BASE = 65536
    private const val ROOT_NODE_ID = 0

    @Volatile
    private var installed = false

    /** 引擎实例是否已捕获（不代表语义已开启） */
    @Volatile
    var engineCaptured = false
        private set

    /** 语义树是否处于开启状态（我们请求的目标状态） */
    @Volatile
    var semanticsRequested = false
        private set

    /** 反射 `setSemanticsEnabled` 是否可用（个别引擎代次可能不可用） */
    @Volatile
    var semanticsControlSupported = true
        private set

    /** 当前路由名（NAMES_ROUTE 节点的 label，弹窗检测的观察锚点） */
    @Volatile
    var routeName: String? = null
        private set

    @Volatile
    var updateCount: Long = 0L
        private set

    private val jniRefs = CopyOnWriteArrayList<WeakReference<Any>>()
    private var flutterViewRef: WeakReference<View>? = null
    private var setSemanticsEnabledMethod: Pair<Class<*>, java.lang.reflect.Method>? = null

    /** 语义树快照：id → 节点（仅根 0 可达部分；主线程读写） */
    @Volatile
    private var nodes: Map<Int, FlutterSemanticsNode> = emptyMap()

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------- install

    /**
     * 安装被动观察 hook（不改目标行为）。目标非 Flutter App 时返回 false。
     * 在 [com.godviewer.app.target.hook.GodViewerModule.onPackageReady] 调用。
     */
    fun ensureInstalled(classLoader: ClassLoader): Boolean {
        if (installed) return true
        val jniClass = runCatching { classLoader.loadClass(JNI_CLASS) }
            .onFailure { GvLog.i(TAG, "no flutter engine in this app ($JNI_CLASS not found)") }
            .getOrNull() ?: return false
        var hooked = 0
        for (method in jniClass.declaredMethods) {
            when {
                method.name == "updateSemantics" && method.parameterCount == 3 -> {
                    if (GvHook.hookMethod(method, UpdateSemanticsHook()) != null) hooked++
                }
                method.name == "setAccessibilityDelegate" && method.parameterCount == 1 -> {
                    if (GvHook.hookMethod(method, CaptureHook(locateView = true)) != null) hooked++
                }
                method.name == "setPlatformMessageHandler" && method.parameterCount == 1 -> {
                    if (GvHook.hookMethod(method, CaptureHook(locateView = false)) != null) hooked++
                }
            }
        }
        installed = true
        GvLog.i(TAG, "flutter bridge installed hooks=$hooked jniClass=${jniClass.name}")
        return hooked > 0
    }

    private class UpdateSemanticsHook : GvMethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                handleUpdateSemantics(param.thisObject, param.args)
            } catch (t: Throwable) {
                GvLog.e(TAG, "handleUpdateSemantics failed", t)
            }
        }
    }

    private class CaptureHook(private val locateView: Boolean) : GvMethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                captureEngine(param.thisObject, if (locateView) param.args.firstOrNull() else null)
            } catch (t: Throwable) {
                GvLog.e(TAG, "captureEngine failed", t)
            }
        }
    }

    // ---------------------------------------------------------------- capture

    private fun captureEngine(jni: Any?, delegate: Any?) {
        if (jni != null) addJni(jni)
        if (delegate != null && flutterViewRef?.get() == null) {
            locateFlutterView(delegate)
        }
    }

    private fun addJni(instance: Any) {
        if (jniRefs.none { it.get() === instance }) {
            jniRefs.add(WeakReference(instance))
            GvLog.i(TAG, "flutter engine captured: ${instance.javaClass.name}")
        }
        jniRefs.removeAll { it.get() == null }
        if (!engineCaptured && jniRefs.isNotEmpty()) {
            engineCaptured = true
            refreshActivation()
        }
    }

    /**
     * 类型扫描定位 FlutterView：delegate(混淆类 c) 的字段里找
     * `AccessibilityNodeProvider`（= 语义桥混淆类 n），再在桥的字段里找 `View`。
     */
    private fun locateFlutterView(delegate: Any) {
        runCatching {
            for (field in delegate.javaClass.declaredFields) {
                field.isAccessible = true
                val bridge = field.get(delegate) as? AccessibilityNodeProvider ?: continue
                for (bridgeField in bridge.javaClass.declaredFields) {
                    bridgeField.isAccessible = true
                    val view = bridgeField.get(bridge) as? View ?: continue
                    flutterViewRef = WeakReference(view)
                    GvLog.i(TAG, "flutter view located: ${view.javaClass.name}")
                    return
                }
            }
            GvLog.d(TAG, "flutter view not located from delegate ${delegate.javaClass.name}")
        }.onFailure { GvLog.w(TAG, "locateFlutterView failed", it) }
    }

    // ------------------------------------------------------------ semantics io

    private fun handleUpdateSemantics(jni: Any?, args: Array<Any?>) {
        if (jni != null) addJni(jni)
        // 旁路成本控制：编辑关闭且无 flutter 规则时不解析（TalkBack 开启时引擎可能持续推送）
        if (!EditMode.isEnabled() && !FlutterRuleManager.hasRules()) return
        val buffer = args.getOrNull(0) as? ByteBuffer ?: return
        val strings = args.getOrNull(1) as? Array<String> ?: return
        @Suppress("UNCHECKED_CAST")
        val attrArgs = (args.getOrNull(2) as? Array<ByteBuffer>) ?: emptyArray()
        val parsed = FlutterSemanticsParser.parse(buffer, strings, attrArgs)
        if (parsed != null) {
            applyParsed(parsed.values)
        }
    }

    /** 合并本次更新并按根 0 可达性收缩（与引擎桥的淘汰策略一致） */
    private fun applyParsed(incoming: Collection<FlutterSemanticsNode>) {
        val map = HashMap(nodes)
        for (node in incoming) map[node.id] = node
        val root = map[ROOT_NODE_ID]
        if (root == null) {
            // 引擎可能主动清空语义树（无界面 / 全部隐藏）
            nodes = emptyMap()
            routeName = null
            FlutterMaskController.onTreeUpdated()
            return
        }
        val identity = FloatArray(16)
        android.opengl.Matrix.setIdentityM(identity, 0)
        val visited = HashSet<Int>(map.size)
        val stack = ArrayDeque<FlutterSemanticsNode>()
        stack.addLast(root)
        visited.add(root.id)
        root.parentId = -1
        root.globalTransform = root.transform?.copyOf() ?: identity
        while (stack.isNotEmpty()) {
            val current = stack.removeFirst()
            val global = current.globalTransform
            for (childId in current.childrenTraversal) {
                if (childId >= ENGINE_NODE_ID_BASE) continue
                val child = map[childId] ?: continue
                child.parentId = current.id
                child.globalTransform = if (global == null) {
                    child.transform?.copyOf()
                } else {
                    val out = FloatArray(16)
                    val childTransform = child.transform
                    if (childTransform == null) global.copyOf() else {
                        android.opengl.Matrix.multiplyMM(out, 0, global, 0, childTransform, 0)
                        out
                    }
                }
                if (visited.add(childId)) stack.addLast(child)
            }
        }
        map.keys.retainAll(visited)
        nodes = map
        updateCount++
        updateRouteName()
        maybeLogProbeSnapshot()
        FlutterMaskController.onTreeUpdated()
    }

    private fun updateRouteName() {
        var found: FlutterSemanticsNode? = null
        for (node in nodes.values) {
            if (node.namesRoute) found = node
        }
        val name = found?.label ?: found?.identifier
        if (name != routeName) {
            GvLog.i(TAG, "flutter route -> ${name ?: "(null)"} (nodes=${nodes.size})")
            routeName = name
            maybeAutoClosePopup(name)
        }
    }

    // ------------------------------------------------------------ popup close

    /** 自动关闭的按钮文案白名单（App 自己点掉 = 真移除弹窗） */
    private val CLOSE_LABELS = setOf(
        "关闭", "跳过", "取消", "知道了", "知道啦", "以后再说", "稍后再说", "稍后",
        "忽略", "不再提示", "暂不更新", "×", "✕", "x", "X",
        "Close", "Skip", "Cancel", "Later", "Not now", "No thanks", "Dismiss", "OK",
    )

    private var lastAutoCloseRoute: String? = null

    /**
     * F3 弹窗自动拦截：检测到新弹窗路由后，在弹窗子树里找"关闭类"按钮并下发语义 TAP，
     * 让 App 自己 pop 路由（真移除）。没有关闭按钮的弹窗（如强制更新）不动 ——
     * 自动替用户点"更新"这类有副作用的按钮越权了。
     */
    private fun maybeAutoClosePopup(route: String?) {
        if (route.isNullOrEmpty()) return
        if (lastAutoCloseRoute == route) return
        lastAutoCloseRoute = route
        postOnMain(600L) {
            val button = findCloseButton()
            if (button == null) {
                GvLog.d(TAG, "popup \"$route\" has no close button; skip auto-close")
            } else if (dispatchTap(button.id)) {
                GvLog.i(
                    TAG,
                    "popup auto-closed via \"${button.displayText()}\" route=\"$route\"",
                )
            }
        }
    }

    private fun findCloseButton(): FlutterSemanticsNode? =
        nodes.values.firstOrNull { node ->
            node.hasTapAction && node.displayText()?.trim()?.let { it in CLOSE_LABELS } == true
        }

    /**
     * 对语义节点下发 TAP（等价无障碍点击）：App 在 Dart 侧自己处理，
     * 弹窗会被真正 pop（真移除），按钮会被真正触发。
     */
    fun dispatchTap(nodeId: Int): Boolean {
        val jni = jniRefs.firstOrNull { it.get() != null }?.get() ?: return false
        return runCatching {
            val method = jni.javaClass.getMethod(
                "dispatchSemanticsAction",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                ByteBuffer::class.java,
                Int::class.javaPrimitiveType,
            )
            method.invoke(jni, nodeId, 1 /* SemanticsAction.tap = 1<<0 */, null, 0)
            GvLog.i(TAG, "semantics tap dispatched node=$nodeId")
            true
        }.onFailure {
            GvLog.w(TAG, "dispatchSemanticsAction($nodeId) failed", it)
        }.getOrDefault(false)
    }

    private var probeSnapshotPending = false

    private fun maybeLogProbeSnapshot() {
        if (!probeSnapshotPending || nodes.isEmpty()) return
        probeSnapshotPending = false
        GvLog.i(TAG, "semantics snapshot nodes=${nodes.size} route=${routeName ?: "-"}")
        var logged = 0
        for (node in nodes.values) {
            if (logged++ >= 30) {
                GvLog.i(TAG, "… more nodes omitted")
                break
            }
            GvLog.i(TAG, "node $node")
        }
    }

    // ---------------------------------------------------------------- control

    /** 编辑模式开关 / 规则变化 / 引擎捕获后的统一开关决策 */
    fun refreshActivation() {
        if (!engineCaptured || !semanticsControlSupported) return
        val want = EditMode.isEnabled() || FlutterRuleManager.hasRules()
        if (want == semanticsRequested) return
        // 系统无障碍（TalkBack 等）开启时不主动关闭语义树，避免打断真实用户
        if (!want && systemAccessibilityOn()) return
        semanticsRequested = want
        postOnMain {
            val enabled = setSemanticsEnabledInternal(want)
            if (!want && enabled) {
                probeSnapshotPending = false
                nodes = emptyMap()
                routeName = null
                FlutterMaskController.clear()
            }
            if (want) probeSnapshotPending = true
        }
    }

    private fun setSemanticsEnabledInternal(on: Boolean): Boolean {
        var any = false
        jniRefs.removeAll { it.get() == null }
        for (ref in jniRefs) {
            val jni = ref.get() ?: continue
            val pair = semanticsMethodFor(jni) ?: continue
            any = runCatching {
                pair.second.invoke(jni, on)
                true
            }.onFailure {
                GvLog.w(TAG, "setSemanticsEnabled($on) failed", it)
            }.getOrDefault(false) || any
        }
        if (any) GvLog.i(TAG, "semantics -> $on (engines=${jniRefs.size})")
        return any
    }

    private fun semanticsMethodFor(jni: Any): Pair<Class<*>, java.lang.reflect.Method>? {
        setSemanticsEnabledMethod?.let { cached ->
            if (cached.first == jni.javaClass) return cached
        }
        val method = runCatching {
            jni.javaClass.getMethod("setSemanticsEnabled", Boolean::class.javaPrimitiveType)
        }.onFailure {
            semanticsControlSupported = false
            GvLog.w(TAG, "setSemanticsEnabled not resolvable; flutter support disabled for this app", it)
        }.getOrNull() ?: return null
        val pair = jni.javaClass to method
        setSemanticsEnabledMethod = pair
        return pair
    }

    fun onEditModeChanged(enabled: Boolean) {
        refreshActivation()
        if (enabled) {
            // 引擎开启语义有短暂延迟：延迟请求一次快照日志，便于真机核对
            postOnMain(1200L) { maybeLogProbeSnapshot() }
        }
    }

    // ------------------------------------------------------------------ query

    /** 捕获到的 FlutterView（可能为空：非 Flutter App / 定位失败） */
    fun flutterView(): View? = flutterViewRef?.get()?.takeIf { it.isAttachedToWindow }

    /** 编辑命中判断：目标 View 即捕获的 FlutterView（或其直接宿主 SurfaceView 子级） */
    fun isFlutterSurface(view: View?): Boolean {
        if (view == null) return false
        val captured = flutterViewRef?.get() ?: return false
        return view === captured || view.parent === captured
    }

    /** FlutterView 屏幕偏移写入 [out]；定位失败时返回 false */
    fun flutterViewLocation(out: IntArray): Boolean {
        val view = flutterView() ?: return false
        view.getLocationOnScreen(out)
        return true
    }

    /**
     * 语义 hitTest：自根按 hitTest 顺序深度优先，返回手指下的最深节点。
     *
     * @param rawX/rawY 屏幕原始坐标
     * @param flutterViewLoc [flutterViewLocation] 的结果
     */
    fun hitTest(rawX: Float, rawY: Float, flutterViewLoc: IntArray): FlutterSemanticsNode? {
        val root = nodes[ROOT_NODE_ID] ?: return null
        val rect = Rect()
        return hitDfs(root, rawX, rawY, flutterViewLoc, rect)
    }

    private fun hitDfs(
        node: FlutterSemanticsNode,
        rawX: Float,
        rawY: Float,
        loc: IntArray,
        rect: Rect,
    ): FlutterSemanticsNode? {
        if (node.isHidden) return null
        if (!node.screenRect(loc, rect)) return null
        if (!rect.contains(rawX.toInt(), rawY.toInt())) return null
        for (childId in node.childrenHitTest) {
            if (childId >= ENGINE_NODE_ID_BASE) continue
            val child = nodes[childId] ?: continue
            hitDfs(child, rawX, rawY, loc, rect)?.let { return it }
        }
        return node
    }

    /** 按规则匹配节点（规则标识优先，其次文本） */
    fun findNodeForRule(rule: FlutterRule): FlutterSemanticsNode? {
        val ruleIdentifier = rule.identifier
        if (!ruleIdentifier.isNullOrBlank()) {
            nodes.values.firstOrNull { it.identifier == ruleIdentifier }?.let { return it }
        }
        val label = rule.label
        val value = rule.value
        if (label.isNullOrBlank() && value.isNullOrBlank()) return null
        return nodes.values.firstOrNull { node ->
            node.label == label && node.value == value &&
                (node.label != null || node.value != null)
        }
    }

    private fun postOnMain(delayMs: Long = 0L, task: () -> Unit) {
        if (delayMs > 0L) mainHandler.postDelayed(task, delayMs) else mainHandler.post(task)
    }

    /** 系统无障碍是否开启（开启时不主动关闭语义树，避免打断 TalkBack 用户） */
    private fun systemAccessibilityOn(): Boolean {
        val app = runCatching { AndroidAppCompat.currentApplication() }.getOrNull() ?: return false
        return runCatching {
            val am = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            am?.isEnabled == true
        }.getOrDefault(false)
    }
}
