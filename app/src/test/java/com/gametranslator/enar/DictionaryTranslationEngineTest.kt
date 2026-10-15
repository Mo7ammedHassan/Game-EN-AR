package com.gametranslator.enar

import com.gametranslator.enar.engine.DictionaryTranslationEngine
import com.gametranslator.enar.model.BoundingBox
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DictionaryTranslationEngine].
 * Verifies that standard game terms are accurately translated into neutral Modern Standard Arabic (MSA).
 */
class DictionaryTranslationEngineTest {

    private lateinit var engine: DictionaryTranslationEngine
    private val bounds = BoundingBox(0, 0, 100, 50)

    @Before
    fun setUp() {
        engine = DictionaryTranslationEngine()
    }

    @Test
    fun `translate common menu actions accurately`() = runBlocking {
        assertEquals("ابدأ", engine.translate("Start", bounds).translatedArabicText)
        assertEquals("متابعة", engine.translate("Continue", bounds).translatedArabicText)
        assertEquals("الخيارات", engine.translate("Options", bounds).translatedArabicText)
        assertEquals("الإعدادات", engine.translate("Settings", bounds).translatedArabicText)
        assertEquals("خروج", engine.translate("Quit", bounds).translatedArabicText)
        assertEquals("خروج", engine.translate("Exit", bounds).translatedArabicText)
    }

    @Test
    fun `translate gameplay stats and actions in neutral Arabic`() = runBlocking {
        assertEquals("الصحة", engine.translate("Health", bounds).translatedArabicText)
        assertEquals("الحقيبة", engine.translate("Inventory", bounds).translatedArabicText)
        assertEquals("هجوم", engine.translate("Attack", bounds).translatedArabicText)
        assertEquals("دفاع", engine.translate("Defend", bounds).translatedArabicText)
        assertEquals("انتهت اللعبة", engine.translate("Game Over", bounds).translatedArabicText)
    }

    @Test
    fun `case insensitivity check`() = runBlocking {
        assertEquals("ابدأ", engine.translate("START", bounds).translatedArabicText)
        assertEquals("ابدأ", engine.translate("start", bounds).translatedArabicText)
        assertEquals("ابدأ", engine.translate("StArT", bounds).translatedArabicText)
    }

    @Test
    fun `engine is always warm and provides valid metadata`() {
        assertTrue(engine.isWarm())
        val meta = engine.getEngineMetadata()
        assertEquals("DictionaryTranslationEngine", meta.engineName)
        assertEquals("CPU_MEMORY_DICTIONARY", meta.executionBackend)
        assertFalse(meta.isQuantized)
    }

    @Test
    fun `latency reporting is non-negative`() = runBlocking {
        val res = engine.translate("Start", bounds)
        assertTrue(res.latencyNs >= 0)
        assertEquals("en", res.sourceLang)
        assertEquals("ar", res.targetLang)
    }
}
