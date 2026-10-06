package com.palash.librewolfandroid

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class BookmarksActivity : AppCompatActivity() {

    private lateinit var store: BookmarkStore
    private lateinit var adapter: BookmarksAdapter
    private lateinit var emptyState: EmptyState
    private var query = ""
    private var sortMode = 0

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@runCatching
            val count = store.importJson(raw)
            Toast.makeText(this, resources.getQuantityString(R.plurals.bookmarks_imported, count, count), Toast.LENGTH_SHORT).show()
            reload()
        }.onFailure {
            Toast.makeText(this, R.string.bookmarks_import_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bookmarks)
        SystemBars.apply(this)
        store = BookmarkStore(this)
        emptyState = EmptyState.of(findViewById(android.R.id.content), R.id.bookmarks_list, R.id.bookmarks_empty)

        adapter = BookmarksAdapter(
            emptyList(),
            onOpen = { bm ->
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .setData(android.net.Uri.parse(bm.url))
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            },
            onDelete = { bm ->
                store.remove(bm.url)
                reload()
                confirm(R.string.bookmark_deleted)
            },
            onMenu = { showBookmarkMenu(it) },
        )
        findViewById<RecyclerView>(R.id.bookmarks_list).apply {
            layoutManager = LinearLayoutManager(this@BookmarksActivity)
            adapter = this@BookmarksActivity.adapter
        }
        findViewById<View>(R.id.bookmarks_back).setOnClickListener { finish() }
        findViewById<View>(R.id.bookmarks_search).setOnClickListener { askQuery() }
        findViewById<View>(R.id.bookmarks_add).setOnClickListener { showBookmarkMenu(null) }
        findViewById<View>(R.id.bookmarks_fab).setOnClickListener { askQuery() }
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val all = store.all().let { entries ->
            when (sortMode) {
                1 -> entries.sortedBy { it.title.lowercase() }
                2 -> entries.sortedWith(compareBy<Bookmark> { it.folder.lowercase() }.thenBy { it.title.lowercase() })
                else -> entries
            }
        }
        val shown = if (query.isEmpty()) all
        else all.filter { it.title.contains(query, true) || it.url.contains(query, true) || it.folder.contains(query, true) }
        adapter.update(shown)
        // The empty message is now a view rather than a Toast. The Toast was gone
        // within seconds, repeated itself on every onResume, and said "no
        // bookmarks" even when the user had plenty and the search simply matched
        // nothing -- which is the one moment they most need telling apart.
        emptyState.bind(
            shown.size,
            {
                if (query.isEmpty()) getString(R.string.no_bookmarks)
                else getString(R.string.no_bookmarks_match, query)
            },
            // Only the never-had-any case gets the hint; telling someone how to
            // add a bookmark while they are narrowing an existing list is noise.
            if (query.isEmpty()) {
                { getString(R.string.no_bookmarks_hint) }
            } else {
                null
            },
        )
    }

    private fun showBookmarkMenu(bookmark: Bookmark?) {
        if (bookmark == null) {
            val actions = arrayOf(
                getString(R.string.add_bookmark),
                getString(R.string.import_bookmarks),
                getString(R.string.export_bookmarks),
                getString(R.string.sort_bookmarks),
            )
            AlertDialog.Builder(this)
                .setItems(actions) { _, which ->
                    when (which) {
                        0 -> showBookmarkEditor(null)
                        1 -> importLauncher.launch(arrayOf("application/json", "text/plain"))
                        2 -> exportBookmarks()
                        3 -> showSortDialog()
                    }
                }
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(bookmark.title)
            .setItems(arrayOf(
                getString(R.string.edit_bookmark),
                getString(R.string.move_bookmark),
                getString(R.string.share),
                getString(R.string.delete),
            )) { _, which ->
                when (which) {
                    0 -> showBookmarkEditor(bookmark)
                    1 -> moveBookmark(bookmark)
                    2 -> shareBookmark(bookmark)
                    3 -> { store.remove(bookmark.url); reload() }
                }
            }
            .show()
    }

    private fun showSortDialog() {
        val options = arrayOf(
            getString(R.string.sort_recent),
            getString(R.string.sort_title),
            getString(R.string.sort_folder),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.sort_bookmarks)
            .setSingleChoiceItems(options, sortMode) { dialog, which ->
                sortMode = which
                dialog.dismiss()
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBookmarkEditor(existing: Bookmark?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val title = EditText(this).apply { hint = getString(R.string.bookmark_title); setText(existing?.title.orEmpty()) }
        val url = EditText(this).apply { hint = "https://"; setText(existing?.url.orEmpty()) }
        val folder = EditText(this).apply { hint = getString(R.string.bookmark_folder); setText(existing?.folder.orEmpty()) }
        layout.addView(title); layout.addView(url); layout.addView(folder)
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.add_bookmark else R.string.edit_bookmark)
            .setView(layout)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val u = url.text.toString().trim()
                if (u.isBlank()) return@setPositiveButton
                if (existing == null) store.add(title.text.toString(), u, folder.text.toString().trim())
                else store.update(existing.url, title.text.toString(), u, folder.text.toString().trim())
                reload()
            }
            .show()
    }

    private fun moveBookmark(bookmark: Bookmark) {
        val folders = store.folders().toMutableList()
        folders.add("Unfiled")
        AlertDialog.Builder(this)
            .setTitle(R.string.move_bookmark)
            .setItems(folders.toTypedArray()) { _, which ->
                store.update(bookmark.url, bookmark.title, bookmark.url, folders[which])
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun shareBookmark(bookmark: Bookmark) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, bookmark.title)
            putExtra(Intent.EXTRA_TEXT, bookmark.url)
        }, getString(R.string.share)))
    }

    private fun exportBookmarks() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, store.exportJson())
        }, getString(R.string.export_bookmarks)))
    }

    private fun askQuery() {
        val input = EditText(this).apply {
            hint = getString(R.string.search_bookmarks)
            setText(query)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.search_bookmarks))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> query = input.text.toString(); reload() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    class BookmarksAdapter(
        private var items: List<Bookmark>,
        private val onOpen: (Bookmark) -> Unit,
        private val onDelete: (Bookmark) -> Unit,
        private val onMenu: (Bookmark) -> Unit,
    ) : RecyclerView.Adapter<BookmarksAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: TextView = v.findViewById(R.id.bm_icon)
            val favicon: android.widget.ImageView = v.findViewById(R.id.bm_favicon)
            val title: TextView = v.findViewById(R.id.bm_title)
            val url: TextView = v.findViewById(R.id.bm_url)
            val delete: ImageButton = v.findViewById(R.id.bm_delete)
        }

        fun update(items: List<Bookmark>) {
            this.items = items
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val bm = items[position]
            h.title.text = bm.title
            h.url.text = if (bm.folder.isBlank()) bm.url else "${bm.folder} · ${bm.url}"
            FaviconCache.bind(h.icon, h.favicon, bm.url, badgeLetter(bm.title, bm.url))
            h.itemView.setOnClickListener { onOpen(bm) }
            h.itemView.setOnLongClickListener { onMenu(bm); true }
            h.delete.setOnClickListener { onDelete(bm) }
            h.delete.contentDescription = h.itemView.context.getString(
                R.string.a11y_delete_bookmark,
                bm.title.ifBlank { bm.url },
            )
        }

        override fun getItemCount(): Int = items.size
    }
}
