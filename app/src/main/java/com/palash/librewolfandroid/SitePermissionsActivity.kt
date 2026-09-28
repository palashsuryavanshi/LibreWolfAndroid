package com.palash.librewolfandroid

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission

/**
 * Website permissions, per capability.
 *
 * Without an extra this is the hub: every capability the browser tracks, with
 * how many sites hold a decision about it. With [EXTRA_PERMISSION] it is the
 * list for one capability, searchable, where a site can be set to allow, ask or
 * block.
 *
 * The list is built from both stores (see [SitePermissionIndex]) because a
 * decision can exist in the engine and not in ours, or the other way round, and
 * showing only one of them is how a site keeps a permission the screen claimed
 * to have cleared.
 */
class SitePermissionsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private var search: EditText? = null
    private var permission: Int? = null
    private var entries: List<SitePermissionIndex.Entry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permission = intent.getIntExtra(EXTRA_PERMISSION, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        root.addView(TextView(this).apply {
            text = title()
            textSize = 24f
            setTextColor(getColor(R.color.librewolf_text))
        })
        if (permission != null) {
            search = EditText(this).apply {
                hint = getString(R.string.search_sites)
                setSingleLine(true)
                textSize = 16f
                setPadding(0, 16, 0, 8)
            }.also { field ->
                field.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) = render()
                })
                root.addView(field)
            }
        }
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 24)
        }
        scroll.addView(list)
        root.addView(scroll)
        setContentView(root)
        SystemBars.apply(this)
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun title(): String = permission?.let { SitePermissionIndex.label(this, it) }
        ?: getString(R.string.site_permissions)

    private fun load() {
        entries = SitePermissionIndex.entries(this)
        render()
    }

    private fun render() {
        list.removeAllViews()
        val selected = permission
        if (selected == null) {
            renderHub()
            return
        }
        val query = search?.text?.toString()?.trim()?.lowercase().orEmpty()
        val rows = entries
            .filter { it.permission == selected }
            .filter { query.isEmpty() || it.authority.contains(query) }
        if (rows.isEmpty()) {
            list.addView(
                TextView(this).apply {
                    text = if (query.isEmpty()) getString(R.string.no_site_permissions) else getString(R.string.no_site_match, query)
                    textSize = 16f
                    setTextColor(getColor(R.color.librewolf_grey))
                    setPadding(0, 24, 0, 24)
                },
            )
        } else {
            rows.forEach { entry -> list.addView(siteRow(entry)) }
        }
        // "Forget" belongs on the *site* rows, not here: forgetting a decision
        // is a statement about one site, and a button that quietly cleared
        // every site holding this capability was the wrong affordance for it.
        rows.firstOrNull()?.let { first ->
            list.addView(
                TextView(this).apply {
                    text = getString(R.string.tap_site_to_change)
                    textSize = 13f
                    setTextColor(getColor(R.color.librewolf_grey))
                    setPadding(0, 24, 0, 4)
                },
            )
            if (first.fromEngine) {
                list.addView(
                    TextView(this).apply {
                        text = getString(R.string.engine_holds_copy)
                        textSize = 13f
                        setTextColor(getColor(R.color.librewolf_grey))
                    },
                )
            }
        }
    }

    private fun renderHub() {
        SitePermissionIndex.capabilities().forEach { capability ->
            val count = entries.count { it.permission == capability }
            val row = TextView(this).apply {
                text = SitePermissionIndex.label(this@SitePermissionsActivity, capability)
                textSize = 18f
                setTextColor(getColor(R.color.librewolf_text))
                setPadding(0, 20, 0, 6)
            }
            list.addView(row)
            list.addView(
                TextView(this).apply {
                    text = if (count == 0) getString(R.string.no_sites) else resources.getQuantityString(
                        R.plurals.sites_with_decision,
                        count,
                        count,
                    )
                    textSize = 14f
                    setTextColor(getColor(R.color.librewolf_grey))
                },
            )
            row.setOnClickListener {
                startActivity(
                    Intent(this@SitePermissionsActivity, SitePermissionsActivity::class.java)
                        .putExtra(EXTRA_PERMISSION, capability),
                )
            }
            row.setOnLongClickListener {
                clearCapabilityDialog(capability)
                true
            }
        }
        list.addView(
            Button(this).apply {
                text = getString(R.string.reset_permissions)
                setOnClickListener { confirmReset() }
            },
        )
    }

    private fun siteRow(entry: SitePermissionIndex.Entry): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 14, 0, 14)
        }
        container.addView(
            TextView(this).apply {
                text = entry.authority
                textSize = 17f
                setTextColor(getColor(R.color.librewolf_text))
            },
        )
        container.addView(
            TextView(this).apply {
                // Saying where the decision is kept matters: a site can hold one
                // in the engine as well as here, and a change made on this screen
                // clears the engine's copy so it cannot act on the old answer.
                text = SitePermissionIndex.valueLabel(this@SitePermissionsActivity, entry.value) +
                    if (entry.fromEngine) getString(R.string.also_in_engine) else ""
                textSize = 14f
                setTextColor(getColor(R.color.librewolf_grey))
            },
        )
        container.setOnClickListener { decideDialog(entry) }
        return container
    }

    private fun decideDialog(entry: SitePermissionIndex.Entry) {
        val capability = permission ?: return
        val options = arrayOf(
            getString(R.string.allowed),
            getString(R.string.ask_every_time),
            getString(R.string.deny),
            getString(R.string.forget_this_site),
        )
        val values = intArrayOf(
            ContentPermission.VALUE_ALLOW,
            ContentPermission.VALUE_PROMPT,
            ContentPermission.VALUE_DENY,
            ContentPermission.VALUE_PROMPT,
        )
        AlertDialog.Builder(this)
            .setTitle(entry.authority)
            .setItems(options) { _, which ->
                if (which == 3) {
                    SitePermissionIndex.clearOrigin(this, entry.authority)
                    Toast.makeText(this, R.string.site_forgotten, Toast.LENGTH_SHORT).show()
                } else {
                    SitePermissionIndex.set(this, entry.authority, capability, values[which])
                }
                load()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearCapabilityDialog(capability: Int) {
        val sites = entries.filter { it.permission == capability }.map { it.authority }.distinct()
        if (sites.isEmpty()) {
            Toast.makeText(this, R.string.no_site_permissions, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reset_capability_title, SitePermissionIndex.label(this, capability)))
            .setMessage(getString(R.string.reset_capability_message, sites.size))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset) { _, _ ->
                sites.forEach { SitePermissionIndex.clearOrigin(this, it) }
                load()
            }
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_permissions)
            .setMessage(R.string.reset_permissions_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset) { _, _ ->
                SitePermissionIndex.clearAll(this)
                Toast.makeText(this, R.string.permissions_reset, Toast.LENGTH_SHORT).show()
                load()
            }
            .show()
    }

    companion object {
        const val EXTRA_PERMISSION = "permission"
    }
}
