package com.palash.librewolfandroid

import org.json.JSONObject

/** Lifecycle of a download managed by our own engine (replaces DownloadManager). */
enum class DownloadState {
    QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED;

    val isActive: Boolean get() = this == QUEUED || this == RUNNING
    val isFinished: Boolean get() = this == COMPLETED || this == CANCELED
}

data class DownloadTask(
    val id: String,
    val url: String,
    var name: String,
    val mime: String,
    val folder: String,
    // SAF tree URI when the user picked a folder with the system picker. Takes
    // precedence over `folder`, which stays as the display name.
    val folderUri: String = "",
    val headers: Map<String, String>,
    val createdAt: Long,
    var state: DownloadState = DownloadState.QUEUED,
    var bytesDownloaded: Long = 0,
    var totalBytes: Long = -1,
    /** MediaStore content uri (API 29+) or absolute file path for legacy storage. */
    var localRef: String = "",
    var supportsRange: Boolean = true,
    var error: String = "",
    val userAgent: String = "",
    /**
     * True when the bytes come from a GeckoView response rather than our own
     * HTTP request. Gecko has already applied the session cookie at that point,
     * which is the only way to fetch a file that needs a sign-in: GeckoView 147
     * exposes no API to read its cookie jar.
     */
    var viaEngine: Boolean = false,
) {
    val percent: Int
        get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("name", name)
        put("mime", mime)
        put("folder", folder)
        put("folderUri", folderUri)
        put("headers", JSONObject(headers as Map<*, *>))
        put("createdAt", createdAt)
        put("state", state.name)
        put("bytesDownloaded", bytesDownloaded)
        put("totalBytes", totalBytes)
        put("localRef", localRef)
        put("supportsRange", supportsRange)
        put("error", error)
        put("userAgent", userAgent)
        put("viaEngine", viaEngine)
    }

    companion object {
        fun fromJson(o: JSONObject): DownloadTask {
            val headers = mutableMapOf<String, String>()
            val h = o.optJSONObject("headers")
            if (h != null) {
                h.keys().forEach { k -> headers[k] = h.optString(k) }
            }
            return DownloadTask(
                id = o.optString("id"),
                url = o.optString("url"),
                name = o.optString("name"),
                mime = o.optString("mime"),
                folder = o.optString("folder"),
                folderUri = o.optString("folderUri"),
                headers = headers,
                createdAt = o.optLong("createdAt"),
                state = runCatching { DownloadState.valueOf(o.optString("state")) }
                    .getOrDefault(DownloadState.QUEUED),
                bytesDownloaded = o.optLong("bytesDownloaded"),
                totalBytes = o.optLong("totalBytes", -1),
                localRef = o.optString("localRef"),
                supportsRange = o.optBoolean("supportsRange", true),
                error = o.optString("error"),
                userAgent = o.optString("userAgent"),
                viaEngine = o.optBoolean("viaEngine", false),
            )
        }
    }
}
