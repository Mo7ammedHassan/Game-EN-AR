package com.gametranslator.enar

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gametranslator.enar.model.FrameBuffer
import com.gametranslator.enar.ocr.CtcDecoder
import com.gametranslator.enar.ocr.DbDecoder
import com.gametranslator.enar.ocr.DetPreprocess
import com.gametranslator.enar.ocr.OcrSnapshot
import com.gametranslator.enar.ocr.OnnxOcrEngine
import com.gametranslator.enar.ocr.OrtOcrSessionProvider
import com.gametranslator.enar.ocr.RecPreprocess
import com.gametranslator.enar.ocr.SnapshotDownscaler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 10 Step-1 baseline: per-stage ART timings on VKJ-NX9 with REAL
 * artifacts (no production changes; measurement only).
 *
 * Stages timed separately so the bottleneck ranking (Step 2) uses measured
 * ART numbers, not intuition: snapshot downscale, det preprocess, ORT det,
 * DB decode, rec crop+preprocess, ORT rec, CTC decode, plus full engine
 * detect/recognize for cross-check. Reports p50/p95/max + N per stage via
 * `OCR_BENCH` log lines. Asserts are loose sanity gates (non-empty outputs
 * where the corpus guarantees them) — this test measures, it does not gate
 * on latency.
 */
@RunWith(AndroidJUnit4::class)
class OcrStageBenchmarkDeviceTest {

    private lateinit var appContext: Context
    private lateinit var testContext: Context

    @Before
    fun setUp() {
        val instr = InstrumentationRegistry.getInstrumentation()
        appContext = instr.targetContext
        testContext = instr.context
    }

    private data class Stats(val p50: Double, val p95: Double, val max: Double, val n: Int)

    private fun summarize(name: String, samplesNs: LongArray): Stats {
        val sorted = samplesNs.sorted()
        val n = sorted.size
        val s = Stats(
            p50 = sorted[(n * 0.50).toInt()] / 1_000_000.0,
            p95 = sorted[(n * 0.95).toInt().coerceAtMost(n - 1)] / 1_000_000.0,
            max = sorted[n - 1] / 1_000_000.0,
            n = n
        )
        Log.i("OCR_BENCH", "$name N=$n p50_ms=${"%.2f".format(java.util.Locale.US, s.p50)} p95_ms=${"%.2f".format(java.util.Locale.US, s.p95)} max_ms=${"%.2f".format(java.util.Locale.US, s.max)}")
        return s
    }

    private fun loadPngSnapshot(asset: String): OcrSnapshot {
        testContext.assets.open(asset).use { stream ->
            val bmp = BitmapFactory.decodeStream(stream)
                ?: throw IllegalStateException("cannot decode $asset")
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            val rgb = ByteArray(w * h * 3)
            for (i in px.indices) {
                rgb[i * 3] = ((px[i] shr 16) and 0xFF).toByte()
                rgb[i * 3 + 1] = ((px[i] shr 8) and 0xFF).toByte()
                rgb[i * 3 + 2] = (px[i] and 0xFF).toByte()
            }
            return OcrSnapshot(w, h, rgb, w, h, System.nanoTime())
        }
    }

