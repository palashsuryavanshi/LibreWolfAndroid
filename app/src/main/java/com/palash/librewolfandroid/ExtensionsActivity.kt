package com.palash.librewolfandroid

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

data class ExtInfo(val id: String, val name: String, val sub: String)

/** One row in the installable list: tapping it installs. */
data class AddonRow(
    val addon: LibreWolfDefaults.Addon,
    val installed: Boolean,
    val onClick: () -> Unit,
)

class ExtensionsActivity : AppCompatActivity() {

    private lateinit var adapter: ExtensionsAdapter
    private lateinit var addonsAdapter: AddonsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_extensions)
        SystemBars.apply(this)

        adapter = ExtensionsAdapter(
            emptyList(),
            onRemove = { uninstall(it) },
        )
        addonsAdapter = AddonsAdapter(emptyList())
        findViewById<RecyclerView>(R.id.extensions_available_list).apply {
            layoutManager = LinearLayoutManager(this@ExtensionsActivity)
            adapter = this@ExtensionsActivity.addonsAdapter
        }
        findViewById<RecyclerView>(R.id.extensions_list).apply {
            layoutManager = LinearLayoutManager(this@ExtensionsActivity)
            adapter = this@ExtensionsActivity.adapter
        }
        findViewById<View>(R.id.extensions_back).setOnClickListener { finish() }
        wireDelegates()
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun controller(): WebExtensionController? {
        val rt = MainActivity.runtimeRef() ?: return null
        return rt.webExtensionController
    }

    private inner class AddonDelegate : WebExtensionController.AddonManagerDelegate {
        override fun onInstalled(extension: WebExtension) {
            runOnUiThread {
                Toast.makeText(
                    this@ExtensionsActivity,
                    installLabel(extension),
                    Toast.LENGTH_SHORT,
                ).show()
                reload()
            }
        }

        override fun onUninstalled(extension: WebExtension) {
            runOnUiThread { reload() }
        }

        override fun onInstallationFailed(
            extension: WebExtension?,
            error: WebExtension.InstallException,
        ) {
            runOnUiThread {
                Toast.makeText(
                    this@ExtensionsActivity,
                    getString(R.string.ext_install_failed) + ": " + error.message,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun installLabel(extension: WebExtension): String {
        val name = extension.metaData.name ?: extension.id
        return name + " installed"
    }

    private fun wireDelegates() {
        val c = controller() ?: return
        try {
            c.promptDelegate = InstallPromptHandler()
        } catch (_e: Exception) {
        }
        try {
            c.setAddonManagerDelegate(AddonDelegate())
        } catch (_e: Exception) {
        }
    }

    /**
     * Takes both delegates back off the runtime.
     *
     * The controller belongs to the process-wide GeckoRuntime, not to this
     * screen, and both delegates are inner classes holding this activity. They
     * were installed on entry and never removed, so after leaving the screen an
     * add-on installing in the background still fired a Toast and re-resolved
     * views on a destroyed activity. Only clear them when this screen is the one
     * that installed them: another instance may already own the controller.
     */
    override fun onDestroy() {
        if (installedDelegates) {
            val c = controller()
            try {
                c?.setAddonManagerDelegate(null)
            } catch (_e: Exception) {
            }
            try {
                c?.promptDelegate = null
            } catch (_e: Exception) {
            }
            installedDelegates = false
        }
        super.onDestroy()
    }

    /** True while this instance owns the runtime's add-on delegates. */
    private var installedDelegates = false

    private inner class InstallPromptHandler : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(
            extension: WebExtension,
            permissions: Array<String>,
            origins: Array<String>,
            dataCollectionPermissions: Array<String>,
        ): GeckoResult<WebExtension.PermissionPromptResponse>? {
            val result = GeckoResult<WebExtension.PermissionPromptResponse>()
            val perms = (permissions.toList() + origins).distinct().take(12)
            val msg = buildString {
                append(extension.metaData.name ?: extension.id)
                if (perms.isNotEmpty()) {
                    append("\n\n")
                    append(perms.joinToString("\n- ", prefix = "- "))
                }
            }
            runOnUiThread {
                AlertDialog.Builder(this@ExtensionsActivity)
                    .setTitle(getString(R.string.ext_permissions))
                    .setMessage(msg)
                    .setPositiveButton(getString(R.string.allow)) { _, _ ->
                        result.complete(
                            WebExtension.PermissionPromptResponse(true, true, true),
                        )
                    }
                    .setNegativeButton(getString(R.string.deny)) { _, _ ->
                        result.complete(
                            WebExtension.PermissionPromptResponse(false, false, false),
                        )
                    }
                    .setOnCancelListener {
                        result.complete(
                            WebExtension.PermissionPromptResponse(false, false, false),
                        )
                    }
                    .show()
            }
            return result
        }
    }

    /**
     * Installs one add-on from its "latest" AMO URL.
     *
     * The confirmation shows the URL because this is a fetch from the network
     * that hands the engine a file to run with page access, and a user who can
     * see where it comes from can decide that is not acceptable. The failure is
     * reported with the engine's own message, because the common case is a 404
     * from a slug that no longer exists and the generic string would hide it.
     */
    private fun confirmInstall(addon: LibreWolfDefaults.Addon) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.install_addon, addon.name))
            .setMessage(addon.url)
            .setPositiveButton(getString(R.string.install)) { _, _ -> installAddon(addon) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun installAddon(addon: LibreWolfDefaults.Addon) {
        val c = controller()
        if (c == null) {
            Toast.makeText(this, getString(R.string.not_available), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.ext_installing), Toast.LENGTH_SHORT).show()
        try {
            c.install(addon.url).accept({ _ -> }, { _ -> })
        } catch (e: Exception) {
            Toast.makeText(this, "${getString(R.string.ext_install_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun reload() {
        val c = controller() ?: return
        try {
            c.list().accept(
                { list: List<WebExtension>? ->
                    val installed = list ?: emptyList()
                    // An add-on already present is matched by name, because the
                    // engine reports its own id (a UUID for a WebExtension) and
                    // the AMO slug has no relationship to it.
                    val installedNames = installed.mapNotNull { it.metaData.name }.toSet()
                    runOnUiThread {
                        addonsAdapter.update(
                            LibreWolfDefaults.INSTALLABLE_ADDONS.map { addon ->
                                AddonRow(
                                    addon = addon,
                                    installed = addon.name in installedNames,
                                    onClick = { confirmInstall(addon) },
                                )
                            },
                        )
                        adapter.update(
                            installed.map {
                                ExtInfo(
                                    id = it.id,
                                    name = it.metaData.name ?: it.id,
                                    sub = listOf(
                                        it.metaData.version,
                                        it.metaData.description,
                                    ).filter { s -> !s.isNullOrEmpty() }.joinToString(" • "),
                                )
                            },
                        )
                        // The installed header is meaningless with nothing under it.
                        findViewById<View>(R.id.extensions_installed_header).visibility =
                            if (installed.isEmpty()) View.GONE else View.VISIBLE
                    }
                },
                { _ -> },
            )
        } catch (_e: Exception) {
        }
    }

    private fun uninstall(info: ExtInfo) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.uninstall_ext) + ": " + info.name + "?")
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                val c = controller() ?: return@setPositiveButton
                try {
                    c.list().accept(
                        { list: List<WebExtension>? ->
                            val ext = list?.find { it.id == info.id }
                            if (ext != null) c.uninstall(ext)
                        },
                        { _ -> },
                    )
                } catch (_e: Exception) {
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    class ExtensionsAdapter(
        private var items: List<ExtInfo>,
        private val onRemove: (ExtInfo) -> Unit,
    ) : RecyclerView.Adapter<ExtensionsAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.ext_name)
            val sub: TextView = v.findViewById(R.id.ext_sub)
            val remove: ImageButton = v.findViewById(R.id.ext_remove)
        }

        fun update(items: List<ExtInfo>) {
            this.items = items
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_extension, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            h.name.text = e.name
            h.sub.text = e.sub
            h.sub.visibility = if (e.sub.isEmpty()) View.GONE else View.VISIBLE
            h.remove.setOnClickListener { onRemove(e) }
        }

        override fun getItemCount(): Int = items.size
    }

    /**
     * The installable list. Reuses the installed-extension row so both lists
     * read as the same kind of thing, with the trailing button becoming "Install"
     * or a tick once it is already there.
     */
    class AddonsAdapter(
        private var items: List<AddonRow>,
    ) : RecyclerView.Adapter<AddonsAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.ext_name)
            val sub: TextView = v.findViewById(R.id.ext_sub)
            val action: ImageButton = v.findViewById(R.id.ext_remove)
        }

        fun update(items: List<AddonRow>) {
            this.items = items
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_extension, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val row = items[position]
            h.name.text = row.addon.name
            h.sub.text = row.addon.summary
            h.sub.visibility = View.VISIBLE
            if (row.installed) {
                // Already present. Tapping would reinstall the same add-on, so the
                // row is inert and says why rather than inviting a no-op.
                h.action.setImageResource(R.drawable.ic_shield)
                h.action.contentDescription = row.addon.name
                h.action.setOnClickListener { }
                h.itemView.setOnClickListener { }
                h.itemView.alpha = 0.6f
                h.itemView.isEnabled = false
            } else {
                h.action.setImageResource(R.drawable.ic_add)
                h.action.contentDescription = row.addon.name
                // The whole row installs, not just the button: a 24dp target is
                // below the comfortable minimum, and the card made this a
                // comfortable target for free.
                h.action.setOnClickListener { row.onClick() }
                h.itemView.setOnClickListener { row.onClick() }
                h.itemView.isEnabled = true
                h.itemView.alpha = 1f
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
