package com.palash.librewolfandroid

/**
 * Bounded LRU with explicit size accounting.
 *
 * This is deliberately free of any Android dependency. The eviction policy is
 * the part of the preview cache that can actually be wrong -- hold too much,
 * evict the wrong entry, keep a stale byte count -- and it is exactly the part
 * that is impossible to verify through `android.util.LruCache`, whose methods
 * throw "not mocked" on a plain JVM. Keeping the policy here means the logic is
 * unit-testable with ordinary objects, while the caller supplies only a way to
 * measure a value's size.
 *
 * Access order is maintained (`LinkedHashMap` with accessOrder = true), so
 * eviction removes the least recently *used* entry, not merely the oldest
 * inserted one. That matters here: the tab the user is looking at now should
 * not be the first thumbnail dropped when memory is tight.
 *
 * Values are expected to be immutable. A caller that mutated a value in place
 * and changed its size would leave the accounting stale; Bitmaps are immutable
 * once decoded, so that cannot happen with [PreviewCache].
 */
internal class BoundedLru<K, V>(
    private val maxBytes: Int,
    private val sizeOf: (V) -> Int,
) {

    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var bytes: Int = 0

    operator fun get(key: K): V? = map[key]

    fun put(key: K, value: V) {
        val incoming = sizeOf(value)
        // An entry larger than the whole budget can never fit. Storing it and
        // immediately evicting it would evict everything else instead.
        if (incoming > maxBytes) return
        map[key]?.let { bytes -= sizeOf(it) }
        map[key] = value
        bytes += incoming
        trimToBudget()
    }

    fun remove(key: K): V? {
        val removed = map.remove(key) ?: return null
        bytes -= sizeOf(removed)
        return removed
    }

    fun clear() {
        map.clear()
        bytes = 0
    }

    fun size(): Int = map.size

    /** Bytes currently accounted for. Never negative. */
    fun byteCount(): Int = bytes

    /** Evict least-recently-used entries until the budget is respected. */
    private fun trimToBudget() {
        val it = map.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val entry = it.next()
            it.remove()
            bytes -= sizeOf(entry.value)
        }
    }
}