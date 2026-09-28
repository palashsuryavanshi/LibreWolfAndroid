package com.palash.librewolfandroid

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Bookmark(
    val title: String,
    val url: String,
    val folder: String = "",
    val id: String = UUID.randomUUID().toString(),
)

/** Local bookmarks with folders and JSON import/export. Never synchronized. */
class BookmarkStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<Bookmark> {
        val out = mutableListOf<Bookmark>()
        val arr = try {
            JSONArray(prefs.getString(K_LIST, "[]"))
        } catch (_: Exception) {
            return out
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("u")
            if (isWebUrl(url)) {
                out.add(
                    Bookmark(
                        title = o.optString("t"),
                        url = url,
                        folder = o.optString("f"),
                        id = o.optString("i").ifBlank { url },
                    ),
                )
            }
        }
        return out
    }

    @Synchronized
    fun folders(): List<String> = all().map { it.folder }.filter { it.isNotBlank() }.distinct().sorted()

    @Synchronized
    fun contains(url: String): Boolean = all().any { it.url == url }

    /** Returns false when already bookmarked. */
    @Synchronized
    fun add(title: String, url: String, folder: String = ""): Boolean {
        if (!isWebUrl(url) || contains(url)) return false
        val entries = all().toMutableList()
        entries.add(0, Bookmark(title.ifEmpty { url }, url, folder))
        save(entries)
        return true
    }

    @Synchronized
    fun update(originalUrl: String, title: String, url: String, folder: String): Boolean {
        if (!isWebUrl(url)) return false
        val entries = all().toMutableList()
        val index = entries.indexOfFirst { it.url == originalUrl }
        if (index < 0) return false
        val current = entries[index]
        if (url != originalUrl && entries.any { it.url == url }) return false
        entries[index] = current.copy(title = title.ifEmpty { url }, url = url, folder = folder)
        save(entries)
        return true
    }

    @Synchronized
    fun remove(url: String) = save(all().filter { it.url != url })

    @Synchronized
    fun clear() = save(emptyList())

    @Synchronized
    fun exportJson(): String {
        val array = JSONArray()
        all().forEach { bookmark ->
            array.put(
                JSONObject()
                    .put("title", bookmark.title)
                    .put("url", bookmark.url)
                    .put("folder", bookmark.folder),
            )
        }
        return JSONObject().put("version", 1).put("bookmarks", array).toString(2)
    }

    @Synchronized
    fun importJson(raw: String): Int {
        val root = JSONObject(raw)
        val array = root.optJSONArray("bookmarks") ?: return 0
        var added = 0
        val current = all().toMutableList()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val url = obj.optString("url")
            if (!isWebUrl(url) || current.any { it.url == url }) continue
            current.add(
                Bookmark(
                    title = obj.optString("title").ifBlank { url },
                    url = url,
                    folder = obj.optString("folder"),
                ),
            )
            added++
        }
        save(current)
        return added
    }

    private fun isWebUrl(url: String): Boolean =
        url.startsWith("https://") || url.startsWith("http://")

    private fun save(entries: List<Bookmark>) {
        val arr = JSONArray()
        for (bookmark in entries) {
            arr.put(
                JSONObject()
                    .put("t", bookmark.title)
                    .put("u", bookmark.url)
                    .put("f", bookmark.folder)
                    .put("i", bookmark.id),
            )
        }
        prefs.edit { putString(K_LIST, arr.toString()) }
    }

    companion object {
        private const val PREFS = "librewolf_bookmarks"
        private const val K_LIST = "entries"
    }
}