    /**
     * TEST-ONLY replica of the pre-Phase-10 downscale inner loop (12 packed
     * reads/px + per-pixel intArrayOf allocation) for A/B comparison against
     * the production hoisted version. Never shipped; exists so the claimed
     * optimization is measured, not assumed.
     */
    private fun oldDownscale(
        plane: ByteBuffer,
        srcW: Int,
        srcH: Int,
        rowStride: Int,
        pixelStride: Int
    ): ByteArray {
        val ratio = 960.0 / maxOf(srcW, srcH).toDouble()
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
                for (shift in intArrayOf(16, 8, 0)) {
                    val p00 = (com.gametranslator.enar.ocr.Bilinear.readRgba(plane, x0, y0, rowStride, pixelStride) shr shift) and 0xFF
                    val p10 = (com.gametranslator.enar.ocr.Bilinear.readRgba(plane, x1, y0, rowStride, pixelStride) shr shift) and 0xFF
                    val p01 = (com.gametranslator.enar.ocr.Bilinear.readRgba(plane, x0, y1, rowStride, pixelStride) shr shift) and 0xFF
                    val p11 = (com.gametranslator.enar.ocr.Bilinear.readRgba(plane, x1, y1, rowStride, pixelStride) shr shift) and 0xFF
                    val top = p00 + (p10 - p00) * fx
                    val bot = p01 + (p11 - p01) * fx
                    rgb[d++] = (top + (bot - top) * fy + 0.5f).toInt()
                        .coerceIn(0, 255).toByte()
                }
            }
        }
        return rgb
    }

    /** Synthetic landscape RGBA frame (2728x1264, rowStride == w*4). */
    private fun syntheticRgba(w: Int, h: Int): ByteBuffer {        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        var v = 0
        for (i in 0 until w * h) {
            buf.put((v and 0xFF).toByte())
            buf.put(((v + 1) and 0xFF).toByte())
            buf.put(((v + 2) and 0xFF).toByte())
            buf.put(255.toByte())
            v += 7
        }
        buf.rewind()
        return buf
    }

    @Test
    fun testStageBreakdown() = runBlocking {
        // ART JIT warmup is MANDATORY before timing: a cold method runs
        // interpreted (10x slower) and the first iterations include compilation.
        // Prior runs without warmup measured interpreter speed, not production.
        // ---- 1. Snapshot downscale (synthetic 2728x1264 RGBA, landscape game frame).
        val plane = syntheticRgba(2728, 1264)
        repeat(20) {
            SnapshotDownscaler.downscale(plane, 2728, 1264, 2728 * 4, 4, 0L)
        }
        val downNs = LongArray(8) {
            val t0 = System.nanoTime()
            val s = SnapshotDownscaler.downscale(plane, 2728, 1264, 2728 * 4, 4, 0L)
            val dt = System.nanoTime() - t0
            assertTrue("downscale dims ${s.width}x${s.height}", s.width == 960 && s.height == 444)
            dt
        }
        summarize("downscale_2728x1264", downNs)

        // A/B: old loop (test-only replica) vs production, interleaved to
        // cancel thermal/load drift. Also asserts byte-identical outputs.
        val ref = SnapshotDownscaler.downscale(plane, 2728, 1264, 2728 * 4, 4, 0L).rgb
        val oldNs = LongArray(8)
        val newNs = LongArray(8)
        for (i in 0 until 8) {
            var t0 = System.nanoTime()
            val o = oldDownscale(plane, 2728, 1264, 2728 * 4, 4)
            oldNs[i] = System.nanoTime() - t0
            assertTrue("old/new byte-identical", o.contentEquals(ref))
            t0 = System.nanoTime()
            val n = SnapshotDownscaler.downscale(plane, 2728, 1264, 2728 * 4, 4, 0L).rgb
            newNs[i] = System.nanoTime() - t0
            assertTrue("new/new stable", n.contentEquals(ref))
        }
        summarize("downscale_AB_old", oldNs)
        summarize("downscale_AB_new", newNs)

        // ---- 2. Real snapshot + det preprocess.
        val snap = loadPngSnapshot("ocr/hud_numbers.png")
        Log.i("OCR_BENCH", "snapshot ${snap.width}x${snap.height} src ${snap.srcWidth}x${snap.srcHeight}")
        repeat(10) { DetPreprocess.process(snap) }
        val preNs = LongArray(5) {
            val t0 = System.nanoTime()
            val input = DetPreprocess.process(snap)!!
            val dt = System.nanoTime() - t0
            assertTrue("det input ${input.width}x${input.height}", input.width > 0 && input.height > 0)
            dt
        }
        summarize("det_preprocess", preNs)
        val detInput = DetPreprocess.process(snap)!!

        // ---- 3. ORT detector sessions.
        val provider = OrtOcrSessionProvider(appContext)
        provider.load()
        val det = provider.detector()
        repeat(5) { det.run(detInput.chw, detInput.batch, detInput.channels, detInput.height, detInput.width) }
        var lastProb = floatArrayOf()
        var lastMapH = 0
        var lastMapW = 0
        val ortDetNs = LongArray(8) {
            val t0 = System.nanoTime()
            val out = det.run(detInput.chw, detInput.batch, detInput.channels, detInput.height, detInput.width)
            val dt = System.nanoTime() - t0
            lastProb = out.prob
            lastMapH = out.mapH
            lastMapW = out.mapW
            dt
        }
        summarize("ort_det", ortDetNs)
        assertTrue("empty prob map", lastProb.isNotEmpty())

        // ---- 4. DB decode on the real prob map.
        repeat(5) { DbDecoder.decode(lastProb, lastMapW, lastMapH, snap.width, snap.height) }
        val dbNs = LongArray(50) {
            val t0 = System.nanoTime()
            val boxes = DbDecoder.decode(lastProb, lastMapW, lastMapH, snap.width, snap.height)
            val dt = System.nanoTime() - t0
            assertTrue("expected 3 boxes, got ${boxes.size}", boxes.size == 3)
            dt
        }
        summarize("db_decode", dbNs)

        // ---- 5. Rec crop + preprocess for a real box (first box, snapshot coords).
        val engine = OnnxOcrEngine({ snap }, provider)
        // Engine not warmed via loadModels (sessions already loaded); build decoder path
        // through public API requires loadModels — call it (idempotent, fast when loaded).
        engine.loadModels()
        val regions = engine.detectRegions(FrameBuffer(snap.width, snap.height, snap.width * 4, 0L, 1, true))
        assertTrue("expected regions, got ${regions.size}", regions.isNotEmpty())
        val r = regions[0].bounds
        val sx0 = (r.left * snap.width / snap.srcWidth)
        val sy0 = (r.top * snap.height / snap.srcHeight)
        val sx1 = (r.right * snap.width / snap.srcWidth)
        val sy1 = (r.bottom * snap.height / snap.srcHeight)
        repeat(3) {
            RecPreprocess.resizeNormImg(
                RecPreprocess.extractCrop(snap.rgb, snap.width, snap.height, sx0, sy0, sx1, sy1)!!
            )
        }
        var lastCropW = 0
        val recPreNs = LongArray(20) {
            val t0 = System.nanoTime()
            val crop = RecPreprocess.extractCrop(snap.rgb, snap.width, snap.height, sx0, sy0, sx1, sy1)!!
            val recIn = RecPreprocess.resizeNormImg(crop)!!
            val dt = System.nanoTime() - t0
            lastCropW = recIn.width
            dt
        }
        summarize("rec_crop_preprocess_w$lastCropW", recPreNs)

        // ---- 6. ORT recognizer + CTC decode.
        val rec = provider.recognizer()
        val crop = RecPreprocess.extractCrop(snap.rgb, snap.width, snap.height, sx0, sy0, sx1, sy1)!!
        val recIn = RecPreprocess.resizeNormImg(crop)!!
        repeat(5) { rec.run(recIn.chw, 1, 3, recIn.height, recIn.width) }
        val decoder = CtcDecoder(provider.characters())
        var lastLogits = floatArrayOf()
        var lastSteps = 0
        val ortRecNs = LongArray(10) {
            val t0 = System.nanoTime()
            val out = rec.run(recIn.chw, 1, 3, recIn.height, recIn.width)
            val dt = System.nanoTime() - t0
            lastLogits = out.logits
            lastSteps = out.steps
            dt
        }
        summarize("ort_rec_w${recIn.width}", ortRecNs)
        val ctcNs = LongArray(100) {
            val t0 = System.nanoTime()
            val res = decoder.decode(lastLogits, 1, lastSteps, 97)
            val dt = System.nanoTime() - t0
            assertTrue(res.isNotEmpty())
            dt
        }
        summarize("ctc_decode", ctcNs)

        // ---- 7. Full engine detect + recognize (cross-check vs part sum).
        repeat(1) { engine.detectRegions(FrameBuffer(snap.width, snap.height, snap.width * 4, 0L, 1, true)) }
        val fullDetNs = LongArray(5) {
            val t0 = System.nanoTime()
            val rs = engine.detectRegions(FrameBuffer(snap.width, snap.height, snap.width * 4, 0L, 1, true))
            val dt = System.nanoTime() - t0
            assertTrue(rs.isNotEmpty())
            dt
        }
        summarize("engine_detect_full", fullDetNs)
        val fullRecNs = LongArray(5) {
            val t0 = System.nanoTime()
            val res = engine.recognizeRegion(
                FrameBuffer(snap.width, snap.height, snap.width * 4, 0L, 1, true),
                regions[0]
            )
            val dt = System.nanoTime() - t0
            assertTrue(res.rawText.isNotEmpty())
            dt
        }
        summarize("engine_recognize_full", fullRecNs)

        engine.release()
        provider.release()
    }
}
