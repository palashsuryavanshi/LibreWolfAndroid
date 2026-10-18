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

    /**
     * Caps the negative caches.
     *
     * `misses` and `imageMisses` record that an origin has already been tried, so
     * a 404 is not requested on every visit. Nothing ever removed from either
     * set, so it grew by one string per distinct origin for the life of the
     * process -- and an origin that failed once was never retried, even after the
     * site put a favicon in place.
     *
     * Clearing on overflow is what the positive caches already do, so a long
     * session cannot grow these without bound. The cost is that the next visit to
     * a failed origin retries once, which is the correct behaviour anyway.
     */
    private const val MAX_MISSES = 5_000

    private fun rememberMiss(set: MutableSet<String>, key: String) {
        if (set.size >= MAX_MISSES) set.clear()
        set.add(key)
    }

    fun cached(url: String): Bitmap? = cache[originKey(url)]

    /** Arbitrary site-provided image, used for web app manifest icons. */
    fun cachedImage(url: String): Bitmap? = images[url]

    fun prefetchImage(url: String) {
        if (url.isBlank() || images.containsKey(url) || imageMisses.contains(url)) return
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        rememberMiss(imageMisses, url)
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
        rememberMiss(misses, key)
        executor.execute {
            val decoded = fetch("$key/favicon.ico", MAX_FAVICON_PX)
            if (decoded != null) {
                if (cache.size >= 100) {
                    cache.clear()
                    // Every scaled variant was derived from a bitmap that is
                    // now gone; keeping them would pin the memory for nothing.
                    scaled.evictAll()
                }
                // A refreshed favicon replaces the old representation, so any
                // scaled copy of the previous one is stale for this origin.
                invalidateScaled(key)
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

    /**
     * Byte budget for the scaled-representation cache.
     *
     * Sized so a full tray of rows, each with its own scaled favicon, cannot
     * come close to it: a 192px favicon is ~144 KB decoded, so ~72 fit before
     * eviction starts, and tray rows are far smaller than that in practice.
     */
    private const val MAX_SCALED_BYTES = 2 * 1024 * 1024

    /**
     * Scaled representations, keyed by `<origin>@<targetPx>`.
     *
     * paint() used to call Bitmap.createScaledBitmap on every single row bind
     * and throw the result away when the row recycled, so scrolling a tray
     * allocated a bitmap per row per bind. Worse, tray rows are 36dp (108-144px
     * depending on density) while the overwhelming majority of favicons are
     * 16 or 32px, so that call was upscaling: paying memory and CPU to make a
     * picture blurrier and no more detailed.
     *
     * Now a favicon smaller than its row is handed to the ImageView as-is and
     * drawn at the row size by the view itself, which is what it wants. Only a
     * favicon genuinely larger than the row is scaled down, and that result is
     * kept and reused.
     *
     * Eviction is safe with respect to what is on screen: dropping an entry
     * only releases this cache's reference. Any view still drawing that bitmap
     * holds its own, and Bitmaps are immutable once decoded, so it keeps
     * drawing correctly.
     */
    private val scaled = object : android.util.LruCache<String, Bitmap>(MAX_SCALED_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Drops every scaled variant belonging to one origin. */
    private fun invalidateScaled(origin: String) {
        val prefix = "$origin@"
        for (k in scaled.snapshot().keys) {
            if (k.startsWith(prefix)) scaled.remove(k)
        }
    }

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
        val origin = originKey(url)
        val ready = cached(url)
        if (ready != null) {
            paint(badge, image, ready, origin)
            return
        }
        badge.text = letter
        image.setImageDrawable(null)
        image.visibility = android.view.View.GONE
        load(url) {
            if (badge.tag != url) return@load
            cached(url)?.let { paint(badge, image, it, origin) }
        }
    }

    private fun paint(
        badge: android.widget.TextView,
        image: android.widget.ImageView,
        bitmap: Bitmap,
        origin: String,
    ) {
        val size = badge.resources.getDimensionPixelSize(R.dimen.favicon_size)
        val shown = representation(bitmap, origin, size)
        image.setImageBitmap(shown)
        image.visibility = android.view.View.VISIBLE
        badge.text = ""
    }

    /**
     * The bitmap to hand the row, without rebuilding it every bind.
     *
     * A source already at or below the row size is returned untouched. Making a
     * 32px favicon into a 144px bitmap costs 144 KB to hold and produces
     * exactly the picture the view would have drawn itself; ImageView scales at
     * draw time either way.
     */
    private fun representation(bitmap: Bitmap, origin: String, size: Int): Bitmap {
        if (size <= 0 || bitmap.isRecycled) return bitmap
        if (bitmap.width <= size && bitmap.height <= size) return bitmap

        val key = "$origin@$size"
        scaled.get(key)?.let { if (!it.isRecycled) return it }

        val out = Bitmap.createScaledBitmap(bitmap, size, size, true)
        scaled.put(key, out)
        return out
    }

    private fun originKey(url: String): String = runCatching {
        val uri = android.net.Uri.parse(url)
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return ""
        if (uri.scheme !in setOf("http", "https")) return ""
        val port = if (uri.port != -1) ":${uri.port}" else ""
        "${uri.scheme}://${uri.host}$port"
    }.getOrDefault("")
}
