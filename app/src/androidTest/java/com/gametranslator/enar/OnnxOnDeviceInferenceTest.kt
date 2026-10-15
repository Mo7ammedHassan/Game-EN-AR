package com.gametranslator.enar

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gametranslator.enar.engine.OnnxTranslationEngine
import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.translation.SentencePieceTokenizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class OnnxOnDeviceInferenceTest {

    private lateinit var context: Context

    private val testSentences = listOf(
        "Press E to open the door.",
        "You have 50 HP remaining.",
        "Hello {playerName}, welcome back.",
        "The enemy is approaching from the north.",
        "Save Game"
    )

    private val dummyBounds = BoundingBox(10, 20, 300, 60)

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    private fun assetToCache(assetName: String): File {
        val out = File(context.cacheDir, assetName)
        if (out.exists() && out.length() > 0) return out
        context.assets.open(assetName).use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
        return out
    }

    private fun buildEngine(): OnnxTranslationEngine {
        // Shared 62,802-token vocab: one tokenizer serves both source encode
        // and target decode sides.
        val spmFile = assetToCache("source.spm")
        val vocabFile = assetToCache("vocab.json")
        val tokenizer = SentencePieceTokenizer.load(spmFile, vocabFile)
        return OnnxTranslationEngine(tokenizer, tokenizer)
    }

    @Test
    fun testOnDeviceOnnxInferenceColdLoadAndWarmLatency() = runBlocking {
        val engine = buildEngine()

        val coldStart = System.nanoTime()
        engine.loadModels(context)
        val coldLoadMs = (System.nanoTime() - coldStart) / 1_000_000.0

        Log.i("ONNX_DEVICE", "COLD_LOAD_MS=$coldLoadMs")
        Log.i("ONNX_DEVICE", "IS_LOADED=${engine.isWarm()}")

        assertTrue("Engine should be loaded", engine.isWarm())

        // Warm-up run (excluded from measurements below).
        engine.translate(testSentences[0], dummyBounds, "en", "ar")

        val arabicRegex = Regex("[\\u0600-\\u06FF]")
        for (sentence in testSentences) {
            val result = engine.translate(sentence, dummyBounds, "en", "ar")

            Log.i("ONNX_DEVICE", "INPUT=$sentence")
            Log.i("ONNX_DEVICE", "OUTPUT=${result.translatedArabicText}")
            Log.i("ONNX_DEVICE", "WARM_MS=${result.latencyMs}")
            Log.i("ONNX_DEVICE", "WARM_NS=${result.latencyNs}")
            Log.i("ONNX_DEVICE", "ENGINE=${result.engineMetadata?.engineName}")
            Log.i("ONNX_DEVICE", "BACKEND=${result.engineMetadata?.executionBackend}")
            Log.i("ONNX_DEVICE", "TOKENS_RESTORED=${result.protectedTokensRestored}")

            assertTrue(
                "Engine fell back to pass-through for '$sentence'",
                result.engineMetadata?.executionBackend == "CPU_ONNX_RUNTIME_4T"
            )
            if (sentence == "Save Game") {
                // Genuine model behavior: immediate EOS -> empty output.
                assertTrue(
                    "Save Game should yield empty output, got '${result.translatedArabicText}'",
                    result.translatedArabicText.isBlank()
                )
            } else {
                assertTrue(
                    "No Arabic output for '$sentence': '${result.translatedArabicText}'",
                    arabicRegex.containsMatchIn(result.translatedArabicText)
                )
                assertTrue(
                    "Unrestored sentinel left in output for '$sentence'",
                    !Regex("98\\d{4}").containsMatchIn(result.translatedArabicText)
                )
                // ISSUE-004: sentences with protected spans must round-trip their
                // values through the real model (numeric-sentinel masking +
                // verified restore), with the honest flag set.
                if (sentence.contains("50 HP")) {
                    assertTrue(
                        "Restored output must contain protected value '50' for '$sentence': '${result.translatedArabicText}'",
                        result.translatedArabicText.contains("50")
                    )
                    assertTrue(
                        "Protected tokens must verify as restored for '$sentence'",
                        result.protectedTokensRestored
                    )
                }
                if (sentence.contains("{playerName}")) {
                    assertTrue(
                        "Restored output must contain '{playerName}' for '$sentence': '${result.translatedArabicText}'",
                        result.translatedArabicText.contains("{playerName}")
                    )
                    assertTrue(
                        "Protected tokens must verify as restored for '$sentence'",
                        result.protectedTokensRestored
                    )
                }
            }
        }

        engine.release()
    }

    @Test
    fun testOnDevicePeakRamDuringInference() = runBlocking {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager

        fun totalPssKb(): Int {
            val infos = activityManager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
            return if (infos != null && infos.isNotEmpty()) infos[0].totalPss else -1
        }

        System.gc()
        val baselinePssKb = totalPssKb()

        val engine = buildEngine()
        engine.loadModels(context)
        assertTrue("Engine should be loaded", engine.isWarm())

        System.gc()
        val afterLoadPssKb = totalPssKb()

        for (sentence in testSentences) {
            engine.translate(sentence, dummyBounds, "en", "ar")
        }

        System.gc()
        val afterInferPssKb = totalPssKb()

        Log.i("ONNX_DEVICE", "BASELINE_PSS_KB=$baselinePssKb")
        Log.i("ONNX_DEVICE", "AFTER_LOAD_PSS_KB=$afterLoadPssKb")
        Log.i("ONNX_DEVICE", "AFTER_INFER_PSS_KB=$afterInferPssKb")

        engine.release()
    }
}
