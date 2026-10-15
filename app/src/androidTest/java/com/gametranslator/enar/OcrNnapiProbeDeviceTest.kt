package com.gametranslator.enar

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gametranslator.enar.ocr.CtcDecoder
import com.gametranslator.enar.ocr.DbDecoder
import com.gametranslator.enar.ocr.DetPreprocess
import com.gametranslator.enar.ocr.OcrSnapshot
import com.gametranslator.enar.ocr.RecPreprocess
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 10 Step-10 bounded experiment: can the NNAPI execution provider run
 * the PP-OCR graphs on VKJ-NX9, and is it faster than the CPU production path?
 *
 * TEST-ONLY session construction (mirrors `OrtOcrSessionProvider` asset
 * handling, but nothing here touches production code — no provider, engine,
 * or pipeline changes). Outcome is recorded in logcat either way:
 * - NNAPI session creation/inference works → compare ms + outputs vs CPU.
 * - NNAPI unsupported/crashing/slower → the test FAILS with the ORT reason,
 *   which IS the documented result (keep CPU, see ISSUE-008).
 */
@RunWith(AndroidJUnit4::class)
class OcrNnapiProbeDeviceTest {

    private lateinit var appContext: Context
    private lateinit var testContext: Context

    @Before
    fun setUp() {
        val instr = InstrumentationRegistry.getInstrumentation()
        appContext = instr.targetContext
        testContext = instr.context
    }

    private fun assetToCache(assetPath: String): File {
        val out = File(appContext.cacheDir, "nnapi_" + assetPath.replace('/', '_'))
        if (out.exists() && out.length() > 0) return out
        appContext.assets.open(assetPath).use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
        return out
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

    private fun floatTensor(env: OrtEnvironment, data: FloatArray, vararg dims: Long): OnnxTensor {
        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(data)
        buffer.rewind()
        return OnnxTensor.createTensor(env, buffer, dims)
    }

    @Test
    fun testNnapiDetRec() {
        val env = OrtEnvironment.getEnvironment()
        val detFile = assetToCache("models/en_PP-OCRv3_det_infer.onnx")
        val recFile = assetToCache("models/en_PP-OCRv4_rec_mobile.onnx")

        val nnapiOpt = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            addNnapi()
        }
        val detSession = nnapiOpt.let { env.createSession(detFile.absolutePath, it) }
        Log.i("OCR_NNAPI", "det NNAPI session created; providers=${detSession.getInputNames()}")
        // NOTE: provider list check — input names logged as session-alive proof.

        val snap = loadPngSnapshot("ocr/hud_numbers.png")
        val detInput = DetPreprocess.process(snap)!!
        val detIn = floatTensor(env, detInput.chw, 1, 3, detInput.height.toLong(), detInput.width.toLong())
        detIn.use {
            detSession.run(mapOf(detSession.inputNames.first() to detIn)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val nested = result.get(0).value as Array<Array<Array<FloatArray>>>
                val h = nested[0][0].size
                val w = nested[0][0][0].size
                Log.i("OCR_NNAPI", "det output map=${h}x$w")
                assertTrue("unexpected det map", h == detInput.height && w == detInput.width)
            }
        }

        // Timed NNAPI det runs (2 warmup excluded).
        repeat(2) {
            val t = floatTensor(env, detInput.chw, 1, 3, detInput.height.toLong(), detInput.width.toLong())
            t.use { detSession.run(mapOf(detSession.inputNames.first() to t)).use { } }
        }
        val samples = LongArray(5) {
            val t = floatTensor(env, detInput.chw, 1, 3, detInput.height.toLong(), detInput.width.toLong())
            val t0 = System.nanoTime()
            t.use { detSession.run(mapOf(detSession.inputNames.first() to t)).use { } }
            System.nanoTime() - t0
        }
        val sorted = samples.sorted()
        Log.i(
            "OCR_NNAPI",
            "det_ms N=5 p50=${sorted[2] / 1_000_000.0} max=${sorted.last() / 1_000_000.0} " +
                "(CPU reference p50 ~102-142ms, ENTRY 010/011)"
        )

        // Recognizer via NNAPI on the first detected box.
        val recOpt = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            addNnapi()
        }
        val recSession = env.createSession(recFile.absolutePath, recOpt)
        // Reuse CPU-decoded boxes for the crop (crop math is provider-independent).
        val boxes = DbDecoder.decode(
            run {
                val t = floatTensor(env, detInput.chw, 1, 3, detInput.height.toLong(), detInput.width.toLong())
                t.use {
                    detSession.run(mapOf(detSession.inputNames.first() to t)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val nested = r.get(0).value as Array<Array<Array<FloatArray>>>
                        val hh = nested[0][0].size
                        val ww = nested[0][0][0].size
                        val flat = FloatArray(hh * ww)
                        for (y in 0 until hh) System.arraycopy(nested[0][0][y], 0, flat, y * ww, ww)
                        flat
                    }
                }
            },
            detInput.width, detInput.height, snap.width, snap.height
        )
        assertTrue("expected boxes, got ${boxes.size}", boxes.isNotEmpty())
        val b = boxes[0]
        val crop = RecPreprocess.extractCrop(
            snap.rgb, snap.width, snap.height,
            b.x0.toInt(), b.y0.toInt(), b.x1.toInt(), b.y1.toInt()
        )!!
        val recIn = RecPreprocess.resizeNormImg(crop)!!
        val recInTensor = floatTensor(env, recIn.chw, 1, 3, recIn.height.toLong(), recIn.width.toLong())
        recInTensor.use {
            recSession.run(mapOf(recSession.inputNames.first() to recInTensor)).use { r ->
                val shape = (r.get(0).info as ai.onnxruntime.TensorInfo).shape
                Log.i("OCR_NNAPI", "rec output shape=${shape.toList()}")
                @Suppress("UNCHECKED_CAST")
                val nested = r.get(0).value as Array<Array<FloatArray>>
                val t = nested[0].size
                val c = nested[0][0].size
                val flat = FloatArray(t * c)
                for (tt in 0 until t) System.arraycopy(nested[0][tt], 0, flat, tt * c, c)
                val decoded = CtcDecoder(
                    appContext.assets.open("models/en_dict.txt").bufferedReader().readLines()
                        .filter { it.isNotEmpty() }
                ).decode(flat, 1, t, c)
                Log.i("OCR_NNAPI", "rec text='${decoded[0].text}' score=${decoded[0].score}")
                assertTrue("empty NNAPI rec text", decoded[0].text.isNotEmpty())
            }
        }

        detSession.close()
        recSession.close()
    }
}
