package com.palash.librewolfandroid

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/**
 * Settings > Search, mirroring the reference layout: default engine, engine
 * management, widget, suggestion groups and address-bar preferences.
 * The "search synchronised tabs" row is intentionally absent - LibreWolf
 * disables Firefox Sync.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var store: PrivacyStore
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        SystemBars.apply(this)
        store = PrivacyStore(this)
        list = findViewById(R.id.search_list)
        findViewById<View>(R.id.search_back).setOnClickListener { finish() }
        build()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) build()
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
        list.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1,
            ).apply { setPadding(0, 0, 0, 0) }
            setBackgroundColor(0xFF2A2A30.toInt())
        })
    }

    private fun row(title: String, subtitle: String? = null, onClick: () -> Unit) {
        val v = layoutInflater.inflate(R.layout.item_setting, list, false)
        v.findViewById<TextView>(R.id.set_title).text = title
        val sub = v.findViewById<TextView>(R.id.set_sub)
        if (subtitle.isNullOrEmpty()) sub.visibility = View.GONE else sub.text = subtitle
        v.findViewById<ImageView>(R.id.set_icon).visibility = View.GONE
        v.findViewById<SwitchCompat>(R.id.set_switch).visibility = View.GONE
        v.setOnClickListener { onClick() }
        list.addView(v)
    }

    private fun switchRow(title: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        val v = layoutInflater.inflate(R.layout.item_setting, list, false)
        v.findViewById<TextView>(R.id.set_title).text = title
        v.findViewById<TextView>(R.id.set_sub).visibility = View.GONE
        v.findViewById<ImageView>(R.id.set_icon).visibility = View.GONE
        val sw = v.findViewById<SwitchCompat>(R.id.set_switch)
        sw.visibility = View.VISIBLE
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        v.setOnClickListener { sw.toggle() }
        list.addView(v)
    }

    /**
     * A switch row that can be turned off because the feature is not there.
     *
     * [switchRow] above renders a live-looking toggle. Using it for something the
     * app cannot do produced the worst of both: the switch looked and behaved as
     * enabled, tapping it raised a "not available" dialog, and the rebuild put it
     * back where it started. To the user the control was broken rather than
     * unavailable, which is a different thing and not the one that was true.
     */
    private fun unavailableRow(title: String, subtitle: String) {
        val v = layoutInflater.inflate(R.layout.item_setting, list, false)
        v.findViewById<TextView>(R.id.set_title).text = title
        v.findViewById<TextView>(R.id.set_sub).text = subtitle
        v.findViewById<ImageView>(R.id.set_icon).visibility = View.GONE
        v.findViewById<SwitchCompat>(R.id.set_switch).visibility = View.GONE
        // Dimmed and non-clickable, so the state is visible before the tap rather
        // than discovered by tapping.
        v.isEnabled = false
        v.alpha = 0.5f
        list.addView(v)
    }

    private fun build() {
        list.removeAllViews()

        val engines = LibreWolfDefaults.SEARCH_ENGINES
        row(getString(R.string.default_search_engine), store.engineFor(false).name) {
            startActivity(Intent(this, DefaultSearchActivity::class.java))
        }
        row(
            getString(R.string.manage_engines),
            getString(R.string.manage_engines_sub),
        ) { startActivity(Intent(this, SearchEnginesActivity::class.java)) }
        unavailableRow(
            getString(R.string.home_widget),
            getString(R.string.not_available),
        )
        divider()

        section(getString(R.string.sec_suggestions))
        switchRow(getString(R.string.show_suggestions), store.searchSuggestions) {
            store.searchSuggestions = it
        }
        switchRow(getString(R.string.show_in_private), store.searchSuggestionsPrivate) {
            store.searchSuggestionsPrivate = it
        }
        switchRow(getString(R.string.show_recent), store.showRecentSearches) {
            store.showRecentSearches = it
        }
        divider()

        section(getString(R.string.sec_address_suggest))
        switchRow(getString(R.string.browsing_history), store.searchHistory) { store.searchHistory = it }
        switchRow(getString(R.string.suggest_bookmarks), store.searchBookmarks) { store.searchBookmarks = it }
        divider()

        section(getString(R.string.sec_address_prefs))
        switchRow(getString(R.string.clipboard_suggestions), store.showClipboardSuggestions) {
            store.showClipboardSuggestions = it
        }
        switchRow(getString(R.string.show_voice_search), store.voiceSearch) {
            store.voiceSearch = it
        }
        switchRow(getString(R.string.autocomplete_urls), store.autocompleteUrls) {
            store.autocompleteUrls = it
        }
    }
}
