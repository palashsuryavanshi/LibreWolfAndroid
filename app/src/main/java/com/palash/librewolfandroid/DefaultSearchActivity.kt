package com.palash.librewolfandroid

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.doAfterTextChanged
import java.net.URLEncoder

/**
 * Settings > Search > Default search engine: the normal browsing list, and a
 * separate list for private browsing that is hidden while the switch above it
 * says the two should share an engine.
 */
class DefaultSearchActivity : AppCompatActivity() {

    private lateinit var store: PrivacyStore
    private lateinit var normalSection: LinearLayout
    private lateinit var privateSection: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_default_search)
        SystemBars.apply(this)
        store = PrivacyStore(this)
        normalSection = findViewById(R.id.normal_section)
        privateSection = findViewById(R.id.private_section)
        findViewById<View>(R.id.default_search_back).setOnClickListener { finish() }
        findViewById<View>(R.id.add_engine).setOnClickListener { addEngineDialog() }
        build()
    }

    override fun onResume() {
        super.onResume()
        if (::normalSection.isInitialized) build()
    }

    private fun sectionLabel(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(getColor(R.color.librewolf_text))
        setPadding(32, 28, 16, 12)
    }

    private fun allEngines(): List<LibreWolfDefaults.SearchEngine> =
        LibreWolfDefaults.SEARCH_ENGINES + store.customEngines

    private fun engineRow(
        engine: LibreWolfDefaults.SearchEngine,
        selected: Boolean,
        onSelect: () -> Unit,
    ): View {
        val v = layoutInflater.inflate(R.layout.item_engine_radio, normalSection, false)
        v.findViewById<TextView>(R.id.engine_name).text = engine.name
        // No engine logos/badges: just the radio and the name.
        v.findViewById<TextView>(R.id.engine_badge).visibility = View.GONE
        v.findViewById<RadioButton>(R.id.engine_radio).isChecked = selected
        val more = v.findViewById<ImageButton>(R.id.engine_more)
        if (selected && store.customEngines.any { it.name == engine.name }) {
            more.visibility = View.VISIBLE
            more.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(engine.name)
                    .setMessage(engine.queryUrl)
                    .setPositiveButton(getString(R.string.save)) { _, _ ->
                        addEngineDialog(engine)
                    }
                    .setNeutralButton(getString(R.string.delete)) { _, _ -> removeEngine(engine) }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }
        v.setOnClickListener { onSelect() }
        return v
    }

    private fun build() {
        normalSection.removeAllViews()
        privateSection.removeAllViews()
        val engines = allEngines()

        normalSection.addView(
            sectionLabel(getString(R.string.normal_default_engine)),
        )
        val selectedNormal = store.customEngine ?: store.searchEngineName
        engines.forEach { engine ->
            normalSection.addView(
                engineRow(engine, engine.name == selectedNormal) {
                    store.customEngine = null
                    store.searchEngineName = engine.name
                    build()
                },
            )
        }

        privateSection.addView(View(this).apply { setBackgroundColor(0xFF2A2A30.toInt()) })
        privateSection.addView(sameEngineSwitch())
        // Turning the switch on means private windows follow the normal engine,
        // so there is nothing left to choose and the list is not built at all.
        if (store.privateUseSameEngine) return
        privateSection.addView(sectionLabel(getString(R.string.private_default_engine)))
        // A built-in private engine is stored by name in the same slot a custom
        // one uses, so both have to be considered or no row is ever shown as
        // selected.
        val selectedPrivate = store.privateCustomEngine ?: store.privateSearchEngineName
        engines.forEach { engine ->
            privateSection.addView(
                engineRow(engine, engine.name == selectedPrivate) {
                    store.privateUseSameEngine = false
                    store.privateCustomEngine = null
                    store.privateSearchEngineName = engine.name
                    build()
                },
            )
        }
    }

    /**
     * The switch that decides whether private windows follow the normal engine.
     *
     * The switch only shows the stored value and the row owns the write, the
     * same arrangement the settings framework uses. A control that writes on its
     * own change cannot be told apart from a change the platform made, and
     * Android restores a switch's state when the activity is re-created.
     */
    private fun sameEngineSwitch(): View {
        val row = layoutInflater.inflate(R.layout.item_setting, privateSection, false)
        row.findViewById<TextView>(R.id.set_title).text = getString(R.string.same_as_normal)
        row.findViewById<TextView>(R.id.set_sub).visibility = View.GONE
        row.findViewById<View>(R.id.set_icon).visibility = View.GONE
        val toggle = row.findViewById<SwitchCompat>(R.id.set_switch)
        toggle.visibility = View.VISIBLE
        toggle.isClickable = false
        toggle.isFocusable = false
        toggle.isChecked = store.privateUseSameEngine
        row.setOnClickListener {
            store.privateUseSameEngine = !store.privateUseSameEngine
            build()
        }
        return row
    }

    private fun addEngineDialog(existing: LibreWolfDefaults.SearchEngine? = null) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val name = EditText(this).apply {
            hint = getString(R.string.engine_name)
            setText(existing?.name.orEmpty())
        }
        val url = EditText(this).apply {
            hint = getString(R.string.engine_url)
            setText(existing?.queryUrl.orEmpty())
        }
        box.addView(name)
        box.addView(url)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_search_engine))
            .setView(box)
            .setPositiveButton(getString(R.string.save), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                val u = url.text.toString().trim()
                if (n.isEmpty() || !u.contains("%s")) {
                    url.error = getString(R.string.engine_url_error)
                    return@setOnClickListener
                }
                val list = store.customEngines.toMutableList()
                list.removeAll { it.name == existing?.name }
                list.add(LibreWolfDefaults.SearchEngine(n, u))
                store.customEngines = list
                dialog.dismiss()
                build()
            }
        }
        dialog.show()
    }

    private fun removeEngine(engine: LibreWolfDefaults.SearchEngine) {
        store.customEngines = store.customEngines.filter { it.name != engine.name }
        if (store.customEngine == engine.name) store.customEngine = null
        if (store.privateCustomEngine == engine.name) store.privateCustomEngine = null
        build()
    }

    @Suppress("unused")
    private fun encode(v: String) = URLEncoder.encode(v, "UTF-8")
}
