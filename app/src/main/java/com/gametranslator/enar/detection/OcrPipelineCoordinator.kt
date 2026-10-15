package com.gametranslator.enar.detection

import android.util.Log
import com.gametranslator.enar.engine.FrameRateLimiter
import com.gametranslator.enar.engine.OcrEngine
import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.FrameBuffer
import com.gametranslator.enar.model.OcrPipelineStats
import com.gametranslator.enar.model.OcrResult
import com.gametranslator.enar.model.TextRegion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * OCR Pipeline Coordinator.
 *
 * Connects the screen capture [frameFlow] to the [OcrEngine] abstraction,
 * applying a two-level gate before any inference is requested:
 *
 * 1. **Change gate**: skips frames where [FrameBuffer.isChanged] is false
 *    (result of Level-1 FrameChangeDetector running on the CaptureThread).
 * 2. **OCR rate limit**: caps OCR invocations at [maxOcrFps] to prevent
 *    thermal/CPU saturation even when content changes rapidly.
 *
 * All OCR inference runs on [Dispatchers.Default] — never on the main thread
 * and never on the CaptureThread (HandlerThread).
 *
 * ## Output flows
 * - [ocrResults]: the latest list of stable OCR results for visible text regions.
 *   Emits an empty list when no text is detected or the pipeline is idle.
 * - [pipelineStats]: diagnostic instrumentation snapshot for the latest processed frame.
 *   Latency values of -1 mean "not measured in this cycle".
 *
 * ## Lifecycle
 * Call [start] once with the upstream [frameFlow] and a coroutine scope.
 * Call [stop] to cancel the coordinator (e.g. when capture service stops).
 * The coordinator owns its own [SupervisorJob] child; stopping it does NOT
 * cancel the parent scope.
 *
 * ## Classification
 * - OCR latency: BENCHMARK REQUIRED — not measured until models load on hardware.
 * - OCR accuracy: BENCHMARK REQUIRED.
 * - Memory impact of coordinator: LOW (no pixel data retained across iterations).
 *
 * @param ocrEngine  Pluggable OCR backend (currently [NoOpOcrEngine] for prototype).
 * @param stabilizer Per-region ring-buffer jitter filter.
 * @param maxOcrFps  Maximum OCR inference invocations per second. Default: 5.
 * @param tracker  Optional spatial tracker (Phase 7). When present, detector
 *   boxes are associated to stable `track-N` ids BEFORE recognition, and every
 *   downstream id (recognition, stabilization, pruning) is the tracker id, so
 *   stabilization operates on tracked regions. Null (default) preserves the
 *   exact legacy behavior (volatile engine ids). MainActivity passes nothing —
 *   production wiring enables it in Phase 7.
 * @param ocrRescanIntervalCycles  Recognition scheduling (Phase 7, Part E).
 *   1 (default) = legacy: recognize every tracked region every cycle. Higher
 *   values skip re-recognition for STABLE, near-static tracks (IoU > 0.98 vs
 *   last OCR) by re-observing their last text — new/changed/unstable/moved
 *   tracks are still recognized every cycle, and every track re-verifies at
 *   least every N cycles (bounded staleness vs OCR-cost trade-off, documented
 *   in ADR-011). Requires [tracker]; ignored when null.
 */
