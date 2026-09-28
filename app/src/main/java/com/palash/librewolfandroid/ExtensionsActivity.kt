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

class ExtensionsActivity : AppCompatActivity() {

    private lateinit var adapter: ExtensionsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_extensions)
        SystemBars.apply(this)

        adapter = ExtensionsAdapter(
            emptyList(),
            onRemove = { uninstall(it) },
        )
        findViewById<RecyclerView>(R.id.extensions_list).apply {
            layoutManager = LinearLayoutManager(this@ExtensionsActivity)
            adapter = this@ExtensionsActivity.adapter
        }
        findViewById<View>(R.id.extensions_back).setOnClickListener { finish() }
        findViewById<View>(R.id.install_ubo).setOnClickListener { confirmInstallUbo() }
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

    private fun confirmInstallUbo() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.install_ubo))
            .setMessage(LibreWolfDefaults.UBO_AMO_URL)
            .setPositiveButton(getString(R.string.install)) { _, _ -> installUbo() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun installUbo() {
        val c = controller()
        if (c == null) {
            Toast.makeText(this, getString(R.string.not_available), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.ext_installing), Toast.LENGTH_SHORT).show()
        try {
            c.install(LibreWolfDefaults.UBO_AMO_URL).accept({ _ -> }, { _ -> })
        } catch (e: Exception) {
            Toast.makeText(this, "${getString(R.string.ext_install_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun reload() {
        val c = controller() ?: return
        try {
            c.list().accept(
                { list: List<WebExtension>? ->
                    runOnUiThread {
                        adapter.update(
                            (list ?: emptyList()).map {
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
}
