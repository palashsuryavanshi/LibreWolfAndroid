package com.palash.librewolfandroid

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class RestorableTab(
    /**
     * Stable across process death. The browser mints these, so a restored tab
     * keeps the identity it had rather than being matched back to a position
     * or re-derived from its URL -- two tabs can legitimately show the same URL.
     */
    val id: Long,
    val url: String,
    val title: String,
    val lastActiveAt: Long,
)

data class ClosedTab(
    val url: String,
    val title: String,
    val closedAt: Long,
)

/**
 * Device-only normal-tab persistence. Private tabs are never restored.
 *
 * Two schema versions exist. Version 1 stored no tab id and identified the
 * current tab by its URL. Version 2 stores both, which is what lets a restore
 * put the user back on the tab they left even when another tab holds the same
 * URL. Version 1 data is still read: ids are minted on load and the current tab
 * falls back to a URL match, then to the first tab. Nothing here throws on
 * malformed input -- an unreadable tab is dropped, never the whole store.
 */
class BrowserSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun saveTabs(tabs: List<RestorableTab>, activeId: Long?) {
        // Unsaveable tabs are filtered BEFORE the cap is applied, not after.
        // Taking the first MAX_TABS rows and then dropping the unusable ones
        // could save fewer than MAX_TABS tabs from a set that had plenty of
        // savable ones, which is how a user with 60 tabs could come back to
        // fewer than the 50 that were kept.
        val savable = tabs.filter { isRestorableUrl(it.url) }
        // The cap keeps the most recently active tabs, then restores the
        // original list order for writing. Taking the head instead dropped the
        // newest tabs, which are the ones a user with more than MAX_TABS open
        // is actually working in. The active tab is pinned first so it can
        // never be the one dropped.
        val pinned = savable.filter { it.id == activeId }
        val rest = savable.filter { it.id != activeId }.sortedByDescending { it.lastActiveAt }
        val kept = (pinned + rest.take((MAX_TABS - pinned.size).coerceAtLeast(0)))
            .sortedBy { tab -> tabs.indexOfFirst { it.id == tab.id } }
        val array = JSONArray()
        kept.forEach { tab ->
            array.put(
                JSONObject()
                    .put("id", tab.id)
                    .put("url", tab.url)
                    .put("title", tab.title)
                    .put("time", tab.lastActiveAt),
            )
        }
        prefs.edit {
            putInt(K_SCHEMA, SCHEMA_VERSION)
            putString(K_TABS, array.toString())
            putLong(K_ACTIVE_ID, activeId ?: -1L)
            remove(K_ACTIVE)
        }
    }

    @Synchronized
    fun tabs(): List<RestorableTab> {
        val seen = mutableSetOf<Long>()
        var minted = 1L
        return parseArray(K_TABS).mapNotNull { obj ->
            val url = obj.optString("url")
            if (!isRestorableUrl(url)) return@mapNotNull null
            if (obj.optString("title").startsWith("Unable to load website")) return@mapNotNull null
            // Schema 1 has no id, and a hand-edited or partially-written store
            // could repeat one. Ids are the primary key for a restored tab, so a
            // duplicate is dropped rather than allowed to collide at runtime.
            var id = obj.optLong("id", 0L)
            if (id <= 0L || !seen.add(id)) {
                while (!seen.add(minted)) minted++
                id = minted
            }
            RestorableTab(
                id = id,
                url = url,
                title = obj.optString("title"),
                lastActiveAt = obj.optLong("time"),
            )
        }
    }

    /** The tab that was in front, or -1 when the store cannot say. */
    @Synchronized
    fun activeId(): Long = prefs.getLong(K_ACTIVE_ID, -1L)

    /**
     * Schema 1 only. Kept so a store written by an older build still resolves
     * to the right tab; a version 2 store clears this key on write.
     */
    @Synchronized
    fun activeUrl(): String? = prefs.getString(K_ACTIVE, null)

    @Synchronized
    fun addClosed(url: String, title: String) {
        if (url.isBlank() || url == "about:blank" ||
            (!url.startsWith("https://") && !url.startsWith("http://"))
        ) return
        val list = closedTabs().toMutableList()
        list.removeAll { it.url == url }
        list.add(0, ClosedTab(url, title, System.currentTimeMillis()))
        saveClosed(list.take(MAX_CLOSED))
    }

    @Synchronized
    fun closedTabs(): List<ClosedTab> = parseArray(K_CLOSED).map { obj ->
        ClosedTab(
            url = obj.optString("url"),
            title = obj.optString("title"),
            closedAt = obj.optLong("time"),
        )
    }.filter { it.url.isNotBlank() }

    @Synchronized
    fun popClosed(): ClosedTab? {
        val list = closedTabs().toMutableList()
        val first = list.firstOrNull() ?: return null
        saveClosed(list.drop(1))
        return first
    }

    @Synchronized
    fun clear() = prefs.edit { clear() }

    private fun saveClosed(tabs: List<ClosedTab>) {
        val array = JSONArray()
        tabs.forEach { tab ->
            array.put(
                JSONObject()
                    .put("url", tab.url)
                    .put("title", tab.title)
                    .put("time", tab.closedAt),
            )
        }
        prefs.edit { putString(K_CLOSED, array.toString()) }
    }

    private fun parseArray(key: String): List<JSONObject> {
        val array = try {
            JSONArray(prefs.getString(key, "[]"))
        } catch (_: Exception) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(::add)
            }
        }
    }

    private fun isRestorableUrl(url: String): Boolean =
        url.isNotBlank() && url != "about:blank" && !url.startsWith("data:") &&
            (url.startsWith("https://") || url.startsWith("http://"))

    companion object {
        private const val PREFS = "browser_sessions"
        private const val K_TABS = "normal_tabs"
        private const val K_ACTIVE = "active_url"
        private const val K_ACTIVE_ID = "active_tab_id"
        private const val K_SCHEMA = "schema_version"
        private const val K_CLOSED = "closed_tabs"
        private const val MAX_TABS = 50
        private const val MAX_CLOSED = 25

        /** Bumped when the shape of [K_TABS] changes. */
        const val SCHEMA_VERSION = 2
    }
}
