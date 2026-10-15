package com.gametranslator.enar

import com.gametranslator.enar.engine.OnnxTranslationEngine
import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.translation.SentencePieceTokenizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnnxTranslationIntegrationTest {

    private val dummyBounds = BoundingBox(10, 20, 300, 60)

    private val testSentences = listOf(
        "Press E to open the door.",
        "You have 50 HP remaining.",
        "Hello {playerName}, welcome back.",
        "The enemy is approaching from the north.",
        "Save Game"
    )

    @Test
    fun `test SentencePiece tokenizer loads shared vocabulary`() {
        val spmFile = File("D:/GamesEn-Ar/scratch/onnx_opus_mt_en_ar_quant/source.spm")
        val vocabFile = File("D:/GamesEn-Ar/scratch/onnx_opus_mt_en_ar_quant/vocab.json")
        if (!spmFile.exists() || !vocabFile.exists()) {
            println("SKIP: tokenizer artifacts not found")
            return
        }

        val tokenizer = SentencePieceTokenizer.load(spmFile, vocabFile)
        assertEquals(62802, tokenizer.getVocabSize())
        assertEquals(0, tokenizer.getEosTokenId())
        assertEquals(62801, tokenizer.getPadTokenId())

        val encoded = tokenizer.encode("Press E to open the door.")
        assertTrue("Encoded tokens should not be empty", encoded.isNotEmpty())
        assertTrue("Last token should be EOS (0)", encoded.last() == 0)
    }

    @Test
    fun `test OnnxTranslationEngine fallback mode when models not loaded`() = runBlocking {
        val engine = OnnxTranslationEngine()

        for (sentence in testSentences) {
            val result = engine.translate(sentence, dummyBounds, "en", "ar")

            assertEquals(sentence, result.originalText)
            assertEquals(dummyBounds, result.bounds)
            assertFalse(result.fromCache)
            assertTrue("Latency should be non-negative", result.latencyNs >= 0L)
            assertTrue("Translated text should not be empty in fallback", result.translatedArabicText.isNotBlank())
            // Fallback must echo the ORIGINAL text: internal sentinel tokens must
            // never leak to the UI layer (ISSUE-004).
            assertEquals(sentence, result.translatedArabicText)
            assertFalse(result.protectedTokensRestored)
        }
    }

    @Test
    fun `test OnnxTranslationEngine metadata`() {
        val engine = OnnxTranslationEngine()
        val metadata = engine.getEngineMetadata()

        assertEquals("OnnxTranslationEngine", metadata.engineName)
        assertEquals("opus-mt-en-ar-v1-INT8", metadata.modelVersion)
        assertTrue("Should not be loaded initially", !engine.isWarm())
    }
}
