package com.palash.librewolfandroid

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class RestorableTab(
    val url: String,
    val title: String,
    val lastActiveAt: Long,
)

data class ClosedTab(
    val url: String,
    val title: String,
    val closedAt: Long,
)

/** Device-only normal-tab persistence. Private tabs are never restored. */
class BrowserSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun saveTabs(tabs: List<RestorableTab>, activeUrl: String?) {
        val array = JSONArray()
        tabs.take(MAX_TABS).forEach { tab ->
            if (tab.url.isBlank() || tab.url == "about:blank" || tab.url.startsWith("data:") ||
                (!tab.url.startsWith("https://") && !tab.url.startsWith("http://"))
            ) return@forEach
            array.put(
                JSONObject()
                    .put("url", tab.url)
                    .put("title", tab.title)
                    .put("time", tab.lastActiveAt),
            )
        }
        prefs.edit {
            putString(K_TABS, array.toString())
            putString(K_ACTIVE, activeUrl)
        }
    }

    @Synchronized
    fun tabs(): List<RestorableTab> = parseArray(K_TABS).map { obj ->
        RestorableTab(
            url = obj.optString("url"),
            title = obj.optString("title"),
            lastActiveAt = obj.optLong("time"),
        )
    }.filter {
        it.url.isNotBlank() && !it.url.startsWith("data:") && it.url != "about:blank" &&
            (it.url.startsWith("https://") || it.url.startsWith("http://")) &&
            !it.title.startsWith("Unable to load website")
    }

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

    companion object {
        private const val PREFS = "browser_sessions"
        private const val K_TABS = "normal_tabs"
        private const val K_ACTIVE = "active_url"
        private const val K_CLOSED = "closed_tabs"
        private const val MAX_TABS = 50
        private const val MAX_CLOSED = 25
    }
}
