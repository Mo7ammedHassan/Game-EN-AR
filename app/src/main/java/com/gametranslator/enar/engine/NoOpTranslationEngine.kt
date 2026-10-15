package com.gametranslator.enar.engine

import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.EngineMetadata
import com.gametranslator.enar.model.TranslationResult

/**
 * No-operation Translation engine implementation.
 *
 * Always returns the source text without modification.
 * Used for:
 * - Unit testing translation pipeline coordinators without ML dependencies.
 * - Performance baseline benchmarking.
 * - Zero-dependency builds.
 */
class NoOpTranslationEngine : TranslationEngine {

    override suspend fun translate(
        text: String,
        targetBounds: BoundingBox,
        sourceLang: String,
        targetLang: String
    ): TranslationResult {
        val startNs = System.nanoTime()
        val latencyNs = System.nanoTime() - startNs
        return TranslationResult(
            originalText = text,
            translatedArabicText = text,
            bounds = targetBounds,
            fromCache = false,
            latencyMs = latencyNs / 1_000_000L,
            latencyNs = latencyNs,
            sourceLang = sourceLang,
            targetLang = targetLang,
            engineMetadata = getEngineMetadata(),
            protectedTokensRestored = false
        )
    }

    override fun isWarm(): Boolean = true

    override fun getEngineMetadata(): EngineMetadata = EngineMetadata(
        engineName = "NoOpTranslationEngine",
        modelVersion = "N/A",
        executionBackend = "NONE",
        isQuantized = false
    )
}
