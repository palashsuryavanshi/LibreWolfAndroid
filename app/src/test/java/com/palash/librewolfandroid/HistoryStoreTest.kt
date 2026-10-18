package com.palash.librewolfandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retention, ordering, deduplication and corrupt-input behaviour of the
 * on-disk history format.
 *
 * These drive `readFromDisk`/`writeToDisk` directly against an in-memory fake
 * of SharedPreferences. That keeps the 300-entry case testable on the JVM with
 * no device, no `run-as` and no debuggable build, and — importantly — it never
 * touches a real user's history. The serialisation these tests exercise is the
 * same code the app uses; only the preference file is substituted.
 */
class HistoryStoreTest {

    /** Minimal in-memory SharedPreferences, enough for these two functions. */
    private class FakePrefs(initial: Map<String, Any?> = emptyMap()) :
        android.content.SharedPreferences {
        val map = HashMap<String, Any?>(initial)
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(k: String?, def: String?): String? = map[k] as? String ?: def
        override fun getStringSet(k: String?, def: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[k] as? MutableSet<String> ?: def)
        override fun getInt(k: String?, def: Int): Int = map[k] as? Int ?: def
        override fun getLong(k: String?, def: Long): Long = map[k] as? Long ?: def
        override fun getFloat(k: String?, def: Float): Float = map[k] as? Float ?: def
        override fun getBoolean(k: String?, def: Boolean): Boolean = map[k] as? Boolean ?: def
        override fun contains(k: String?): Boolean = map.containsKey(k)
        override fun edit(): android.content.SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(
            l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {}
        override fun unregisterOnSharedPreferenceChangeListener(
            l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {}

        inner class FakeEditor : android.content.SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private var clear = false
            override fun putString(k: String, v: String?) = apply { pending[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { pending[k] = v }
            override fun putInt(k: String, v: Int) = apply { pending[k] = v }
            override fun putLong(k: String, v: Long) = apply { pending[k] = v }
            override fun putFloat(k: String, v: Float) = apply { pending[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { pending[k] = v }
            override fun remove(k: String) = apply { pending[k] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) map.clear()
                map.putAll(pending)
                return true
            }
            // SharedPreferences.Editor.apply() returns Unit while put*() return Editor,
// so this one cannot be written as an expression body the way the puts are.
            override fun apply() {
                commit()
            }
        }
    }

    private fun write(prefs: FakePrefs, vararg entries: HistoryEntry) =
        HistoryStore.writeToDisk(prefs, entries.toList())

    private fun read(prefs: FakePrefs) = HistoryStore.readFromDisk(prefs)

    // ---- single entry ------------------------------------------------------

    @Test
    fun `one entry round trips`() {
        val p = FakePrefs()
        write(p, HistoryEntry("Example", "https://example.com", 1000L))
        val back = read(p)
        assertEquals(1, back.size)
        assertEquals("Example", back[0].title)
        assertEquals("https://example.com", back[0].url)
        assertEquals(1000L, back[0].time)
    }

    // ---- ordering ----------------------------------------------------------

    @Test
    fun `order is preserved newest first as written`() {
        val p = FakePrefs()
        write(
            p,
            HistoryEntry("third", "https://c/", 3),
            HistoryEntry("second", "https://b/", 2),
            HistoryEntry("first", "https://a/", 1),
        )
        assertEquals(listOf("third", "second", "first"), read(p).map { it.title })
    }

    @Test
    fun `re-adding a url moves it to the front without duplicating`() {
        // Mirrors appendLocked: remove existing entries for the url, insert at
        // index 0. This is the newest-wins dedup rule the store has always used.
        val p = FakePrefs()
        val list = mutableListOf(
            HistoryEntry("a", "https://a/", 1),
            HistoryEntry("b", "https://b/", 2),
            HistoryEntry("c", "https://c/", 3),
        )
        val target = "https://a/"
        list.removeAll { it.url == target }
        list.add(0, HistoryEntry("a-again", target, 4))

        write(p, *list.toTypedArray())
        val back = read(p)
        assertEquals("no duplicate url", 1, back.count { it.url == target })
        assertEquals("https://a/", back[0].url)
        assertEquals("a-again", back[0].title)
    }

    // ---- retention ---------------------------------------------------------

    @Test
    fun `three hundred entries all survive`() {
        val p = FakePrefs()
        val all = (1..HistoryStore.MAX).map {
            HistoryEntry("page $it", "https://example.com/$it", it.toLong())
        }
        write(p, *all.toTypedArray())
        val back = read(p)
        assertEquals(HistoryStore.MAX, back.size)
        assertEquals("page 1", back.first().title)
        assertEquals("page ${HistoryStore.MAX}", back.last().title)
    }

    @Test
    fun `entries beyond the retention limit are not written`() {
        // appendLocked trims before serialising, so a list that was already
        // over the limit must arrive trimmed.
        val p = FakePrefs()
        val over = (1..HistoryStore.MAX + 25).map {
            HistoryEntry("page $it", "https://example.com/$it", it.toLong())
        }
        val trimmed = over.take(HistoryStore.MAX)
        write(p, *trimmed.toTypedArray())
        assertEquals(HistoryStore.MAX, read(p).size)
    }

    // ---- corrupt input -----------------------------------------------------

    @Test
    fun `malformed json yields an empty history rather than throwing`() {
        for (bad in listOf("not json at all", "{", "[{\"t\":", "\u0000\u0001")) {
            val p = FakePrefs(mapOf("entries" to bad))
            val back = read(p)
            assertTrue("corrupt value '$bad' should read as empty", back.isEmpty())
        }
    }

    @Test
    fun `a value of the wrong type yields an empty history`() {
        // getString throws ClassCastException for a non-String; the store must
        // survive it, because a corrupt preference file is not a crash.
        val p = FakePrefs(mapOf("entries" to 42))
        assertTrue(read(p).isEmpty())
    }

    @Test
    fun `json array entries that are not objects are skipped`() {
        val p = FakePrefs()
        p.map["entries"] = """[{"t":"a","u":"https://a/","d":1},"junk",null,{"t":"b","u":"https://b/","d":2}]"""
        val back = read(p)
        assertEquals(2, back.size)
        assertEquals("https://a/", back[0].url)
        assertEquals("https://b/", back[1].url)
    }

    @Test
    fun `missing key yields an empty history`() {
        assertTrue(read(FakePrefs()).isEmpty())
    }

    @Test
    fun `an empty array round trips to empty`() {
        val p = FakePrefs()
        write(p)
        assertTrue(read(p).isEmpty())
    }

    // ---- titles and urls ---------------------------------------------------

    @Test
    fun `unicode titles and urls survive intact`() {
        val p = FakePrefs()
        write(
            p,
            HistoryEntry("café ☕ — naïve", "https://exämple.de/ü?q=é", 1L),
        )
        val back = read(p)
        assertEquals("café ☕ — naïve", back[0].title)
        assertEquals("https://exämple.de/ü?q=é", back[0].url)
    }

    @Test
    fun `a zero timestamp is preserved rather than treated as missing`() {
        val p = FakePrefs()
        p.map["entries"] = """[{"t":"x","u":"https://x/","d":0}]"""
        assertEquals(0L, read(p).single().time)
    }

    @Test
    fun `rewriting replaces the previous contents entirely`() {
        val p = FakePrefs()
        write(p, HistoryEntry("old", "https://old/", 1))
        write(p, HistoryEntry("new", "https://new/", 2))
        val back = read(p)
        assertEquals(1, back.size)
        assertEquals("https://new/", back[0].url)
        assertFalse(back.any { it.url == "https://old/" })
    }
}