package com.gametranslator.enar

import com.gametranslator.enar.engine.NoOpTranslationEngine
import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.translation.NoOpTranslationCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NoOpTranslationEngine] and [NoOpTranslationCache].
 */
class TranslationBaselineTest {

    @Test
    fun `noop translation engine returns original text and preserves bounds`() = runBlocking {
        val engine = NoOpTranslationEngine()
        val bounds = BoundingBox(10, 20, 200, 80)
        val result = engine.translate("Hello World", bounds)

        assertEquals("Hello World", result.originalText)
        assertEquals("Hello World", result.translatedArabicText)
        assertEquals(bounds, result.bounds)
        assertFalse(result.fromCache)
        assertTrue(engine.isWarm())

        val meta = engine.getEngineMetadata()
        assertEquals("NoOpTranslationEngine", meta.engineName)
    }

    @Test
    fun `noop translation cache returns null on get and reports zero size`() {
        val cache = NoOpTranslationCache()
        assertEquals(0, cache.size())
        assertNull(cache.get("Start"))

        cache.put("Start", "ابدأ")
        assertEquals(0, cache.size())
        assertNull(cache.get("Start"))

        cache.clear()
        assertEquals(0, cache.size())
    }
}