class OcrPipelineCoordinator(
    private val ocrEngine: OcrEngine,
    private val stabilizer: OcrResultStabilizer = OcrResultStabilizer(),
    private val maxOcrFps: Int = 5,
    private val tracker: RegionTracker? = null,
    private val ocrRescanIntervalCycles: Int = 1,
    private val rescanStableIou: Double = RESCAN_STABLE_IOU
) {

    companion object {
        private const val TAG = "OcrPipelineCoordinator"

        /** A stable track must barely move to skip re-recognition (0.90 tolerates sub-pixel/1-2px detector jitter). */
        const val RESCAN_STABLE_IOU = 0.90
    }

    init {
        require(ocrRescanIntervalCycles >= 1) {
            "ocrRescanIntervalCycles must be >= 1, got $ocrRescanIntervalCycles"
        }
    }

    private val _ocrResults = MutableStateFlow<List<OcrResult>>(emptyList())
    val ocrResults: StateFlow<List<OcrResult>> = _ocrResults.asStateFlow()

    private val _pipelineStats = MutableStateFlow<OcrPipelineStats?>(null)
    val pipelineStats: StateFlow<OcrPipelineStats?> = _pipelineStats.asStateFlow()

    // Rate limiter applied to OCR invocations (independent of the 30 FPS capture limiter).
    private val ocrRateLimiter = FrameRateLimiter(targetFps = maxOcrFps)

    // Last real recognition per tracked region (rescan policy memory only;
    // populated solely when [tracker] is present — bounded via activeIds prune).
    private data class LastOcr(
        val text: String,
        val confidence: Float,
        val bounds: BoundingBox,
        var cyclesSinceOcr: Int
    )

    private val lastRecognition = mutableMapOf<String, LastOcr>()

    private data class ScheduledResult(val result: OcrResult, val rescanSkipped: Boolean)

    /**
     * Part E recognition scheduling: recognize a region unless it is provably
     * fresh — tracked AND stable AND near-static (IoU > 0.98 vs last OCR) AND
     * re-verified within [ocrRescanIntervalCycles]. Fresh tracks re-observe
     * their last text (keeps stabilization alive without model cost).
     *
     * With the legacy configuration (`tracker == null` or interval == 1) this
     * always recognizes, exactly like the pre-Phase-7 code path.
     *
     * @return the observation, or null only when recognition itself throws
     *   (legacy skip-and-age path).
     */
    private suspend fun scheduleRecognition(
        frame: FrameBuffer,
        region: TextRegion
    ): ScheduledResult? {
        val usePolicy = tracker != null && ocrRescanIntervalCycles > 1
        val last = if (usePolicy) lastRecognition[region.id] else null
        if (last != null &&
            stabilizer.getState(region.id) == TrackState.STABLE &&
            RegionTracker.iou(last.bounds, region.bounds) > rescanStableIou &&
            last.cyclesSinceOcr < ocrRescanIntervalCycles - 1
        ) {
            last.cyclesSinceOcr++
            return ScheduledResult(
                OcrResult(
                    regionId = region.id,
                    rawText = last.text,
                    confidence = last.confidence,
                    bounds = region.bounds,
                    isStable = false
                ),
                rescanSkipped = true
            )
        }
        return try {
            val result = ocrEngine.recognizeRegion(frame, region)
            if (tracker != null) {
                lastRecognition[region.id] =
                    LastOcr(result.rawText, result.confidence, region.bounds, 0)
            }
            ScheduledResult(result, rescanSkipped = false)
        } catch (e: Exception) {
            Log.w(TAG, "OcrEngine.recognizeRegion failed for region ${region.id}", e)
            null
        }
    }

    // Coordinator-owned job; cancelled on stop() without affecting the parent scope.
    private var coordinatorJob: Job? = null

    /**
     * Starts collecting [frameFlow] and processing changed frames through [ocrEngine].
     *
     * Safe to call multiple times — any previous collection is cancelled first.
     *
     * @param frameFlow  Upstream StateFlow from [ScreenCaptureService].
     * @param parentScope Coroutine scope used as parent (caller manages its lifetime).
     */
    fun start(frameFlow: StateFlow<FrameBuffer?>, parentScope: CoroutineScope) {
        coordinatorJob?.cancel()
        coordinatorJob = parentScope.launch(Dispatchers.Default + SupervisorJob()) {
            Log.i(TAG, "OcrPipelineCoordinator started. Engine: ${ocrEngine.getEngineMetadata().engineName}")

            frameFlow
                .filterNotNull()
                .collect { frame ->
                    processFrame(frame)
                }
        }
        Log.d(TAG, "Coordinator collection job launched.")
    }

    /** Cancels the coordinator's collection job. Does not affect the parent scope. */
    fun stop() {
        coordinatorJob?.cancel()
        coordinatorJob = null
        stabilizer.reset()
        tracker?.reset()
        lastRecognition.clear()
        ocrRateLimiter.reset()
        _ocrResults.value = emptyList()
        _pipelineStats.value = null
        Log.i(TAG, "OcrPipelineCoordinator stopped.")
    }

    // ---- Private processing ----

    private suspend fun processFrame(frame: FrameBuffer) {
        // Gate 1: only process frames that carry a detected content change.
        if (!frame.isChanged) {
            _pipelineStats.value = OcrPipelineStats(
                frameTimestampNs = frame.timestamp,
                changeDetectionLatencyNs = frame.changeLatencyNs,
                skippedReason = OcrPipelineStats.SkipReason.NO_CHANGE
            )
            return
        }

        // Gate 2: cap OCR inference rate regardless of how often content changes.
        if (!ocrRateLimiter.shouldProcessFrame()) {
            _pipelineStats.value = OcrPipelineStats(
                frameTimestampNs = frame.timestamp,
                changeDetectionLatencyNs = frame.changeLatencyNs,
                skippedReason = OcrPipelineStats.SkipReason.RATE_LIMITED
            )
            return
        }

        // --- Text Detection ---
        val detStartNs = System.nanoTime()
        val regions: List<TextRegion>
        try {
            regions = ocrEngine.detectRegions(frame)
        } catch (e: Exception) {
            Log.w(TAG, "OcrEngine.detectRegions failed", e)
            _pipelineStats.value = OcrPipelineStats(
                frameTimestampNs = frame.timestamp,
                changeDetectionLatencyNs = frame.changeLatencyNs,
                skippedReason = OcrPipelineStats.SkipReason.OCR_ERROR
            )
            return
        }
        val detLatencyNs = System.nanoTime() - detStartNs

        if (regions.isEmpty()) {
            stabilizer.pruneStaleRegions(emptySet())
            _ocrResults.value = emptyList()
            _pipelineStats.value = OcrPipelineStats(
                frameTimestampNs = frame.timestamp,
                changeDetectionLatencyNs = frame.changeLatencyNs,
                ocrDetectionLatencyNs = detLatencyNs,
                detectedRegionCount = 0
            )
            return
        }

        // --- Spatial tracking (optional; null tracker = legacy engine ids) ---
        // TrackedRegion.region carries the detector box with id rewritten to
        // the stable trackId, so every downstream id is the tracker id.
        val trackedRegions: List<TextRegion> =
            tracker?.update(regions)?.map { it.region } ?: regions

        // --- Text Recognition (scheduled per region; see scheduleRecognition) ---
        val recStartNs = System.nanoTime()
        val rawResults = mutableListOf<OcrResult>()
        val skippedIds = mutableSetOf<String>()
        for (region in trackedRegions) {
            val scheduled = scheduleRecognition(frame, region)
            if (scheduled != null) {
                rawResults.add(scheduled.result)
                if (scheduled.rescanSkipped) skippedIds.add(region.id)
            }
        }
        val recLatencyNs = System.nanoTime() - recStartNs

        // --- Stabilization pass ---
        // Skipped-rescan tracks re-observe last text, so they stay in the active
        // set (only hard recognize-failures age out, as in legacy behavior).
        val activeIds = rawResults.map { it.regionId }.toSet() + skippedIds
        lastRecognition.keys.retainAll(activeIds)
        stabilizer.pruneStaleRegions(activeIds)
        val stableResults = rawResults.map { stabilizer.stabilize(it) }

        // Publish only results that are either stable OR have non-empty text
        // (no-op engine will emit empty strings which we filter to keep the list clean).
        val publishable = stableResults.filter { it.rawText.isNotEmpty() }

        _ocrResults.value = publishable
        _pipelineStats.value = OcrPipelineStats(
            frameTimestampNs = frame.timestamp,
            changeDetectionLatencyNs = frame.changeLatencyNs,
            ocrDetectionLatencyNs = detLatencyNs,
            ocrRecognitionLatencyNs = recLatencyNs,
            detectedRegionCount = regions.size,
            recognizedRegionCount = rawResults.size - skippedIds.size,
            skippedReason = OcrPipelineStats.SkipReason.NONE
        )

        if (publishable.isNotEmpty()) {
            Log.d(
                TAG,
                "OCR cycle: det=${detLatencyNs / 1_000_000}ms " +
                "rec=${recLatencyNs / 1_000_000}ms " +
                "regions=${regions.size} " +
                "stable=${publishable.count { it.isStable }} " +
                "skipped=${skippedIds.size}"
            )
        }
    }
}
