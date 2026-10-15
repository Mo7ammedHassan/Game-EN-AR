package com.gametranslator.enar

import com.gametranslator.enar.detection.RegionTracker
import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.TextRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [RegionTracker].
 *
 * Covers IoU geometry (incl. the strict > 0.6 boundary), identity across frames,
 * appear/disappear/expiry, multi-region and ambiguous matching, determinism,
 * constructor guards, and degenerate boxes. No Android dependencies.
 */
class RegionTrackerTest {

    private fun box(l: Int, t: Int, r: Int, b: Int) = BoundingBox(l, t, r, b)

    private fun det(id: String, bounds: BoundingBox) = TextRegion(
        id = id,
        bounds = bounds,
        confidence = 0.9f
    )

    // ---- IoU geometry ----

    @Test
    fun `iou identical boxes is one`() {
        assertEquals(1.0, RegionTracker.iou(box(0, 0, 10, 10), box(0, 0, 10, 10)), 1e-9)
    }

    @Test
    fun `iou disjoint boxes is zero`() {
        assertEquals(0.0, RegionTracker.iou(box(0, 0, 10, 10), box(20, 20, 30, 30)), 1e-9)
    }

    @Test
    fun `iou partial overlap measured`() {
        // inter=50, union=150 → 1/3.
        assertEquals(1.0 / 3.0, RegionTracker.iou(box(0, 0, 10, 10), box(5, 0, 15, 10)), 1e-9)
    }

    @Test
    fun `iou degenerate boxes are zero not nan`() {
        assertEquals(0.0, RegionTracker.iou(box(5, 5, 5, 10), box(0, 0, 10, 10)), 1e-9)
        assertEquals(0.0, RegionTracker.iou(box(0, 0, 0, 0), box(0, 0, 0, 0)), 1e-9)
        val iou = RegionTracker.iou(box(0, 0, 10, 10), box(0, 0, 10, 10))
        assertTrue(iou.isFinite())
    }

    // ---- Identity & threshold ----

    @Test
    fun `identical region keeps identity`() {
        val tracker = RegionTracker()
        val first = tracker.update(listOf(det("engine-1", box(0, 0, 100, 20))))
        val second = tracker.update(listOf(det("engine-9", box(0, 0, 100, 20))))

        assertEquals(1, first.size)
        assertEquals("track-0", first[0].trackId)
        assertEquals("track-0", second[0].trackId)
        // Detector ids are rewritten to the stable track id.
        assertEquals("track-0", second[0].region.id)
        assertEquals(1, tracker.activeTrackCount())
    }

    @Test
    fun `small movement within threshold keeps track and refreshes bounds`() {
        val tracker = RegionTracker()
        tracker.update(listOf(det("a", box(0, 0, 100, 20))))
        // IoU = 1900/2100 ≈ 0.905 > 0.6.
        val moved = tracker.update(listOf(det("b", box(5, 0, 105, 20))))

        assertEquals("track-0", moved[0].trackId)
        assertEquals(box(5, 0, 105, 20), moved[0].region.bounds)
    }

    @Test
    fun `exact iou 0_6 does NOT match — strict threshold`() {
        // inter=150, union=250 → exactly 0.6.
        assertEquals(0.6, RegionTracker.iou(box(0, 0, 20, 10), box(5, 0, 25, 10)), 1e-9)

        val tracker = RegionTracker()
        tracker.update(listOf(det("a", box(0, 0, 20, 10))))
        val result = tracker.update(listOf(det("b", box(5, 0, 25, 10))))

        assertEquals("track-1", result[0].trackId) // new track: boundary does not match
        assertEquals(2, tracker.activeTrackCount()) // old track retained within grace
    }

    @Test
    fun `just above threshold matches`() {
        // inter=160, union=240 → 0.6667.
        val tracker = RegionTracker()
        tracker.update(listOf(det("a", box(0, 0, 20, 10))))
        val result = tracker.update(listOf(det("b", box(4, 0, 24, 10))))

        assertEquals("track-0", result[0].trackId)
    }

    @Test
    fun `large movement opens new track, old ages in grace`() {
        val tracker = RegionTracker()
        tracker.update(listOf(det("a", box(0, 0, 100, 20))))
        // IoU = 400/3600 ≈ 0.111.
        val result = tracker.update(listOf(det("b", box(80, 0, 180, 20))))

        assertEquals(1, result.size)
        assertEquals("track-1", result[0].trackId)
        assertEquals(2, tracker.activeTrackCount())
    }

    // ---- Appear / disappear / expiry ----

