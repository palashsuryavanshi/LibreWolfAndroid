package com.palash.librewolfandroid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Same-origin favicon loader used by the tab tray; no third-party favicon service is contacted. */
object FaviconCache {
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val misses = ConcurrentHashMap.newKeySet<String>()
    private val images = ConcurrentHashMap<String, Bitmap>()
    private val imageMisses = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "librewolf-favicon").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun cached(url: String): Bitmap? = cache[originKey(url)]

    /** Arbitrary site-provided image, used for web app manifest icons. */
    fun cachedImage(url: String): Bitmap? = images[url]

    fun prefetchImage(url: String) {
        if (url.isBlank() || images.containsKey(url) || imageMisses.contains(url)) return
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        imageMisses.add(url)
        executor.execute {
            val decoded = fetch(url)
            if (decoded != null) {
                if (images.size >= 20) images.clear()
                images[url] = decoded
            }
        }
    }

    fun load(url: String, onLoaded: () -> Unit) {
        val key = originKey(url)
        if (key.isBlank() || cache.containsKey(key) || misses.contains(key)) return
        misses.add(key)
        executor.execute {
            val decoded = fetch("$key/favicon.ico")
            if (decoded != null) {
                if (cache.size >= 100) cache.clear()
                cache[key] = decoded
                main.post(onLoaded)
            }
        }
    }

    private fun fetch(url: String): Bitmap? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 4_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*")
        }
        try {
            if (connection.responseCode in 200..299) {
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /**
     * Binds a site's own icon to a list row. The letter badge is shown until the
     * icon arrives, and the row tag guards against a recycled row being filled
     * with the previous entry's icon.
     */
    fun bind(badge: android.widget.TextView, image: android.widget.ImageView, url: String, letter: String) {
        badge.tag = url
        val ready = cached(url)
        if (ready != null) {
            paint(badge, image, ready)
            return
        }
        badge.text = letter
        image.setImageDrawable(null)
        image.visibility = android.view.View.GONE
        load(url) {
            if (badge.tag != url) return@load
            cached(url)?.let { paint(badge, image, it) }
        }
    }

    private fun paint(badge: android.widget.TextView, image: android.widget.ImageView, bitmap: Bitmap) {
        val size = badge.resources.getDimensionPixelSize(R.dimen.favicon_size)
        val scaled = if (bitmap.width == size && bitmap.height == size) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, size, size, true)
        }
        image.setImageBitmap(scaled)
        image.visibility = android.view.View.VISIBLE
        badge.text = ""
    }

    private fun originKey(url: String): String = runCatching {
        val uri = android.net.Uri.parse(url)
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return ""
        if (uri.scheme !in setOf("http", "https")) return ""
        val port = if (uri.port != -1) ":${uri.port}" else ""
        "${uri.scheme}://${uri.host}$port"
    }.getOrDefault("")
}
