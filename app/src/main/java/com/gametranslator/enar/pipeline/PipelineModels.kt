package com.gametranslator.enar.pipeline

/**
 * Viewport configuration for capture→overlay mapping (Phase 7).
 *
 * Steady-state truth (verified in `ScreenCaptureService`): the `VirtualDisplay`
 * is built from current display metrics, so capture pixels == display pixels
 * and this is effectively identity. The fields exist so downscaled capture,
 * letterboxing, rotation, and density changes stay expressible without touching
 * pipeline logic — the owner refreshes via
 * [GameTranslatorPipeline.updateViewport] when they change.
 *
 * @param displayWidthPx Overlay/display width in physical pixels (> 0).
 * @param displayHeightPx Overlay/display height in physical pixels (> 0).
 * @param captureWidthPx Captured frame width in OCR pixels (> 0; == display
 *   width in steady state).
 * @param captureHeightPx Captured frame height in OCR pixels (> 0).
 * @param densityScale `DisplayMetrics.scaledDensity` for text sizing (> 0).
 * @param rotationClockwiseDeg Display-vs-capture rotation, {0, 90, 180, 270}.
 */
data class Viewport(
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val captureWidthPx: Int = displayWidthPx,
    val captureHeightPx: Int = displayHeightPx,
    val densityScale: Float = 1f,
    val rotationClockwiseDeg: Int = 0
) {
    init {
        require(displayWidthPx > 0) { "displayWidthPx must be > 0" }
        require(displayHeightPx > 0) { "displayHeightPx must be > 0" }
        require(captureWidthPx > 0) { "captureWidthPx must be > 0" }
        require(captureHeightPx > 0) { "captureHeightPx must be > 0" }
        require(densityScale > 0f && densityScale.isFinite()) {
            "densityScale must be positive finite"
        }
        require(rotationClockwiseDeg == 0 || rotationClockwiseDeg == 90 ||
            rotationClockwiseDeg == 180 || rotationClockwiseDeg == 270) {
            "rotation must be one of {0, 90, 180, 270}"
        }
    }
}

/**
 * Point-in-time diagnostic snapshot of the integrated pipeline (Part P).
 * All counters are lifetime-since-(re)start; latencies are nanoseconds.
 * No frame contents, OCR strings, or translations are ever logged or retained
 * here — counts and timings only.
 */
data class PipelineSnapshot(
    val cycles: Long,
    val stableReceived: Long,
    val unstableReceived: Long,
    val blankSkipped: Long,
    val launched: Long,
    val completedTranslations: Long,
    val cacheHitsObserved: Long,
    val cacheMissesObserved: Long,
    val staleDropped: Long,
    val supersededMarked: Long,
    val skippedBusy: Long,
    val unverifiedSuppressed: Long,
    val coldSkipped: Long,
    val translationFailures: Long,
    val retryLaunches: Long,
    val overlayUpdates: Long,
    val overlayHides: Long,
    val overlayFailures: Long,
    val regionsRemoved: Long,
    val lastEndToEndNs: Long,
    val maxEndToEndNs: Long,
    val inFlight: Int,
    val trackedRegions: Int,
    val overlayRegions: Int
)
