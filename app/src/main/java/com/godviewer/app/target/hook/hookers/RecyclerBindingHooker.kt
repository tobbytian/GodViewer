package com.godviewer.app.target.hook.hookers

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.View.OnAttachStateChangeListener
import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.edit.EditMode
import com.godviewer.app.target.hook.GvHook
import com.godviewer.app.target.hook.GvMethodHook
import com.godviewer.app.target.hook.MethodHookParam
import com.godviewer.app.target.rule.ViewRuleApplier
import com.godviewer.app.target.rule.ViewRuleManager
import com.godviewer.app.target.rule.ViewRuleManager.hasRepeatableRules
import com.godviewer.app.target.rule.findTargetInItem
import com.godviewer.app.target.rule.getAttachedActivityFromView
import com.godviewer.app.target.rule.viewTypeMatches
import com.godviewer.app.target.util.getObjectField
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * RecyclerView 绑定期规则应用 Hook（GodMode-Pro RecyclerAdapterHook 方法移植）：
 *
 * - `Adapter.bindViewHolder(VH, int)` **before**：若该 holder 有上一次绑定的
 *   记账 token，先按旧 epoch **丢弃**（只清记账不还原——宿主马上写下一行内容，
 *   还原反而会把旧行内容写进新行），再发新 epoch token。
 * - `bindViewHolder` **after**：布局发生前精确应用 repeatable 规则——这是消除
 *   "先渲染一帧宿主原始内容"闪现的关键路径（对比 RepeatableRuleReconciler 的
 *   布局期对账，本路径把效果写进本次测量布局，而非渲染完一帧之后）。
 * - `Adapter.onViewRecycled(VH)` **before**：按 epoch 还原（恢复各视图基线），
 *   回收的 item 不带规则效果进缓存。
 * - itemView attach 监听：缓存视图免 rebind 复用（如下拉回看）时的规则恢复
 *   入口；attach 回调先于本帧 child 测量执行，同步应用使塌缩直接参与本次测量
 *   （GodMode-Pro 实测修复 GONE 规则的"列表回弹"）。
 *
 * 不 hook `notifyDataSetChanged`：数据变更后 RecyclerView 必然经历布局，
 * Activity 的 OnGlobalLayout 重放（ActivityLifecycleHooker.replay）天然兜底。
 *
 * 安装门控：仅当该包存在 repeatable 规则或编辑模式开启时安装（[installIfNeeded]，
 * Activity onPostResume 时调用）；装上后回调内仍按规则存在与否即时早退，删除
 * 规则后 hook 留守但零开销。epoch 记账语义见 [ViewRuleApplier]。
 *
 * 全程 runCatching 隔离 + 弱引用记账，任何异常只丢一次绑定，绝不影响宿主列表。
 */
object RecyclerBindingHooker {

    private const val TAG = "RvBindHook"

    /** 已安装的 ClassLoader 家族（插件化环境同名类可能来自不同 ClassLoader） */
    private val installedLoaders = WeakHashMap<ClassLoader, Boolean>()

    /** install 成功时缓存的反射类（refreshBoundItems 复用，免重复解析） */
    private var adapterClass: Class<*>? = null
    private var holderClass: Class<*>? = null

    /** holder -> 当前绑定 token（弱键，holder 回收即消失；bind 恒在主线程） */
    private val bindings = WeakHashMap<Any, BindingToken>()

    /** 绑定代次发生器；对账器命名空间占 0，hook 路径从 1 起 */
    private val epoch = AtomicLong(ViewRuleApplier.EPOCH_RECONCILER)

