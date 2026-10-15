package com.gametranslator.enar

import com.gametranslator.enar.render.BidiUtils
import com.gametranslator.enar.render.BidiUtils.BaseDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BidiUtils] — real Unicode Bidi Algorithm behavior
 * (`java.text.Bidi` + first-strong scan), the contract the Android
 * `FIRSTSTRONG_LTR` paragraph direction implements natively.
 */
class BidiUtilsTest {

    @Test
    fun `pure arabic is rtl single run`() {
        assertEquals(BaseDirection.RTL, BidiUtils.baseDirection("مرحبا بك"))
        assertTrue(BidiUtils.isRtlParagraph("مرحبا بك"))
        assertEquals(1, BidiUtils.runCount("مرحبا بك"))
        assertFalse(BidiUtils.isMixedDirection("مرحبا بك"))
    }

    @Test
    fun `pure english is ltr single run`() {
        assertEquals(BaseDirection.LTR, BidiUtils.baseDirection("Press E to open"))
        assertFalse(BidiUtils.isRtlParagraph("Press E to open"))
        assertEquals(1, BidiUtils.runCount("Press E to open"))
        assertFalse(BidiUtils.isMixedDirection("Press E to open"))
    }

    @Test
    fun `english-first mixed splits two runs`() {
        val text = "Hello مرحبا"
        assertEquals(BaseDirection.LTR, BidiUtils.baseDirection(text))
        assertEquals(2, BidiUtils.runCount(text))
        assertTrue(BidiUtils.isMixedDirection(text))
    }

    @Test
    fun `arabic-first mixed splits two runs`() {
        val text = "مرحبا Hello"
        assertEquals(BaseDirection.RTL, BidiUtils.baseDirection(text))
        assertEquals(2, BidiUtils.runCount(text))
        assertTrue(BidiUtils.isMixedDirection(text))
    }

    @Test
    fun `arabic with numbers is rtl`() {
        assertEquals(BaseDirection.RTL, BidiUtils.baseDirection("باق 50 HP"))
        assertTrue(BidiUtils.runCount("باق 50 HP") >= 2)
        assertTrue(BidiUtils.isMixedDirection("باق 50 HP"))
    }

    @Test
    fun `first strong wins over leading weak`() {
        // Digits/punctuation are weak: direction follows the first STRONG char.
        assertEquals(BaseDirection.RTL, BidiUtils.baseDirection("50 باق"))
        assertEquals(BaseDirection.LTR, BidiUtils.baseDirection("50 HP left"))
    }

    @Test
    fun `weak-only text is neutral`() {
        assertEquals(BaseDirection.NEUTRAL, BidiUtils.baseDirection("100%"))
        assertEquals(BaseDirection.NEUTRAL, BidiUtils.baseDirection("\$100"))
        assertEquals(BaseDirection.NEUTRAL, BidiUtils.baseDirection("..."))
        assertEquals(BaseDirection.NEUTRAL, BidiUtils.baseDirection(""))
        assertFalse(BidiUtils.isMixedDirection("100%"))
    }

    @Test
    fun `placeholders are ltr not mixed`() {
        assertEquals(BaseDirection.LTR, BidiUtils.baseDirection("{playerName}"))
        assertFalse(BidiUtils.isMixedDirection("{playerName}"))
    }

    @Test
    fun `empty text has zero runs`() {
        assertEquals(0, BidiUtils.runCount(""))
    }

    @Test
    fun `game terms mixed detection`() {
        assertTrue(BidiUtils.isMixedDirection("اضغط E للفتح"))
        assertTrue(BidiUtils.isMixedDirection("لديك 50 HP باق"))
        assertFalse(BidiUtils.isMixedDirection("العدو يقترب"))
    }
}
