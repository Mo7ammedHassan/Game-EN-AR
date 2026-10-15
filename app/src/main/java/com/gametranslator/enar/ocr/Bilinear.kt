package com.gametranslator.enar.ocr

import java.nio.ByteBuffer

/**
 * Shared bilinear image math (Phase 9 preprocessing).
 *
 * WHY one helper: the snapshot downscaler AND the detector/recognizer
 * resizers all need aspect-preserving bilinear sampling. OpenCV-compatible
 * mapping (`src = (dst + 0.5) * scale - 0.5`, clamp-to-edge) is used so
 * outputs stay within ~1 level of the `cv2.INTER_LINEAR` reference the
 * parity goldens were recorded with (`resize_dst_u8.raw` — interior pixels
 * match closely; single-edge pixels may differ by level rounding, covered by
 * the golden test tolerance).
 *
 * Pure JVM (operates on byte arrays / ByteBuffers, no Android classes).
 */
object Bilinear {

    /**
     * Resizes an RGB888 image with bilinear sampling.
     *
     * @param src Row-major RGB bytes (`srcW * srcH * 3`).
     * @return Row-major RGB bytes (`dstW * dstH * 3`).
     */
    fun resizeRgb(src: ByteArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): ByteArray {
        require(src.size == srcW * srcH * 3) { "src size mismatch" }
        require(srcW > 0 && srcH > 0 && dstW > 0 && dstH > 0) { "dims must be > 0" }
        val dst = ByteArray(dstW * dstH * 3)
        // Degenerate fast paths (avoid 0/0): single-pixel lines replicate.
        val xScale = srcW.toDouble() / dstW.toDouble()
        val yScale = srcH.toDouble() / dstH.toDouble()
        var d = 0
        for (dy in 0 until dstH) {
            val sy = ((dy + 0.5) * yScale - 0.5).coerceIn(0.0, (srcH - 1).toDouble())
            val y0 = sy.toInt().coerceIn(0, srcH - 1)
            val y1 = (y0 + 1).coerceIn(0, srcH - 1)
            val fy = (sy - y0).toFloat()
            for (dx in 0 until dstW) {
                val sx = ((dx + 0.5) * xScale - 0.5).coerceIn(0.0, (srcW - 1).toDouble())
                val x0 = sx.toInt().coerceIn(0, srcW - 1)
                val x1 = (x0 + 1).coerceIn(0, srcW - 1)
                val fx = (sx - x0).toFloat()
                for (c in 0..2) {
                    val p00 = src[(y0 * srcW + x0) * 3 + c].toInt() and 0xFF
                    val p10 = src[(y0 * srcW + x1) * 3 + c].toInt() and 0xFF
                    val p01 = src[(y1 * srcW + x0) * 3 + c].toInt() and 0xFF
                    val p11 = src[(y1 * srcW + x1) * 3 + c].toInt() and 0xFF
                    val top = p00 + (p10 - p00) * fx
                    val bot = p01 + (p11 - p01) * fx
                    dst[d++] = (top + (bot - top) * fy + 0.5f).toInt()
                        .coerceIn(0, 255).toByte()
                }
            }
        }
        return dst
    }

    /**
     * Reads one RGBA_8888 pixel via absolute buffer positions (no array copy —
     * the full-resolution plane is never materialized).
     *
     * @return packed `0xRRGGBB`.
     */
    fun readRgba(
        plane: ByteBuffer,
        x: Int,
        y: Int,
        rowStride: Int,
        pixelStride: Int
    ): Int {
        val pos = y * rowStride + x * pixelStride
        val r = plane.get(pos).toInt() and 0xFF
        val g = plane.get(pos + 1).toInt() and 0xFF
        val b = plane.get(pos + 2).toInt() and 0xFF
        return (r shl 16) or (g shl 8) or b
    }
}
