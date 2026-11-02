package com.godviewer.app.host

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.PathInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.godviewer.app.R
import com.godviewer.app.host.entry.EntryControlUi
import com.godviewer.app.host.prefs.HostPrefs
import com.godviewer.app.host.ui.HomeFragment
import com.godviewer.app.host.ui.RulesFragment
import com.godviewer.app.host.ui.SettingsFragment
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.databinding.ActivityMainBinding
import com.godviewer.app.databinding.ItemBottomNavBinding
import com.godviewer.app.host.ui.HostActivity

class MainActivity : HostActivity() {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private var selectedTabId = R.id.nav_home

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        EntryControlUi.refresh(this)
    }

    private data class Tab(
        val id: Int,
        val tag: String,
        val iconRes: Int,
        val labelRes: Int,
        val item: ItemBottomNavBinding,
        val factory: () -> Fragment,
    )

    private lateinit var tabs: List<Tab>

    /** 滑动联动预览中的 Tab 下标（-1 = 无），及高亮插值起止位 */
    private var dragPreviewIdx = -1
    private var dragFromHighlightX = 0f
    private var dragToHighlightX = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        maybeRequestNotificationPermission()
        EntryControlUi.refresh(this)
        setupTabs()

        // 内容区左右滑动：双页联动跟手 —— 手不抬就能看到下一页滑入，
        // dock 高亮按进度插值跟随；松手超过阈值无缝完成翻页，否则弹回
        binding.fragmentContainer.onResolvePage = { forward -> resolveAdjacentPage(forward) }
        binding.fragmentContainer.onDragProgress = { progress -> onSwipeDragProgress(progress) }
        binding.fragmentContainer.onPageSettled = { commit -> onSwipeSettled(commit) }

        val openSettings = intent?.getBooleanExtra(SettingsFragment.EXTRA_OPEN_SETTINGS, false) == true
        selectedTabId = when {
            openSettings -> R.id.nav_settings
            savedInstanceState != null -> savedInstanceState.getInt(STATE_TAB, R.id.nav_home)
            else -> R.id.nav_home
        }
        // 升级前若停在已删除的弹窗 Tab：id 对不上任何现有 Tab，回落首页
        if (tabs.none { it.id == selectedTabId }) selectedTabId = R.id.nav_home
        selectTab(selectedTabId, animate = false)

        // 预创建其余 Tab（保持隐藏）：首次点击/滑动不再现建页面，消除切换前的卡顿延迟
        binding.fragmentContainer.post { prewarmTabs() }

        // 冷启动时在线检查更新（设置里可关「自动更新」）
        if (savedInstanceState == null) {
            UpdateChecker.checkOnLaunch(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // 打开宿主时清空过期目标；本体入口下通知回到「尚无活动目标」
        HostControlBridge.clearTargetState(this)
        EntryControlUi.refresh(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, selectedTabId)
    }

    private fun maybeRequestNotificationPermission() {
        // 仅首页「本体通知」开关打开时需要宿主通知权限
        val needsHostNotify = HostPrefs.isHostNotifyEnabled(this)
        if (!needsHostNotify) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setupTabs() {
        tabs = listOf(
            Tab(
                id = R.id.nav_home,
                tag = TAB_HOME,
                iconRes = R.drawable.ic_nav_home_24,
                labelRes = R.string.nav_home,
                item = binding.navHome,
                factory = { HomeFragment() },
            ),
            Tab(
                id = R.id.nav_rules,
                tag = TAB_RULES,
                iconRes = R.drawable.ic_nav_rules_24,
                labelRes = R.string.nav_rules,
                item = binding.navRules,
                factory = { RulesFragment() },
            ),
            Tab(
                id = R.id.nav_settings,
                tag = TAB_SETTINGS,
                iconRes = R.drawable.ic_nav_settings_24,
                labelRes = R.string.nav_settings,
                item = binding.navSettings,
                factory = { SettingsFragment() },
            ),
        )

        tabs.forEach { tab ->
            tab.item.iconView.setImageResource(tab.iconRes)
            // 纯图标 dock：文案只用于无障碍 contentDescription
            tab.item.iconView.contentDescription = getString(tab.labelRes)
            tab.item.root.setOnClickListener {
                if (selectedTabId == tab.id) return@setOnClickListener
                selectTab(tab.id, animate = true)
            }
        }
    }

    private fun selectTab(tabId: Int, animate: Boolean) {
        val target = tabs.firstOrNull { it.id == tabId } ?: return
        val fm = supportFragmentManager
        // 滑动提交时预览页已与目标页并存可见，不能只看「第一个可见页」——
        // 只要有别的页还露着、或目标页还没显示，就要跑一遍收尾事务
        val othersVisible = tabs.any { tab ->
            tab.tag != target.tag && fm.findFragmentByTag(tab.tag)?.isHidden == false
        }
        val targetHidden = fm.findFragmentByTag(target.tag)?.isHidden ?: true

        if (othersVisible || targetHidden) {
            // 隐藏页从 GONE 拉回 INVISIBLE：show 时不再整页重新测量，起手更顺滑
            if (animate) {
                tabs.forEach { tab ->
                    fm.findFragmentByTag(tab.tag)?.view?.let { v ->
                        if (v.visibility == View.GONE) v.visibility = View.INVISIBLE
                    }
                }
            }
            val transaction = fm.beginTransaction()
            if (animate) {
                // 方向感知：切往后的新页从右进、旧页向左出；往前反之
                val from = tabs.indexOfFirst { it.id == selectedTabId }
                val to = tabs.indexOfFirst { it.id == tabId }
                if (to >= from) {
                    transaction.setCustomAnimations(
                        R.anim.tab_slide_in_right,
                        R.anim.tab_slide_out_left,
                    )
                } else {
                    transaction.setCustomAnimations(
                        R.anim.tab_slide_in_left,
                        R.anim.tab_slide_out_right,
                    )
                }
            }
            tabs.forEach { tab ->
                fm.findFragmentByTag(tab.tag)?.let { transaction.hide(it) }
            }
            var fragment = fm.findFragmentByTag(target.tag)
            if (fragment == null) {
                fragment = target.factory()
                transaction.add(R.id.fragmentContainer, fragment, target.tag)
            } else {
                transaction.show(fragment)
            }
            transaction.commitNowAllowingStateLoss()
        }

        selectedTabId = tabId
        refreshTabVisuals(animate)
    }

    private fun refreshTabVisuals(animate: Boolean) {
        val selectedColor = ContextCompat.getColor(this, R.color.home_title_text)
        val unselectedColor = ContextCompat.getColor(this, R.color.home_nav_item_unselected)
        tabs.forEach { tab ->
            val selected = tab.id == selectedTabId
            tab.item.iconView.setColorFilter(if (selected) selectedColor else unselectedColor)
        }
        // 选中底片平移到目标按钮下方（与页面切换动画同步，180ms）
        val target = tabs.firstOrNull { it.id == selectedTabId }?.item?.root ?: return
        val highlight = binding.navHighlight
        val place = {
            val x = target.left.toFloat()
            highlight.visibility = View.VISIBLE
            if (animate) {
                highlight.animate()
                    .translationX(x)
                    .setDuration(HIGHLIGHT_ANIM_MS)
                    .setInterpolator(SMOOTH_INTERPOLATOR)
                    .start()
            } else {
                highlight.animate().cancel()
                highlight.translationX = x
            }
        }
        if (target.isLaidOut) place() else highlight.post { place() }
    }

    /** 预创建其余 Tab 并保持隐藏，消除首次切换时的页面构建延迟 */
    private fun prewarmTabs() {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        var changed = false
        tabs.forEach { tab ->
            if (tab.id == selectedTabId) return@forEach
            if (fm.findFragmentByTag(tab.tag) == null) {
                val fragment = tab.factory()
                tx.add(R.id.fragmentContainer, fragment, tab.tag)
                tx.hide(fragment)
                changed = true
            }
        }
        if (changed) tx.commitNowAllowingStateLoss()
        // GONE 的视图不参与测量：置 INVISIBLE 让下一帧先完成一次 measure/layout 预热
        tabs.forEach { tab ->
            fm.findFragmentByTag(tab.tag)?.view?.let { v ->
                if (v.visibility == View.GONE) v.visibility = View.INVISIBLE
            }
        }
    }

    // ---- 滑动联动：容器负责两页平移，这里负责 fragment 显隐与 dock 高亮 ----

    /** 按方向取相邻页并确保其可见；同时记录高亮插值起止位。无相邻页返回 null */
    private fun resolveAdjacentPage(forward: Boolean): View? {
        val idx = tabs.indexOfFirst { it.id == selectedTabId }
        val next = idx + if (forward) 1 else -1
        val fm = supportFragmentManager
        // 方向反转：撤掉上一个预览页，避免残留页留在屏上
        if (dragPreviewIdx >= 0 && dragPreviewIdx != next) hideTabFragment(dragPreviewIdx)
        dragPreviewIdx = -1
        if (next == idx || next !in tabs.indices) return null

        val tab = tabs[next]
        var fragment = fm.findFragmentByTag(tab.tag)
        if (fragment == null) {
            // 极早滑动时 prewarm 还没跑：现场创建
            fragment = tab.factory()
            fm.beginTransaction()
                .add(R.id.fragmentContainer, fragment, tab.tag)
                .commitNowAllowingStateLoss()
        } else if (fragment.isHidden) {
            fm.beginTransaction().show(fragment).commitNowAllowingStateLoss()
        }
        val view = fragment.view ?: return null
        if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE

        dragPreviewIdx = next
        val highlight = binding.navHighlight
        // 起手一次停掉点击 dock 遗留的高亮动画（拖动中每帧 cancel 是无谓开销）
        highlight.animate().cancel()
        dragFromHighlightX = highlight.translationX
        dragToHighlightX = tab.item.root.left.toFloat()
        return view
    }

    /** 联动进度 → dock 高亮在两 Tab 位置间线性插值 */
    private fun onSwipeDragProgress(progress: Float) {
        if (dragPreviewIdx !in tabs.indices) return
        val highlight = binding.navHighlight
        highlight.translationX =
            dragFromHighlightX + (dragToHighlightX - dragFromHighlightX) * progress
    }

    /** settle 结束：完成则无动画提交（视觉已到位），否则撤预览、高亮归位 */
    private fun onSwipeSettled(commit: Boolean) {
        val preview = dragPreviewIdx
        dragPreviewIdx = -1
        if (commit && preview in tabs.indices) {
            selectTab(tabs[preview].id, animate = false)
        } else {
            if (preview in tabs.indices) hideTabFragment(preview)
            tabs.firstOrNull { it.id == selectedTabId }?.item?.root?.let { root ->
                val highlight = binding.navHighlight
                highlight.animate().cancel()
                highlight.translationX = root.left.toFloat()
            }
        }
    }

    /** 隐藏单个 Tab 的 fragment（预览取消/方向反转时用；残留位移由容器 preDraw 清理） */
    private fun hideTabFragment(idx: Int) {
        val fragment = supportFragmentManager.findFragmentByTag(tabs[idx].tag) ?: return
        if (fragment.isHidden) return
        supportFragmentManager.beginTransaction()
            .hide(fragment)
            .commitNowAllowingStateLoss()
    }

    companion object {
        private const val STATE_TAB = "main_selected_tab"
        private const val TAB_HOME = "tab_home"
        private const val TAB_RULES = "tab_rules"
        private const val TAB_SETTINGS = "tab_settings"

        /** 高亮底片平移时长，与 tab_slide_* 动画保持一致 */
        private const val HIGHLIGHT_ANIM_MS = 400L

        /** 与 tab_slide_* 的 fast_out_slow_in 同曲线，底片与页面动画同步起落 */
        private val SMOOTH_INTERPOLATOR = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    }
}
