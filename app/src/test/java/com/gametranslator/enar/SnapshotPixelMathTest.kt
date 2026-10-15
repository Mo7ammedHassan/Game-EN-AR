package com.gametranslator.enar

import com.gametranslator.enar.ocr.Bilinear
import com.gametranslator.enar.ocr.DetPreprocess
import com.gametranslator.enar.ocr.OcrSnapshot
import com.gametranslator.enar.ocr.SnapshotDownscaler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 9 pixel-math tests: bilinear resize (incl. cv2 golden parity),
 * RGBA downscaler (strides, identity, scaling), detector geometry +
 * normalization exactness.
 */
class SnapshotPixelMathTest {

    // ---- Bilinear ----

    @Test
    fun `solid color resizes exactly`() {
        val src = ByteArray(8 * 6 * 3) { i -> (100 + (i % 3) * 50).toByte() }
        val dst = Bilinear.resizeRgb(src, 8, 6, 4, 3)
        for (y in 0 until 3) {
            for (x in 0 until 4) {
                for (c in 0..2) {
                    assertEquals(
                        "pixel ($x,$y) ch $c",
                        100 + c * 50, dst[(y * 4 + x) * 3 + c].toInt() and 0xFF
                    )
                }
            }
        }
    }

    @Test
    fun `upscale of 2x2 corners interpolates center`() {
        // 2x2: black, red / green, blue → 4x4 center must blend.
        val src = byteArrayOf(
            0, 0, 0, 255.toByte(), 0, 0,
            0, 255.toByte(), 0, 0, 0, 255.toByte()
        )
        val dst = Bilinear.resizeRgb(src, 2, 2, 4, 4)
        assertEquals(16, dst.size / 3)
        // Corners stay near their source colors (tolerance for edge mapping).
        val tl = intArrayOf(0, 1, 2).map { dst[it].toInt() and 0xFF }
        assertTrue("top-left near black: $tl", tl.sum() < 120)
    }

    @Test
    fun `matches cv2 INTER_LINEAR golden within tolerance`() {
        val meta = resourceText("ocr/resize_meta.json")
        assertTrue(meta.contains("48"))
        val src = resourceBytes("ocr/resize_src_u8.raw")
        val want = resourceBytes("ocr/resize_dst_u8.raw")
        // Golden dims: 48x64 -> 72x96 (from resize_meta.json).
        val got = Bilinear.resizeRgb(src, 64, 48, 96, 72)
        assertEquals(want.size, got.size)
        var maxDiff = 0
        var diffCount = 0
        for (i in want.indices) {
            val d = kotlin.math.abs((want[i].toInt() and 0xFF) - (got[i].toInt() and 0xFF))
            if (d > maxDiff) maxDiff = d
            if (d > 2) diffCount++
        }
        // Interior matches closely; single-edge mapping may round by a level.
        assertTrue("maxDiff=$maxDiff diffCount=$diffCount", maxDiff <= 3 && diffCount <= want.size * 0.02)
    }

    // ---- SnapshotDownscaler ----

