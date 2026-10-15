package com.gametranslator.enar

import com.gametranslator.enar.detection.OcrResultStabilizer
import com.gametranslator.enar.detection.TrackState
import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.OcrResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [OcrResultStabilizer].
 * Covers stabilization logic, Levenshtein distance, and stale region pruning.
 * No Android dependencies; runs on the JVM test runner.
 */
class OcrResultStabilizerTest {

    private lateinit var stabilizer: OcrResultStabilizer

    private val dummyBounds = BoundingBox(0, 0, 100, 20)

    private fun makeResult(regionId: String, text: String) = OcrResult(
        regionId = regionId,
        rawText = text,
        confidence = 0.9f,
        bounds = dummyBounds,
        isStable = false
    )

    @Before
    fun setUp() {
        stabilizer = OcrResultStabilizer(windowSize = 3, maxEditDistance = 1)
    }

    // ---- Levenshtein tests ----

    @Test
    fun `levenshtein identical strings returns zero`() {
        assertEquals(0, stabilizer.levenshtein("ATTACK", "ATTACK"))
    }

    @Test
    fun `levenshtein single substitution returns one`() {
        assertEquals(1, stabilizer.levenshtein("ATTACK", "ATTAVK"))
    }

    @Test
    fun `levenshtein empty string returns other string length`() {
        assertEquals(6, stabilizer.levenshtein("", "ATTACK"))
        assertEquals(6, stabilizer.levenshtein("ATTACK", ""))
    }

    @Test
    fun `levenshtein transposition counted correctly`() {
        assertEquals(2, stabilizer.levenshtein("ab", "ba"))
    }

    // ---- Stabilization tests ----

    @Test
    fun `not stable before window fills`() {
        val result1 = stabilizer.stabilize(makeResult("r1", "ATTACK"))
        assertFalse("Should not be stable after 1 frame", result1.isStable)

        val result2 = stabilizer.stabilize(makeResult("r1", "ATTACK"))
        assertFalse("Should not be stable after 2 frames", result2.isStable)
    }

    @Test
    fun `stable after windowSize identical results`() {
        repeat(2) { stabilizer.stabilize(makeResult("r1", "ATTACK")) }
        val result = stabilizer.stabilize(makeResult("r1", "ATTACK"))
        assertTrue("Should be stable after windowSize identical results", result.isStable)
    }

    @Test
    fun `stable with results within maxEditDistance`() {
        stabilizer.stabilize(makeResult("r1", "ATTACK"))
        stabilizer.stabilize(makeResult("r1", "ATTAVK"))  // edit distance 1
        val result = stabilizer.stabilize(makeResult("r1", "ATTACK"))
        assertTrue("Should be stable with edit distance <= 1", result.isStable)
    }

    @Test
    fun `not stable when text changes drastically`() {
        stabilizer.stabilize(makeResult("r1", "ATTACK"))
        stabilizer.stabilize(makeResult("r1", "ATTACK"))
        val result = stabilizer.stabilize(makeResult("r1", "DEFEND"))  // edit distance >> 1
        assertFalse("Should not be stable after large text change", result.isStable)
    }

    @Test
    fun `stability resets after text change`() {
        repeat(3) { stabilizer.stabilize(makeResult("r1", "ATTACK")) }
        // Now inject a very different text — window no longer consistent
        stabilizer.stabilize(makeResult("r1", "DEFEND"))
        stabilizer.stabilize(makeResult("r1", "DEFEND"))
        val result = stabilizer.stabilize(makeResult("r1", "DEFEND"))
        // The window now has [ATTACK, DEFEND, DEFEND, DEFEND] but deque keeps last 3:
        // [DEFEND, DEFEND, DEFEND] — should be stable again
        assertTrue("Should re-stabilize on new consistent text", result.isStable)
    }

    // ---- Prune tests ----

    @Test
    fun `pruneStaleRegions removes inactive regions`() {
        stabilizer.stabilize(makeResult("r1", "ATTACK"))
        stabilizer.stabilize(makeResult("r2", "DEFEND"))
        assertEquals(2, stabilizer.trackedRegionCount())

        stabilizer.pruneStaleRegions(setOf("r1"))
        assertEquals(1, stabilizer.trackedRegionCount())
    }

    @Test
    fun `reset clears all region history`() {
        stabilizer.stabilize(makeResult("r1", "ATTACK"))
        stabilizer.stabilize(makeResult("r2", "DEFEND"))
        stabilizer.reset()
        assertEquals(0, stabilizer.trackedRegionCount())
    }

    // ---- Multiple independent regions ----

