package com.palash.librewolfandroid

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(val title: String, val url: String, val time: Long)

/** Local browsing history (device-only, no sync — LibreWolf policy). */
class HistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun add(title: String, url: String) {
        if ((!url.startsWith("https://") && !url.startsWith("http://")) || url == "about:blank") return
        val entries = all().toMutableList()
        entries.removeAll { it.url == url }
        entries.add(0, HistoryEntry(title.ifEmpty { url }, url, System.currentTimeMillis()))
        while (entries.size > MAX) entries.removeAt(entries.size - 1)
        save(entries)
    }

    @Synchronized
    fun all(): List<HistoryEntry> {
        val out = mutableListOf<HistoryEntry>()
        val arr = try {
            JSONArray(prefs.getString(K_LIST, "[]"))
        } catch (_: Exception) {
            return out
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(HistoryEntry(o.optString("t"), o.optString("u"), o.optLong("d")))
        }
        return out
    }

    @Synchronized
    fun remove(url: String) = save(all().filter { it.url != url })

    @Synchronized
    fun clear() = save(emptyList())

    @Synchronized
    fun clearBefore(cutoff: Long) = save(all().filter { it.time >= cutoff })

    private fun save(entries: List<HistoryEntry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().put("t", e.title).put("u", e.url).put("d", e.time))
        }
        prefs.edit { putString(K_LIST, arr.toString()) }
    }

    companion object {
        private const val PREFS = "librewolf_history"
        private const val K_LIST = "entries"
        private const val MAX = 300
    }
}
