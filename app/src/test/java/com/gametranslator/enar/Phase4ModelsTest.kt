package com.gametranslator.enar

import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.ProtectedText
import com.gametranslator.enar.model.TranslationPipelineStats
import com.gametranslator.enar.model.TranslationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Phase 4 models: [TranslationResult], [ProtectedText], [TranslationPipelineStats].
 */
class Phase4ModelsTest {

    @Test
    fun `translation result model defaults and calculations`() {
        val bounds = BoundingBox(0, 0, 100, 20)
        val result = TranslationResult(
            originalText = "Attack",
            translatedArabicText = "هجوم",
            bounds = bounds,
            fromCache = false,
            latencyMs = 5L
        )

        assertEquals("Attack", result.originalText)
        assertEquals("هجوم", result.translatedArabicText)
        assertEquals("en", result.sourceLang)
        assertEquals("ar", result.targetLang)
        assertEquals(5_000_000L, result.latencyNs)
        assertFalse(result.protectedTokensRestored)
    }

    @Test
    fun `protected text model maps placeholders`() {
        val tokenMap = mapOf("987650" to "{player}")
        val protected = ProtectedText("Hello 987650", tokenMap)

        assertEquals("Hello 987650", protected.maskedText)
        assertEquals("{player}", protected.tokenMap["987650"])
    }

    @Test
    fun `translation pipeline stats records breakdowns`() {
        val stats = TranslationPipelineStats(
            originalTextLength = 10,
            totalLatencyNs = 10_000_000L,
            preprocessingLatencyNs = 1_000_000L,
            tokenizationLatencyNs = 2_000_000L,
            inferenceLatencyNs = 6_000_000L,
            detokenizationLatencyNs = 1_000_000L,
            fromCache = false,
            tokenCount = 5,
            engineName = "LiteRtTranslationEngine"
        )

        assertEquals(10, stats.originalTextLength)
        assertEquals(10_000_000L, stats.totalLatencyNs)
        assertEquals("LiteRtTranslationEngine", stats.engineName)
        assertFalse(stats.fromCache)
    }
}