    @Test
    fun `region appearing from empty frame opens track-0`() {
        val tracker = RegionTracker()
        assertTrue(tracker.update(emptyList()).isEmpty())
        val result = tracker.update(listOf(det("a", box(0, 0, 50, 10))))
        assertEquals("track-0", result[0].trackId)
    }

    @Test
    fun `absent track expires after grace window`() {
        val tracker = RegionTracker(maxMissedFrames = 3)
        tracker.update(listOf(det("a", box(0, 0, 50, 10))))

        tracker.update(emptyList()) // missed=1: retained
        assertEquals(1, tracker.activeTrackCount())
        tracker.update(emptyList()) // missed=2: retained
        assertEquals(1, tracker.activeTrackCount())
        tracker.update(emptyList()) // missed=3: retained (tolerated)
        assertEquals(1, tracker.activeTrackCount())
        tracker.update(emptyList()) // missed=4 > 3: removed
        assertEquals(0, tracker.activeTrackCount())
    }

    @Test
    fun `zero grace removes immediately`() {
        val tracker = RegionTracker(maxMissedFrames = 0)
        tracker.update(listOf(det("a", box(0, 0, 50, 10))))
        tracker.update(emptyList())
        assertEquals(0, tracker.activeTrackCount())
    }

    @Test
    fun `reappearance after expiry opens new track`() {
        val tracker = RegionTracker(maxMissedFrames = 0)
        tracker.update(listOf(det("a", box(0, 0, 50, 10))))
        tracker.update(emptyList())
        val result = tracker.update(listOf(det("b", box(0, 0, 50, 10))))
        assertEquals("track-1", result[0].trackId) // NOT track-0: stale identity forgotten
    }

    // ---- Multiple & ambiguous ----

    @Test
    fun `multiple regions tracked independently in creation order`() {
        val tracker = RegionTracker()
        tracker.update(
            listOf(
                det("a", box(0, 0, 100, 20)),
                det("b", box(0, 30, 100, 50))
            )
        )
        val result = tracker.update(
            listOf(
                det("c", box(0, 30, 100, 50)),
                det("d", box(2, 0, 102, 20))
            )
        )

        assertEquals(listOf("track-0", "track-1"), result.map { it.trackId })
        assertEquals(box(2, 0, 102, 20), result[0].region.bounds)
    }

    @Test
    fun `one track two detections — better overlap wins, loser opens track`() {
        val tracker = RegionTracker()
        tracker.update(listOf(det("t", box(0, 0, 100, 20))))
        // detA IoU ≈ 0.961, detB IoU ≈ 0.818 — both above threshold.
        val result = tracker.update(
            listOf(
                det("b", box(10, 0, 110, 20)),
                det("a", box(2, 0, 102, 20))
            )
        )

        val byId = result.associateBy { it.trackId }
        assertEquals(box(2, 0, 102, 20), byId["track-0"]!!.region.bounds)
        assertEquals(box(10, 0, 110, 20), byId["track-1"]!!.region.bounds)
    }

    @Test
    fun `two tracks one detection — tie goes to older track deterministically`() {
        val tracker = RegionTracker()
        tracker.update(
            listOf(
                det("a", box(0, 0, 100, 20)),
                det("b", box(10, 0, 110, 20))
            )
        )
        // Equal IoU (≈0.905) against both tracks.
        val result = tracker.update(listOf(det("c", box(5, 0, 105, 20))))

        assertEquals(1, result.size)
        assertEquals("track-0", result[0].trackId)
        assertEquals(2, tracker.activeTrackCount()) // track-1 ages, not removed
    }

    @Test
    fun `same input twice yields identical assignment`() {
        val frames = listOf(
            listOf(det("a", box(0, 0, 100, 20)), det("b", box(0, 30, 100, 50))),
            listOf(det("c", box(3, 0, 103, 20)), det("d", box(0, 33, 100, 53)))
        )
        fun run(): List<String> {
            val tracker = RegionTracker()
            return frames.flatMap { tracker.update(it) }.map { it.trackId }
        }
        assertEquals(run(), run())
    }

    // ---- Guards & lifecycle ----

    @Test
    fun `constructor guards reject nonsense`() {
        try {
            RegionTracker(iouThreshold = 0.0)
            fail("threshold 0 must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            RegionTracker(iouThreshold = 1.5)
            fail("threshold > 1 must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            RegionTracker(maxMissedFrames = -1)
            fail("negative grace must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `reset clears tracks and restarts numbering`() {
        val tracker = RegionTracker()
        tracker.update(listOf(det("a", box(0, 0, 50, 10))))
        tracker.reset()
        assertEquals(0, tracker.activeTrackCount())
        val result = tracker.update(listOf(det("b", box(0, 0, 50, 10))))
        assertEquals("track-0", result[0].trackId)
    }
}
