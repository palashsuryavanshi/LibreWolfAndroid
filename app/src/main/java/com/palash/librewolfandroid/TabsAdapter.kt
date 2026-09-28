package com.palash.librewolfandroid

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/** Snapshot of a browser tab for the tab tray. */
data class TabItem(
    val id: Long,
    val title: String,
    val url: String,
    val isPrivate: Boolean,
    val isSelected: Boolean,
    val isLoading: Boolean = false,
    val favicon: Bitmap? = null,
)

/** Letter-badge colors for tab/history rows. */
fun badgeColorFor(key: String): Int {
    val palette = intArrayOf(
        0xFF7C4DFF.toInt(), 0xFF00C853.toInt(), 0xFFFF6D00.toInt(),
        0xFF2962FF.toInt(), 0xFFFF1744.toInt(), 0xFF00B8D4.toInt(),
        0xFFC51162.toInt(), 0xFF64DD17.toInt(),
    )
    return palette[(key.hashCode() and 0x7fffffff) % palette.size]
}

fun badgeLetter(title: String, url: String): String {
    val s = title.ifEmpty { url }.trim()
    if (s.isEmpty()) return "•"
    val stripped = s.removePrefix("http://").removePrefix("https://").removePrefix("www.")
    return stripped.first().uppercaseChar().toString()
}

private fun bindIcon(icon: TextView, tab: TabItem) {
    val favicon = tab.favicon
    if (favicon == null) {
        icon.text = badgeLetter(tab.title, tab.url)
        icon.setPadding(0, 0, 0, 0)
        icon.setBackgroundColor(badgeColorFor(tab.url.ifEmpty { tab.title }))
        return
    }
    icon.text = ""
    icon.setPadding(0, 0, 0, 0)
    icon.background = BitmapDrawable(icon.resources, favicon).apply {
        setBounds(0, 0, icon.layoutParams?.width ?: 28, icon.layoutParams?.height ?: 28)
    }
}

class TabsAdapter(
    private var tabs: List<TabItem>,
    private val onSelect: (Long) -> Unit,
    private val onClose: (Long) -> Unit,
    private val onMenu: (TabItem) -> Unit = {},
) : RecyclerView.Adapter<TabsAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView = v as MaterialCardView
        val icon: TextView = v.findViewById(R.id.tab_icon)
        val title: TextView = v.findViewById(R.id.tab_title)
        val url: TextView = v.findViewById(R.id.tab_url)
        val privateTag: TextView = v.findViewById(R.id.tab_private)
        val loading: android.widget.ProgressBar = v.findViewById(R.id.tab_loading)
        val close: ImageButton = v.findViewById(R.id.tab_close)
    }

    fun update(tabs: List<TabItem>) {
        this.tabs = tabs
        notifyDataSetChanged()
    }

    fun get(position: Int): TabItem? = tabs.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val tab = tabs[position]
        h.title.text = tab.title.ifEmpty { tab.url.ifEmpty { "New tab" } }
        h.url.text = tab.url
        bindIcon(h.icon, tab)
        h.privateTag.visibility = if (tab.isPrivate) View.VISIBLE else View.GONE
        h.loading.visibility = if (tab.isLoading) View.VISIBLE else View.GONE
        h.card.setCardBackgroundColor(
            h.itemView.context.getColor(
                if (tab.isSelected) R.color.librewolf_card2 else R.color.librewolf_card,
            ),
        )
        h.card.strokeWidth = if (tab.isSelected) 4 else 0
        h.card.strokeColor = h.itemView.context.getColor(R.color.librewolf_accent)
        h.itemView.setOnClickListener { onSelect(tab.id) }
        h.itemView.setOnLongClickListener { onMenu(tab); true }
        h.close.setOnClickListener { onClose(tab.id) }
    }

    override fun getItemCount(): Int = tabs.size
}

/** Groups tabs by domain with section headers (Tab Groups). */
class GroupedTabsAdapter(
    private var items: List<TabItem>,
    private val onSelect: (Long) -> Unit,
    private val onClose: (Long) -> Unit,
    private val onMenu: (TabItem) -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed interface Row {
        data class Header(val domain: String, val count: Int) : Row
        data class TabRow(val item: TabItem) : Row
    }

    private var rows: List<Row> = build(items)

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v as TextView
    }

    class TabVH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView = v as MaterialCardView
        val icon: TextView = v.findViewById(R.id.tab_icon)
        val title: TextView = v.findViewById(R.id.tab_title)
        val url: TextView = v.findViewById(R.id.tab_url)
        val privateTag: TextView = v.findViewById(R.id.tab_private)
        val loading: android.widget.ProgressBar = v.findViewById(R.id.tab_loading)
        val close: ImageButton = v.findViewById(R.id.tab_close)
    }

    fun itemAt(position: Int): TabItem? = (rows.getOrNull(position) as? Row.TabRow)?.item

    fun update(items: List<TabItem>) {
        this.items = items
        this.rows = build(items)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderVH(
                TextView(parent.context).apply {
                    setPadding(48, 24, 48, 8)
                    textSize = 14f
                    setTextColor(parent.context.getColor(R.color.librewolf_grey))
                },
            )
        } else {
            TabVH(inf.inflate(R.layout.item_tab, parent, false))
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (h as HeaderVH).text.text = row.domain + "  (" + row.count + ")"
            is Row.TabRow -> {
                h as TabVH
                val t = row.item
                h.title.text = t.title.ifEmpty { t.url.ifEmpty { "New tab" } }
                h.url.text = t.url
                bindIcon(h.icon, t)
                h.privateTag.visibility = if (t.isPrivate) View.VISIBLE else View.GONE
                h.loading.visibility = if (t.isLoading) View.VISIBLE else View.GONE
                h.card.strokeWidth = if (t.isSelected) 4 else 0
                h.card.strokeColor = h.itemView.context.getColor(R.color.librewolf_accent)
                h.itemView.setOnClickListener { onSelect(t.id) }
                h.itemView.setOnLongClickListener { onMenu(t); true }
                h.close.setOnClickListener { onClose(t.id) }
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    private companion object {
        fun build(items: List<TabItem>): List<Row> {
            val groups = LinkedHashMap<String, MutableList<TabItem>>()
            for (t in items) {
                val domain = try {
                    android.net.Uri.parse(t.url).host?.removePrefix("www.") ?: t.url
                } catch (_e: Exception) {
                    t.url
                }
                groups.getOrPut(domain) { mutableListOf() }.add(t)
            }
            val out = mutableListOf<Row>()
            for ((domain, list) in groups) {
                out.add(Row.Header(domain, list.size))
                list.forEach { out.add(Row.TabRow(it)) }
            }
            return out
        }
    }
}
