package com.godviewer.app.target.hook.hookers

import android.app.Dialog
import android.os.Handler
import android.os.Looper
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.entry.PopupIntercept
import com.godviewer.app.shared.model.DialogRule
import com.godviewer.app.target.dialog.ModuleDialogUi
import com.godviewer.app.target.hook.GvHook
import com.godviewer.app.target.hook.GvMethodHook
import com.godviewer.app.target.hook.IHooker
import com.godviewer.app.target.hook.MethodHookParam
import com.godviewer.app.target.rule.DialogFingerprint
import com.godviewer.app.target.rule.DialogRuleStore
import java.util.Collections
import java.util.WeakHashMap

/**
 * always-on Dialog 跟踪 Hook（强制弹窗出现时编辑模式未必开启，不能懒装）：
 *
 * - `Dialog.show()` after：登记跟踪后匹配规则。**规则命中的自动关闭不受时间窗口
 *   限制**（更新弹窗常在开屏广告/首页加载后才弹，晚于窗口也必须拦），且在 show 的
 *   同一次主线程消息里同步 dismiss——窗口尚未绘制即消失，用户看不到闪烁。
 * - 内容常在 show 之后异步填充：该包有规则时未命中先做「下一循环 + 1.2s 终评」
 *   两次补匹配；**问询上报延迟到终评**仍未命中才发（否则会给将被自动关闭的弹窗
 *   白发通知）。该包无规则的应用维持 show 即上报（检测功能本义）。
 * - 检测问询仍只处理应用打开 N 秒窗口内的弹窗（[PopupIntercept.withinOpenWindow]），
 *   窗口外的多半是用户主动点出的界面，不打扰。跳过模块自身对话框。
 * - 自动关闭带限流（[AUTO_DISMISS_PER_MINUTE]）：与「dismiss 后立刻 re-show」的
 *   应用死循环打架时降级为问询上报。
 * - `Dialog.dismiss()` / `Dialog.hide()` after：移出跟踪集。
 *
 * 跟踪集用 WeakHashMap：对话框被回收时条目自动消失；每次消费前仍校验
 * [Dialog.isShowing]，双重防泄漏。
 */
