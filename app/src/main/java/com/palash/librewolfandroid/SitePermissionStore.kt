package com.palash.librewolfandroid

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/** Per-origin browser-level permission decisions, independent of Firefox Sync. */
class SitePermissionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun get(origin: String, permission: Int): Int? {
        val key = originKey(origin)
        val raw = prefs.getString(key, null) ?: return null
        return runCatching { JSONObject(raw).optInt(permission.toString(), Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } }
            .getOrNull()
    }

    @Synchronized
    fun set(origin: String, permission: Int, value: Int) {
        val key = originKey(origin)
        val current = runCatching { JSONObject(prefs.getString(key, "{}") ?: "{}") }.getOrElse { JSONObject() }
        current.put(permission.toString(), value)
        prefs.edit { putString(key, current.toString()) }
    }

    @Synchronized
    fun all(): Map<String, Map<Int, Int>> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith("permission:") || value !is String) return@mapNotNull null
        val origin = key.removePrefix("permission:")
        val obj = runCatching { JSONObject(value) }.getOrNull() ?: return@mapNotNull null
        origin to obj.keys().asSequence().mapNotNull { name -> name.toIntOrNull()?.let { it to obj.optInt(name) } }.toMap()
    }.toMap()

    @Synchronized
    fun clearOrigin(origin: String) {
        prefs.edit { remove("permission:${origin.lowercase()}") }
    }

    @Synchronized
    fun clear() = prefs.edit { clear() }

    private fun originKey(uri: String): String = runCatching {
        val parsed = android.net.Uri.parse(uri)
        val host = parsed.host?.lowercase() ?: return uri
        val port = if (parsed.port != -1) ":${parsed.port}" else ""
        "$host$port"
    }.getOrDefault(uri.lowercase())
        .let { "permission:$it" }

    companion object {
        private const val PREFS = "site_permissions"
    }
}
