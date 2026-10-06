package com.palash.librewolfandroid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Downloads screen backed by the in-app engine, so pause/resume are real
 * user-controlled operations rather than system state.
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var adapter: DownloadsAdapter
    private lateinit var store: DownloadStore
    private lateinit var emptyState: EmptyState
    private var filter = 0 // 0 all, 1 images, 2 docs, 3 other
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        SystemBars.apply(this)
        store = DownloadStore(this)
        emptyState = EmptyState.of(findViewById(android.R.id.content), R.id.downloads_list, R.id.downloads_empty)
        DownloadService.ensureChannel(this)

        adapter = DownloadsAdapter(
            emptyList(),
            onOpen = { openEntry(it) },
            onRemove = { removeEntry(it) },
            onShare = { shareEntry(it) },
            onPause = { DownloadEngine.pause(it.id); reload() },
            onResume = { DownloadEngine.resume(it.id); reload() },
            onCancel = { DownloadEngine.cancel(it.id); reload() },
            onRetry = { DownloadEngine.retry(it.id); reload() },
        )
        findViewById<RecyclerView>(R.id.downloads_list).apply {
            layoutManager = LinearLayoutManager(this@DownloadsActivity)
            adapter = this@DownloadsActivity.adapter
        }
        findViewById<View>(R.id.downloads_back).setOnClickListener { finish() }
        findViewById<View>(R.id.downloads_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.downloads_fab).setOnClickListener { askQuery() }
        chip(R.id.chip_all, 0)
        chip(R.id.chip_images, 1)
        chip(R.id.chip_docs, 2)
        chip(R.id.chip_other, 3)
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun chip(id: Int, value: Int) {
        findViewById<TextView>(id).setOnClickListener {
            filter = value
            paintChips()
            reload()
        }
    }

    private fun paintChips() {
        val ids = intArrayOf(R.id.chip_all, R.id.chip_images, R.id.chip_docs, R.id.chip_other)
        ids.forEachIndexed { i, id ->
            findViewById<TextView>(id).apply {
                setBackgroundResource(if (i == filter) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
                setTextColor(getColor(if (i == filter) R.color.librewolf_text else R.color.librewolf_grey))
            }
        }
    }

    private fun statusLabel(t: DownloadTask): String = when (t.state) {
        DownloadState.QUEUED -> getString(R.string.download_queued)
        DownloadState.RUNNING -> if (t.totalBytes > 0) {
            "${t.percent}% • ${human(t.bytesDownloaded)} / ${human(t.totalBytes)}"
        } else {
            human(t.bytesDownloaded)
        }
        DownloadState.PAUSED ->
            "${getString(R.string.download_paused)} • ${human(t.bytesDownloaded)}"
        DownloadState.COMPLETED -> "${getString(R.string.download_complete)} • ${human(t.bytesDownloaded)}"
        DownloadState.FAILED ->
            "${getString(R.string.download_failed)} • ${t.error.ifBlank { human(t.bytesDownloaded) }}"
        DownloadState.CANCELED -> getString(R.string.cancel_download)
    }

    private fun human(bytes: Long): String = Format.bytes(this, bytes)

    /** Folder a completed download lives in, when it is not the default. */
    private fun folderLabel(t: DownloadTask): String {
        if (t.folder.isBlank()) return ""
        return "  •  ${t.folder}"
    }

    private fun reload() {
        val all = store.all()
        val shown = all.filter { t ->
            val okFilter = when (filter) {
                1 -> t.mime.startsWith("image/")
                2 -> t.mime.startsWith("application/pdf") || t.mime.contains("word") ||
                    t.mime.contains("officedocument") || t.mime.startsWith("text/")
                3 -> !(t.mime.startsWith("image/") || t.mime.startsWith("application/pdf") ||
                    t.mime.contains("word") || t.mime.contains("officedocument") ||
                    t.mime.startsWith("text/"))
                else -> true
            }
            okFilter && (query.isEmpty() || t.name.contains(query, true))
        }
        adapter.update(shown.map { it to statusLabel(it) + folderLabel(it) })
        // The message distinguishes "nothing has ever been downloaded" from
        // "nothing in this filter". The old check was against the whole store,
        // so picking Images with no images on the device produced a blank screen
        // with no explanation, and the Toast repeated on every onResume.
        emptyState.bind(
            shown.size,
            {
                if (all.isEmpty()) getString(R.string.no_downloads) else getString(R.string.no_downloads_match)
            },
            if (all.isEmpty()) {
                { getString(R.string.no_downloads_hint) }
            } else {
                null
            },
        )
    }

    private fun openEntry(t: DownloadTask) {
        if (t.state != DownloadState.COMPLETED) return
        val intent = DownloadEngine.openIntent(this, t.id)
        if (intent == null) {
            Toast.makeText(this, getString(R.string.not_available), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.open_file)))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.not_available), Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareEntry(t: DownloadTask) {
        if (t.state != DownloadState.COMPLETED) return
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = t.mime.ifEmpty { "*/*" }
                putExtra(Intent.EXTRA_STREAM, downloadUri(t))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.not_available), Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadUri(t: DownloadTask): android.net.Uri? {
        val ref = t.localRef
        if (ref.isEmpty()) return null
        return if (ref.startsWith("content://")) {
            android.net.Uri.parse(ref)
        } else {
            androidx.core.content.FileProvider.getUriForFile(
                this,
                packageName + ".fileprovider",
                java.io.File(ref),
            )
        }
    }

    private fun removeEntry(t: DownloadTask) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete) + ": " + t.name + "?")
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                DownloadEngine.remove(t.id)
                reload()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun askQuery() {
        val input = EditText(this).apply {
            hint = getString(R.string.search_downloads)
            setText(query)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.search_downloads))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> query = input.text.toString(); reload() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    class DownloadsAdapter(
        private var items: List<Pair<DownloadTask, String>>,
        private val onOpen: (DownloadTask) -> Unit,
        private val onRemove: (DownloadTask) -> Unit,
        private val onShare: (DownloadTask) -> Unit,
        private val onPause: (DownloadTask) -> Unit,
        private val onResume: (DownloadTask) -> Unit,
        private val onCancel: (DownloadTask) -> Unit,
        private val onRetry: (DownloadTask) -> Unit,
    ) : RecyclerView.Adapter<DownloadsAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.dl_icon)
            val name: TextView = v.findViewById(R.id.dl_name)
            val sub: TextView = v.findViewById(R.id.dl_sub)
            val more: ImageButton = v.findViewById(R.id.dl_more)
        }

        fun update(items: List<Pair<DownloadTask, String>>) {
            this.items = items
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val (e, status) = items[position]
            val ctx = h.itemView.context
            h.name.text = e.name
            h.sub.text = status
            h.icon.setImageResource(
                when {
                    e.mime.startsWith("image/") -> R.drawable.ic_doc
                    e.name.endsWith(".apk", true) -> R.drawable.ic_apk
                    else -> R.drawable.ic_doc
                },
            )
            h.itemView.setOnClickListener { onOpen(e) }
            h.more.setOnClickListener { v ->
                androidx.appcompat.widget.PopupMenu(v.context, v).apply {
                    when (e.state) {
                        DownloadState.PAUSED -> {
                            menu.add(ctx.getString(R.string.resume_download))
                                .setOnMenuItemClickListener { onResume(e); true }
                            menu.add(ctx.getString(R.string.retry_download))
                                .setOnMenuItemClickListener { onRetry(e); true }
                        }
                        DownloadState.FAILED, DownloadState.CANCELED -> {
                            menu.add(ctx.getString(R.string.retry_download))
                                .setOnMenuItemClickListener { onRetry(e); true }
                        }
                        DownloadState.QUEUED, DownloadState.RUNNING -> {
                            menu.add(ctx.getString(R.string.pause_download))
                                .setOnMenuItemClickListener { onPause(e); true }
                        }
                        DownloadState.COMPLETED -> Unit
                    }
                    if (e.state == DownloadState.COMPLETED) {
                        menu.add(ctx.getString(R.string.open_file))
                            .setOnMenuItemClickListener { onOpen(e); true }
                        menu.add(ctx.getString(R.string.share_download))
                            .setOnMenuItemClickListener { onShare(e); true }
                    }
                    if (e.state.isActive) {
                        menu.add(ctx.getString(R.string.cancel_download))
                            .setOnMenuItemClickListener { onCancel(e); true }
                    }
                    menu.add(ctx.getString(R.string.delete))
                        .setOnMenuItemClickListener { onRemove(e); true }
                    show()
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
