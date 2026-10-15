package com.gametranslator.enar.engine

import com.gametranslator.enar.model.EngineMetadata
import com.gametranslator.enar.model.FrameBuffer
import com.gametranslator.enar.model.OcrResult
import com.gametranslator.enar.model.TextRegion

/**
 * No-operation OCR engine implementation.
 *
 * Always returns empty region lists and empty recognition results.
 * Used for:
 * - Unit testing the [OcrPipelineCoordinator] without real models.
 * - Verifying pipeline threading and lifecycle without ML dependencies.
 * - Build verification where no model assets exist.
 *
 * NOT suitable for production. Replace with [LiteRtOcrEngine] (or any
 * other [OcrEngine] implementation) after model acquisition and benchmarking.
 */
class NoOpOcrEngine : OcrEngine {

    override suspend fun detectRegions(frame: FrameBuffer): List<TextRegion> = emptyList()

    override suspend fun recognizeRegion(crop: FrameBuffer, region: TextRegion): OcrResult =
        OcrResult(
            regionId = region.id,
            rawText = "",
            confidence = 0f,
            bounds = region.bounds,
            isStable = false
        )

    override fun getEngineMetadata(): EngineMetadata = EngineMetadata(
        engineName = "NoOpOcrEngine",
        modelVersion = "N/A",
        executionBackend = "NONE",
        isQuantized = false
    )
}
