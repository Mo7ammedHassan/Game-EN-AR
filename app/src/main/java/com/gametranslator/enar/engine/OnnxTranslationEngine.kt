package com.gametranslator.enar.engine

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.EngineMetadata
import com.gametranslator.enar.model.TranslationResult
import com.gametranslator.enar.translation.TokenProtector
import com.gametranslator.enar.translation.TranslationTokenizer
import java.io.File
import java.io.FileOutputStream
import java.nio.LongBuffer

class OnnxTranslationEngine(
    private val sourceTokenizer: TranslationTokenizer? = null,
    private val targetTokenizer: TranslationTokenizer? = null,
    private val tokenProtector: TokenProtector = TokenProtector()
) : TranslationEngine {

    companion object {
        private const val TAG = "OnnxTranslationEngine"

        const val ENCODER_MODEL_NAME = "encoder_model_quantized.onnx"
        const val DECODER_MODEL_NAME = "decoder_model_quantized.onnx"
        const val DECODER_WITH_PAST_MODEL_NAME = "decoder_with_past_model_quantized.onnx"

        private const val DECODER_START_TOKEN_ID = 62801
        private const val EOS_TOKEN_ID = 0
        private const val MAX_DECODER_STEPS = 64
        private const val TARGET_VOCAB_SIZE = 32000
    }

    private var ortEnv: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var decoderWithPastSession: OrtSession? = null
    private var isLoaded: Boolean = false

    fun loadModels(context: Context) {
        Log.i(TAG, "Initializing ONNX Runtime Mobile sessions...")
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }

            val encFile = copyAssetToCache(context, ENCODER_MODEL_NAME)
            val decFile = copyAssetToCache(context, DECODER_MODEL_NAME)
            val decPastFile = copyAssetToCache(context, DECODER_WITH_PAST_MODEL_NAME)

            if (encFile.exists() && decFile.exists() && decPastFile.exists()) {
                encoderSession = ortEnv?.createSession(encFile.absolutePath, options)
                decoderSession = ortEnv?.createSession(decFile.absolutePath, options)
                decoderWithPastSession = ortEnv?.createSession(decPastFile.absolutePath, options)
                isLoaded = true
                Log.i(TAG, "ONNX Runtime NMT sessions loaded successfully.")
            } else {
                Log.w(TAG, "ONNX model files not present in assets. Engine running in fallback mode.")
                isLoaded = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX Runtime sessions: ${e.message}", e)
            isLoaded = false
        }
    }

    fun release() {
        try {
            encoderSession?.close()
            decoderSession?.close()
            decoderWithPastSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error during ONNX cleanup: ${e.message}")
        } finally {
            encoderSession = null
            decoderSession = null
            decoderWithPastSession = null
            ortEnv = null
            isLoaded = false
            Log.i(TAG, "OnnxTranslationEngine released.")
        }
    }

    override suspend fun translate(
        text: String,
        targetBounds: BoundingBox,
        sourceLang: String,
        targetLang: String
    ): TranslationResult {
        val startNs = System.nanoTime()

        val protected = tokenProtector.protect(text)

        if (!isLoaded || sourceTokenizer == null || targetTokenizer == null ||
            encoderSession == null || decoderSession == null || decoderWithPastSession == null
        ) {
            val latencyNs = System.nanoTime() - startNs
            return TranslationResult(
                originalText = text,
                // Fallback returns the ORIGINAL text (never internal sentinel tokens).
                translatedArabicText = text,
                bounds = targetBounds,
                fromCache = false,
                latencyMs = latencyNs / 1_000_000L,
                latencyNs = latencyNs,
                sourceLang = sourceLang,
                targetLang = targetLang,
                engineMetadata = getEngineMetadata(),
                protectedTokensRestored = false
            )
        }

        val rawArabicText: String
        val fullyRestored: Boolean

        try {
            val maskedIds = sourceTokenizer.encode(protected.maskedText)
            val rawMaskedArabic = targetTokenizer.decode(runGreedyInference(maskedIds))
            val missing = tokenProtector.missingPlaceholders(rawMaskedArabic, protected.tokenMap)

            if (protected.tokenMap.isNotEmpty() && missing.isNotEmpty()) {
                // ISSUE-004 safety net: the model dropped/rewrote a sentinel. Retry once
                // with the original unmasked text — numbers copy natively, so the result
                // stays fluent instead of silently corrupted. The flag stays false so the
                // pipeline knows protected spans were NOT verifiably restored.
                Log.w(TAG, "Sentinel loss $missing for input '$text'; retrying unmasked.")
                val rawIds = sourceTokenizer.encode(text)
                val rawFallbackArabic = targetTokenizer.decode(runGreedyInference(rawIds))
                rawArabicText = tokenProtector.restore(rawFallbackArabic, protected.tokenMap)
                fullyRestored = false
            } else {
                rawArabicText = tokenProtector.restore(rawMaskedArabic, protected.tokenMap)
                fullyRestored = protected.tokenMap.isNotEmpty()
            }

        } catch (e: Exception) {
            Log.e(TAG, "ONNX translation inference error: ${e.message}", e)
            val latencyNs = System.nanoTime() - startNs
            return TranslationResult(
                originalText = text,
                // Fallback returns the ORIGINAL text (never internal sentinel tokens).
                translatedArabicText = text,
                bounds = targetBounds,
                fromCache = false,
                latencyMs = latencyNs / 1_000_000L,
                latencyNs = latencyNs,
                sourceLang = sourceLang,
                targetLang = targetLang,
                engineMetadata = getEngineMetadata(),
                protectedTokensRestored = false
            )
        }

        val latencyNs = System.nanoTime() - startNs
        return TranslationResult(
            originalText = text,
            translatedArabicText = rawArabicText,
            bounds = targetBounds,
            fromCache = false,
            latencyMs = latencyNs / 1_000_000L,
            latencyNs = latencyNs,
            sourceLang = sourceLang,
            targetLang = targetLang,
            engineMetadata = getEngineMetadata(),
            protectedTokensRestored = fullyRestored
        )
    }

    /**
     * Greedy autoregressive decode of already-encoded input IDs.
     * Returns generated target token IDs (without EOS). Throws on inference failure
     * so the caller can fall back. Pure inference: no token protection involved.
     */
    private fun runGreedyInference(inputTokenIds: IntArray): IntArray {
        val seqLen = inputTokenIds.size.toLong()

        val env = ortEnv ?: throw IllegalStateException("ORT environment closed")

        // ORT JNI requires DIRECT (off-heap) buffers — heap-backed
        // LongBuffer.wrap() throws IllegalArgumentException here.
        val inputIdsTensor = longTensor(env, inputTokenIds.map { it.toLong() }.toLongArray(), longArrayOf(1, seqLen))
        val attentionMaskTensor = longTensor(env, LongArray(inputTokenIds.size) { 1L }, longArrayOf(1, seqLen))

        val encoderInputs = mapOf(
            "input_ids" to inputIdsTensor,
            "attention_mask" to attentionMaskTensor
        )

        val encoderOutputs = encoderSession!!.run(encoderInputs)
        val lastHiddenState = encoderOutputs.get(0) as? OnnxTensor
            ?: throw IllegalStateException("Encoder output is not an OnnxTensor")

        val generatedTokens = mutableListOf<Int>()
        var currentTokenId = DECODER_START_TOKEN_ID
        // Tensor ownership discipline (native use-after-free causes SIGSEGV):
        // member tensors of an OrtSession.Result are owned by that Result and
        // must NEVER be closed individually — only the Result itself is closed,
        // exactly once. Encoder presents are static: the step-0 Result
        // (firstOutputs) is therefore kept open until the end of runGreedyInference(),
        // while per-step with-past Results are closed when superseded.
        var firstOutputs: OrtSession.Result? = null
        var prevOutputs: OrtSession.Result? = null
        val encoderPast = mutableListOf<OnnxTensor>()

        for (step in 0 until MAX_DECODER_STEPS) {
            val decoderInputIdsTensor = longTensor(env, longArrayOf(currentTokenId.toLong()), longArrayOf(1, 1))

            val decoderInputs = mutableMapOf<String, OnnxTensor>()
            decoderInputs["input_ids"] = decoderInputIdsTensor
            decoderInputs["encoder_attention_mask"] = attentionMaskTensor

            if (step == 0) {
                decoderInputs["encoder_hidden_states"] = lastHiddenState
            } else {
                // Step 1 reuses the decoder presents from the step-0 outputs
                // (firstOutputs); later steps use the previous with-past
                // outputs. Encoder presents always come from the retained
                // step-0 list.
                val prev = if (step == 1) {
                    firstOutputs
                        ?: throw IllegalStateException("Missing step-0 outputs at step 1")
                } else {
                    prevOutputs
                        ?: throw IllegalStateException("Missing decoder past states at step $step")
                }
                for (i in 0 until 6) {
                    val pastDecKey = prev.get("present.$i.decoder.key").get() as? OnnxTensor
                        ?: throw IllegalStateException("past decoder key is not OnnxTensor")
                    val pastDecValue = prev.get("present.$i.decoder.value").get() as? OnnxTensor
                        ?: throw IllegalStateException("past decoder value is not OnnxTensor")
                    decoderInputs["past_key_values.$i.decoder.key"] = pastDecKey
                    decoderInputs["past_key_values.$i.decoder.value"] = pastDecValue
                    decoderInputs["past_key_values.$i.encoder.key"] = encoderPast[i * 2]
                    decoderInputs["past_key_values.$i.encoder.value"] = encoderPast[i * 2 + 1]
                }
            }

            val decoderOutputs = if (step == 0) {
                decoderSession!!.run(decoderInputs)
            } else {
                decoderWithPastSession!!.run(decoderInputs)
            }

            val logitsTensor = decoderOutputs.get(0) as? OnnxTensor
                ?: throw IllegalStateException("Logits output is not an OnnxTensor")
            val logitsData = logitsTensor.floatBuffer
            val vocabSize = logitsData.limit()

            var bestId = 0
            var bestScore = Float.NEGATIVE_INFINITY
            val limit = minOf(TARGET_VOCAB_SIZE, vocabSize)
            for (i in 0 until limit) {
                val score = logitsData.get(i)
                if (score > bestScore) {
                    bestScore = score
                    bestId = i
                }
            }

            // The step-0 logits already predict the FIRST output token, so
            // every step's argmax is appended (until EOS).
            if (bestId == EOS_TOKEN_ID) {
                decoderInputIdsTensor.close()
                if (step == 0) {
                    firstOutputs = decoderOutputs
                } else {
                    prevOutputs?.close()
                    prevOutputs = decoderOutputs
                }
                break
            }
            generatedTokens.add(bestId)
            currentTokenId = bestId

            if (step == 0) {
                firstOutputs = decoderOutputs
                for (i in 0 until 6) {
                    encoderPast.add(
                        decoderOutputs.get("present.$i.encoder.key").get() as? OnnxTensor
                            ?: throw IllegalStateException("past encoder key is not OnnxTensor")
                    )
                    encoderPast.add(
                        decoderOutputs.get("present.$i.encoder.value").get() as? OnnxTensor
                            ?: throw IllegalStateException("past encoder value is not OnnxTensor")
                    )
                }
            } else {
                prevOutputs?.close()
                prevOutputs = decoderOutputs
            }

            decoderInputIdsTensor.close()
        }

        prevOutputs?.close()
        firstOutputs?.close()

        inputIdsTensor.close()
        attentionMaskTensor.close()
        encoderOutputs.close()

        return generatedTokens.toIntArray()
    }

    override fun isWarm(): Boolean = isLoaded

    override fun getEngineMetadata(): EngineMetadata = EngineMetadata(
        engineName = "OnnxTranslationEngine",
        modelVersion = "opus-mt-en-ar-v1-INT8",
        executionBackend = if (isLoaded) "CPU_ONNX_RUNTIME_4T" else "NONE_MODEL_NOT_LOADED",
        isQuantized = true
    )

    private fun longTensor(env: OrtEnvironment, values: LongArray, shape: LongArray): OnnxTensor {
        val byteBuf = java.nio.ByteBuffer
            .allocateDirect(values.size * java.lang.Long.BYTES)
            .order(java.nio.ByteOrder.nativeOrder())
        val longBuf = byteBuf.asLongBuffer()
        longBuf.put(values)
        longBuf.flip()
        return OnnxTensor.createTensor(env, longBuf, shape)
    }

    private fun copyAssetToCache(context: Context, assetName: String): File {
        val cacheFile = File(context.cacheDir, assetName)
        if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile

        try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.v(TAG, "Asset $assetName not found in app bundle: ${e.message}")
        }
        return cacheFile
    }
}
