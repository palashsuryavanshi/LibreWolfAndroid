package com.palash.librewolfandroid

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Settings > Search > Manage alternative search engines. */
class SearchEnginesActivity : AppCompatActivity() {

    private lateinit var store: PrivacyStore
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search_engines)
        SystemBars.apply(this)
        store = PrivacyStore(this)
        list = findViewById(R.id.engines_list)
        findViewById<View>(R.id.engines_back).setOnClickListener { finish() }
        build()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) build()
    }

    private fun build() {
        list.removeAllViews()
        val engines = LibreWolfDefaults.SEARCH_ENGINES + store.customEngines
        val current = store.engineFor(false)
        engines.forEach { engine ->
            val v = layoutInflater.inflate(R.layout.item_setting, list, false)
            v.findViewById<TextView>(R.id.set_title).text = engine.name
            val sub = v.findViewById<TextView>(R.id.set_sub)
            if (engine.name == current.name) {
                sub.visibility = View.VISIBLE
                sub.text = getString(R.string.default_badge)
            } else {
                sub.visibility = View.GONE
            }
            v.findViewById<ImageView>(R.id.set_icon).visibility = View.GONE
            v.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.set_switch).visibility = View.GONE
            v.setOnClickListener {
                store.customEngine = null
                store.searchEngineName = engine.name
                build()
            }
            list.addView(v)
        }
    }
}