    @Test
    fun `separate region IDs track independently`() {
        repeat(3) { stabilizer.stabilize(makeResult("r1", "ATTACK")) }
        // r2 only has 1 frame — not stable
        val r2 = stabilizer.stabilize(makeResult("r2", "MENU"))
        assertFalse("r2 should not be stable with only 1 frame", r2.isStable)

        // r1 has 3 consistent frames — stable
        val r1 = stabilizer.stabilize(makeResult("r1", "ATTACK"))
        assertTrue("r1 should be stable with 4 consistent frames (window=3)", r1.isStable)
    }

    // ---- Hardened state machine (Phase 5) ----

    private fun makeConfidentResult(regionId: String, text: String, confidence: Float) = OcrResult(
        regionId = regionId,
        rawText = text,
        confidence = confidence,
        bounds = dummyBounds,
        isStable = false
    )

    @Test
    fun `state lifecycle observing stable changed stable`() {
        repeat(3) { stabilizer.stabilize(makeResult("r1", "ATTACK")) }
        assertEquals(TrackState.STABLE, stabilizer.getState("r1"))
        assertEquals("ATTACK", stabilizer.getStableText("r1"))

        stabilizer.stabilize(makeResult("r1", "DEFEND"))
        assertEquals(TrackState.CHANGED, stabilizer.getState("r1"))

        stabilizer.stabilize(makeResult("r1", "DEFEND"))
        assertEquals(TrackState.CHANGED, stabilizer.getState("r1"))

        val restabilized = stabilizer.stabilize(makeResult("r1", "DEFEND"))
        assertTrue(restabilized.isStable)
        assertEquals(TrackState.STABLE, stabilizer.getState("r1"))
        assertEquals("DEFEND", stabilizer.getStableText("r1"))
    }

    @Test
    fun `new region without history is observing not changed`() {
        stabilizer.stabilize(makeResult("r1", "HELLO"))
        assertEquals(TrackState.OBSERVING, stabilizer.getState("r1"))
        assertEquals(null, stabilizer.getStableText("r1"))
    }

    @Test
    fun `small drift stays stable and advances stable text`() {
        repeat(3) { stabilizer.stabilize(makeResult("r1", "ATTACK")) }
        val drifted = stabilizer.stabilize(makeResult("r1", "ATTAVK")) // distance 1
        assertTrue("Within-tolerance drift must not flicker stability", drifted.isStable)
        assertEquals(TrackState.STABLE, stabilizer.getState("r1"))
        assertEquals("ATTAVK", stabilizer.getStableText("r1"))
    }

    @Test
    fun `one-frame corruption never stabilizes`() {
        stabilizer.stabilize(makeResult("r1", "ATTACK"))
        val corrupted = stabilizer.stabilize(makeResult("r1", "XXQZZZ"))
        assertFalse(corrupted.isStable)
        assertEquals(TrackState.OBSERVING, stabilizer.getState("r1"))
    }

    @Test
    fun `two-frame corruption inside window still not stable`() {
        val graced = OcrResultStabilizer(windowSize = 3, maxEditDistance = 1)
        graced.stabilize(makeResult("r1", "ATTACK"))
        graced.stabilize(makeResult("r1", "XXQZZZ")) // clears window → [X]
        graced.stabilize(makeResult("r1", "QQQWWW")) // clears window → [Q]
        val secondGood = graced.stabilize(makeResult("r1", "ATTACK"))
        assertFalse("Only 1 good frame after corruption", secondGood.isStable)
        val thirdGood = graced.stabilize(makeResult("r1", "ATTACK"))
        assertFalse("Only 2 good frames after corruption", thirdGood.isStable)
        val fourthGood = graced.stabilize(makeResult("r1", "ATTACK"))
        assertTrue("3 consistent frames re-stabilize", fourthGood.isStable)
    }

    @Test
    fun `empty text freezes record instead of corrupting`() {
        repeat(3) { stabilizer.stabilize(makeResult("r1", "ATTACK")) }
        val blank = stabilizer.stabilize(makeResult("r1", ""))
        assertTrue("Stored stability survives a transient blank", blank.isStable)
        assertEquals(TrackState.STABLE, stabilizer.getState("r1"))

        // Window untouched: next real observation stays stable immediately.
        val next = stabilizer.stabilize(makeResult("r1", "ATTACK"))
        assertTrue(next.isStable)
    }

    @Test
    fun `empty text on unknown region is not stable`() {
        val blank = stabilizer.stabilize(makeResult("fresh", ""))
        assertFalse(blank.isStable)
        assertEquals(0, stabilizer.trackedRegionCount())
    }