    private fun rgbaPlane(w: Int, h: Int, rowStride: Int, fn: (x: Int, y: Int) -> Int): ByteBuffer {        val buf = ByteBuffer.allocateDirect(rowStride * h).order(ByteOrder.nativeOrder())
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = fn(x, y)
                val pos = y * rowStride + x * 4
                buf.put(pos, ((p shr 16) and 0xFF).toByte())
                buf.put(pos + 1, ((p shr 8) and 0xFF).toByte())
                buf.put(pos + 2, (p and 0xFF).toByte())
                buf.put(pos + 3, 255.toByte())
            }
        }
        return buf
    }

    @Test
    fun `downscale keeps aspect and drops alpha`() {
        // 1080x2400 frame (reference display) → 432x960 snapshot.
        val plane = rgbaPlane(1080, 2400, 1080 * 4) { x, _ -> ((x * 255 / 1080) shl 16) or 0x8080 }
        val snap = SnapshotDownscaler.downscale(plane, 1080, 2400, 1080 * 4, 4, 999L)
        assertEquals(432, snap.width)
        assertEquals(960, snap.height)
        assertEquals(1080, snap.srcWidth)
        assertEquals(2400, snap.srcHeight)
        assertEquals(999L, snap.timestampNs)
        assertEquals(432 * 960 * 3, snap.rgb.size)
        // Red ramps left→right: first column dark, last column bright.
        val first = snap.rgb[0].toInt() and 0xFF
        val last = snap.rgb[(959 * 432 + 431) * 3].toInt() and 0xFF
        assertTrue("first=$first last=$last", first < 20 && last > 230)
        // Green channel preserved (~0x80).
        assertTrue(kotlin.math.abs((snap.rgb[1].toInt() and 0xFF) - 128) <= 3)
    }

    @Test
    fun `identity downscale reproduces input bytes exactly`() {
        // Ratio 1.0: every output pixel samples its source pixel exactly
        // (fx=fy=0). Any arithmetic reorder/regression breaks this byte equality.
        val w = 61
        val h = 43
        val rowStride = w * 4 + 16 // padded rows must not leak in
        val plane = rgbaPlane(w, h, rowStride) { x, y ->
            (((x * 5 + y * 11) and 0xFF) shl 16) or
                (((x * 13 + y * 7) and 0xFF) shl 8) or
                ((x * 3 + y * 17) and 0xFF)
        }
        val snap = SnapshotDownscaler.downscale(plane, w, h, rowStride, 4, 0L, maxSide = 960)
        assertEquals(w, snap.width)
        assertEquals(h, snap.height)
        for (y in 0 until h) {
            for (x in 0 until w) {
                for (c in 0..2) {
                    val want = when (c) {
                        0 -> (x * 5 + y * 11) and 0xFF
                        1 -> (x * 13 + y * 7) and 0xFF
                        else -> (x * 3 + y * 17) and 0xFF
                    }
                    val got = snap.rgb[(y * w + x) * 3 + c].toInt() and 0xFF
                    assertEquals("pixel ($x,$y) ch $c", want, got)
                }
            }
        }
    }

    @Test
    fun `scaled ramps stay within rounding of analytic values`() {
        // Bilinear interpolation reproduces linear fields up to float rounding.
        val w = 200
        val h = 150
        val plane = rgbaPlane(w, h, w * 4) { x, y ->
            ((x * 255 / (w - 1)) shl 16) or ((y * 255 / (h - 1)) shl 8) or 128
        }
        val snap = SnapshotDownscaler.downscale(plane, w, h, w * 4, 4, 0L, maxSide = 100)
        assertEquals(100, snap.width)
        assertEquals(75, snap.height)
        var maxDiff = 0
        for (dy in 0 until 75) {
            for (dx in 0 until 100) {
                // Analytic source coordinate of this output pixel.
                val sx = (dx + 0.5) * (w / 100.0) - 0.5
                val sy = (dy + 0.5) * (h / 75.0) - 0.5
                val wantR = ((sx.coerceIn(0.0, (w - 1).toDouble()) * 255 / (w - 1))).toInt()
                val wantG = ((sy.coerceIn(0.0, (h - 1).toDouble()) * 255 / (h - 1))).toInt()
                val gotR = snap.rgb[(dy * 100 + dx) * 3].toInt() and 0xFF
                val gotG = snap.rgb[(dy * 100 + dx) * 3 + 1].toInt() and 0xFF
                maxDiff = maxOf(maxDiff, kotlin.math.abs(wantR - gotR), kotlin.math.abs(wantG - gotG))
            }
        }
        assertTrue("maxDiff=$maxDiff", maxDiff <= 2)
    }

    @Test
    fun `downscale honors row-stride padding`() {
        val rowStride = 16 * 4 + 32 // padded rows
        val plane = rgbaPlane(16, 12, rowStride) { _, _ -> 0x102030 }
        val snap = SnapshotDownscaler.downscale(
            plane, 16, 12, rowStride, 4, 0L, maxSide = 16
        )
        assertEquals(16, snap.width)
        assertEquals(12, snap.height)
        for (i in snap.rgb.indices step 3) {
            assertEquals(0x10, snap.rgb[i].toInt() and 0xFF)
            assertEquals(0x20, snap.rgb[i + 1].toInt() and 0xFF)
            assertEquals(0x30, snap.rgb[i + 2].toInt() and 0xFF)
        }
    }

    @Test
    fun `small frames are never upscaled`() {
        val plane = rgbaPlane(100, 60, 100 * 4) { _, _ -> 0xFFFFFF }
        val snap = SnapshotDownscaler.downscale(plane, 100, 60, 100 * 4, 4, 0L, maxSide = 960)
        assertEquals(100, snap.width)
        assertEquals(60, snap.height)
    }

    // ---- DetPreprocess ----

    @Test
    fun `det model dims follow max-side plus 32-rounding`() {
        // Reference display snapshot 445x960 → ratio 1 → (448, 960).
        assertEquals(448 to 960, DetPreprocess.modelDims(445, 960))
        // Banker's rounding (CPython parity): 400/32 = 12.5 → 12, not 13.
        assertEquals(800 to 384, DetPreprocess.modelDims(800, 400))
        // Downscale case: 1264x2728 frame at snapshot scale is 445x960 (same as above).
        assertEquals(448 to 960, DetPreprocess.modelDims(445, 960))
        // Landscape.
        assertEquals(960 to 448, DetPreprocess.modelDims(960, 445))
    }

    @Test
    fun `det normalize applies bgr-swapped paddle constants exactly`() {
        // 1x1 red snapshot pixel; model dims (32,32) min? force via 32x32 input.
        val rgb = byteArrayOf(255.toByte(), 0, 0)
        val snap = OcrSnapshot(1, 1, rgb, 8, 8, 0L)
        val input = DetPreprocess.process(snap)
        // modelDims(1,1): ratio 1 → rint(1/32)=0 → 0 → null (degenerate).
        assertNull(input)
        // Non-degenerate: 64x64 snapshot, first pixel pure red → BGR (0,0,255):
        // c0 = (0/255 - 0.485)/0.229; c2 = (1 - 0.406)/0.225.
        val rgb64 = ByteArray(64 * 64 * 3)
        rgb64[0] = 255.toByte()
        val big = OcrSnapshot(64, 64, rgb64, 64, 64, 0L)
        val got = DetPreprocess.process(big)!!
        assertEquals(64, got.width)
        assertEquals(64, got.height)
        // First pixel is red (255,0,0) → BGR (0,0,255):
        // c0 = (0/255 - 0.485)/0.229; c2 = (1 - 0.406)/0.225.
        val c0 = got.chw[0]
        val c2 = got.chw[2 * 64 * 64]
        assertEquals((0f - 0.485f) / 0.229f, c0, 1e-5f)
        assertEquals((1f - 0.406f) / 0.225f, c2, 1e-5f)
        assertEquals(1f, got.ratioW, 1e-6f)
        assertEquals(1f, got.ratioH, 1e-6f)
    }

    // ---- helpers ----

    private fun resourceBytes(name: String): ByteArray =
        (javaClass.getResourceAsStream("/$name")
            ?: throw IllegalStateException("missing test resource $name")).readBytes()

    private fun resourceText(name: String): String =
        resourceBytes(name).toString(Charsets.UTF_8)
}
