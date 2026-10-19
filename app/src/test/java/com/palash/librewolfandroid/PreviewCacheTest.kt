package com.palash.librewolfandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eviction policy that keeps preview memory bounded.
 *
 * These run against ordinary objects, because the policy was deliberately kept
 * free of Android types. An earlier version of this test exercised
 * [PreviewCache] itself and could not run at all: `android.util.LruCache` is a
 * JVM stub whose methods throw, so the test failed before it asserted
 * anything. Testing the policy directly is what makes the claims below mean
 * something.
 */
class BoundedLruTest {

    private fun cache(max: Int) = BoundedLru<String, Int>(max) { it }

    @Test
    fun aStoredValueIsRetrieved() {
        val c = cache(100)
        c.put("a", 10)
        assertEquals(10, c.get("a"))
    }

    @Test
    fun anUnknownKeyReturnsNull() {
        assertNull(cache(100).get("nope"))
    }

    @Test
    fun evictsLeastRecentlyUsedWhenOverBudget() {
        // Budget 20. Insert three, each of size 10: after "c" the total is 30
        // and something must go. "a" is the oldest, so it goes.
        val c = cache(20)
        c.put("a", 10)
        c.put("b", 10)
        c.put("c", 10)

        assertNull("the least recently used entry should be evicted", c.get("a"))
        assertNotNull("recent entries survive", c.get("b"))
        assertNotNull("recent entries survive", c.get("c"))
        assertTrue("two entries of 10 fit exactly in 20", c.byteCount() <= 20)
    }

    @Test
    fun readingAnEntryProtectsItFromEviction() {
        // Touch "a" after inserting it, so "b" becomes the least recently used
        // and is the one dropped when "c" arrives. This is the behaviour that
        // keeps the tab the user is looking at from being the first to lose its
        // thumbnail.
        val c = cache(20)
        c.put("a", 10)
        c.put("b", 10)
        assertEquals(10, c.get("a"))     // touch "a"
        c.put("c", 10)

        assertNotNull("the touched entry is the most recently used", c.get("a"))
        assertNull("the untouched older entry is evicted instead", c.get("b"))
        assertNotNull(c.get("c"))
    }

    @Test
    fun aValueLargerThanTheWholeBudgetIsRefused() {
        // Otherwise the oversized entry is stored and then evicted, which would
        // evict every legitimate entry to make room for something that cannot
        // fit anyway.
        val c = cache(10)
        c.put("huge", 5000)
        assertNull(c.get("huge"))
        assertEquals(0, c.size())
    }

    @Test
    fun replacingAKeyDoesNotDoubleCountItsBytes() {
        val c = cache(100)
        c.put("a", 10)
        c.put("a", 20)
        assertEquals("the old value's size must be released first", 20, c.byteCount())
        assertEquals(1, c.size())
    }

    @Test
    fun byteCountNeverGoesNegative() {
        val c = cache(100)
        c.put("a", 10)
        c.remove("a")
        assertEquals(0, c.byteCount())
        c.remove("a")                       // removing again is a no-op
        assertEquals(0, c.byteCount())
    }

    @Test
    fun removeDropsOnlyTheNamedEntry() {
        val c = cache(100)
        c.put("a", 10)
        c.put("b", 10)
        c.remove("a")
        assertNull(c.get("a"))
        assertNotNull("removing one key must not disturb another", c.get("b"))
        assertEquals(10, c.byteCount())
    }

    @Test
    fun clearDropsEverything() {
        val c = cache(100)
        c.put("a", 10)
        c.put("b", 20)
        c.clear()
        assertEquals(0, c.size())
        assertEquals(0, c.byteCount())
    }

    @Test
    fun aZeroBudgetRefusesEverything() {
        val c = cache(0)
        c.put("a", 1)
        assertEquals(0, c.size())
        assertEquals(0, c.byteCount())
    }

    @Test
    fun keysAreIndependent() {
        // A preview attached to the wrong tab would be a visible defect, so the
        // keying is worth an explicit assertion.
        val c = cache(100)
        c.put("1", 10)
        c.put("2", 10)
        assertEquals(10, c.get("1"))
        assertEquals(10, c.get("2"))
        c.remove("1")
        assertNull(c.get("1"))
        assertEquals(10, c.get("2"))
    }
}

/**
 * [PreviewCache] itself is thin: it supplies a size function and nothing else.
 * Its construction and reporting are checked here, because they no longer touch
 * any Android type at construction time.
 */
class PreviewCacheTest {

    @Test
    fun constructsWithTheDefaultBudget() {
        val c = PreviewCache()
        assertNotNull(c)
        assertEquals(0, c.size())
        assertEquals(0, c.byteCount())
    }

    @Test
    fun constructsWithACustomBudget() {
        val c = PreviewCache(maxBytes = 1024)
        assertEquals(0, c.byteCount())
    }

    @Test
    fun clearingReportsZero() {
        val c = PreviewCache()
        c.clear()
        assertEquals(0, c.size())
        assertEquals(0, c.byteCount())
    }

    @Test
    fun removingAnUnknownTabIsSafe() {
        // Tab ids come from a counter; a stale removal after a close must not
        // throw or corrupt the accounting.
        val c = PreviewCache()
        c.remove(99L)
        c.remove(-1L)
        assertEquals(0, c.byteCount())
    }

    @Test
    fun theDefaultBudgetIsBounded() {
        assertTrue(PreviewCache.DEFAULT_MAX_BYTES > 0)
        assertTrue(
            "a bounded cache must not be unbounded",
            PreviewCache.DEFAULT_MAX_BYTES <= 4 * 1024 * 1024,
        )
    }
}