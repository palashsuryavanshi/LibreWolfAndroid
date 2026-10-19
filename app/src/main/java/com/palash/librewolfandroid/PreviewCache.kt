package com.palash.librewolfandroid

import android.graphics.Bitmap

/**
 * Bounded in-memory cache of tab page previews.
 *
 * ## Why a cache at all
 *
 * The tray showed only a title and a letter badge. A preview has to come from
 * the real page, and `GeckoView.capturePixels()` is not free: it produces a
 * full-size bitmap that is then scaled down. Capturing on every bind, or on
 * every progress tick, would both churn memory and stall the UI thread while
 * the bitmap is decoded.
 *
 * ## What is cached, and how big
 *
 * Values are already-downscaled thumbnails, sized to the tray card rather than
 * to the page. A full-resolution capture is ~2 MB for a 1080x2400 page; the
 * thumbnail actually displayed is a fraction of that. Storing the full capture
 * would let a tray of twenty tabs hold tens of megabytes of bitmaps nobody can
 * see at that resolution.
 *
 * The bound lives in [BoundedLru], which is pure Kotlin so the eviction policy
 * is unit-testable; this class only supplies the size of a [Bitmap].
 *
 * ## Thread safety
 *
 * [BoundedLru] is not internally synchronised. All access from the app is on
 * the main thread -- `selectTab` and `onPageStop` both run there, and the tray
 * is only ever painted there -- so no lock is needed. The capture callback is
 * dispatched by the engine, and the entry point re-asserts the main thread, as
 * noted at the call site.
 *
 * ## Eviction and what is on screen
 *
 * Eviction drops only this cache's reference. A view that has already been
 * handed a bitmap keeps its own, and a decoded [Bitmap] is immutable, so
 * nothing on screen can be recycled out from under a draw.
 *
 * ## Identity
 *
 * Keys are the stable tab id. A tab that is closed, or whose session is
 * replaced, must not keep a stale preview that then gets shown for a different
 * tab: [remove] is called on close.
 */
class PreviewCache(private val maxBytes: Int = DEFAULT_MAX_BYTES) {

    private val cache = BoundedLru<Long, Bitmap>(maxBytes) { it.byteCount }

    operator fun get(tabId: Long): Bitmap? = cache.get(tabId)?.takeIf { !it.isRecycled }

    fun put(tabId: Long, bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        cache.put(tabId, bitmap)
    }

    /** Drop one tab's preview. Called on close and on session replacement. */
    fun remove(tabId: Long) = cache.remove(tabId)

    /** Drop everything. Used when the engine profile is cleared or reset. */
    fun clear() = cache.clear()

    fun size(): Int = cache.size()

    /** Estimated bytes currently held, for tests and diagnostics. */
    fun byteCount(): Int = cache.byteCount()

    companion object {
        /**
         * Two megabytes. A 1080x2400 ARGB bitmap is ~9.7 MB, so this holds
         * roughly twenty thumbnails at that size, far fewer once the real
         * dimensions are known -- and the tray scrolls correctly with none.
         */
        const val DEFAULT_MAX_BYTES = 2 * 1024 * 1024
    }
}