    @Test
    fun `below-confidence observations are dropped`() {
        val strict = OcrResultStabilizer(windowSize = 3, maxEditDistance = 1, minConfidence = 0.5f)
        strict.stabilize(makeConfidentResult("r1", "ATTACK", 0.9f))
        strict.stabilize(makeConfidentResult("r1", "GARBAGE", 0.1f)) // dropped: no window advance
        strict.stabilize(makeConfidentResult("r1", "ATTACK", 0.9f))
        val third = strict.stabilize(makeConfidentResult("r1", "ATTACK", 0.9f))
        assertTrue("Weak frame must not break the window", third.isStable)
    }

    @Test
    fun `disappearance within grace freezes then resumes`() {
        val graced = OcrResultStabilizer(windowSize = 3, maxEditDistance = 1, maxMissedFrames = 2)
        repeat(3) { graced.stabilize(makeResult("r1", "ATTACK")) }

        graced.pruneStaleRegions(emptySet())
        assertEquals(TrackState.DISAPPEARED, graced.getState("r1"))
        assertEquals(1, graced.trackedRegionCount()) // retained, not removed

        graced.pruneStaleRegions(emptySet()) // missed=2 ≤ 2: still retained
        assertEquals(1, graced.trackedRegionCount())

        // Reappearance with same text resumes immediately — no re-observation debt.
        val resumed = graced.stabilize(makeResult("r1", "ATTACK"))
        assertTrue(resumed.isStable)
        assertEquals(TrackState.STABLE, graced.getState("r1"))
    }

    @Test
    fun `disappearance beyond grace removes record`() {
        val graced = OcrResultStabilizer(windowSize = 3, maxEditDistance = 1, maxMissedFrames = 1)
        repeat(3) { graced.stabilize(makeResult("r1", "ATTACK")) }

        graced.pruneStaleRegions(emptySet()) // missed=1: disappeared but kept
        assertEquals(TrackState.DISAPPEARED, graced.getState("r1"))
        graced.pruneStaleRegions(emptySet()) // missed=2 > 1: removed
        assertEquals(0, graced.trackedRegionCount())
        assertEquals(null, graced.getState("r1"))
        assertEquals(null, graced.getStableText("r1"))

        // Look-alike starts over at NEW: stale text cannot resurrect.
        val reappeared = graced.stabilize(makeResult("r1", "ATTACK"))
        assertFalse(reappeared.isStable)
        assertEquals(TrackState.OBSERVING, graced.getState("r1"))
    }

    @Test
    fun `reappearance with changed text goes changed not observing`() {
        val graced = OcrResultStabilizer(windowSize = 3, maxEditDistance = 1, maxMissedFrames = 2)
        repeat(3) { graced.stabilize(makeResult("r1", "ATTACK")) }
        graced.pruneStaleRegions(emptySet())

        val changed = graced.stabilize(makeResult("r1", "DEFEND"))
        assertFalse(changed.isStable)
        assertEquals(TrackState.CHANGED, graced.getState("r1"))
    }

    @Test
    fun `cross-region isolation under interleaved observations`() {
        val graced = OcrResultStabilizer(windowSize = 3, maxEditDistance = 1, maxMissedFrames = 2)
        // Interleave two regions frame by frame.
        repeat(3) {
            graced.stabilize(makeResult("r1", "ATTACK"))
            graced.stabilize(makeResult("r2", "DEFEND"))
        }
        assertEquals(TrackState.STABLE, graced.getState("r1"))
        assertEquals(TrackState.STABLE, graced.getState("r2"))

        // Corrupt r2 only: r1 must be unaffected.
        graced.stabilize(makeResult("r2", "ZZZZZZ"))
        assertEquals(TrackState.STABLE, graced.getState("r1"))
        assertEquals(TrackState.CHANGED, graced.getState("r2"))

        // Disappear r1 only: r2 keeps its record.
        graced.pruneStaleRegions(setOf("r2"))
        assertEquals(TrackState.DISAPPEARED, graced.getState("r1"))
        assertEquals(TrackState.CHANGED, graced.getState("r2"))
    }

    @Test
    fun `constructor guards reject nonsense`() {
        try {
            OcrResultStabilizer(windowSize = 0)
            fail("windowSize 0 must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            OcrResultStabilizer(maxEditDistance = -1)
            fail("negative maxEditDistance must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            OcrResultStabilizer(maxMissedFrames = -1)
            fail("negative maxMissedFrames must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            OcrResultStabilizer(minConfidence = 1.5f)
            fail("minConfidence > 1 must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
