package com.godviewer.app.host.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 承载 Tab 内容的容器：**ViewPager 式双页联动跟手**。
 *
 * 拖动中当前页与下一页同步反向平移（当前页 translationX = 位移，
 * 下一页 = 位移 ± 页宽），手指不抬就能看到下一页滑入；dock 高亮经
 * [onDragProgress] 按进度插值移动。松手：超过阈值两页一起走到终位后经
 * [onPageSettled] 提交（Activity 以无动画方式切 tab，视觉连续）；
 * 没超过一起弹回原位并取消预览。
 *
 * - 阈值随屏宽**开方**自适应（360dp 基准屏约 52dp，夹 0.85x–1.5x，非一比一）；
 *   另有**甩动判定**：快速轻扫（≥600dp/s 且同向、位移 ≥24dp）不过 52dp 阈值也翻页，
 *   反向甩动即使过阈值也弹回——松手结果跟手的「甩劲」走，不只看位置；
 * - 方向到头（[onResolvePage] 返回 null）时 0.25 阻尼跟手，松手弹回；
 * - 拖动/settle 期间给两页开**硬件层**（[View.LAYER_TYPE_HARDWARE]）：整页只栅格化
 *   一次，逐帧只平移贴图，复杂页面拖动不掉帧；手势结束即释放；
 * - 拖动中方向反转会撤掉旧预览页、按新方向重新取页；
 * - 手势打断进行中的 settle 动画时先立即完成提交（状态机不悬空），
 *   再从当下视觉位置续拖；
 * - 只有水平位移明显占优（1.2x）才拦截，页内垂直滚动不受影响。
 */
class SwipeSwitchFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * 按方向取联动页：forward = true 左滑（下一页）。返回该页 View 并确保其
     * VISIBLE（Activity 负责 fragment show/预览记录）；null = 该方向没有页。
     * 拖动中方向反转会被再次调用；Activity 须在实现里自行撤掉上一个预览页。
     */
    var onResolvePage: ((Boolean) -> View?)? = null

    /** 联动进度 [0,1]（|位移| / 页宽），用于 dock 高亮插值 */
    var onDragProgress: ((Float) -> Unit)? = null

    /** settle 结束：true = 已完成翻页（视觉已到位，Activity 提交 tab）；false = 已弹回 */
    var onPageSettled: ((Boolean) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** 甩动速度采样上限（px/s），夹系统最大 fling 速度防野值 */
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    /** 与点击 dock 时页面/高亮动画同曲线（fast_out_slow_in） */
    private val smooth = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /** 甩动速度采样器（beginGesture 取，UP/CANCEL 后回收） */
    private var velocityTracker: VelocityTracker? = null

    /** 两页硬件层是否已开启（每次手势一对开关，防重复 setLayerType） */
    private var dragLayersOn = false

    private var downX = 0f
    private var downY = 0f
    private var horizontal = false
    private var scrollClaimed = false

    /** 手势起点位移（动画被新手势打断时从当前视觉位续拖） */
    private var startTranslation = 0f

    /** 当前跟手位移（当前页的 translationX） */
    private var translation = 0f

    /** 联动状态 */
    private var currentPage: View? = null
    private var targetPage: View? = null
    private var targetForward = false

    /** 本手势是否做过方向解析（决定结束时要不要回调 Activity） */
    private var resolved = false

    /** 当前方向是否到头（onResolvePage 返回 null → 阻尼模式） */
    private var edgeBlocked = false

    /** 进行中的 settle：记录终态供 finishSettle 幂等执行 */
    private data class PendingSettle(
        val commit: Boolean,
        val endCurrent: Float,
        val endTarget: Float,
    )

    private var pendingSettle: PendingSettle? = null

    /** settle 动画：逐帧驱动两页平移并把进度推给 dock 高亮，保证同步 */
    private var settleAnimator: ValueAnimator? = null

    /** 转场后被 hide 的页带着残留位移，归零防止再次 show 时从屏外开始 */
    private val hiddenOffsetCleaner = ViewTreeObserver.OnPreDrawListener {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE && child.translationX != 0f) {
                child.translationX = 0f
            }
        }
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(hiddenOffsetCleaner)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(hiddenOffsetCleaner)
        super.onDetachedFromWindow()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginGesture(ev)
            MotionEvent.ACTION_MOVE -> if (!horizontal && !scrollClaimed) claim(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!horizontal) snapSettledViewsIfIdle()
                recycleVelocityTracker()
            }
        }
        return horizontal
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            // 无 child 接手时事件直接进这里，水平/垂直判定也要在此做
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (!horizontal && !scrollClaimed) claim(ev)
                if (horizontal) drag(ev)
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(ev)
                settle(fire = true, velocity = readVelocity())
                recycleVelocityTracker()
            }
            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.addMovement(ev)
                settle(fire = false, velocity = 0f)
                recycleVelocityTracker()
            }
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP ||
            ev.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            horizontal = false
        }
        return true
    }

    /** DOWN：先立即完成进行中的 settle（提交状态、回调 Activity），再从当下位置开新手势 */
    private fun beginGesture(ev: MotionEvent) {
        finishSettle(force = true)
        cancelChildAnimations()
        if (dragLayersOn) clearDragLayers()
        recycleVelocityTracker()
        velocityTracker = VelocityTracker.obtain().apply { addMovement(ev) }
        downX = ev.x
        downY = ev.y
        horizontal = false
        scrollClaimed = false
        currentPage = visibleChildWithMinOffset()
        targetPage = null
        resolved = false
        edgeBlocked = false
        translation = currentPage?.translationX ?: 0f
        startTranslation = translation
    }

    /** 水平/垂直占优判定（intercept 与自持事件两条路径共用）。1.2x：斜向起手更早认出横滑，减少被垂直抢先锁死 */
    private fun claim(ev: MotionEvent) {
        val dx = ev.x - downX
        val dy = ev.y - downY
        when {
            abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.2f -> horizontal = true
            abs(dy) > touchSlop -> scrollClaimed = true
        }
    }

    /** 联动跟手：当前页 + 目标页同步平移，进度推给 dock 高亮 */
    private fun drag(ev: MotionEvent) {
        val page = currentPage ?: return
        if (width == 0) return
        val raw = ev.x - downX
        val beyond = when {
            raw > touchSlop -> raw - touchSlop
            raw < -touchSlop -> raw + touchSlop
            else -> 0f
        }

        // 方向解析 / 反转重取（基于合成位移的符号）
        val tentative = startTranslation + beyond
        if (tentative != 0f && (!resolved || (tentative < 0) != targetForward)) {
            resolveForward(tentative < 0)
        }

        val scale = if (resolved && targetPage == null) EDGE_DAMPING else 1f
        translation = startTranslation + beyond * scale

        ensureDragLayers()
        page.translationX = translation
        targetPage?.translationX =
            translation + if (targetForward) width.toFloat() else -width.toFloat()

        val progress = (abs(translation) / width).coerceIn(0f, 1f)
        onDragProgress?.invoke(progress)
    }

    /** 解析联动方向：要到目标页则记录之；到头则进入阻尼模式 */
    private fun resolveForward(forward: Boolean) {
        resolved = true
        targetForward = forward
        targetPage = onResolvePage?.invoke(forward)
        edgeBlocked = targetPage == null
        if (edgeBlocked) targetPage = null
        // 拖动中途换方向取到的新目标页：跟上已开启的硬件层
        if (dragLayersOn) targetPage?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    /** 首帧拖动时给两页开硬件层：整页只栅格化一次，后续逐帧只平移贴图 */
    private fun ensureDragLayers() {
        if (dragLayersOn) return
        dragLayersOn = true
        currentPage?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        targetPage?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    /** 手势彻底结束：所有 child 释放硬件层（含打断残留），恢复常规渲染 */
    private fun clearDragLayers() {
        dragLayersOn = false
        for (i in 0 until childCount) {
            getChildAt(i).setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    /** 读取 UP 时刻的水平甩动速度（px/s，带符号；无采样返回 0） */
    private fun readVelocity(): Float {
        val tracker = velocityTracker ?: return 0f
        tracker.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
        return tracker.xVelocity
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /**
     * 松手/取消：
     * - **甩动优先**：|速度| ≥ 600dp/s 时跟甩劲走——同向且位移 ≥24dp 即翻页
     *   （不必过 52dp 阈值，快速轻扫也翻）；反向甩动即使过阈值也弹回；
     * - 慢速松手按位移阈值判定；
     * - 超判定且有目标页且 fire（UP）→ 双页走到终位，提交翻页；
     * - 其余 → 双页弹回原位，取消预览。
     * 没做过方向解析且位移≈0（点按）→ 静默复位，不回调。
     */
    private fun settle(fire: Boolean, velocity: Float = 0f) {
        val page = currentPage
        val distance = translation
        if (!resolved) {
            page?.translationX = 0f
            translation = 0f
            startTranslation = 0f
            return
        }
        if (page == null || width == 0) {
            finishCallbacksOnly(commit = false)
            return
        }
        val density = resources.displayMetrics.density
        val flingSpeed = abs(velocity) >= FLING_MIN_VELOCITY_DP * density
        // 位移方向与甩动方向是否一致（同号 = 还在往目标方向走）
        val aligned = distance * velocity > 0f
        val commit = fire && !edgeBlocked && targetPage != null && when {
            flingSpeed && !aligned -> false
            flingSpeed -> abs(distance) >= FLING_MIN_DISTANCE_DP * density
            else -> abs(distance) >= thresholdPx()
        }
        val widthF = width.toFloat()
        val endCurrent = when {
            commit && targetForward -> -widthF
            commit -> widthF
            else -> 0f
        }
        val endTarget = if (commit) 0f else if (targetForward) widthF else -widthF
        val target = targetPage
        val startTarget = target?.translationX

        // 结清上一轮（正常不会存在，防御），再启动本轮 settle
        settleAnimator?.cancel()
        settleAnimator = null
        pendingSettle = PendingSettle(commit, endCurrent, endTarget)

        // 速度与点击 dock 一致：剩余距离按「整页 400ms」等比折算时长，同曲线；
        // 甩动手势整体提速 0.7x，跟上手指的「甩劲」
        val remaining = abs(endCurrent - distance).coerceAtMost(widthF)
        var durationMs = (SETTLE_BASE_MS * remaining / widthF)
            .toLong()
            .coerceIn(MIN_SETTLE_MS, SETTLE_BASE_MS)
        if (flingSpeed) {
            durationMs = (durationMs * FLING_DURATION_FACTOR).toLong()
                .coerceAtLeast(MIN_SETTLE_MS)
        }

        settleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = smooth
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val tx = distance + (endCurrent - distance) * t
                page.translationX = tx
                if (target != null && startTarget != null) {
                    target.translationX = startTarget + (endTarget - startTarget) * t
                }
                // 高亮与两页同帧同曲线推进
                onDragProgress?.invoke((abs(tx) / widthF).coerceIn(0f, 1f))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finishSettle(force = false)
                }
            })
            start()
        }
    }

    /**
     * 完成 settle：停掉动画、把两页 snap 到终态（幂等），回调 Activity，清联动状态。
     * [force] = true 用于新手势 DOWN 打断进行中动画时立即完成。
     */
    private fun finishSettle(force: Boolean) {
        val state = pendingSettle ?: return
        pendingSettle = null
        settleAnimator?.cancel()
        settleAnimator = null
        currentPage?.translationX = state.endCurrent
        targetPage?.translationX = state.endTarget
        if (force) cancelChildAnimations()
        val commit = state.commit
        // 状态复位（提交由 Activity 的 selectTab 决定新的 current）
        currentPage = null
        targetPage = null
        resolved = false
        edgeBlocked = false
        translation = 0f
        startTranslation = 0f
        onPageSettled?.invoke(commit)
        clearDragLayers()
    }

    /** 没有 pending settle 时的无操作分支（点按等场景不回调） */
    private fun finishCallbacksOnly(commit: Boolean) {
        currentPage = null
        targetPage = null
        resolved = false
        edgeBlocked = false
        translation = 0f
        startTranslation = 0f
        pendingSettle = null
        onPageSettled?.invoke(commit)
        clearDragLayers()
    }

    /** 未进入横滑的手势结束（点按打断等）：把可能残留的偏移归位 */
    private fun snapSettledViewsIfIdle() {
        if (pendingSettle != null) return
        if (abs(translation) < 1f && targetPage == null) return
        if (dragLayersOn) clearDragLayers()
        currentPage?.let { animateBackToZero(it) }
        targetPage?.let { animateBackToZero(it) }
        targetPage = null
        resolved = false
        edgeBlocked = false
        translation = 0f
        startTranslation = 0f
        onDragProgress?.invoke(0f)
    }

    private fun animateBackToZero(view: View) {
        val durationMs = if (width > 0) {
            (SETTLE_BASE_MS * abs(view.translationX) / width)
                .toLong()
                .coerceIn(MIN_SETTLE_MS, SETTLE_BASE_MS)
        } else {
            MIN_SETTLE_MS
        }
        view.animate().cancel()
        view.animate()
            .translationX(0f)
            .setDuration(durationMs)
            .setInterpolator(smooth)
            .start()
    }

    /** 当前应跟手的页：可见者中位移最小的（转场打断时也合理） */
    private fun visibleChildWithMinOffset(): View? {
        var best: View? = null
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (best == null || abs(child.translationX) < abs(best.translationX)) {
                best = child
            }
        }
        return best
    }

    /** 阈值：360dp 基准屏 ≈52dp，按屏宽开方缩放夹 0.85x–1.5x（非一比一） */
    private fun thresholdPx(): Float {
        if (width <= 0) return touchSlop.toFloat()
        val density = resources.displayMetrics.density
        val widthDp = width / density
        val scale = sqrt(widthDp / REF_WIDTH_DP).coerceIn(MIN_SCALE, MAX_SCALE)
        return BASE_THRESHOLD_DP * density * scale
    }

    private fun cancelChildAnimations() {
        for (i in 0 until childCount) {
            getChildAt(i).animate().cancel()
        }
    }

    companion object {
        /** 到头方向的跟手阻尼系数 */
        private const val EDGE_DAMPING = 0.25f

        /** 阈值基准：360dp 宽屏约滑 52dp 翻页 */
        private const val BASE_THRESHOLD_DP = 52f
        private const val REF_WIDTH_DP = 360f

        /** 屏宽对阈值的影响夹在 0.85x–1.5x：屏幕变宽阈值不等比放大 */
        private const val MIN_SCALE = 0.85f
        private const val MAX_SCALE = 1.5f

        /** 整页动画时长（ms）：与点击 dock 的 tab_slide_* / 高亮动画一致（400ms fast_out_slow_in） */
        private const val SETTLE_BASE_MS = 400L

        /** settle 最短时长（ms）：拖到接近满幅时松手不至于瞬移 */
        private const val MIN_SETTLE_MS = 60L

        /** 甩动判定的最小速度（dp/s）：达到即按甩劲而非位移阈值决定松手结果 */
        private const val FLING_MIN_VELOCITY_DP = 600f

        /** 甩动同向时的最小位移（dp）：防快速点划误翻页 */
        private const val FLING_MIN_DISTANCE_DP = 24f

        /** 甩动时 settle 整体提速系数（时长 × 0.7） */
        private const val FLING_DURATION_FACTOR = 0.7f
    }
}