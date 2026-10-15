package com.gametranslator.enar

import com.gametranslator.enar.detection.RegionTracker
import com.gametranslator.enar.detection.OcrResultStabilizer
import com.gametranslator.enar.detection.TrackState
import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.OcrResult
import com.gametranslator.enar.model.TextRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for the tracker → stabilizer composition contract that
 * Phase 7 will wire into the live pipeline (the coordinator itself is
 * deliberately NOT rewired by this task):
 *
 * ```
 * detections → tracker.update() → prune(activeTrackIds) → stabilize() per track
 * ```
 *
 * Region identity for stabilization ALWAYS comes from the tracker (`trackId`),
 * never from volatile detector ids. Tracker and stabilizer grace windows are
 * paired (`maxMissedFrames = 2` on both) so a track expires on both layers in
 * the same cycle.
 */
class TrackerStabilizerIntegrationTest {

    private lateinit var tracker: RegionTracker
    private lateinit var stabilizer: OcrResultStabilizer

    @Before
    fun setUp() {
        tracker = RegionTracker(maxMissedFrames = 2)
        stabilizer = OcrResultStabilizer(windowSize = 3, maxEditDistance = 1, maxMissedFrames = 2)
    }

    private fun box(l: Int, t: Int, r: Int, b: Int) = BoundingBox(l, t, r, b)

    /**
     * Simulates one OCR cycle. Returns (trackId, stabilized result) pairs in
     * track-creation order.
     */
    private fun cycle(items: List<Pair<BoundingBox, String>>): List<Pair<String, OcrResult>> {
        val detections = items.mapIndexed { index, (bounds, _) ->
            TextRegion(id = "engine-$index", bounds = bounds, confidence = 0.9f)
        }
        val textByBounds = items.toMap()
        val tracked = tracker.update(detections)
        stabilizer.pruneStaleRegions(tracked.map { it.trackId }.toSet())
        return tracked.map { t ->
            val text = textByBounds[t.region.bounds] ?: ""
            t.trackId to stabilizer.stabilize(
                OcrResult(
                    regionId = t.trackId,
                    rawText = text,
                    confidence = 0.9f,
                    bounds = t.region.bounds
                )
            )
        }
    }

    @Test
    fun `static dialogue stabilizes on third frame under one track`() {
        val boxA = box(0, 0, 200, 40)
        val r1 = cycle(listOf(boxA to "Hello traveler"))
        val r2 = cycle(listOf(boxA to "Hello traveler"))
        val r3 = cycle(listOf(boxA to "Hello traveler"))

        assertEquals(listOf("track-0"), r1.map { it.first })
        assertFalse(r1[0].second.isStable)
        assertFalse(r2[0].second.isStable)
        assertTrue(r3[0].second.isStable)
        assertEquals(TrackState.STABLE, stabilizer.getState("track-0"))
    }

    @Test
    fun `jitter within threshold keeps identity and stabilizes`() {
        cycle(listOf(box(0, 0, 200, 40) to "Hello traveler"))
        cycle(listOf(box(2, 1, 202, 41) to "Hello traveler"))
        val r3 = cycle(listOf(box(4, 0, 204, 40) to "Hello traveler"))

        assertEquals(listOf("track-0"), r3.map { it.first })
        assertTrue(r3[0].second.isStable)
    }

    @Test
    fun `teleport opens new track without transferring stability`() {
        val near = box(0, 0, 200, 40)
        val far = box(600, 400, 800, 440)
        repeat(3) { cycle(listOf(near to "Hello traveler")) }
        assertEquals(TrackState.STABLE, stabilizer.getState("track-0"))

        val moved = cycle(listOf(far to "Hello traveler"))
        assertEquals(listOf("track-1"), moved.map { it.first })
        // Same text, but a NEW track must re-prove itself from scratch.
        assertFalse(moved[0].second.isStable)
        assertEquals(TrackState.OBSERVING, stabilizer.getState("track-1"))
        // Old track ages gracefully instead of vanishing.
        assertEquals(TrackState.DISAPPEARED, stabilizer.getState("track-0"))
    }

    @Test
    fun `dialogue change transitions changed then stable with new text`() {
        val boxA = box(0, 0, 200, 40)
        repeat(3) { cycle(listOf(boxA to "Hello traveler")) }

        val changed = cycle(listOf(boxA to "Farewell hero"))
        assertFalse(changed[0].second.isStable)
        assertEquals(TrackState.CHANGED, stabilizer.getState("track-0"))

        cycle(listOf(boxA to "Farewell hero"))
        val restabilized = cycle(listOf(boxA to "Farewell hero"))
        assertTrue(restabilized[0].second.isStable)
        assertEquals("Farewell hero", stabilizer.getStableText("track-0"))
    }

    @Test
    fun `one-frame detection gap resumes immediately`() {
        val boxA = box(0, 0, 200, 40)
        repeat(3) { cycle(listOf(boxA to "Hello traveler")) }

        val gap = cycle(emptyList())
        assertTrue(gap.isEmpty())
        assertEquals(TrackState.DISAPPEARED, stabilizer.getState("track-0"))

        val back = cycle(listOf(boxA to "Hello traveler"))
        assertEquals(listOf("track-0"), back.map { it.first })
        assertTrue("Frozen window must resume without re-observation debt", back[0].second.isStable)
    }

    @Test
    fun `long gap resets identity — no resurrection`() {
        val boxA = box(0, 0, 200, 40)
        repeat(3) { cycle(listOf(boxA to "Hello traveler")) }

        cycle(emptyList()) // missed=1: kept
        cycle(emptyList()) // missed=2: kept
        cycle(emptyList()) // missed=3 > 2: removed on both layers
        assertEquals(0, tracker.activeTrackCount())
        assertEquals(0, stabilizer.trackedRegionCount())

        val back = cycle(listOf(boxA to "Hello traveler"))
        assertEquals(listOf("track-1"), back.map { it.first })
        assertFalse(back[0].second.isStable)
    }

    @Test
    fun `two dialogues evolve independently`() {
        val boxA = box(0, 0, 200, 40)
        val boxB = box(0, 100, 200, 140)
        repeat(3) {
            cycle(listOf(boxA to "Hello traveler", boxB to "Press E"))
        }
        assertEquals(TrackState.STABLE, stabilizer.getState("track-0"))
        assertEquals(TrackState.STABLE, stabilizer.getState("track-1"))

        // Only box B changes (beyond tolerance — "Press E"→"Press F" would be
        // tolerated drift at distance 1): box A must stay stable.
        val fourth = cycle(listOf(boxA to "Hello traveler", boxB to "Open map"))
        val byTrack = fourth.toMap()
        assertTrue(byTrack["track-0"]!!.isStable)
        assertFalse(byTrack["track-1"]!!.isStable)
        assertEquals(TrackState.CHANGED, stabilizer.getState("track-1"))
    }
}
