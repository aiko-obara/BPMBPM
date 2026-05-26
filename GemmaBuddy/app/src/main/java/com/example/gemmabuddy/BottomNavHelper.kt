package com.example.gemmabuddy

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

enum class NavTab { HOME, BUDDIES, HISTORY, SETTINGS }

fun Activity.setupBottomNav(activeTab: NavTab) {
    val nav = findViewById<View>(R.id.bottom_nav_container) ?: return

    fun setActive(layout: LinearLayout, icon: TextView, label: TextView) {
        icon.setTextColor(getColor(R.color.nr_primary))
        label.setTextColor(getColor(R.color.nr_primary))
        layout.setBackgroundColor(getColor(android.R.color.transparent))
    }

    val tabs = listOf(
        Triple(NavTab.HOME, R.id.nav_home, R.id.nav_home_icon to R.id.nav_home_label),
        Triple(NavTab.BUDDIES, R.id.nav_buddies, R.id.nav_buddies_icon to R.id.nav_buddies_label),
        Triple(NavTab.HISTORY, R.id.nav_history, R.id.nav_history_icon to R.id.nav_history_label),
        Triple(NavTab.SETTINGS, R.id.nav_settings, R.id.nav_settings_icon to R.id.nav_settings_label)
    )

    tabs.forEach { (tab, layoutId, ids) ->
        val layout = nav.findViewById<LinearLayout>(layoutId) ?: return@forEach
        val icon = nav.findViewById<TextView>(ids.first) ?: return@forEach
        val label = nav.findViewById<TextView>(ids.second) ?: return@forEach

        if (tab == activeTab) {
            setActive(layout, icon, label)
        }

        layout.setOnClickListener {
            if (tab == activeTab) return@setOnClickListener
            val intent = when (tab) {
                NavTab.HOME -> Intent(this, MainActivity::class.java)
                NavTab.BUDDIES -> Intent(this, CharacterGenerationActivity::class.java)
                NavTab.HISTORY -> Intent(this, MemoryActivity::class.java)
                NavTab.SETTINGS -> Intent(this, SettingsActivity::class.java)
            }
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            overridePendingTransition(0, 0)
        }
    }
}
