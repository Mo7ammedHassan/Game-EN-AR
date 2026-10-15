package com.gametranslator.enar.engine

import com.gametranslator.enar.model.EngineMetadata
import com.gametranslator.enar.model.TranslationResult
import com.gametranslator.enar.model.BoundingBox

/**
 * Pluggable abstract Translation Engine interface.
 * Decouples pipeline execution from specific NMT model runtimes (MarianMT/Opus-MT, LiteRT, ONNX).
 */
interface TranslationEngine {
    /**
     * Translates English text to Arabic asynchronously.
     */
    suspend fun translate(
        text: String,
        targetBounds: BoundingBox,
        sourceLang: String = "en",
        targetLang: String = "ar"
    ): TranslationResult

    /**
     * Returns true if model tensors are loaded and warmed up in memory.
     */
    fun isWarm(): Boolean

    /**
     * Returns metadata for active runtime backend.
     */
    fun getEngineMetadata(): EngineMetadata
}
