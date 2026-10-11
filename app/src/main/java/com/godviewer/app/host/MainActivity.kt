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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        maybeRequestNotificationPermission()
        EntryControlUi.refresh(this)
        setupTabs()

        // 内容区左右滑动切换 Tab（仅水平滑动占优时触发，不影响页内垂直滚动）
        binding.fragmentContainer.onSwipe = { forward ->
            val idx = tabs.indexOfFirst { it.id == selectedTabId }
            val next = idx + if (forward) 1 else -1
            if (next != idx && next in tabs.indices) selectTab(tabs[next].id, animate = true)
        }

        val openSettings = intent?.getBooleanExtra(SettingsFragment.EXTRA_OPEN_SETTINGS, false) == true
        selectedTabId = when {
            openSettings -> R.id.nav_settings
            savedInstanceState != null -> savedInstanceState.getInt(STATE_TAB, R.id.nav_home)
            else -> R.id.nav_home
        }
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
        val currentVisible = tabs
            .mapNotNull { fm.findFragmentByTag(it.tag) }
            .firstOrNull { it.isVisible && !it.isHidden }

        if (currentVisible?.tag != target.tag) {
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
