package com.gametranslator.enar.engine

import com.gametranslator.enar.model.EngineMetadata
import com.gametranslator.enar.model.FrameBuffer
import com.gametranslator.enar.model.OcrResult
import com.gametranslator.enar.model.TextRegion

/**
 * Pluggable abstract OCR Engine interface.
 * Decouples pipeline execution from specific vision model runtimes (LiteRT, ONNX, ML Kit).
 */
interface OcrEngine {
    /**
     * Detects candidate text bounding boxes from a frame buffer.
     */
    suspend fun detectRegions(frame: FrameBuffer): List<TextRegion>

    /**
     * Recognizes English text inside a cropped region of interest.
     */
    suspend fun recognizeRegion(crop: FrameBuffer, region: TextRegion): OcrResult

    /**
     * Returns runtime metadata (backend, delegate, quantization status).
     */
    fun getEngineMetadata(): EngineMetadata
}
