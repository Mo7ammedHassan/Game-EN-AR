package com.gametranslator.enar

import com.gametranslator.enar.render.OverlayRect
import com.gametranslator.enar.render.OverlayRegion
import com.gametranslator.enar.render.OverlayStyle
import com.gametranslator.enar.render.TextAlignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [OverlayStyle] and [OverlayRegion]/[OverlayRect].
 *
 * Pins deterministic defaults, constructor validation, renderability rules,
 * and — critically — logical-order preservation: stored text is NEVER
 * reversed, reshaped, or reordered by this layer.
 */
class OverlayStyleRegionTest {

    // ---- Style defaults & helpers ----

    @Test
    fun `sane deterministic defaults`() {
        val style = OverlayStyle()
        assertEquals(18f, style.textSizeSp, 0f)
        assertEquals(TextAlignment.CENTER, style.alignment)
        assertNull(style.backgroundColor)
        assertFalse(style.hasBackground())
        assertTrue(style.hasOutline()) // default readability outline on
        assertFalse(style.hasShadow())
        assertEquals(Int.MAX_VALUE, style.maxLines)
        assertFalse(style.ellipsize)
    }

    @Test
    fun `invalid styles rejected`() {
        try {
            OverlayStyle(textSizeSp = 0f)
            fail("zero text size must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            OverlayStyle(alpha = 1.5f)
            fail("alpha > 1 must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            OverlayStyle(alpha = -0.1f)
            fail("alpha < 0 must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            OverlayStyle(paddingPx = -1)
            fail("negative padding must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            OverlayStyle(lineSpacingMult = 0f)
            fail("zero spacing mult must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            OverlayStyle(maxLines = 0)
            fail("maxLines 0 must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            OverlayStyle(textSizeSp = Float.NaN)
            fail("NaN text size must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `outline helper needs width and color`() {
        assertTrue(OverlayStyle(outlineColor = 0xFF000000.toInt(), outlineWidthPx = 2f).hasOutline())
        assertFalse(OverlayStyle(outlineColor = null, outlineWidthPx = 2f).hasOutline())
        assertFalse(OverlayStyle(outlineColor = 0xFF000000.toInt(), outlineWidthPx = 0f).hasOutline())
        assertTrue(OverlayStyle(shadowRadiusPx = 4f).hasShadow())
    }

    // ---- Rect & renderability ----

    @Test
    fun `rect area rules`() {
        assertTrue(OverlayRect(0f, 0f, 100f, 20f).hasArea())
        assertFalse(OverlayRect(50f, 50f, 50f, 50f).hasArea()) // zero area
        assertFalse(OverlayRect(10f, 10f, 5f, 5f).hasArea()) // inverted
        assertFalse(OverlayRect(0f, 0f, Float.NaN, 10f).hasArea())
    }

    @Test
    fun `renderable needs text and area`() {
        val ok = OverlayRegion("r1", OverlayRect(0f, 0f, 100f, 20f), "مرحبا")
        assertTrue(ok.isRenderable())

        assertFalse(
            OverlayRegion("r2", OverlayRect(0f, 0f, 100f, 20f), "").isRenderable()
        )
        assertFalse(
            OverlayRegion("r3", OverlayRect(0f, 0f, 100f, 20f), "   ").isRenderable()
        )
        assertFalse(
            OverlayRegion("r4", OverlayRect(0f, 0f, 0f, 20f), "مرحبا").isRenderable()
        )
    }

    // ---- Logical order preservation ----

    @Test
    fun `logical text stored byte-identical — never reversed or shaped`() {
        val cases = listOf(
            "مرحبا بك في اللعبة",
            "Press E to open the door.",
            "اضغط E للفتح",
            "لديك 50 HP باق",
            "التكلفة: \$100 أو 50%",
            "مرحباً يا {playerName} مرحباً بكم",
            "Score: 12500 pts (100%)",
            "Line one\nLine two\nLine three"
        )
        for (text in cases) {
            val region = OverlayRegion("r", OverlayRect(0f, 0f, 400f, 100f), text)
            assertEquals("Text must be preserved byte-identical: '$text'", text, region.text)
        }
    }
}
