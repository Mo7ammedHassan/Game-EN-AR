package com.gametranslator.enar

import com.gametranslator.enar.translation.LruTranslationCache
import com.gametranslator.enar.translation.NoOpTranslationCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for [LruTranslationCache].
 *
 * Covers miss/hit/insert/update, the planned capacity bound (500), deterministic
 * LRU eviction and recency refresh, replacement, clear, constructor guards, key
 * isolation (the cache itself never normalizes — that is the decorator's job),
 * and concurrent access invariants.
 */
class LruTranslationCacheTest {

    @Test
    fun `miss on empty cache`() {
        val cache = LruTranslationCache(8)
        assertNull(cache.get("Start"))
        assertEquals(0, cache.size())
    }

    @Test
    fun `insert then hit`() {
        val cache = LruTranslationCache(8)
        cache.put("Start", "ابدأ")
        assertEquals("ابدأ", cache.get("Start"))
        assertEquals(1, cache.size())
    }

    @Test
    fun `replacement of existing key keeps size`() {
        val cache = LruTranslationCache(8)
        cache.put("Start", "ابدأ")
        cache.put("Start", "ابتداء")
        assertEquals("ابتداء", cache.get("Start"))
        assertEquals(1, cache.size())
    }

    @Test
    fun `default capacity is 500`() {
        assertEquals(500, LruTranslationCache.DEFAULT_CAPACITY)
        assertEquals(0, LruTranslationCache().size())
    }

    @Test
    fun `size never exceeds capacity 500`() {
        val cache = LruTranslationCache()
        for (i in 0 until 510) {
            cache.put("key-$i", "قيمة-$i")
        }
        assertEquals(500, cache.size())
        // Newest entries survive; oldest are gone.
        assertEquals("قيمة-509", cache.get("key-509"))
        assertNull(cache.get("key-0"))
        assertNull(cache.get("key-9"))
    }

    @Test
    fun `evicts least-recently-used entry deterministically`() {
        val cache = LruTranslationCache(3)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")
        cache.put("d", "4") // over capacity → evicts "a" (oldest, untouched)

        assertNull(cache.get("a"))
        assertEquals("2", cache.get("b"))
        assertEquals("3", cache.get("c"))
        assertEquals("4", cache.get("d"))
        assertEquals(3, cache.size())
    }

    @Test
    fun `read access refreshes recency`() {
        val cache = LruTranslationCache(3)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")
        assertEquals("1", cache.get("a")) // "a" now most-recently-used
        cache.put("d", "4") // evicts "b", NOT "a"

        assertEquals("1", cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("3", cache.get("c"))
        assertEquals("4", cache.get("d"))
    }

    @Test
    fun `update refreshes recency`() {
        val cache = LruTranslationCache(3)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")
        cache.put("a", "1-new") // rewrite counts as use
        cache.put("d", "4") // evicts "b"

        assertEquals("1-new", cache.get("a"))
        assertNull(cache.get("b"))
    }

    @Test
    fun `clear empties the cache`() {
        val cache = LruTranslationCache(4)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get("a"))
    }

    @Test
    fun `non-positive capacity rejected`() {
        try {
            LruTranslationCache(0)
            fail("capacity 0 must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            LruTranslationCache(-5)
            fail("negative capacity must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `keys are literal — no hidden normalization`() {
        // Canonicalization is CachedTranslationEngine's job; the map itself is exact.
        val cache = LruTranslationCache(8)
        cache.put("Score 50", "نقاط 50")
        assertNull(cache.get("Score  50"))
        assertNull(cache.get("score 50"))
        assertEquals("نقاط 50", cache.get("Score 50"))
    }

    @Test
    fun `noop cache contract still holds`() {
        val cache = NoOpTranslationCache()
        cache.put("k", "v")
        assertNull(cache.get("k"))
        assertEquals(0, cache.size())
    }

    @Test
    fun `concurrent access stays bounded and consistent`() {
        val cache = LruTranslationCache(50)
        val threads = 8
        val opsPerThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        repeat(threads) { t ->
            pool.execute {
                try {
                    start.await()
                    for (i in 0 until opsPerThread) {
                        val key = "t$t-key-${i % 25}"
                        cache.put(key, "v-$i")
                        cache.get(key)
                        cache.get("absent")
                        cache.size()
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue("threads did not finish", pool.awaitTermination(60, TimeUnit.SECONDS))
        if (failure.get() != null) throw AssertionError(failure.get())

        // Invariants that must hold regardless of interleaving.
        assertTrue("size must stay bounded: ${cache.size()}", cache.size() <= 50)
        assertTrue("size must be positive", cache.size() > 0)
    }
}
