package com.gametranslator.enar.ocr

import kotlin.math.max
import kotlin.math.min

/**
 * Differentiable-Binarization probability-map decoder (Phase 9).
 *
 * Faithful port of reference `DBPostProcess` (thresh 0.3, box_thresh 0.5,
 * max 1000 candidates, unclip 1.6, 2×2 dilation, fast polygon-mean score)
 * with ONE documented architectural simplification: the pipeline's
 * [com.gametranslator.enar.model.TextRegion] carries axis-aligned
 * [com.gametranslator.enar.model.BoundingBox] (rotation is unrepresentable
 * downstream), so rotated `minAreaRect` quads are replaced by axis-aligned
 * boxes throughout. For horizontal game UI text the quad IS the AABB, and the
 * corpus golden (`det_probmap_*`: 3/3 boxes, IoU-pinned by test) bounds the
 * divergence. Vertical/rotated text is out of scope (documented, not silent:
 * tall boxes still decode, just axis-aligned).
 *
 * Algorithm (model-input pixel space):
 * 1. bitmap = prob > thresh; optional 2×2 dilation (expands down-right,
 *    matching the reference kernel anchor within the 1-px tolerance the
 *    unclip expansion absorbs).
 * 2. 4-connected flood-fill components (≡ `findContours RETR_LIST` blobs for
 *    solid text; hole contours the reference emits separately are filtered
 *    by `min_size` there and absent here — same surviving set).
 * 3. Per blob: AABB; skip when short side < [OcrConfig.DET_MIN_SIDE];
 *    score = mean prob over the AABB (reference masks the rotated quad —
 *    identical for horizontal text); skip when score < box_thresh.
 * 4. Uniform outward expansion by `d = area × unclip_ratio / perimeter`
 *    (reference `pyclipper` JT_ROUND on quads ≈ uniform for rectangles);
 *    skip when short side < [OcrConfig.DET_MIN_SIDE_AFTER_UNCLIP].
 * 5. Scale to destination (snapshot) dims with banker's rounding + clip
 *    (mirrors `np.round` + `np.clip`).
 *
 * Pure JVM. Output boxes are `[x0, y0, x1, y1]` ordered, in DESTINATION
 * (snapshot) coordinates.
 */
object DbDecoder {

    data class DetBox(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val score: Float
    ) {
        val width: Float get() = x1 - x0
        val height: Float get() = y1 - y0
    }

    fun decode(
        prob: FloatArray,
        mapW: Int,
        mapH: Int,
        destW: Int,
        destH: Int,
        thresh: Float = OcrConfig.DET_THRESH,
        boxThresh: Float = OcrConfig.DET_BOX_THRESH,
        unclipRatio: Float = OcrConfig.DET_UNCLIP_RATIO,
        useDilation: Boolean = OcrConfig.DET_USE_DILATION,
        maxCandidates: Int = OcrConfig.DET_MAX_CANDIDATES
    ): List<DetBox> {
        require(prob.size == mapW * mapH) { "prob size mismatch" }
        require(mapW > 0 && mapH > 0 && destW > 0 && destH > 0) { "dims must be > 0" }

        // 1. Binarize (+ dilate).
        val bitmap = BooleanArray(mapW * mapH)
        for (i in prob.indices) bitmap[i] = prob[i] > thresh
        val mask = if (useDilation) dilate2x2(bitmap, mapW, mapH) else bitmap

        // 2. Connected components (4-connectivity), capped like max_candidates.
        val boxes = mutableListOf<DetBox>()
        val seen = BooleanArray(mapW * mapH)
        var blobs = 0
        for (seed in mask.indices) {
            if (!mask[seed] || seen[seed]) continue
            if (blobs >= maxCandidates) break
            blobs++
            var x0 = mapW; var y0 = mapH; var x1 = -1; var y1 = -1
            val stack = ArrayDeque<Int>()
            stack.add(seed)
            seen[seed] = true
            while (stack.isNotEmpty()) {
                val p = stack.removeLast()
                val px = p % mapW
                val py = p / mapW
                if (px < x0) x0 = px
                if (px > x1) x1 = px
                if (py < y0) y0 = py
                if (py > y1) y1 = py
                // 4-neighbours.
                if (px > 0) tryAdd(mask, seen, stack, p - 1)
                if (px < mapW - 1) tryAdd(mask, seen, stack, p + 1)
                if (py > 0) tryAdd(mask, seen, stack, p - mapW)
                if (py < mapH - 1) tryAdd(mask, seen, stack, p + mapW)
            }
            // AABB inclusive → half-open [x0, x1+1).
            val bw = (x1 - x0 + 1).toFloat()
            val bh = (y1 - y0 + 1).toFloat()
            // 3. Size + score filter.
            if (min(bw, bh) < OcrConfig.DET_MIN_SIDE) continue
            var sum = 0.0
            for (yy in y0..y1) {
                val row = yy * mapW
                for (xx in x0..x1) sum += prob[row + xx]
            }
            val score = (sum / (bw * bh)).toFloat()
            if (score < boxThresh) continue
            // 4. Unclip expansion + re-filter.
            val area = bw * bh
            val perimeter = 2f * (bw + bh)
            val d = area * unclipRatio / perimeter
            val ex0 = x0 - d
            val ey0 = y0 - d
            val ex1 = x1 + 1 + d
            val ey1 = y1 + 1 + d
            if (min(ex1 - ex0, ey1 - ey0) < OcrConfig.DET_MIN_SIDE_AFTER_UNCLIP) continue
            // 5. Scale to destination with banker's rounding + clip.
            val sx0 = Math.rint(ex0.toDouble() / mapW * destW).toFloat()
                .coerceIn(0f, destW.toFloat())
            val sy0 = Math.rint(ey0.toDouble() / mapH * destH).toFloat()
                .coerceIn(0f, destH.toFloat())
            val sx1 = Math.rint(ex1.toDouble() / mapW * destW).toFloat()
                .coerceIn(0f, destW.toFloat())
            val sy1 = Math.rint(ey1.toDouble() / mapH * destH).toFloat()
                .coerceIn(0f, destH.toFloat())
            boxes.add(DetBox(sx0, sy0, sx1, sy1, score))
        }
        return boxes
    }

    private fun tryAdd(
        mask: BooleanArray,
        seen: BooleanArray,
        stack: ArrayDeque<Int>,
        p: Int
    ) {
        if (mask[p] && !seen[p]) {
            seen[p] = true
            stack.add(p)
        }
    }

    /** 2×2 all-ones dilation expanding down-right (reference-anchor equivalent
     * within the absorbed 1-px tolerance). */
    internal fun dilate2x2(bitmap: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = bitmap[y * w + x]
                if (!v && x > 0) v = bitmap[y * w + x - 1]
                if (!v && y > 0) v = bitmap[(y - 1) * w + x]
                if (!v && x > 0 && y > 0) v = bitmap[(y - 1) * w + x - 1]
                out[y * w + x] = v
            }
        }
        return out
    }

    /** Box overlap metric for golden tests (reference-vs-port agreement). */
    fun iou(a: DetBox, b: DetBox): Float {
        val ix0 = max(a.x0, b.x0)
        val iy0 = max(a.y0, b.y0)
        val ix1 = min(a.x1, b.x1)
        val iy1 = min(a.y1, b.y1)
        val inter = max(0f, ix1 - ix0) * max(0f, iy1 - iy0)
        if (inter <= 0f) return 0f
        val union = a.width * a.height + b.width * b.height - inter
        return if (union <= 0f) 0f else inter / union
    }
}
