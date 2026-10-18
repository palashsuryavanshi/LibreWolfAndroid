package com.palash.librewolfandroid

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class HistoryEntry(val title: String, val url: String, val time: Long)

/**
 * Local browsing history (device-only, no sync — LibreWolf policy).
 *
 * ## Why this is not a plain SharedPreferences read-modify-write any more
 *
 * The original implementation parsed the whole JSON array out of
 * SharedPreferences, mutated it and wrote it back, synchronously, under
 * `@Synchronized`. `MainActivity` calls [add] from `onPageStop` for every
 * successful page load, so every navigation performed a full parse plus a full
 * serialise plus a disk write on the main thread, and the cost grew with the
 * number of entries up to the [MAX] retention limit.
 *
 * The shape now is: an in-memory list is the single source of truth, and disk
 * is a follower.
 *
 * - The in-memory list is only ever touched under [lock], and the operations on
 *   it are list edits measured in microseconds. That is the only work the main
 *   thread does, and it never parses, serialises or touches disk.
 * - [io] is a single thread, so every disk operation is serialised against
 *   every other. A queued write can never land after a later clear, and a
 *   rapid burst of navigations cannot interleave two read-modify-writes.
 * - The initial load happens on [io] at construction, so the navigation path
 *   never pays for it.
 *
 * ## Ordering and visibility
 *
 * [add] mutates the in-memory list synchronously, so [all] immediately
 * afterwards returns the new entry — a caller that opens History a moment
 * after a page loads sees it, with no sleep and no refresh delay. Only the
 * *persistence* of that state is deferred. If the process dies before the
 * queued write lands, the in-flight navigation is lost from history, which is
 * the same guarantee `onPause` already provides elsewhere and is preferable to
 * blocking the UI thread on every page load.
 *
 * ## Why the queue cannot grow without bound
 *
 * Every mutation that needs writing sets [persistQueued]. If a write is already
 * pending, no second task is submitted: the pending task serialises whatever
 * the list holds when it runs. Navigating quickly therefore collapses into a
 * bounded number of writes instead of one per navigation, and the final state
 * on disk is always the latest list.
 *
 * ## Testability
 *
 * Nothing above requires `run-as` or a debuggable build: [readFromDisk] and
 * [writeToDisk] take an injectable [SharedPreferences], so the 300-entry case
 * can be exercised on the JVM in `HistoryStoreTest` against a fake. The app's
 * own history is never touched by that test.
 */
class HistoryStore(context: Context) {

    private val prefs: android.content.SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "librewolf-history").apply {
            isDaemon = true
            // History writing must never compete with rendering.
            priority = Thread.MIN_PRIORITY
        }
    }

    private val lock = Any()

    /** Null until the first load completes. Guarded by [lock]. */
    private var cache: MutableList<HistoryEntry>? = null

    private val persistQueued = AtomicBoolean(false)

    init {
        // Load on the writer thread so the navigation path never parses.
        io.execute {
            synchronized(lock) {
                if (cache == null) {
                    cache = readFromDisk(prefs).toMutableList()
                }
            }
        }
    }

    // ---- public API -------------------------------------------------------

    fun add(title: String, url: String) {
        if (!isRecordable(url)) return
        synchronized(lock) {
            val list = cache
            if (list == null) {
                // Not loaded yet. Do the whole read-modify-write on the writer
                // thread so the main thread still never parses. Ordering is
                // preserved because io is single threaded.
                io.execute {
                    synchronized(lock) {
                        val existing = cache ?: readFromDisk(prefs).toMutableList()
                            .also { cache = it }
                        appendLocked(existing, title, url)
                        writeToDisk(prefs, existing)
                    }
                }
                return
            }
            appendLocked(list, title, url)
        }
        schedulePersist()
    }

    fun all(): List<HistoryEntry> = synchronized(lock) {
        // Cold read only: if the background load has not landed yet (a screen
        // opened in the first few milliseconds of process start), read here so
        // the caller sees real history rather than an empty list. This is not on
        // the navigation path.
        if (cache == null) {
            cache = readFromDisk(prefs).toMutableList()
        }
        cache!!.toList()
    }

    fun remove(url: String) {
        synchronized(lock) { if (cache != null) cache!!.removeAll { it.url == url } }
        schedulePersist()
    }

    fun clear() {
        synchronized(lock) { cache = mutableListOf() }
        // Queued behind any pending add on the single writer thread, so a clear
        // can never be followed by an older write resurrecting cleared history.
        schedulePersist()
    }

    fun clearBefore(cutoff: Long) {
        synchronized(lock) {
            if (cache != null) {
                val kept = cache!!.filter { it.time >= cutoff }
                cache = kept.toMutableList()
            }
        }
        schedulePersist()
    }

    // ---- internals --------------------------------------------------------

    private fun isRecordable(url: String): Boolean {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return false
        if (url == "about:blank") return false
        return true
    }

    /** Caller holds [lock]. */
    private fun appendLocked(list: MutableList<HistoryEntry>, title: String, url: String) {
        list.removeAll { it.url == url }
        list.add(0, HistoryEntry(title.ifEmpty { url }, url, System.currentTimeMillis()))
        while (list.size > MAX) list.removeAt(list.size - 1)
    }

    /**
     * Submits at most one pending write. A second mutation while a write is
     * queued does not queue another; the queued task serialises the list as it
     * stands when it runs.
     */
    private fun schedulePersist() {
        if (!persistQueued.compareAndSet(false, true)) return
        io.execute {
            try {
                val snapshot = synchronized(lock) { cache?.toList() ?: return@execute }
                writeToDisk(prefs, snapshot)
            } finally {
                persistQueued.set(false)
            }
        }
    }

    companion object {
        private const val PREFS = "librewolf_history"
        private const val K_LIST = "entries"
        const val MAX = 300

        internal fun readFromDisk(prefs: android.content.SharedPreferences): List<HistoryEntry> {
            val out = mutableListOf<HistoryEntry>()
            val raw = try {
                prefs.getString(K_LIST, "[]")
            } catch (_: Exception) {
                // A value of the wrong type is a corrupt store, not a crash.
                return out
            }
            val arr = try {
                JSONArray(raw ?: "[]")
            } catch (_: Exception) {
                return out
            }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(HistoryEntry(o.optString("t"), o.optString("u"), o.optLong("d")))
            }
            return out
        }

        internal fun writeToDisk(
            prefs: android.content.SharedPreferences,
            entries: List<HistoryEntry>,
        ) {
            val arr = JSONArray()
            for (e in entries) {
                arr.put(JSONObject().put("t", e.title).put("u", e.url).put("d", e.time))
            }
            // apply(): the caller is already off the main thread, and apply
            // keeps the write off this thread's disk I/O as well.
            prefs.edit { putString(K_LIST, arr.toString()) }
        }
    }
}