package com.gametranslator.enar.ocr

/**
 * Detector input geometry + normalization (Phase 9, Paddle-faithful).
 *
 * Mirrors reference `DetResizeForTest(limit_side_len=960, limit_type=max)`:
 * scale so the LONG side == 960 (never upscale), then round each dim to a
 * multiple of 32 with banker's rounding (`Math.rint`, matching CPython
 * `round()` — plain half-up `round()` diverges on exact *.5 cases such as
 * 400/32 = 12.5 → 12, and the parity golden arbitrates this).
 * `cv2.resize` then stretches directly to the rounded dims, so the two axis
 * ratios differ slightly — both are tracked ([ratioW]/[ratioH]).
 *
 * Normalization mirrors reference `NormalizeImage(scale=1/255,
 * mean=[0.485,0.456,0.406], std=[0.229,0.224,0.225], order=hwc)` INCLUDING
 * the Paddle quirk of applying the RGB-ordered constants to BGR-ordered
 * channels: the snapshot is RGB, so channels are reversed to BGR first and
 * the identical arithmetic follows. Output is CHW float32 (the ORT input).
 *
 * Pure JVM. Returns null when the snapshot is degenerate for detection
 * (rounded dims ≤ 0 — the reference returns None the same way).
 */
object DetPreprocess {

    data class DetInput(
        val chw: FloatArray,
        val batch: Int = 1,
        val channels: Int = 3,
        val height: Int,
        val width: Int,
        val ratioH: Float,
        val ratioW: Float
    )

    fun process(snapshot: OcrSnapshot): DetInput? {
        val (modelW, modelH) = modelDims(snapshot.width, snapshot.height)
            ?: return null
        val resized = Bilinear.resizeRgb(snapshot.rgb, snapshot.width, snapshot.height, modelW, modelH)
        val chw = FloatArray(3 * modelH * modelW)
        val mean = OcrConfig.DET_MEAN
        val std = OcrConfig.DET_STD
        val scale = OcrConfig.DET_SCALE
        // BGR channel order (c=0 → snapshot B at offset 2).
        for (y in 0 until modelH) {
            for (x in 0 until modelW) {
                val s = (y * modelW + x) * 3
                val b = (resized[s + 2].toInt() and 0xFF) * scale
                val g = (resized[s + 1].toInt() and 0xFF) * scale
                val r = (resized[s].toInt() and 0xFF) * scale
                chw[x + y * modelW] = (b - mean[0]) / std[0]
                chw[modelW * modelH + x + y * modelW] = (g - mean[1]) / std[1]
                chw[2 * modelW * modelH + x + y * modelW] = (r - mean[2]) / std[2]
            }
        }
        return DetInput(
            chw = chw,
            height = modelH,
            width = modelW,
            ratioH = modelH.toFloat() / snapshot.height.toFloat(),
            ratioW = modelW.toFloat() / snapshot.width.toFloat()
        )
    }

    /**
     * Model-input dims for a snapshot (public for geometry tests).
     * Null when degenerate (mirrors reference `None`).
     */
    fun modelDims(snapW: Int, snapH: Int): Pair<Int, Int>? {
        val limit = OcrConfig.DET_LIMIT_SIDE
        val ratio = if (maxOf(snapW, snapH) > limit) {
            limit.toDouble() / maxOf(snapW, snapH).toDouble()
        } else {
            1.0
        }
        val m = OcrConfig.DET_SIZE_MULTIPLE
        val modelH = (Math.rint(snapH * ratio / m) * m).toInt()
        val modelW = (Math.rint(snapW * ratio / m) * m).toInt()
        if (modelW <= 0 || modelH <= 0) return null
        return modelW to modelH
    }
}
