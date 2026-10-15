package com.gametranslator.enar

import com.gametranslator.enar.translation.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [TextNormalizer].
 *
 * Scope contract under test: whitespace/line-break/Unicode canonicalization is
 * aggressive (cache keys must be stable), while meaning — case, punctuation,
 * protected spans — is never touched. Non-ASCII inputs are literal UTF-8
 * characters (codepoints verified in-file: U+00A0/2009/2003/200B/FEFF,
 * U+201C/201D/2018/2019, U+FF21-23, U+FB01, U+2026, U+060C, U+2014, U+0007).
 */
class TextNormalizerTest {

    private lateinit var normalizer: TextNormalizer

    @Before
    fun setUp() {
        normalizer = TextNormalizer()
    }

    @Test
    fun `repeated spaces collapse`() {
        assertEquals("Score 50", normalizer.normalize("Score   50"))
        assertEquals("Score 50", normalizer.normalize("Score\t 50"))
    }

    @Test
    fun `line breaks fold to spaces`() {
        assertEquals("Line one Line two", normalizer.normalize("Line one\nLine two"))
        assertEquals("Line one Line two", normalizer.normalize("Line one\r\nLine two"))
        assertEquals("Line one Line two", normalizer.normalize("Line one\rLine two"))
        assertEquals("Press E to open", normalizer.normalize("Press E\n\nto   open"))
    }

    @Test
    fun `leading and trailing whitespace trimmed`() {
        assertEquals("Save Game", normalizer.normalize("  Save Game  "))
        assertEquals("Save Game", normalizer.normalize("\n Save Game \t"))
    }

    @Test
    fun `unicode spaces normalize`() {
        // NBSP / thin space / em space / zero-width space / BOM.
        assertEquals("Score 50", normalizer.normalize("Score 50"))
        assertEquals("a b c", normalizer.normalize("a b c"))
        assertEquals("hi there", normalizer.normalize("hi​there"))
        assertEquals("bom here", normalizer.normalize("﻿bom here"))
    }

    @Test
    fun `curly quotes unify`() {
        // U+201C U+201D U+2018 U+2019.
        assertEquals("\"Quest\" 'Reward'", normalizer.normalize("“Quest” ‘Reward’"))
    }

    @Test
    fun `nfkc compatibility variants canonicalize`() {
        // Fullwidth ABC (U+FF21-23), fi-ligature (U+FB01).
        assertEquals("ABC", normalizer.normalize("ＡＢＣ"))
        assertEquals("fi", normalizer.normalize("ﬁ"))
        // Documented NFKC mapping (same transform the model's nmt_nfkc applies).
        assertEquals("...", normalizer.normalize("…"))
    }

    @Test
    fun `punctuation preserved`() {
        assertEquals("Hello, World!", normalizer.normalize("Hello, World!"))
        assertEquals("Quest: done.", normalizer.normalize(" Quest:  done. "))
        // Arabic comma U+060C, em dash U+2014: no NFKC decomposition, preserved.
        assertEquals("مرحبا، بك!", normalizer.normalize("مرحبا، بك!"))
        assertEquals("Wait — what?", normalizer.normalize("Wait — what?"))
    }

    @Test
    fun `case preserved — no folding`() {
        assertEquals("START", normalizer.normalize("START"))
        assertEquals("Press E to open the door.", normalizer.normalize("Press E to open the door."))
    }

    @Test
    fun `protected spans pass through byte-identical`() {
        val spans = listOf(
            "{playerName}", "{0}", "%d", "%s", "%.2f", "%1\$s",
            "\$100", "50G", "100%", "50 XP", "80/100", "#99",
            "<color=#FFF>", "</color>", "<b>"
        )
        for (span in spans) {
            assertEquals("Span must survive normalization: '$span'", span, normalizer.normalize(span))
        }
        // And embedded in running text (only spacing around them may collapse).
        assertEquals(
            "Give {playerName} \$100 and %d potions",
            normalizer.normalize("Give  {playerName}   \$100 and %d  potions")
        )
    }

    @Test
    fun `already-normalized input unchanged and idempotent`() {
        val clean = "You have 50 HP remaining."
        assertEquals(clean, normalizer.normalize(clean))
        val messy = "  Give   {player}\n\n \$100\tand %d   potions  "
        val once = normalizer.normalize(messy)
        assertEquals(once, normalizer.normalize(once))
    }

    @Test
    fun `control characters removed`() {
        // BEL U+0007.
        assertEquals("Score 50", normalizer.normalize("Score 50"))
    }

    @Test
    fun `empty and blank handling`() {
        assertEquals("", normalizer.normalize(""))
        assertEquals("", normalizer.normalize("   "))
        assertEquals("", normalizer.normalize("\n\t "))
    }
}
