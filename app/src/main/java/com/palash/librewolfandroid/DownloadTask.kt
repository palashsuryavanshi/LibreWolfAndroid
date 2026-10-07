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
        put("headers", JSONObject(redactHeaders(headers) as Map<*, *>))
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
        /**
         * Header names whose value is a credential.
         *
         * The download store is a plain SharedPreferences file inside the app's
         * data directory. Anything written there is readable by anything that
         * gets at the device or its backup, and these are exactly the values that
         * would let someone act as the user against the origin that issued them.
         * So they are dropped on the way to disk.
         *
         * Dropping them costs nothing operationally. A resumed download is
         * re-fetched through Gecko, which owns the cookie jar and re-attaches the
         * session's own cookies, so the stored headers were never what
         * authenticated the retry. See DownloadEngine.registerDownloadRefresher.
         */
        private val SENSITIVE_HEADERS = setOf(
            "cookie", "set-cookie", "cookie2", "set-cookie2",
            "authorization", "proxy-authorization",
            "www-authenticate", "proxy-authenticate",
            "x-api-key", "x-auth-token", "x-csrf-token",
            "x-amz-security-token", "x-goog-api-key",
            "authentication-info", "proxy-authentication-info",
        )

        /** Request headers with every credential-bearing value removed. */
        fun redactHeaders(headers: Map<String, String>): Map<String, String> =
            headers.filterKeys { it.lowercase() !in SENSITIVE_HEADERS }

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