    /**
     * Activity 恢复时尝试安装（幂等）：该包存在 repeatable 规则，或编辑模式开启
     * （新建规则要立刻生效）才装；已装则直接返回。
     */
    fun installIfNeeded(activity: Activity) {
        val loader = activity.classLoader ?: return
        synchronized(installedLoaders) {
            if (installedLoaders[loader] == true) return
        }
        if (!hasRepeatableRules() && !EditMode.isEnabled()) {
            return
        }
        val adapterClass = runCatching {
            Class.forName("androidx.recyclerview.widget.RecyclerView\$Adapter", false, loader)
        }.getOrNull() ?: return
        val holderClass = runCatching {
            Class.forName("androidx.recyclerview.widget.RecyclerView\$ViewHolder", false, loader)
        }.getOrNull() ?: return

        val bindInstalled = GvHook.findAndHookMethod(
            adapterClass,
            "bindViewHolder",
            object : GvMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching { onBeforeBind(param.args.getOrNull(0)) }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { onAfterBind(param.args.getOrNull(0)) }
                }
            },
            holderClass,
            Int::class.javaPrimitiveType!!,
        ) != null

        val recycleInstalled = GvHook.findAndHookMethod(
            adapterClass,
            "onViewRecycled",
            object : GvMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching { onRecycled(param.args.getOrNull(0)) }
                }
            },
            holderClass,
        ) != null

        if (bindInstalled && recycleInstalled) {
            synchronized(installedLoaders) { installedLoaders[loader] = true }
            this.adapterClass = adapterClass
            this.holderClass = holderClass
            // 对账器让位信号（状态住 ViewRuleApplier，避免 rule → hook 反向依赖）
            ViewRuleApplier.bindHookActive = true
            // 规则变更的即时生效入口：对账器让位了 RecyclerView 子树，新规则保存 /
            // 换值后由 ViewRuleManager 经此回调让本 hook 立即重应用可见 item
            ViewRuleApplier.boundItemRefresher = { activity ->
                runCatching { refreshBoundItems(activity) }
                    .onFailure { GvLog.w(TAG, "refresh bound items failed", it) }
            }
            GvLog.i(TAG, "RecyclerView adapter hooks installed loader=$loader")
        } else {
            // 逐项路径只在 bind+recycle 成对时安全：不成对就整体不启用，
            // 布局期对账继续全覆盖（对账器未让位）
            GvLog.w(
                TAG,
                "RecyclerView adapter hooks partial: bind=$bindInstalled recycle=$recycleInstalled",
            )
        }
    }

    /** bindViewHolder before：终结上一次绑定，发新 token */
    private fun onBeforeBind(holder: Any?) {
        if (holder == null || !hasRepeatableRules()) return
        bindings.remove(holder)?.let { previous ->
            previous.active = false
            previous.itemRoot.get()?.let { root ->
                previous.listener?.let { root.removeOnAttachStateChangeListener(it) }
                // 只清记账不还原：宿主 bind 马上写下一行内容（discard 语义）
                ViewRuleApplier.discardRepeatableByEpoch(root, previous.epoch)
            }
        }
        bindings[holder] = BindingToken(epoch.incrementAndGet())
    }

    /** bindViewHolder after：布局前精确应用 repeatable 规则 */
    private fun onAfterBind(holder: Any?) {
        if (holder == null || !hasRepeatableRules()) return
        val token = bindings[holder] ?: return
        val itemView = holderItemView(holder, token) ?: return
        val activity = getAttachedActivityFromView(itemView) ?: return
        if (activity.isFinishing) return
        val activityClass = activity.componentName?.className ?: return
        val boundViewType = holderViewType(holder)
        token.viewType = boundViewType
        token.itemRoot = WeakReference(itemView)
        var applied = false
        for (rule in ViewRuleManager.rulesForActivity(activityClass)) {
            if (!rule.repeatable) continue
            if (!viewTypeMatches(rule, boundViewType)) continue
            val target = findTargetInItem(itemView, rule) ?: continue
            if (ViewRuleApplier.applyRepeatable(target, rule, token.epoch)) {
                applied = true
            }
        }
        if (applied) {
            ensureLifecycleListener(token, itemView)
        }
    }

    /** onViewRecycled before：按 epoch 还原，回收的 item 不带效果进缓存 */
    private fun onRecycled(holder: Any?) {
        if (holder == null) return
        val token = bindings.remove(holder) ?: return
        token.active = false
        val itemView = token.itemRoot.get() ?: holderItemView(holder, null) ?: return
        token.listener?.let { itemView.removeOnAttachStateChangeListener(it) }
        // 门控关闭后回收仍要释放上一次绑定遗留的效果
        ViewRuleApplier.revokeRepeatableByEpoch(itemView, token.epoch)
    }

    /** attach 监听：缓存视图免 rebind 复用时同步恢复规则（先于测量，防列表回弹） */
    private fun ensureLifecycleListener(token: BindingToken, itemView: View) {
        if (token.listener != null) return
        val listener = object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                if (!token.active || !hasRepeatableRules()) return
                runCatching { applyForToken(token, view) }
            }

            override fun onViewDetachedFromWindow(view: View) {
                if (!token.active) return
                runCatching { ViewRuleApplier.revokeRepeatableByEpoch(view, token.epoch) }
            }
        }
        token.listener = listener
        itemView.addOnAttachStateChangeListener(listener)
    }

    /** 对 token 对应的 item 执行一轮规则应用（attach 恢复路径） */
    private fun applyForToken(token: BindingToken, itemView: View) {
        val activity = getAttachedActivityFromView(itemView) ?: return
        if (activity.isFinishing) return
        val activityClass = activity.componentName?.className ?: return
        for (rule in ViewRuleManager.rulesForActivity(activityClass)) {
            if (!rule.repeatable) continue
            if (!viewTypeMatches(rule, token.viewType)) continue
            val target = findTargetInItem(itemView, rule) ?: continue
            ViewRuleApplier.applyRepeatable(target, rule, token.epoch)
        }
    }

    /**
     * 让当前已绑定的可见 RecyclerView item 立即重应用规则（规则保存 / 换值后的
     * 即时生效路径）：对每个可见 item 模拟一次"重绑定"——终结旧 token（discard 旧
     * 效果记账）并发新 token 应用。仅经 [ViewRuleApplier.boundItemRefresher] 在
     * 规则变更时触发，不在每帧路径上。
     */
    private fun refreshBoundItems(activity: Activity) {
        if (!ViewRuleApplier.bindHookActive) return
        val rvClass = adapterClass ?: return
        if (holderClass == null) return
        val getChildViewHolder = runCatching {
            rvClass.getMethod("getChildViewHolder", View::class.java)
        }.getOrNull() ?: return
        val decor = activity.window?.decorView ?: return
        fun walk(view: View) {
            if (rvClass.isInstance(view)) {
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        val child = view.getChildAt(i)
                        val holder = runCatching { getChildViewHolder.invoke(view, child) }
                            .getOrNull()
                        if (holder != null) {
                            onBeforeBind(holder)
                            onAfterBind(holder)
                        }
                        // 嵌套 RecyclerView（卡片内嵌列表）同样刷新
                        walk(child)
                    }
                }
                return
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    walk(view.getChildAt(i))
                }
            }
        }
        walk(decor)
    }

    private fun holderItemView(holder: Any, token: BindingToken?): View? {
        token?.itemRoot?.get()?.let { return it }
        return runCatching { getObjectField(holder, "itemView") as? View }.getOrNull()
    }

    /** holder.getItemViewType()：反射失败返回 -1（bind 热路径不记日志防刷屏） */
    private fun holderViewType(holder: Any): Int {
        return runCatching {
            (holder.javaClass.getMethod("getItemViewType").invoke(holder) as? Int) ?: -1
        }.getOrDefault(-1)
    }

    /** 一次 ViewHolder 绑定的记账 token（bind 恒在主线程，无需同步） */
    private class BindingToken(val epoch: Long) {
        var itemRoot = WeakReference<View>(null)
        var viewType: Int = -1
        var active = true
        var listener: OnAttachStateChangeListener? = null
    }
}
