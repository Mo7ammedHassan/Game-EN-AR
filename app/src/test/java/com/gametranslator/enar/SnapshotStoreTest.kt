package com.gametranslator.enar

import com.gametranslator.enar.ocr.OcrSnapshot
import com.gametranslator.enar.ocr.SnapshotStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9 pixel-transport tests: latest-only slot, throttle gating,
 * overwrite/stale accounting, lifecycle, generation monotonicity.
 *
 * No Android classes: deterministic fake clock (`nowMs` is caller-supplied).
 */
class SnapshotStoreTest {

    private fun snap(w: Int = 432, h: Int = 960, seed: Byte = 0): OcrSnapshot =
        OcrSnapshot(w, h, ByteArray(w * h * 3) { (it + seed).toByte() }, 1080, 2400, 123L)

    @Test
    fun `empty store acquires null`() {
        assertNull(SnapshotStore().acquire())
    }

    @Test
    fun `publish then acquire returns latest with generation stamped`() {
        val store = SnapshotStore()
        assertTrue(store.publish(snap(), 1000L))
        val got = store.acquire()
        assertNotNull(got)
        assertEquals(1L, got!!.generation)
        assertEquals(123L, got.timestampNs)
        val stats = store.stats()
        assertEquals(1L, stats.published)
        assertEquals(0L, stats.overwritten)
        assertEquals(0L, stats.throttled)
        assertTrue(stats.hasSnapshot)
    }

    @Test
    fun `newer publish overwrites older (latest wins, counted)`() {
        val store = SnapshotStore(minIntervalMs = 0L)
        assertTrue(store.publish(snap(seed = 1), 1000L))
        assertTrue(store.publish(snap(seed = 2), 1001L))
        val got = store.acquire()!!
        assertEquals(2L, got.generation)
        assertEquals((1 + 2).toByte(), got.rgb[1])
        val stats = store.stats()
        assertEquals(2L, stats.published)
        assertEquals(1L, stats.overwritten)
    }

    @Test
    fun `rapid publishes are throttled, not queued`() {
        val store = SnapshotStore(minIntervalMs = 200L)
        assertTrue(store.publish(snap(seed = 1), 1000L))
        assertFalse(store.publish(snap(seed = 2), 1100L)) // 100ms < 200ms
        assertFalse(store.publish(snap(seed = 3), 1199L))
        assertTrue(store.publish(snap(seed = 4), 1200L)) // boundary: due
        val got = store.acquire()!!
        assertEquals((3 + 4).toByte(), got.rgb[3])
        val stats = store.stats()
        assertEquals(2L, stats.published)
        assertEquals(2L, stats.throttled)
    }

    @Test
    fun `clear drops the slot and store accepts new publishes after`() {
        val store = SnapshotStore()
        assertTrue(store.publish(snap(), 1000L))
        store.clear()
        assertNull(store.acquire())
        assertFalse(store.stats().hasSnapshot)
        assertTrue(store.publish(snap(), 1001L))
        assertNotNull(store.acquire())
    }

    @Test
    fun `acquire does not consume (repeat reads see latest)`() {
        val store = SnapshotStore()
        store.publish(snap(), 0L)
        val a = store.acquire()
        val b = store.acquire()
        assertEquals(a!!.generation, b!!.generation)
    }

    @Test
    fun `generations are monotonic across overwrites`() {
        val store = SnapshotStore(minIntervalMs = 0L)
        val gens = mutableListOf<Long>()
        repeat(5) {
            store.publish(snap(seed = it.toByte()), it.toLong())
            gens.add(store.acquire()!!.generation)
        }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), gens)
    }

    @Test
    fun `snapshot validates its payload`() {
        try {
            OcrSnapshot(0, 10, ByteArray(0), 1080, 2400, 0L)
            org.junit.Assert.fail("zero width must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            OcrSnapshot(4, 4, ByteArray(4 * 4 * 3 - 1), 1080, 2400, 0L)
            org.junit.Assert.fail("short rgb must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        // Scale is uniform (aspect preserved by construction).
        val s = snap(w = 445, h = 960)
        assertEquals(445f / 1080f, s.scale(), 1e-6f)
        assertEquals(10f / s.scale(), s.toFrameX(10f), 1e-3f)
    }
}
