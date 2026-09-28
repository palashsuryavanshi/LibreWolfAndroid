package com.palash.librewolfandroid

import android.Manifest
import android.app.DownloadManager
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Renders any screen from [SettingsScreens].
 *
 * The activity is deliberately dumb: it draws sections and components, runs the
 * search, and gets out of the way. A new settings page is a declaration in
 * [SettingsScreens], not a new activity, so navigation, insets, theming and the
 * back stack are handled once.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var ctx: SettingsContext
    private lateinit var list: LinearLayout
    private lateinit var searchInput: EditText
    private var screenId: String = SettingsScreens.ROOT
    private var searchHits: List<SettingsSearchHit> = emptyList()

    /** The site the compatibility page is about, when one was passed in. */
    private var site: String? = null

    private val roleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (screenId == SettingsScreens.ROOT) render()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // A deliberate request from Settings clears the "already asked"
            // bookkeeping, so the outcome is reported rather than swallowed.
            Toast.makeText(
                this,
                if (granted) R.string.onboarding_notifications_granted else R.string.onboarding_notifications_denied,
                Toast.LENGTH_LONG,
            ).show()
            if (screenId == SettingsScreens.PRIVACY_PERMISSIONS) render()
        }

    /**
     * Asks Android for the notification permission on the user's behalf, from a
     * place the user chose rather than as a side effect of a site request.
     *
     * Once Android has permanently denied it there is no dialog left to launch,
     * so the app's own permissions page is opened instead of a button that would
     * silently do nothing.
     */
    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.onboarding_notifications_already, Toast.LENGTH_SHORT).show()
            return
        }
        val store = PrivacyStore(this)
        if (store.notificationPermissionAsked && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            Toast.makeText(this, R.string.onboarding_notifications_denied, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
            return
        }
        store.notificationPermissionAsked = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        SystemBars.apply(this)
        ctx = SettingsContext(this)
        // A component that changes a value asks for this, so the row shows the
        // new value instead of the one it had when the screen was built.
        ctx.onNeedsRender = { render() }
        list = findViewById(R.id.settings_list)
        searchInput = findViewById(R.id.settings_search)
        screenId = intent.getStringExtra(EXTRA_SCREEN) ?: SettingsScreens.ROOT
        site = intent.getStringExtra(EXTRA_SITE)
        findViewById<View>(R.id.settings_back).setOnClickListener {
            if (searchInput.text.isNotEmpty()) {
                searchInput.setText("")
            } else {
                finish()
            }
        }
        findViewById<TextView>(R.id.settings_title).text =
            currentScreen()?.title ?: getString(R.string.settings)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render()
        })
        if (screenId != SettingsScreens.ROOT) searchInput.visibility = View.GONE
        render()
    }

    private fun currentScreen(): Screen? =
        SettingsScreens.render(screenId, site) ?: SettingsScreens.screen(SettingsScreens.ROOT)

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        screenId = intent.getStringExtra(EXTRA_SCREEN) ?: SettingsScreens.ROOT
        site = intent.getStringExtra(EXTRA_SITE)
        searchInput.visibility = if (screenId == SettingsScreens.ROOT) View.VISIBLE else View.GONE
        searchInput.setText("")
        findViewById<TextView>(R.id.settings_title).text =
            currentScreen()?.title ?: getString(R.string.settings)
        render()
    }

    // ---- rendering ----

    private fun render() {
        list.removeAllViews()
        val query = searchInput.text?.toString().orEmpty()
        if (query.isNotBlank()) {
            renderSearch(query)
            return
        }
        val screen = currentScreen() ?: return
        screen.build(ctx).forEach { section ->
            if (section.title != null || section.subtitle != null) {
                list.addView(sectionHeader(section))
            }
            if (section.subtitle != null && section.title != null) {
                list.addView(sectionSubtitle(section))
            }
            section.settings.forEach { renderSetting(ctx, list, it) }
            if (section.footer != null) {
                list.addView(footerView(section.footer))
            }
        }
    }

    private fun renderSearch(query: String) {
        if (searchHits.isEmpty()) searchHits = SettingsIndex.build(ctx)
        val results = SettingsIndex.search(searchHits, query)
        if (results.isEmpty()) {
            list.addView(
                TextView(this).apply {
                    text = getString(R.string.settings_search_none, query)
                    textSize = 16f
                    setTextColor(getColor(R.color.librewolf_grey))
                    setPadding(16, 32, 16, 32)
                },
            )
            return
        }
        // Grouped by the screen a result lives on, so a query that matches
        // several things in one place reads as one place.
        results.groupBy { it.screenId }.forEach { (id, hits) ->
            val screen = SettingsScreens.screen(id)
            list.addView(
                TextView(this).apply {
                    text = screen?.title.orEmpty()
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(getColor(R.color.librewolf_accent))
                    setPadding(16, 24, 16, 6)
                },
            )
            hits.forEach { hit ->
                val view = layoutInflater.inflate(R.layout.item_setting, list, false)
                view.findViewById<TextView>(R.id.set_title).text = hit.setting.title
                val sub = view.findViewById<TextView>(R.id.set_sub)
                val detail = hit.setting.subtitle ?: hit.setting.valueOrNull()
                if (detail.isNullOrEmpty()) sub.visibility = View.GONE else sub.text = detail
                view.findViewById<android.widget.ImageView>(R.id.set_icon).visibility = View.GONE
                view.findViewById<View>(R.id.set_switch).visibility = View.GONE
                view.setOnClickListener { ctx.open(id) }
                list.addView(view)
            }
        }
    }

    private fun sectionHeader(section: Section): View = TextView(this).apply {
        text = section.title.orEmpty()
        textSize = 16f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(getColor(R.color.librewolf_text))
        setPadding(16, 32, 16, 0)
    }

    private fun sectionSubtitle(section: Section): View = TextView(this).apply {
        text = section.subtitle.orEmpty()
        textSize = 14f
        setTextColor(getColor(R.color.librewolf_grey))
        setPadding(16, 4, 16, 8)
    }

    private fun footerView(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(getColor(R.color.librewolf_grey))
        setPadding(16, 20, 16, 8)
    }

    companion object {
        const val EXTRA_SCREEN = "screen"

        /** The site the compatibility page is about, when reached from the menu. */
        const val EXTRA_SITE = "site"
    }
}

/**
 * The value a component currently holds, for a search result.
 *
 * Captured when the screen was built, so a result reads "Cookies & site data:
 * Block third-party cookies" without the index needing a context of its own.
 */
private fun Setting.valueOrNull(): String? = when (this) {
    is InfoSetting -> value
    is ChoiceSetting -> current
    is TextSetting -> current
    is SliderSetting -> format(current)
    is ToggleSetting -> if (current) "On" else "Off"
    else -> null
}
