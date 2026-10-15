package com.gametranslator.enar.ocr

import android.util.Log
import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.EngineMetadata
import com.gametranslator.enar.model.FrameBuffer
import com.gametranslator.enar.model.OcrResult
import com.gametranslator.enar.model.TextRegion
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Real ONNX OCR engine: DB detector + CTC recognizer behind [OcrEngine].
 *
 * DATA FLOW (the Phase 7 gap, closed):
 * ```
 * ScreenCaptureService --(changed+due)--> SnapshotStore.publish(snapshot)
 * OcrPipelineCoordinator --(changed frame)--> detectRegions(frame)
 *   └─> snapshotProvider() = store.acquire()   // latest pixels, never blocks
 *   └─> DetPreprocess → detector → DbDecoder → frame-space boxes
 * OcrPipelineCoordinator --(per region)--> recognizeRegion(frame, region)
 *   └─> crop from the ACTIVE snapshot (the one detection just used —
 *       a newer published frame can never swap pixels mid-cycle)
 *   └─> RecPreprocess → recognizer → CtcDecoder → text + score
 * ```
 *
 * CONTRACT NOTES (why no interface/coordinator changes were needed):
 * - `FrameBuffer` stays metadata-only: `detectRegions`/`recognizeRegion`
 *   ignore its (nonexistent) pixels and pull the side-channel snapshot.
 *   The `crop` argument of `recognizeRegion` is therefore intentionally
 *   unused — documented, not an oversight.
 * - Cold/absent snapshots degrade to EMPTY results (same shape as
 *   [com.gametranslator.enar.engine.NoOpOcrEngine]), never throws: the
 *   coordinator's empty path prunes nothing destructive (tracks simply age).
 * - One OCR cycle is strictly sequential in the coordinator, so a single
 *   `@Volatile activeSnapshot` is race-free; a newer store publish mid-cycle
 *   is picked up by the NEXT detection (latest-frame semantics preserved).
 * - Boxes are sorted top-to-bottom/left-to-right (reference `sorted_boxes`,
 *   10-px row tolerance) and given volatile `det-N` ids — the optional
 *   `RegionTracker` rewrites them to `track-N` exactly as before.
 * - `release()` drops sessions + the active snapshot (deterministic teardown;
 *   snapshots themselves are GC'd immutable bytes — nothing native to close).
 */
class OnnxOcrEngine(
    private val snapshotProvider: () -> OcrSnapshot?,
    private val sessions: OcrSessionProvider
) : com.gametranslator.enar.engine.OcrEngine {

    companion object {
        private const val TAG = "OnnxOcrEngine"
    }

    @Volatile
    private var activeSnapshot: OcrSnapshot? = null

    @Volatile
    private var decoder: CtcDecoder? = null

    /** Loads ORT sessions + dict off the caller's thread. Never throws. */
    fun loadModels() {
        try {
            sessions.load()
            decoder = CtcDecoder(sessions.characters())
            Log.i(TAG, "OCR engine warm (dict=${sessions.characters().size}).")
        } catch (e: Exception) {
            Log.e(TAG, "OCR loadModels failed (cold engine): ${e.message}", e)
            decoder = null
        }
    }

    fun isWarm(): Boolean = sessions.isLoaded() && decoder != null

    /** Releases sessions + active snapshot. Idempotent, safe pre-load. */
    fun release() {
        activeSnapshot = null
        decoder = null
        try {
            sessions.release()
        } catch (e: Exception) {
            Log.w(TAG, "OCR release: ${e.message}")
        }
    }

    override suspend fun detectRegions(frame: FrameBuffer): List<TextRegion> {
        val snap = snapshotProvider() ?: return emptyList()
        if (!isWarm()) return emptyList()
        activeSnapshot = snap
        return try {
            val input = DetPreprocess.process(snap) ?: return emptyList()
            val out = sessions.detector()
                .run(input.chw, input.batch, input.channels, input.height, input.width)
            require(out.prob.size == out.mapH * out.mapW) { "det output size mismatch" }
            val boxes = DbDecoder.decode(out.prob, out.mapW, out.mapH, snap.width, snap.height)
            toFrameRegions(boxes, snap)
        } catch (e: Exception) {
            Log.w(TAG, "detectRegions failed (isolated): ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun recognizeRegion(
        crop: FrameBuffer,
        region: TextRegion
    ): OcrResult {
        val snap = activeSnapshot
        val dec = decoder
        if (snap == null || dec == null || !sessions.isLoaded()) {
            return emptyResult(region)
        }
        return try {
            // Frame px → snapshot px (uniform scale), then clip.
            val sx0 = (region.bounds.left * snap.scale()).roundToInt()
            val sy0 = (region.bounds.top * snap.scale()).roundToInt()
            val sx1 = (region.bounds.right * snap.scale()).roundToInt()
            val sy1 = (region.bounds.bottom * snap.scale()).roundToInt()
            val cropImg = RecPreprocess.extractCrop(
                snap.rgb, snap.width, snap.height, sx0, sy0, sx1, sy1
            ) ?: return emptyResult(region, 0f)
            val recIn = RecPreprocess.resizeNormImg(cropImg) ?: return emptyResult(region, 0f)
            val out = sessions.recognizer()
                .run(recIn.chw, OcrConfig.REC_BATCH, 3, recIn.height, recIn.width)
            require(out.logits.size == out.batch * out.steps * out.classes) {
                "rec output size mismatch"
            }
            val decoded = dec.decode(out.logits, out.batch, out.steps, out.classes)
            if (decoded.isEmpty()) return emptyResult(region, 0f)
            // Batch is always 1: single result.
            val (text, score) = decoded[0]
            if (text.isEmpty() || score < OcrConfig.REC_TEXT_SCORE) {
                // Reference drops below-threshold texts: empty freezes the
                // stabilizer instead of corrupting it (score kept for stats).
                return emptyResult(region, score)
            }
            OcrResult(
                regionId = region.id,
                rawText = text,
                confidence = score,
                bounds = region.bounds,
                isStable = false
            )
        } catch (e: Exception) {
            Log.w(TAG, "recognizeRegion failed for ${region.id} (isolated): ${e.message}", e)
            emptyResult(region, 0f)
        }
    }

    override fun getEngineMetadata(): EngineMetadata = EngineMetadata(
        engineName = "OnnxOcrEngine",
        modelVersion = "ppocr_en_det_v3+rec_v4",
        executionBackend = if (isWarm()) "CPU_ORT_${OcrConfig.ORT_INTRA_OP_THREADS}T" else "NONE_MODEL_NOT_LOADED",
        isQuantized = false
    )

    private fun emptyResult(region: TextRegion, confidence: Float = 0f) = OcrResult(
        regionId = region.id,
        rawText = "",
        confidence = confidence,
        bounds = region.bounds,
        isStable = false
    )

    /**
     * Snapshot boxes → frame-space regions: clip to the frame, drop
     * sub-3px sides (reference `filter_tag_det_res`), sort in reading order,
     * assign volatile ids.
     */
    internal fun toFrameRegions(boxes: List<DbDecoder.DetBox>, snap: OcrSnapshot): List<TextRegion> {
        val kept = boxes.mapNotNull { b ->
            val left = max(0, (b.x0 / snap.scale()).roundToInt())
            val top = max(0, (b.y0 / snap.scale()).roundToInt())
            val right = min(snap.srcWidth, (b.x1 / snap.scale()).roundToInt())
            val bottom = min(snap.srcHeight, (b.y1 / snap.scale()).roundToInt())
            if (right - left <= OcrConfig.DET_MIN_FRAME_SIDE ||
                bottom - top <= OcrConfig.DET_MIN_FRAME_SIDE
            ) {
                null
            } else {
                TextRegion(
                    id = "",
                    bounds = BoundingBox(left, top, right, bottom),
                    confidence = b.score
                )
            }
        }
        return sortReadingOrder(kept).mapIndexed { i, r -> r.copy(id = "det-$i") }
    }

    /** Reference `sorted_boxes`: top-to-bottom, left-to-right within 10 px rows. */
    internal fun sortReadingOrder(regions: List<TextRegion>): List<TextRegion> {
        val sorted = regions.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val boxes = sorted.toMutableList()
        for (i in 0 until boxes.size - 1) {
            val a = boxes[i]
            val b = boxes[i + 1]
            if (kotlin.math.abs(b.bounds.top - a.bounds.top) < 10 &&
                b.bounds.left < a.bounds.left
            ) {
                boxes[i] = b
                boxes[i + 1] = a
            }
        }
        return boxes
    }
}
