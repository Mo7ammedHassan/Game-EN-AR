package com.gametranslator.enar.translation

/**
 * Bounded least-recently-used [TranslationCache] backed by an access-ordered
 * `LinkedHashMap`.
 *
 * Contract (per implementation-plan.md Phase 5):
 * - Capacity is fixed at construction (default [DEFAULT_CAPACITY] = 500); the
 *   eldest (least-recently-used) entry is evicted deterministically on overflow.
 * - `get`/`put` are O(1) expected time; every access (hit AND update) refreshes
 *   recency. No background threads, no unbounded growth: memory is bounded by
 *   `capacity` live entries plus one transient eldest during `put`.
 * - Thread-safe for the asynchronous translation pipeline via a single intrinsic
 *   lock around the map. Contention is a short map probe (no I/O, no inference
 *   under the lock). Concurrent duplicate misses may translate twice (last write
 *   wins); translation is deterministic, so this is benign by design.
 * - The hot `get` path allocates nothing beyond the map internals (no copies,
 *   no boxing beyond the stored reference).
 */
class LruTranslationCache(
    private val capacity: Int = DEFAULT_CAPACITY
) : TranslationCache {

    companion object {
        /** Phase 5 planned bound on live entries. */
        const val DEFAULT_CAPACITY = 500
    }

    init {
        require(capacity > 0) { "LRU cache capacity must be positive, got $capacity" }
    }

    private val lock = Any()

    private val map = object : LinkedHashMap<String, String>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            // Evaluated inside put() while holding [lock]: deterministic LRU eviction.
            return size > capacity
        }
    }

    override fun get(sourceText: String): String? = synchronized(lock) {
        map[sourceText]
    }

    override fun put(sourceText: String, translatedText: String) {
        synchronized(lock) {
            map[sourceText] = translatedText
        }
    }

    override fun clear() {
        synchronized(lock) {
            map.clear()
        }
    }

    override fun size(): Int = synchronized(lock) {
        map.size
    }
}
