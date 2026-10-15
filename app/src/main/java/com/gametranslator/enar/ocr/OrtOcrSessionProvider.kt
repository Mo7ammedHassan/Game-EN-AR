package com.gametranslator.enar.ocr

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ONNX Runtime OCR sessions (Phase 9 production provider).
 *
 * Runs the RapidOCR-exported PP-OCR ONNX graphs (`en_PP-OCRv3_det_infer` +
 * `en_PP-OCRv4_rec_mobile`, FP32) on CPU with the session options PROVEN by
 * the NMT path (4 intra-op threads, BASIC_OPT, asset→cache copy, strict
 * single-ownership close discipline per ISSUE-005: close only `Result`
 * objects, never member tensors, exactly once).
 *
 * Memory notes: inputs travel as direct `FloatBuffer`s (heap buffers are
 * rejected by the ORT tensor overload — same lesson as ISSUE-005); outputs
 * are copied out of the `Result` into plain arrays BEFORE the result closes,
 * so no tensor outlives its parent. Sessions load once and are reused for
 * every frame/region (never one instance per region).
 */
class OrtOcrSessionProvider(
    private val appContext: Context
) : OcrSessionProvider {

    companion object {
        private const val TAG = "OrtOcrSessionProvider"

        const val DET_MODEL_NAME = "en_PP-OCRv3_det_infer.onnx"
        const val REC_MODEL_NAME = "en_PP-OCRv4_rec_mobile.onnx"
        const val DICT_NAME = "en_dict.txt"
        private const val ASSET_DIR = "models"

        /**
         * Flattens a `[1,1,H,W]` ORT float tensor value to a row-major map.
         *
         * Pure function (no natives) so JVM tests pin the nesting depth: the
         * channel dim sits at index 1, i.e. rows live at `nested[0][0][y]`
         * (`float[1][1][H][W]`). Indexing `nested[0][y][0]` instead reads the
         * channel axis and corrupts dims (found on-device as
         * `src.length=448 ... dst.length=960`). Validates against the
         * requested input dims — the export is fully-convolutional same-size,
         * so any deviation means a wrong model, and fail-closed beats
         * mis-mapped boxes.
         */
        internal fun flattenDetProb(
            nested: Array<Array<Array<FloatArray>>>,
            height: Int,
            width: Int
        ): DetOutput {
            require(nested.size == 1 && nested[0].isNotEmpty()) { "det batch/channel mismatch" }
            val h = nested[0][0].size
            val w = nested[0][0][0].size
            require(h == height && w == width) {
                "det output ${h}x$w != input ${height}x$width"
            }
            val flat = FloatArray(h * w)
            for (y in 0 until h) {
                System.arraycopy(nested[0][0][y], 0, flat, y * w, w)
            }
            return DetOutput(flat, h, w)
        }
    }

    private var ortEnv: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var dictLines: List<String> = emptyList()
    private var loaded = false

    override fun load() {
        if (loaded) return
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(OcrConfig.ORT_INTRA_OP_THREADS)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            val detFile = copyAssetToCache(appContext, "$ASSET_DIR/$DET_MODEL_NAME")
            val recFile = copyAssetToCache(appContext, "$ASSET_DIR/$REC_MODEL_NAME")
            val dictFile = copyAssetToCache(appContext, "$ASSET_DIR/$DICT_NAME")
            if (!detFile.exists() || !recFile.exists() || !dictFile.exists()) {
                throw IllegalStateException("OCR assets missing in $ASSET_DIR")
            }
            detSession = ortEnv?.createSession(detFile.absolutePath, options)
            recSession = ortEnv?.createSession(recFile.absolutePath, options)
            // NOTE: the file's standalone " " entry (index 94) MUST be kept
            // (`isNotEmpty` keeps it): the decoder table is
            // ['blank'] + 95 lines + [' '] = 97 classes, exactly the model's
            // output width — same construction as reference `CTCLabelDecode`.
            dictLines = dictFile.readLines().filter { it.isNotEmpty() }
            if (dictLines.isEmpty()) throw IllegalStateException("OCR dict empty")
            loaded = true
            Log.i(TAG, "OCR sessions loaded (det+rec, dict=${dictLines.size} entries).")
        } catch (e: Exception) {
            Log.e(TAG, "OCR session load failed: ${e.message}", e)
            release()
            throw e
        }
    }

    override fun isLoaded(): Boolean = loaded

    override fun detector(): DetSession = OrtDetSession(detSession!!)

    override fun recognizer(): RecSession = OrtRecSession(recSession!!)

    override fun characters(): List<String> = dictLines

    override fun release() {
        try {
            detSession?.close()
            recSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            Log.w(TAG, "OCR session release: ${e.message}")
        } finally {
            detSession = null
            recSession = null
            ortEnv = null
            dictLines = emptyList()
            loaded = false
        }
    }

    private class OrtDetSession(private var session: OrtSession?) : DetSession {
        override fun run(
            input: FloatArray,
            batch: Int,
            channels: Int,
            height: Int,
            width: Int
        ): DetOutput {
            val s = session ?: throw IllegalStateException("detector released")
            val buffer = ByteBuffer.allocateDirect(input.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            buffer.put(input)
            buffer.rewind()
            val tensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(), buffer,
                longArrayOf(batch.toLong(), channels.toLong(), height.toLong(), width.toLong())
            )
            tensor.use {
                s.run(mapOf(s.inputNames.first() to tensor)).use { result ->
                    val value = result.get(0).value
                    @Suppress("UNCHECKED_CAST")
                    val nested = value as Array<Array<Array<FloatArray>>>
                    return flattenDetProb(nested, height, width)
                }
            }
        }

        override fun close() {
            session?.close()
            session = null
        }
    }

    private class OrtRecSession(private var session: OrtSession?) : RecSession {
        override fun run(
            input: FloatArray,
            batch: Int,
            channels: Int,
            height: Int,
            width: Int
        ): RecOutput {
            val s = session ?: throw IllegalStateException("recognizer released")
            val buffer = ByteBuffer.allocateDirect(input.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            buffer.put(input)
            buffer.rewind()
            val tensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(), buffer,
                longArrayOf(batch.toLong(), channels.toLong(), height.toLong(), width.toLong())
            )
            tensor.use {
                s.run(mapOf(s.inputNames.first() to tensor)).use { result ->
                    val info = result.get(0).info as ai.onnxruntime.TensorInfo
                    val shape = info.shape // [B, T, C]
                    val b = shape[0].toInt()
                    val t = shape[1].toInt()
                    val c = shape[2].toInt()
                    val value = result.get(0).value
                    @Suppress("UNCHECKED_CAST")
                    val nested = value as Array<Array<FloatArray>>
                    val flat = FloatArray(b * t * c)
                    for (bb in 0 until b) {
                        for (tt in 0 until t) {
                            System.arraycopy(nested[bb][tt], 0, flat, (bb * t + tt) * c, c)
                        }
                    }
                    return RecOutput(flat, b, t, c)
                }
            }
        }

        override fun close() {
            session?.close()
            session = null
        }
    }

    private fun copyAssetToCache(context: Context, assetPath: String): File {
        val outFile = File(context.cacheDir, assetPath.replace('/', '_'))
        if (outFile.exists()) return outFile
        context.assets.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }
}
