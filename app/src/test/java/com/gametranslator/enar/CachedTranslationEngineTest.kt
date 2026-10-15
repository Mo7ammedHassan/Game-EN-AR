package com.gametranslator.enar

import com.gametranslator.enar.engine.CachedTranslationEngine
import com.gametranslator.enar.engine.TranslationEngine
import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.EngineMetadata
import com.gametranslator.enar.model.TranslationResult
import com.gametranslator.enar.translation.LruTranslationCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration tests for [CachedTranslationEngine]:
 * raw OCR text → normalize → cache lookup → (miss ? delegate : hit),
 * including the ISSUE-004 admission policy for `protectedTokensRestored`.
 */
class CachedTranslationEngineTest {

    private val boundsA = BoundingBox(1, 2, 3, 4)
    private val boundsB = BoundingBox(5, 6, 7, 8)

    /** Deterministic stand-in for a real engine (no ONNX on the JVM classpath). */
    private class FakeEngine(
        var warm: Boolean = true,
        var restoredFlag: Boolean = true
    ) : TranslationEngine {
        var calls = 0
        var lastInput: String? = null

        override suspend fun translate(
            text: String,
            targetBounds: BoundingBox,
            sourceLang: String,
            targetLang: String
        ): TranslationResult {
            calls++
            lastInput = text
            return TranslationResult(
                originalText = text,
                translatedArabicText = "AR[$text]",
                bounds = targetBounds,
                fromCache = false,
                latencyMs = 3L,
                sourceLang = sourceLang,
                targetLang = targetLang,
                engineMetadata = getEngineMetadata(),
                protectedTokensRestored = restoredFlag
            )
        }

        override fun isWarm(): Boolean = warm

        override fun getEngineMetadata(): EngineMetadata = EngineMetadata(
            engineName = "Fake",
            modelVersion = "0",
            executionBackend = "TEST",
            isQuantized = false
        )
    }

    @Test
    fun `miss delegates with normalized input, keeps raw original`() = runBlocking {
        val fake = FakeEngine()
        val engine = CachedTranslationEngine(fake, LruTranslationCache(8))

        val raw = "  Press   E\nto open!"
        val result = engine.translate(raw, boundsA)

        assertEquals(1, fake.calls)
        assertEquals("Press E to open!", fake.lastInput) // delegate sees canonical form
        assertEquals(raw, result.originalText) // display/traceability keeps raw text
        assertEquals("AR[Press E to open!]", result.translatedArabicText)
        assertEquals(boundsA, result.bounds)
        assertFalse(result.fromCache)
    }

    @Test
    fun `repeated input is a hit — delegate called once`() = runBlocking {
        val fake = FakeEngine()
        val engine = CachedTranslationEngine(fake, LruTranslationCache(8))

        val first = engine.translate("Save Game", boundsA)
        val second = engine.translate("Save Game", boundsB)

        assertEquals(1, fake.calls)
        assertFalse(first.fromCache)
        assertTrue(second.fromCache)
        assertEquals(first.translatedArabicText, second.translatedArabicText)
        assertEquals(boundsB, second.bounds) // hit uses the CURRENT request bounds
        assertTrue(second.latencyNs >= 0)
    }

    @Test
    fun `spacing variants share one normalized key`() = runBlocking {
        val fake = FakeEngine()
        val engine = CachedTranslationEngine(fake, LruTranslationCache(8))

        engine.translate("Score   50", boundsA)
        val hit = engine.translate("Score 50", boundsA)

        assertEquals(1, fake.calls)
        assertTrue(hit.fromCache)
    }

    @Test
    fun `flag-false with spans is NOT cached — re-translates`() = runBlocking {
        val fake = FakeEngine(restoredFlag = false) // simulates sentinel-loss retry output
        val engine = CachedTranslationEngine(fake, LruTranslationCache(8))

        val first = engine.translate("You have 50 HP!", boundsA)
        val second = engine.translate("You have 50 HP!", boundsA)

        assertEquals(2, fake.calls) // unsafe result must not be reused
        assertFalse(first.fromCache)
        assertFalse(second.fromCache)
        assertFalse(second.protectedTokensRestored)
    }

    @Test
    fun `flag-false without spans IS cached — vacuous safety`() = runBlocking {
        val fake = FakeEngine(restoredFlag = false) // plain text: nothing to restore
        val engine = CachedTranslationEngine(fake, LruTranslationCache(8))

        engine.translate("Save Game", boundsA)
        val hit = engine.translate("Save Game", boundsA)

        assertEquals(1, fake.calls)
        assertTrue(hit.fromCache)
        // Hits carry no restoration concern by admission construction.
        assertTrue(hit.protectedTokensRestored)
    }

    @Test
    fun `flag-true result is cached and hit reports flag true`() = runBlocking {
        val fake = FakeEngine(restoredFlag = true)
        val engine = CachedTranslationEngine(fake, LruTranslationCache(8))

        engine.translate("You have 50 HP!", boundsA)
        val hit = engine.translate("You have 50 HP!", boundsA)

        assertEquals(1, fake.calls)
        assertTrue(hit.fromCache)
        assertTrue(hit.protectedTokensRestored)
        assertTrue(hit.translatedArabicText.contains("50"))
    }

    @Test
    fun `cold delegate never populates the cache`() = runBlocking {
        val fake = FakeEngine(warm = false, restoredFlag = true) // e.g. ONNX echo fallback
        val engine = CachedTranslationEngine(fake, LruTranslationCache(8))

        engine.translate("Save Game", boundsA)
        engine.translate("Save Game", boundsA)

        assertEquals(2, fake.calls) // echo must not poison future (warmed) lookups
    }

    @Test
    fun `warmth and metadata are transparent`() {
        val fake = FakeEngine(warm = false)
        val engine = CachedTranslationEngine(fake, LruTranslationCache(8))
        assertFalse(engine.isWarm())
        assertEquals("Fake", engine.getEngineMetadata().engineName)

        fake.warm = true
        assertTrue(engine.isWarm())
    }

    @Test
    fun `default cache bound is 500`() {
        val engine = CachedTranslationEngine(FakeEngine())
        assertTrue(engine.isWarm())
        assertEquals(500, LruTranslationCache.DEFAULT_CAPACITY)
    }
}
