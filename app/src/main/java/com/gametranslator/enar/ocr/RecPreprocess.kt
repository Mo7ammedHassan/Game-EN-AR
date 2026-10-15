package com.gametranslator.enar.ocr

import kotlin.math.ceil

/**
 * Recognizer crop + `resize_norm_img` port (Phase 9, Paddle-faithful).
 *
 * [extractCrop]: axis-aligned sub-rectangle copy from snapshot RGB (the
 * reference perspective-warps quads — identical for our AABB boxes), plus the
 * reference tall-crop rule (`h / w >= 1.5` → 90° CCW `rot90`, e.g. vertical
 * text photographed sideways).
 *
 * [resizeNormImg]: single-image form of reference `resize_norm_img` with
 * `rec_image_shape = [3, 48, 320]`. With batch = 1 the batch width equals
 * this image's own width, so NO right-padding ever materializes (the padding
 * branch exists in code for structure parity but is a no-op here):
 * `imgW = int(48 * w/h)`; `resizedW = min(imgW, ceil(48 * w/h))`; bilinear to
 * `(resizedW, 48)`; normalize `(v/255 - 0.5) / 0.5`; zero-pad right to `imgW`.
 *
 * Returns null when the crop is degenerate (zero area) or the input would
 * exceed [OcrConfig.REC_MAX_WIDTH] (degenerate-sliver guard — caller emits an
 * empty result, the stabilizer freezes, nothing corrupts).
 *
 * Pure JVM. All coordinates are snapshot pixels.
 */
object RecPreprocess {

    data class RecInput(
        val chw: FloatArray,
        val height: Int,
        val width: Int
    )

    data class Crop(val rgb: ByteArray, val width: Int, val height: Int)

    fun extractCrop(
        snapshotRgb: ByteArray,
        snapW: Int,
        snapH: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int
    ): Crop? {
        val cx0 = x0.coerceIn(0, snapW)
        val cy0 = y0.coerceIn(0, snapH)
        val cx1 = x1.coerceIn(0, snapW)
        val cy1 = y1.coerceIn(0, snapH)
        val w = cx1 - cx0
        val h = cy1 - cy0
        if (w <= 0 || h <= 0) return null
        val out = ByteArray(w * h * 3)
        for (y in 0 until h) {
            System.arraycopy(snapshotRgb, ((cy0 + y) * snapW + cx0) * 3, out, y * w * 3, w * 3)
        }
        // Reference tall-crop rule: h/w >= 1.5 → rot90 (CCW, like np.rot90).
        if (h.toDouble() / w.toDouble() >= 1.5) {
            return rot90Ccw(out, w, h)
        }
        return Crop(out, w, h)
    }

    fun resizeNormImg(crop: Crop): RecInput? {
        val h = OcrConfig.REC_HEIGHT
        val ratio = crop.width.toDouble() / crop.height.toDouble()
        val imgW = (h * ratio).toInt()
        if (imgW <= 0) return null
        if (imgW > OcrConfig.REC_MAX_WIDTH) return null
        val resizedW = minOf(imgW, ceil(h * ratio).toInt()).coerceAtLeast(1)
        val resized = Bilinear.resizeRgb(crop.rgb, crop.width, crop.height, resizedW, h)
        val chw = FloatArray(3 * h * imgW) // zero-filled = right padding.
        for (y in 0 until h) {
            for (x in 0 until resizedW) {
                for (c in 0..2) {
                    val v = (resized[(y * resizedW + x) * 3 + c].toInt() and 0xFF) / 255f
                    chw[c * h * imgW + y * imgW + x] = (v - OcrConfig.REC_OFFSET) / OcrConfig.REC_SCALE
                }
            }
        }
        return RecInput(chw, h, imgW)
    }

    /** 90° counter-clockwise (np.rot90 semantics), RGB888. */
    internal fun rot90Ccw(rgb: ByteArray, w: Int, h: Int): Crop {
        // np.rot90: out[j][h-1-i]... precisely out[y'][x'] = in[x'][h-1-y']
        // with out dims (h, w): outW = h, outH = w.
        val out = ByteArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val ox = y
                val oy = w - 1 - x
                System.arraycopy(rgb, (y * w + x) * 3, out, (oy * h + ox) * 3, 3)
            }
        }
        return Crop(out, h, w)
    }
}
