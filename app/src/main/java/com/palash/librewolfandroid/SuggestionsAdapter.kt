package com.palash.librewolfandroid

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class Suggestion(val title: String, val sub: String, val icon: String, val onPick: () -> Unit)

class SuggestionsAdapter(
    private var items: List<Suggestion>,
) : RecyclerView.Adapter<SuggestionsAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: TextView = v.findViewById(R.id.sug_icon)
        val title: TextView = v.findViewById(R.id.sug_title)
        val sub: TextView = v.findViewById(R.id.sug_sub)
    }

    fun update(items: List<Suggestion>) {
        this.items = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_suggestion, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val s = items[position]
        h.title.text = s.title
        h.sub.text = s.sub
        h.sub.visibility = if (s.sub.isEmpty()) View.GONE else View.VISIBLE
        h.icon.text = s.icon
        h.icon.setBackgroundColor(badgeColorFor(s.sub.ifEmpty { s.title }))
        h.itemView.setOnClickListener { s.onPick() }
    }

    override fun getItemCount(): Int = items.size
}
