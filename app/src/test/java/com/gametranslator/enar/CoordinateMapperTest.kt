package com.gametranslator.enar

import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.render.CaptureGeometry
import com.gametranslator.enar.render.CoordinateMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [CoordinateMapper] — the canonical capture→overlay transform.
 * Every rotation is verified corner by corner; invalid geometries are rejected.
 */
class CoordinateMapperTest {

    private fun assertPair(expectedX: Float, expectedY: Float, actual: Pair<Float, Float>) {
        assertEquals(expectedX, actual.first, 1e-3f)
        assertEquals(expectedY, actual.second, 1e-3f)
    }

    @Test
    fun `identity when capture equals display`() {
        val mapper = CoordinateMapper(CaptureGeometry(1080, 2400, 1080, 2400))
        assertEquals(1f, mapper.scaleX(), 1e-6f)
        assertEquals(1f, mapper.scaleY(), 1e-6f)
        val rect = mapper.toOverlay(BoundingBox(10, 20, 110, 220))
        assertEquals(10f, rect.left, 1e-3f)
        assertEquals(20f, rect.top, 1e-3f)
        assertEquals(110f, rect.right, 1e-3f)
        assertEquals(220f, rect.bottom, 1e-3f)
    }

    @Test
    fun `uniform downscale doubles coordinates`() {
        val mapper = CoordinateMapper(CaptureGeometry(540, 1200, 1080, 2400))
        assertEquals(2f, mapper.scaleX(), 1e-6f)
        assertEquals(2f, mapper.scaleY(), 1e-6f)
        val rect = mapper.toOverlay(BoundingBox(10, 20, 110, 220))
        assertEquals(20f, rect.left, 1e-3f)
        assertEquals(40f, rect.top, 1e-3f)
        assertEquals(220f, rect.right, 1e-3f)
        assertEquals(440f, rect.bottom, 1e-3f)
    }

    @Test
    fun `non-uniform scale per axis`() {
        val mapper = CoordinateMapper(CaptureGeometry(100, 100, 200, 400))
        assertEquals(2f, mapper.scaleX(), 1e-6f)
        assertEquals(4f, mapper.scaleY(), 1e-6f)
        val rect = mapper.toOverlay(BoundingBox(10, 10, 20, 20))
        assertEquals(20f, rect.left, 1e-3f)
        assertEquals(40f, rect.top, 1e-3f)
    }

    @Test
    fun `letterbox offset shifts output`() {
        // Content 2000 tall on a 2400 display, shifted down 200 px (letterbox bar).
        // Note scaleY = 2400/2000 = 1.2 also applies: this is scale AND offset.
        val mapper = CoordinateMapper(
            CaptureGeometry(1080, 2000, 1080, 2400, offsetXPx = 0f, offsetYPx = 200f)
        )
        assertEquals(1f, mapper.scaleX(), 1e-6f)
        assertEquals(1.2f, mapper.scaleY(), 1e-6f)
        val rect = mapper.toOverlay(BoundingBox(0, 0, 100, 100))
        assertEquals(0f, rect.left, 1e-3f)
        assertEquals(200f, rect.top, 1e-3f)
        assertEquals(100f, rect.right, 1e-3f)
        assertEquals(320f, rect.bottom, 1e-3f)
    }

    @Test
    fun `rotation 90 maps corners clockwise`() {
        // Frame 100x200, display 400x200: sx=2, sy=2.
        val mapper = CoordinateMapper(CaptureGeometry(100, 200, 400, 200, rotationClockwiseDeg = 90))
        assertPair(400f, 0f, mapper.toOverlay(0, 0))
        assertPair(0f, 200f, mapper.toOverlay(100, 200))
        assertPair(300f, 100f, mapper.toOverlay(50, 50)) // (200-50)*2, 50*2
    }

    @Test
    fun `rotation 180 flips both axes`() {
        val mapper = CoordinateMapper(CaptureGeometry(100, 200, 100, 200, rotationClockwiseDeg = 180))
        assertPair(100f, 200f, mapper.toOverlay(0, 0))
        assertPair(0f, 0f, mapper.toOverlay(100, 200))
        val rect = mapper.toOverlay(BoundingBox(10, 20, 30, 40))
        assertEquals(70f, rect.left, 1e-3f)
        assertEquals(160f, rect.top, 1e-3f)
        assertEquals(90f, rect.right, 1e-3f)
        assertEquals(180f, rect.bottom, 1e-3f)
    }

    @Test
    fun `rotation 270 maps corners`() {
        // Frame 100x200, display 400x200: sx=2, sy=2.
        val mapper = CoordinateMapper(CaptureGeometry(100, 200, 400, 200, rotationClockwiseDeg = 270))
        assertPair(0f, 200f, mapper.toOverlay(0, 0))
        assertPair(400f, 0f, mapper.toOverlay(100, 200))
    }

    @Test
    fun `rotated box stays valid axis-aligned rect`() {
        val mapper = CoordinateMapper(CaptureGeometry(100, 200, 400, 200, rotationClockwiseDeg = 90))
        val rect = mapper.toOverlay(BoundingBox(10, 20, 30, 40))
        assertTrue(rect.hasArea())
        assertTrue(rect.left <= rect.right)
        assertTrue(rect.top <= rect.bottom)
    }

    @Test
    fun `zero-area box maps to zero-area rect`() {
        val mapper = CoordinateMapper(CaptureGeometry(1080, 2400, 1080, 2400))
        val rect = mapper.toOverlay(BoundingBox(50, 50, 50, 50))
        assertEquals(0f, rect.width, 1e-6f)
        assertEquals(0f, rect.height, 1e-6f)
        assertFalse(rect.hasArea())
    }

    @Test
    fun `invalid geometry rejected`() {
        try {
            CaptureGeometry(0, 100, 100, 100)
            fail("zero frameWidth must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            CaptureGeometry(100, 100, 100, 100, rotationClockwiseDeg = 45)
            fail("rotation 45 must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            CaptureGeometry(100, 100, 100, 100, offsetXPx = Float.NaN)
            fail("NaN offset must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
