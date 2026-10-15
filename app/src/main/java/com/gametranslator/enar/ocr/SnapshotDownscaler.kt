package com.gametranslator.enar.ocr

import java.nio.ByteBuffer

/**
 * CaptureThread → snapshot conversion (Phase 9 pixel-transport producer).
 *
 * Reads RGBA_8888 plane bytes DIRECTLY (absolute `ByteBuffer` gets — the
 * 13.8 MB full-resolution frame is never copied) and emits one compact
 * RGB888 [OcrSnapshot] at [OcrConfig.SNAPSHOT_MAX_SIDE]. Bilinear downsample
 * via [Bilinear]; aspect preserved, so [OcrSnapshot.scale] is uniform and the
 * detector geometry stays a single multiply.
 *
 * Call ONLY while the source `Image` is open, on the CaptureThread, and only
 * for changed frames past the store throttle (the caller owns that gating —
 * this object is a pure converter). Cost is one strided pass over the source
 * (≈ 0.4 MP reads for the reference display) plus the snapshot allocation.
 */
object SnapshotDownscaler {

    /**
     * @param plane Direct plane buffer of an RGBA_8888 image (position/limit
     *   are NOT modified — absolute gets only).
     * @param srcW / srcH Frame dims in px (== `Image.width/height`).
     * @param rowStride Plane row stride in bytes (may exceed `srcW * 4`).
     * @param pixelStride Plane pixel stride in bytes (4 for RGBA_8888).
     * @param timestampNs Source frame timestamp, carried into the snapshot.
     */
    fun downscale(
        plane: ByteBuffer,
        srcW: Int,
        srcH: Int,
        rowStride: Int,
        pixelStride: Int,
        timestampNs: Long,
        maxSide: Int = OcrConfig.SNAPSHOT_MAX_SIDE
    ): OcrSnapshot {
        require(srcW > 0 && srcH > 0) { "source dims must be > 0" }
        require(rowStride >= srcW * pixelStride) { "rowStride too small" }
        require(pixelStride >= 4) { "need RGBA access (pixelStride=$pixelStride)" }

        val ratio = if (maxOf(srcW, srcH) > maxSide) {
            maxSide.toDouble() / maxOf(srcW, srcH).toDouble()
        } else {
            1.0
        }
        val dstW = (srcW * ratio).toInt().coerceAtLeast(1)
        val dstH = (srcH * ratio).toInt().coerceAtLeast(1)

        val rgb = ByteArray(dstW * dstH * 3)
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
                // Phase-10 optimization (bit-identical): read each packed
                // source pixel ONCE (4 reads/px, not 12) and blend channels
                // from the packed ints. Previously every channel re-read all
                // four corners (12 reads/px) and allocated an intArrayOf per
                // pixel (427k allocs/snapshot). Same pixels, same per-channel
                // arithmetic in the same order — output bytes are unchanged
                // (pinned by SnapshotPixelMathTest exact + cv2-tolerance tests).
                val c00 = Bilinear.readRgba(plane, x0, y0, rowStride, pixelStride)
                val c10 = Bilinear.readRgba(plane, x1, y0, rowStride, pixelStride)
                val c01 = Bilinear.readRgba(plane, x0, y1, rowStride, pixelStride)
                val c11 = Bilinear.readRgba(plane, x1, y1, rowStride, pixelStride)
                rgb[d++] = blendChannel(c00, c10, c01, c11, 16, fx, fy)
                rgb[d++] = blendChannel(c00, c10, c01, c11, 8, fx, fy)
                rgb[d++] = blendChannel(c00, c10, c01, c11, 0, fx, fy)
            }
        }
        return OcrSnapshot(
            width = dstW,
            height = dstH,
            rgb = rgb,
            srcWidth = srcW,
            srcHeight = srcH,
            timestampNs = timestampNs
        )
    }

    /**
     * One bilinear channel blend from four packed `0xRRGGBB` corners.
     * Same per-channel arithmetic the loop previously inlined (extract, then
     * top/bot lerp, then vertical lerp, round-half-up, clamp) — factored out
     * so corners are read once, not per channel.
     */
    private inline fun blendChannel(
        c00: Int,
        c10: Int,
        c01: Int,
        c11: Int,
        shift: Int,
        fx: Float,
        fy: Float
    ): Byte {
        val p00 = (c00 shr shift) and 0xFF
        val p10 = (c10 shr shift) and 0xFF
        val p01 = (c01 shr shift) and 0xFF
        val p11 = (c11 shr shift) and 0xFF
        val top = p00 + (p10 - p00) * fx
        val bot = p01 + (p11 - p01) * fx
        return (top + (bot - top) * fy + 0.5f).toInt()
            .coerceIn(0, 255).toByte()
    }
}
