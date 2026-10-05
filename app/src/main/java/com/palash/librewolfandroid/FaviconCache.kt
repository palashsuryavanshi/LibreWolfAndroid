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
            val decoded = fetch(url, MAX_IMAGE_PX)
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
            val decoded = fetch("$key/favicon.ico", MAX_FAVICON_PX)
            if (decoded != null) {
                if (cache.size >= 100) cache.clear()
                cache[key] = decoded
                main.post(onLoaded)
            }
        }
    }

    /**
     * Network fetch with two ceilings. The byte cap stops a hostile server
     * from filling memory before decode even starts; the dimension cap stops
     * a valid-but-enormous image from becoming a bitmap the device cannot
     * hold. Tray rows render at 36dp (144px at most), so 192px keeps full
     * quality with headroom; manifest icons become launcher shortcut icons,
     * which want more.
     */
    private const val MAX_FAVICON_PX = 192
    private const val MAX_IMAGE_PX = 512
    private const val MAX_BYTES = 4 * 1024 * 1024

    private fun fetch(url: String, maxPx: Int): Bitmap? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 4_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*")
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            val bytes = connection.inputStream.use { readCapped(it, MAX_BYTES) }
                ?: return@runCatching null
            decodeBounded(bytes, maxPx)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun readCapped(stream: java.io.InputStream, cap: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buf)
            if (read == -1) break
            total += read
            if (total > cap) return null
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    private fun decodeBounded(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().also { it.inSampleSize = sample },
        )
    }

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
