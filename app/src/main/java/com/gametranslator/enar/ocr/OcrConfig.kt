package com.gametranslator.enar.ocr

/**
 * Phase 9 OCR numeric parameters — single source of truth.
 *
 * Every value traces to the reference implementation that runs the SAME
 * ONNX artifacts on the dev machine (`rapidocr_onnxruntime`, Apache-2.0,
 * PaddlePaddle-origin semantics) or to a documented project decision.
 * Changing any value requires re-running the reference-parity goldens
 * (`app/src/test/resources/ocr/`, see `docs/research.md` §OCR-Phase-9).
 */
object OcrConfig {
    // ---- Snapshot transport (project decision; detector-driven) ----
    /** Snapshot long side in px. Matches [DET_LIMIT_SIDE] so the detector
     * resize is usually identity; bounds snapshot RAM at 960×540×3 ≈ 1.5 MB
     * for 16:9 and ≈1.3 MB for the 1264×2728 reference display. */
    const val SNAPSHOT_MAX_SIDE = 960

    /** Minimum time between published snapshots. Aligned with the coordinator
     * `maxOcrFps = 5` gate so at most one snapshot exists per OCR cycle. */
    const val SNAPSHOT_MIN_INTERVAL_MS = 200L

    // ---- Detector preprocess (PaddleOCR mobile-det inference defaults) ----
    /** Model-input long side; `limit_type = max` (PaddleOCR `--det_limit_side_len
     *  960 --det_limit_type max`). NOTE: rapidocr_onnxruntime's *packaged*
     * default is 736/min (doc-scan tuned); max/960 is PaddleOCR's own mobile
     * inference default and appropriate for screenshots. Parity goldens were
     * recorded with max/960 — the value and the goldens must move together. */
    const val DET_LIMIT_SIDE = 960

    /** DB head stride alignment: dims round to multiples of 32. */
    const val DET_SIZE_MULTIPLE = 32

    /** NormalizeImage: `scale = 1/255`, RGB-order mean/std applied to the
     * BGR-ordered buffer (Paddle quirk — replicated exactly). */
    const val DET_SCALE = 1.0f / 255.0f
    val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    // ---- Detector postprocess (rapidocr_onnxruntime DBPostProcess defaults) ----
    const val DET_THRESH = 0.3f
    const val DET_BOX_THRESH = 0.5f
    const val DET_UNCLIP_RATIO = 1.6f
    const val DET_USE_DILATION = true
    const val DET_MAX_CANDIDATES = 1000
    /** Minimum short side (px, model-input scale) — mirrors `min_size = 3`
     * plus the post-unclip `min_size + 2` re-filter. */
    const val DET_MIN_SIDE = 3
    const val DET_MIN_SIDE_AFTER_UNCLIP = 5

    /** Engine-level box filter mirroring reference `filter_tag_det_res`
     * (drop rects with width or height ≤ 3 px, frame scale). */
    const val DET_MIN_FRAME_SIDE = 3

    // ---- Recognizer preprocess (`resize_norm_img`, rec_img_shape [3,48,320]) ----
    const val REC_HEIGHT = 48

    /** Per-region inference uses batch = 1, so no right-padding ever occurs
     * (resized width == batch width by construction). */
    const val REC_BATCH = 1

    /** Hard safety bound on recognizer input width. The snapshot is ≤ 960 px
     * wide, so legitimate crops stay far below this; beyond it the crop is a
     * degenerate sliver whose output would be meaningless (freeze, don't
     * corrupt). */
    const val REC_MAX_WIDTH = 2048

    /** Normalize: `(x/255 - 0.5) / 0.5`. */
    const val REC_OFFSET = 0.5f
    const val REC_SCALE = 0.5f

    // ---- Recognizer postprocess (reference `CTCLabelDecode`) ----
    /** Reference `RapidOCR()` text-score gate: below this the text is
     * dropped (empty result → stabilizer freezes, never corrupts). */
    const val REC_TEXT_SCORE = 0.5f

    // ---- Runtime (ORT CPU; mirrors the proven NMT session options) ----
    /** Inference thread count. Same 4-thread CPU value proven for the NMT
     * path on VKJ-NX9 (ENTRY 001/002); OCR delegates stay unevaluated until
     * device measurement (no delegate is assumed faster). */
    const val ORT_INTRA_OP_THREADS = 4
}
