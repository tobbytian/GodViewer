package com.godviewer.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.godviewer.app.databinding.ActivityMainBinding
import com.godviewer.app.databinding.ItemBottomNavBinding
import com.godviewer.app.ui.host.GuideFragment
import com.godviewer.app.ui.host.HomeFragment
import com.godviewer.app.ui.host.HostActivity
import com.godviewer.app.ui.host.RulesFragment
import com.godviewer.app.ui.host.SettingsFragment
import com.godviewer.app.util.HostControlBridge
import com.godviewer.app.util.HostControlNotifier

class MainActivity : HostActivity() {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private var selectedTabId = R.id.nav_home

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        HostControlNotifier.refresh(this)
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
        HostControlNotifier.refresh(this)
        setupTabs()

        val openSettings = intent?.getBooleanExtra(SettingsFragment.EXTRA_OPEN_SETTINGS, false) == true
        selectedTabId = when {
            openSettings -> R.id.nav_settings
            savedInstanceState != null -> savedInstanceState.getInt(STATE_TAB, R.id.nav_home)
            else -> R.id.nav_home
        }
        selectTab(selectedTabId, animate = false)
    }

    override fun onResume() {
        super.onResume()
        // Opening the host app means previous target is no longer the active control context.
        HostControlBridge.clearTargetState(this)
        HostControlNotifier.refresh(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, selectedTabId)
    }

    private fun maybeRequestNotificationPermission() {
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
                id = R.id.nav_guide,
                tag = TAB_GUIDE,
                iconRes = R.drawable.ic_nav_guide_24,
                labelRes = R.string.nav_guide,
                item = binding.navGuide,
                factory = { GuideFragment() },
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
            tab.item.labelView.setText(tab.labelRes)
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
            val transaction = fm.beginTransaction()
            if (animate) {
                transaction.setCustomAnimations(R.anim.tab_fade_in, R.anim.tab_fade_out)
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
        refreshTabVisuals()
    }

    private fun refreshTabVisuals() {
        val selectedColor = ContextCompat.getColor(this, R.color.home_title_text)
        val unselectedColor = ContextCompat.getColor(this, R.color.home_nav_item_unselected)
        tabs.forEach { tab ->
            val selected = tab.id == selectedTabId
            tab.item.iconHighlight.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            tab.item.iconView.setColorFilter(if (selected) selectedColor else unselectedColor)
            tab.item.labelView.setTextColor(if (selected) selectedColor else unselectedColor)
        }
    }

    companion object {
        private const val STATE_TAB = "main_selected_tab"
        private const val TAB_HOME = "tab_home"
        private const val TAB_GUIDE = "tab_guide"
        private const val TAB_RULES = "tab_rules"
        private const val TAB_SETTINGS = "tab_settings"
    }
}