class DialogHooker : IHooker {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onHook() {
        GvHook.findAndHookMethod(
            Dialog::class.java,
            "show",
            object : GvMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val dialog = param.thisObject as? Dialog ?: return
                        if (ModuleDialogUi.isModuleDialog(dialog)) return
                        val context = runCatching { dialog.context }.getOrNull() ?: return
                        // 一律登记跟踪：宿主手动「关闭弹窗」按钮不依赖拦截开关
                        tracked[dialog] = System.currentTimeMillis()
                        val texts = DialogFingerprint.textsOf(dialog)
                        val pkgHasRules =
                            DialogRuleStore.all().any { it.packageName == context.packageName }
                        // 该包已有规则时首评不上报问询：内容常在 show 之后异步填充，
                        // 此刻指纹不全大概率匹配不上，立即上报会给 1.2s 后将被自动
                        // 关闭的弹窗白弹一条通知
                        val hit = evaluate(dialog, context, texts, allowReport = !pkgHasRules)
                        if (!hit && pkgHasRules) {
                            val decor = dialog.window?.decorView
                            // 下一循环先评一次：内容常在同一帧内就绪，命中即同步 dismiss（无闪烁）
                            decor?.post {
                                runCatching { evaluate(dialog, context, allowReport = false) }
                            }
                            // 1.2s 后终评：仍未命中才上报问询（allowReport=true）
                            scheduleMissRetry(dialog, context)
                        } else if (!hit && texts.isEmpty()) {
                            // 无规则应用维持旧行为：空指纹时下一循环补一次
                            dialog.window?.decorView?.post {
                                runCatching { evaluate(dialog, context) }
                            }
                        }
                    }.onFailure {
                        GvLog.w(TAG, "show hook failed", it)
                    }
                }
            },
        )

        val removeFromTrack = object : GvMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching {
                    val dialog = param.thisObject as? Dialog ?: return
                    tracked.remove(dialog)
                }
            }
        }
        GvHook.findAndHookMethod(Dialog::class.java, "dismiss", removeFromTrack)
        GvHook.findAndHookMethod(Dialog::class.java, "hide", removeFromTrack)
        GvLog.i(TAG, "Dialog.show/dismiss/hide hooked")
    }

    /**
     * 单个弹窗的匹配判定 + 执行（show 主路径与补匹配共用）。
     *
     * @return 命中规则并已自动关闭时 true（限流降级视为未命中，走问询路径）。
     *
     * 规则重放**不查时间窗口**（更新弹窗常在开屏广告之后才弹）；窗口只用于
     * 「检测问询」上报——窗口外的多半是用户主动点出的界面，不打扰。
     * @param allowReport false = 本次未命中也不上报问询（首评/中间补评专用：
     * 内容尚未填充完，等 [scheduleMissRetry] 终评仍未命中时再上报）。
     */
    private fun evaluate(
        dialog: Dialog,
        context: android.content.Context,
        precomputedTexts: List<String>? = null,
        allowReport: Boolean = true,
    ): Boolean {
        if (!dialog.isShowing) return false
        if (ModuleDialogUi.isModuleDialog(dialog)) return false
        val texts = precomputedTexts ?: DialogFingerprint.textsOf(dialog)
        val activityClass =
            ModuleDialogUi.activityOf(context)?.componentName?.className.orEmpty()
        val versionCode = DialogFingerprint.versionCodeOf(context)
        val matched = DialogRuleStore.findMatch(
            packageName = context.packageName,
            versionCode = versionCode,
            activityClass = activityClass,
            dialogClass = dialog.javaClass.name,
            texts = texts,
        )
        // 规则重放受开关闸门（状态经广播从宿主同步，缓存默认关）
        if (matched != null && PopupIntercept.isEnabled(context)) {
            if (!tryAcquireAutoDismiss(context.packageName, dialog.javaClass.name)) {
                GvLog.w(
                    TAG,
                    "auto dismiss rate-limited, fallback to report: " +
                        "${dialog.javaClass.name}",
                )
            } else {
                GvLog.i(
                    TAG,
                    "dialog rule hit, dismissing: ${dialog.javaClass.name} " +
                        "texts=${texts.size} sameVer=${matched.matchVersionCode == versionCode}",
                )
                // 同步 dismiss（evaluate 恒在主线程：show 主路径、decorView.postDelayed、
                // 宿主关闭指令均如此）——show 的同一次消息里移除窗口，尚未绘制即消失，
                // 用户看不到「先弹出来再关」的闪烁；失败再 post 兜底
                runCatching { dialog.dismiss() }
                    .onFailure {
                        GvLog.w(TAG, "rule dismiss failed", it)
                        mainHandler.post { runCatching { dialog.dismiss() } }
                    }
                HostControlBridge.reportPopupIntercepted(
                    context,
                    context.packageName,
                    DialogRule.titleOf(texts, dialog.javaClass.name),
                    HostControlBridge.KIND_AUTO,
                )
                return true
            }
        }
        val rules = DialogRuleStore.all()
        if (rules.isNotEmpty()) {
            GvLog.d(
                TAG,
                "replay miss: class=${dialog.javaClass.name} texts=${texts.size} " +
                    "ver=$versionCode verOk=${rules.any { it.matchVersionCode == versionCode }} " +
                    "activity=$activityClass matched=${matched != null} " +
                    "gate=${PopupIntercept.isEnabled(context)} rules=${rules.size}",
            )
        } else {
            GvLog.d(
                TAG,
                "dialog tracked: ${dialog.javaClass.name} " +
                    "texts=${texts.size} activity=$activityClass",
            )
        }
        // 问询上报只处理应用刚打开那几秒内的弹窗（按 包名|类名 节流防刷屏；
        // 宿主端按最新开关二次把关，这里不查开关）。首评/中间补评不上报，
        // 留给终评决定——避免给即将被自动关闭的弹窗白发通知
        if (PopupIntercept.withinOpenWindow(context)) {
            if (allowReport) {
                reportDetected(context, dialog, texts)
            } else {
                GvLog.d(TAG, "defer ask-report to final retry: ${dialog.javaClass.name}")
            }
        } else {
            GvLog.d(TAG, "outside open window, skip ask-report: ${dialog.javaClass.name}")
        }
        return false
    }

    /**
     * 未命中的终评（每个 Dialog 实例一次，1.2s 后）：此刻内容应已填充完，
     * 仍未命中才上报问询。该包无规则时跳过，避免给普通弹窗白挂定时器。
     */
    private fun scheduleMissRetry(dialog: Dialog, context: android.content.Context) {
        if (!DialogRuleStore.all().any { it.packageName == context.packageName }) return
        if (!retryMarked.add(dialog)) return
        dialog.window?.decorView?.postDelayed(
            { runCatching { evaluate(dialog, context, allowReport = true) } },
            RETRY_TEXT_MS,
        )
    }

    companion object {
        private const val TAG = "DlgHook"

        /** 同一应用同一弹窗类的上报最小间隔（ms），避免每次 resume 都刷通知 */
        private const val REPORT_MIN_INTERVAL_MS = 10_000L

        /** 指纹为空/未命中时的补匹配延迟（ms）：等异步内容填充 */
        private const val RETRY_TEXT_MS = 1_200L

        /** 单个 包名|弹窗类 滚动窗口内自动关闭次数上限：防「关了又弹」死循环 */
        private const val AUTO_DISMISS_PER_MINUTE = 10

        /** 限流滚动窗口（ms） */
        private const val RATE_WINDOW_MS = 60_000L

        /** showing 的外来 Dialog → 首次跟踪时间（weak，键回收即消失） */
        private val tracked: MutableMap<Dialog, Long> =
            Collections.synchronizedMap(WeakHashMap())

        /** 已延迟补匹配过的 Dialog（weak，每实例只补一次） */
        private val retryMarked: MutableSet<Dialog> = Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<Dialog, Boolean>()),
        )

        /** 上报节流：包名|弹窗类 → 上次上报时间 */
        private val lastReportAt: MutableMap<String, Long> =
            Collections.synchronizedMap(HashMap())

        /** 自动关闭限流：包名|弹窗类 → 滚动窗口内的触发时间戳 */
        private val autoDismissAt: MutableMap<String, MutableList<Long>> =
            Collections.synchronizedMap(HashMap())

        /** 当前跟踪到的外来对话框快照（调用方需自行校验 isShowing） */
        fun trackedDialogs(): List<Dialog> = synchronized(tracked) { tracked.keys.toList() }

        /**
         * 限流放行（滚动窗口计数 +1）：超过 [AUTO_DISMISS_PER_MINUTE] 拒绝，
         * 调用方降级为问询上报。防与应用「dismiss 后立刻 re-show」逻辑互相打架。
         */
        private fun tryAcquireAutoDismiss(pkg: String, dialogClass: String): Boolean {
            val key = "$pkg|$dialogClass"
            val now = System.currentTimeMillis()
            synchronized(autoDismissAt) {
                val list = autoDismissAt.getOrPut(key) { ArrayList() }
                list.removeAll { it < now - RATE_WINDOW_MS }
                if (list.size >= AUTO_DISMISS_PER_MINUTE) return false
                list.add(now)
                return true
            }
        }

        /** 检测到新弹窗 → 上报宿主（best-effort，节流后发） */
        private fun reportDetected(
            context: android.content.Context,
            dialog: Dialog,
            texts: List<String>,
        ) {
            runCatching {
                val pkg = context.packageName
                val key = "$pkg|${dialog.javaClass.name}"
                val now = System.currentTimeMillis()
                val last = lastReportAt[key] ?: 0L
                if (now - last < REPORT_MIN_INTERVAL_MS) {
                    // 检测到了但被 10s 节流压掉：留痕，避免误判"没检测到"
                    GvLog.d(
                        TAG,
                        "report throttled key=$key remainMs=${REPORT_MIN_INTERVAL_MS - (now - last)}",
                    )
                    return
                }
                lastReportAt[key] = now
                // 描述取首条指纹文本，无文本回退弹窗类短名
                val label = DialogRule.titleOf(texts, dialog.javaClass.name)
                HostControlBridge.reportPopupDetected(context, pkg, label)
            }.onFailure {
                GvLog.w(TAG, "reportPopupDetected failed", it)
            }
        }
    }
}