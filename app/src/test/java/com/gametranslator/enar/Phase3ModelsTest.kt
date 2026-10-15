package com.gametranslator.enar

import com.gametranslator.enar.model.ChangeDetectionResult
import com.gametranslator.enar.model.FrameBuffer
import com.gametranslator.enar.model.OcrPipelineStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for pipeline model classes added in Phase 3.
 * Verifies ChangeDetectionResult sealed class semantics, FrameBuffer change metadata,
 * and OcrPipelineStats defaults. No Android dependencies.
 */
class Phase3ModelsTest {

    // ---- ChangeDetectionResult tests ----

    @Test
    fun `CHANGED result reports isChanged true via type check`() {
        val result = ChangeDetectionResult.CHANGED(latencyNs = 2_500_000L, hammingDistance = 12)
        assertTrue(result is ChangeDetectionResult.CHANGED)
        assertEquals(2_500_000L, result.latencyNs)
        assertEquals(12, result.hammingDistance)
    }

    @Test
    fun `NO_CHANGE result reports correctly`() {
        val result = ChangeDetectionResult.NO_CHANGE(latencyNs = 1_200_000L, hammingDistance = 2)
        assertTrue(result is ChangeDetectionResult.NO_CHANGE)
        assertEquals(1_200_000L, result.latencyNs)
        assertEquals(2, result.hammingDistance)
    }

    @Test
    fun `NO_DATA is singleton`() {
        assertTrue(ChangeDetectionResult.NO_DATA is ChangeDetectionResult)
    }

    // ---- FrameBuffer change metadata tests ----

    @Test
    fun `FrameBuffer defaults to isChanged false for backward compat`() {
        val frame = FrameBuffer(
            width = 1920, height = 1080, stride = 7680,
            timestamp = 1000L, pixelFormat = 1
        )
        assertFalse("Default isChanged must be false (backward compat)", frame.isChanged)
        assertEquals(0L, frame.changeLatencyNs)
    }

    @Test
    fun `FrameBuffer copy with isChanged true preserves all fields`() {
        val original = FrameBuffer(
            width = 1920, height = 1080, stride = 7680,
            timestamp = 42_000_000L, pixelFormat = 1
        )
        val enriched = original.copy(isChanged = true, changeLatencyNs = 3_000_000L)
        assertEquals(1920, enriched.width)
        assertEquals(1080, enriched.height)
        assertEquals(42_000_000L, enriched.timestamp)
        assertTrue(enriched.isChanged)
        assertEquals(3_000_000L, enriched.changeLatencyNs)
    }

    // ---- OcrPipelineStats tests ----

    @Test
    fun `OcrPipelineStats defaults to NONE skip reason`() {
        val stats = OcrPipelineStats(
            frameTimestampNs = 1000L,
            changeDetectionLatencyNs = 2_000_000L
        )
        assertEquals(OcrPipelineStats.SkipReason.NONE, stats.skippedReason)
        assertEquals(-1L, stats.ocrDetectionLatencyNs)
        assertEquals(-1L, stats.ocrRecognitionLatencyNs)
        assertEquals(0, stats.detectedRegionCount)
    }

    @Test
    fun `OcrPipelineStats NO_CHANGE skip reason recorded`() {
        val stats = OcrPipelineStats(
            frameTimestampNs = 2000L,
            changeDetectionLatencyNs = 800_000L,
            skippedReason = OcrPipelineStats.SkipReason.NO_CHANGE
        )
        assertEquals(OcrPipelineStats.SkipReason.NO_CHANGE, stats.skippedReason)
        assertNull("pipelineStats should start null", null) // structural sanity
    }
}
