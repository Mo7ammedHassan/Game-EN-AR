package com.gametranslator.enar

import com.gametranslator.enar.engine.DictionaryTranslationEngine
import com.gametranslator.enar.engine.NoOpTranslationEngine
import com.gametranslator.enar.engine.TranslationEngine
import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.translation.TokenProtector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for the Translation Engine execution path across representative game sentences.
 * Verifies end-to-end token protection, dictionary fallback, and latency recording.
 */
class TranslationIntegrationTest {

    private lateinit var dictionaryEngine: TranslationEngine
    private lateinit var noopEngine: TranslationEngine
    private lateinit var tokenProtector: TokenProtector

    private val dummyBounds = BoundingBox(10, 20, 300, 60)

    // The 5 required representative test sentences
    private val testSentences = listOf(
        "Press E to open the door.",
        "You have 50 HP remaining.",
        "Hello {playerName}, welcome back.",
        "The enemy is approaching from the north.",
        "Save Game"
    )

    @Before
    fun setUp() {
        dictionaryEngine = DictionaryTranslationEngine()
        noopEngine = NoOpTranslationEngine()
        tokenProtector = TokenProtector()
    }

    @Test
    fun `test all 5 representative sentences through DictionaryTranslationEngine`() = runBlocking {
        for (sentence in testSentences) {
            val result = dictionaryEngine.translate(sentence, dummyBounds)

            assertNotNull(result)
            assertEquals(sentence, result.originalText)
            assertEquals(dummyBounds, result.bounds)
            assertFalse(result.fromCache)
            assertTrue("Latency should be measured non-negative", result.latencyNs >= 0L)
            assertTrue("Translated Arabic text must not be empty", result.translatedArabicText.isNotBlank())
        }
    }

    @Test
    fun `test TokenProtector variable masking on representative sentences`() {
        // Sentence 1: "Press E to open the door."
        val p1 = tokenProtector.protect(testSentences[0])
        // "E" is a single letter, not a number/specifier, so it remains in masked text
        assertEquals("Press E to open the door.", p1.maskedText)

        // Sentence 2: "You have 50 HP remaining."
        val p2 = tokenProtector.protect(testSentences[1])
        assertTrue("50 should be masked", p2.tokenMap.values.contains("50"))
        assertTrue("Sentinel must be digits: ${p2.maskedText}",
            Regex("\\d{3,6}").containsMatchIn(p2.maskedText))

        // Sentence 3: "Hello {playerName}, welcome back."
        val p3 = tokenProtector.protect(testSentences[2])
        assertTrue(p3.tokenMap.values.contains("{playerName}"))
        assertTrue(Regex("\\d{3,6}").containsMatchIn(p3.maskedText))

        // Sentence 4: "The enemy is approaching from the north."
        val p4 = tokenProtector.protect(testSentences[3])
        assertEquals("The enemy is approaching from the north.", p4.maskedText)
        assertTrue(p4.tokenMap.isEmpty())

        // Sentence 5: "Save Game"
        val p5 = tokenProtector.protect(testSentences[4])
        assertEquals("Save Game", p5.maskedText)
        assertTrue(p5.tokenMap.isEmpty())
    }

    @Test
    fun `test end-to-end mask translate and restore workflow`() = runBlocking {
        val input = "You have 50 HP remaining."
        val protected = tokenProtector.protect(input)

        val result = dictionaryEngine.translate(protected.maskedText, dummyBounds)
        val restored = tokenProtector.restore(result.translatedArabicText, protected.tokenMap)

        assertTrue("Restored Arabic string must contain 50", restored.contains("50"))
        assertTrue("No sentinel residue may leak: ${result.translatedArabicText}",
            tokenProtector.missingPlaceholders(result.translatedArabicText, protected.tokenMap).isEmpty())
        assertTrue(restored.isNotBlank())
    }
}
