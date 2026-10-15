package com.gametranslator.enar

import com.gametranslator.enar.engine.NoOpOcrEngine
import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.FrameBuffer
import com.gametranslator.enar.model.TextRegion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NoOpOcrEngine].
 * Verifies that the no-op implementation fulfils the OcrEngine contract
 * and returns well-formed (but empty) results.
 * No Android dependencies; runs on JVM test runner.
 */
class NoOpOcrEngineTest {

    private val engine = NoOpOcrEngine()

    private val dummyFrame = FrameBuffer(
        width = 1920, height = 1080, stride = 7680,
        timestamp = 0L, pixelFormat = 1,
        isChanged = true
    )

    private val dummyRegion = TextRegion(
        id = "region_001",
        bounds = BoundingBox(10, 20, 200, 50),
        confidence = 0.85f
    )

    @Test
    fun `detectRegions returns empty list`() = runBlocking {
        val regions = engine.detectRegions(dummyFrame)
        assertTrue("NoOp detectRegions must return empty list", regions.isEmpty())
    }

    @Test
    fun `recognizeRegion returns OcrResult with empty text`() = runBlocking {
        val result = engine.recognizeRegion(dummyFrame, dummyRegion)
        assertEquals("NoOp text must be empty", "", result.rawText)
        assertEquals("Region ID must be preserved", dummyRegion.id, result.regionId)
        assertEquals(0f, result.confidence, 0.001f)
        assertFalse("NoOp result should not be stable", result.isStable)
    }

    @Test
    fun `getEngineMetadata returns NoOp identity`() {
        val meta = engine.getEngineMetadata()
        assertEquals("NoOpOcrEngine", meta.engineName)
        assertEquals("NONE", meta.executionBackend)
        assertFalse(meta.isQuantized)
    }
}
