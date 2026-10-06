package com.palash.librewolfandroid

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/**
 * Settings > Tabs: tab view, close-tab policy, inactive tabs, privacy report
 * and tab groups. Mirrors the reference layout with working behaviour.
 */
class TabsSettingsActivity : AppCompatActivity() {

    private lateinit var store: PrivacyStore
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tabs_settings)
        SystemBars.apply(this)
        store = PrivacyStore(this)
        list = findViewById(R.id.tabs_settings_list)
        findViewById<View>(R.id.tabs_settings_back).setOnClickListener { finish() }
        build()
    }

    private fun section(title: String) {
        list.addView(
            TextView(this).apply {
                text = title
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.librewolf_text))
                setPadding(8, 28, 8, 12)
            },
        )
    }

    private fun divider() {
        list.addView(
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1,
                )
                setBackgroundColor(0xFF2A2A30.toInt())
            },
        )
    }

    private fun radio(label: String, selected: Boolean, onSelect: () -> Unit) {
        val v = layoutInflater.inflate(R.layout.item_engine_radio, list, false)
        EngineRow.bindLabel(v, label, selected)
        v.setOnClickListener { onSelect() }
        list.addView(v)
    }

    private fun switchRow(title: String, subtitle: String?, initial: Boolean, onChange: (Boolean) -> Unit) {
        val v = layoutInflater.inflate(R.layout.item_setting, list, false)
        v.findViewById<TextView>(R.id.set_title).text = title
        val sub = v.findViewById<TextView>(R.id.set_sub)
        if (subtitle.isNullOrEmpty()) {
            sub.visibility = View.GONE
        } else {
            sub.text = subtitle
        }
        v.findViewById<ImageView>(R.id.set_icon).visibility = View.GONE
        val sw = v.findViewById<SwitchCompat>(R.id.set_switch)
        sw.visibility = View.VISIBLE
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        v.setOnClickListener { sw.toggle() }
        list.addView(v)
    }

    private fun build() {
        list.removeAllViews()

        section(getString(R.string.tab_view))
        radio(getString(R.string.view_list), store.tabViewColumns == 1) {
            store.tabViewColumns = 1
            build()
        }
        radio(getString(R.string.view_grid), store.tabViewColumns == 2) {
            store.tabViewColumns = 2
            build()
        }
        divider()

        section(getString(R.string.close_tabs))
        val days = store.closeTabsAfterDays
        radio(getString(R.string.never), days == 0) { store.closeTabsAfterDays = 0; build() }
        radio(getString(R.string.after_one_day), days == 1) { store.closeTabsAfterDays = 1; build() }
        radio(getString(R.string.after_one_week), days == 7) { store.closeTabsAfterDays = 7; build() }
        radio(getString(R.string.after_one_month), days == 30) { store.closeTabsAfterDays = 30; build() }
        divider()

        switchRow(
            getString(R.string.move_old_tabs),
            getString(R.string.move_old_tabs_sub),
            store.moveOldTabsToInactive,
        ) { store.moveOldTabsToInactive = it }
        divider()

        section(getString(R.string.privacy_report))
        switchRow(getString(R.string.enable_privacy_report), null, store.privacyReport) {
            store.privacyReport = it
        }
        switchRow(getString(R.string.enable_tab_groups), null, store.tabGroups) {
            store.tabGroups = it
        }
    }
}
