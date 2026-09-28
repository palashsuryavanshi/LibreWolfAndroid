package com.palash.librewolfandroid

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Calendar

class HistoryActivity : AppCompatActivity() {

    private lateinit var store: HistoryStore
    private lateinit var adapter: HistoryAdapter
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        SystemBars.apply(this)
        store = HistoryStore(this)

        adapter = HistoryAdapter(
            emptyList(),
            onOpen = { entry ->
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .setData(android.net.Uri.parse(entry.url))
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            },
            onDelete = { entry ->
                store.remove(entry.url)
                reload()
            },
        )
        findViewById<RecyclerView>(R.id.history_list).apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = this@HistoryActivity.adapter
        }
        findViewById<View>(R.id.history_back).setOnClickListener { finish() }
        findViewById<View>(R.id.history_search).setOnClickListener { askQuery() }
        findViewById<View>(R.id.history_clear).setOnClickListener { showClearDialog() }
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val all = store.all()
        val filtered = if (query.isEmpty()) all else all.filter {
            it.title.contains(query, true) || it.url.contains(query, true)
        }
        adapter.update(grouped(filtered))
    }

    private fun grouped(entries: List<HistoryEntry>): List<HistoryRow> {
        val rows = mutableListOf<HistoryRow>()
        if (entries.isEmpty()) {
            rows.add(HistoryRow.Empty)
            return rows
        }
        val cal = Calendar.getInstance()
        val todayStart = cal.apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayStart = cal.timeInMillis
        val today = entries.filter { it.time >= todayStart }
        val yesterday = entries.filter { it.time in yesterdayStart until todayStart }
        val older = entries.filter { it.time < yesterdayStart }
        if (today.isNotEmpty()) {
            rows.add(HistoryRow.Header(getString(R.string.today)))
            today.forEach { rows.add(HistoryRow.Item(it)) }
        }
        if (yesterday.isNotEmpty()) {
            rows.add(HistoryRow.Header(getString(R.string.yesterday)))
            yesterday.forEach { rows.add(HistoryRow.Item(it)) }
        }
        if (older.isNotEmpty()) {
            rows.add(HistoryRow.Header(getString(R.string.older)))
            older.forEach { rows.add(HistoryRow.Item(it)) }
        }
        return rows
    }

    private fun showClearDialog() {
        val options = arrayOf(
            getString(R.string.clear_history_all),
            getString(R.string.clear_history_7_days),
            getString(R.string.clear_history_30_days),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_history)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> store.clear()
                    1 -> store.clearBefore(System.currentTimeMillis() - 7 * 86_400_000L)
                    2 -> store.clearBefore(System.currentTimeMillis() - 30 * 86_400_000L)
                }
                reload()
            }
            .show()
    }

    private fun askQuery() {
        val input = EditText(this).apply {
            hint = getString(R.string.search_history)
            setText(query)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.search_history))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> query = input.text.toString(); reload() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    sealed interface HistoryRow {
        data object Empty : HistoryRow
        data class Header(val text: String) : HistoryRow
        data class Item(val entry: HistoryEntry) : HistoryRow
    }

    class HistoryAdapter(
        private var rows: List<HistoryRow>,
        private val onOpen: (HistoryEntry) -> Unit,
        private val onDelete: (HistoryEntry) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            val text: TextView = v as TextView
        }

        class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: TextView = v.findViewById(R.id.hist_icon)
            val favicon: android.widget.ImageView = v.findViewById(R.id.hist_favicon)
            val title: TextView = v.findViewById(R.id.hist_title)
            val url: TextView = v.findViewById(R.id.hist_url)
            val time: TextView = v.findViewById(R.id.hist_time)
            val delete: ImageButton = v.findViewById(R.id.hist_delete)
        }

        class EmptyVH(v: View) : RecyclerView.ViewHolder(v)

        fun update(rows: List<HistoryRow>) {
            this.rows = rows
            notifyDataSetChanged()
        }

        override fun getItemViewType(p: Int): Int = when (rows[p]) {
            is HistoryRow.Header -> 0
            is HistoryRow.Item -> 1
            is HistoryRow.Empty -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (type) {
                0 -> HeaderVH(
                    TextView(parent.context).apply {
                        setPadding(48, 32, 48, 8)
                        textSize = 15f
                        setTextColor(parent.context.getColor(R.color.librewolf_text))
                    },
                )
                1 -> ItemVH(inf.inflate(R.layout.item_history, parent, false))
                else -> EmptyVH(
                    TextView(parent.context).apply {
                        setPadding(48, 64, 48, 64)
                        textSize = 16f
                        textAlignment = View.TEXT_ALIGNMENT_CENTER
                        setText(R.string.no_history)
                        setTextColor(parent.context.getColor(R.color.librewolf_grey))
                    },
                )
            }
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is HistoryRow.Header -> (h as HeaderVH).text.text = row.text
                is HistoryRow.Item -> {
                    h as ItemVH
                    h.title.text = row.entry.title
                    h.url.text = row.entry.url
                    h.time.text = Format.relativeTime(h.itemView.context, row.entry.time)
                    h.icon.setBackgroundColor(badgeColorFor(row.entry.url))
                    FaviconCache.bind(h.icon, h.favicon, row.entry.url, badgeLetter(row.entry.title, row.entry.url))
                    h.itemView.setOnClickListener { onOpen(row.entry) }
                    h.delete.setOnClickListener { onDelete(row.entry) }
                }
                is HistoryRow.Empty -> Unit
            }
        }

        override fun getItemCount(): Int = rows.size
    }
}
