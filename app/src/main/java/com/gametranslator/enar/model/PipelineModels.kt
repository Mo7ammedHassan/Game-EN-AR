package com.gametranslator.enar.model

import android.graphics.Rect

/**
 * Pure Kotlin bounding box data model for cross-platform and fast unit testing.
 */
data class BoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun toAndroidRect(): Rect = Rect(left, top, right, bottom)

    companion object {
        fun fromAndroidRect(rect: Rect): BoundingBox =
            BoundingBox(rect.left, rect.top, rect.right, rect.bottom)
    }
}

/**
 * Data model for a detected text bounding box region.
 */
data class TextRegion(
    val id: String,
    val bounds: BoundingBox,
    val confidence: Float,
    val lineId: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Result model produced by OCR recognition engines.
 */
data class OcrResult(
    val regionId: String,
    val rawText: String,
    val confidence: Float,
    val bounds: BoundingBox,
    val isStable: Boolean = false
)

/**
 * Result model produced by Translation engines.
 */
data class TranslationResult(
    val originalText: String,
    val translatedArabicText: String,
    val bounds: BoundingBox,
    val fromCache: Boolean,
    val latencyMs: Long = 0L,
    val latencyNs: Long = latencyMs * 1_000_000L,
    val sourceLang: String = "en",
    val targetLang: String = "ar",
    val engineMetadata: EngineMetadata? = null,
    val protectedTokensRestored: Boolean = false
)

/**
 * Frame buffer abstraction wrapping captured frame metadata.
 *
 * Pixel data is NOT copied here. Change detection runs on the CaptureThread
 * with direct Image plane access before the Image is closed; the result is
 * stored in [isChanged] / [changeLatencyNs] so downstream consumers (OCR
 * coordinator) can gate inference without touching raw pixels.
 *
 * When real OCR inference is integrated (Phase 9), a pre-scaled pixel snapshot
 * will be produced separately via a bounded buffer mechanism — not by extending
 * this struct — to avoid StateFlow retaining large byte arrays.
 */
data class FrameBuffer(
    val width: Int,
    val height: Int,
    val stride: Int,
    val timestamp: Long,
    val pixelFormat: Int,
    /** True if Level-1 change detection found meaningful luminance difference vs. previous keyframe. */
    val isChanged: Boolean = false,
    /** Wall-clock nanoseconds spent inside FrameChangeDetector.detect() for this frame. */
    val changeLatencyNs: Long = 0L
)

/**
 * Metadata for engine component reporting.
 */
data class EngineMetadata(
    val engineName: String,
    val modelVersion: String,
    val executionBackend: String, // e.g. "CPU_XNNPACK", "GPU_OPENCL", "NPU_QNN"
    val isQuantized: Boolean
)

/**
 * Result of the Level 1 cheap frame change detector.
 * Carries latency instrumentation for diagnostic tracking.
 */
sealed class ChangeDetectionResult {
    /** Frame content differs from previous keyframe enough to trigger OCR. */
    data class CHANGED(
        val latencyNs: Long,
        val hammingDistance: Int = -1
    ) : ChangeDetectionResult()

    /** Frame content is statically similar to the previous keyframe; OCR skipped. */
    data class NO_CHANGE(
        val latencyNs: Long,
        val hammingDistance: Int = 0
    ) : ChangeDetectionResult()

    /** Frame had no valid image planes; treated as unknown state. */
    object NO_DATA : ChangeDetectionResult()
}

/**
 * Instrumentation snapshot for the OCR pipeline (non-production diagnostic).
 * All latencies are wall-clock nanoseconds as measured on the worker thread.
 * Values of -1 indicate "not measured in this cycle".
 *
 * Classification: BENCHMARK REQUIRED — latency targets are unverified until
 * measured on physical ARM64 hardware.
 */
data class OcrPipelineStats(
    val frameTimestampNs: Long,
    val changeDetectionLatencyNs: Long,
    val ocrDetectionLatencyNs: Long = -1L,
    val ocrRecognitionLatencyNs: Long = -1L,
    val detectedRegionCount: Int = 0,
    val recognizedRegionCount: Int = 0,
    val skippedReason: SkipReason = SkipReason.NONE
) {
    enum class SkipReason { NONE, NO_CHANGE, NO_DATA, RATE_LIMITED, OCR_ERROR }
}

/**
 * Encapsulates text where dynamic variables and placeholders have been extracted and replaced
 * with neutral tokens for safe machine translation.
 */
data class ProtectedText(
    val maskedText: String,
    val tokenMap: Map<String, String> // e.g. "987650" -> "{player}" (numeric sentinel, see ISSUE-004)
)

/**
 * Instrumentation snapshot for Translation engine latency breakdowns.
 * All latencies are wall-clock nanoseconds.
 *
 * Classification: BENCHMARK REQUIRED — latency targets are unverified until
 * measured on physical ARM64 hardware.
 */
data class TranslationPipelineStats(
    val originalTextLength: Int,
    val totalLatencyNs: Long,
    val preprocessingLatencyNs: Long = 0L,
    val tokenizationLatencyNs: Long = 0L,
    val inferenceLatencyNs: Long = 0L,
    val detokenizationLatencyNs: Long = 0L,
    val postprocessingLatencyNs: Long = 0L,
    val fromCache: Boolean = false,
    val tokenCount: Int = 0,
    val engineName: String = "Unknown"
)


