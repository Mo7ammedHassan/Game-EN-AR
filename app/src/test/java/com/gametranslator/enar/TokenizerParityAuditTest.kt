package com.gametranslator.enar

import com.gametranslator.enar.translation.SentencePieceTokenizer
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File

/**
 * Tokenizer parity audit: Android [SentencePieceTokenizer] output must be
 * ID-for-ID identical to the reference HuggingFace `MarianTokenizer`
 * (`Helsinki-NLP/opus-mt-en-ar`, `transformers==4.57.6`).
 *
 * Reference sequences were dumped from the Python tokenizer (see
 * `docs/research.md` §5B–5C). Any mismatch here is a correctness failure:
 * different encoder input IDs mean different encoder outputs.
 *
 * Coverage: the original 13 parity inputs plus 3 sentinel-masked shapes (ISSUE-004),
 * pinning the exact encoder input of the validated protection path (16 total).
 */
class TokenizerParityAuditTest {

    private val spmFile = File("D:/GamesEn-Ar/scratch/onnx_opus_mt_en_ar_quant/source.spm")
    private val vocabFile = File("D:/GamesEn-Ar/scratch/onnx_opus_mt_en_ar_quant/vocab.json")

    // (input, expected HF token IDs)
    private val referenceCases = listOf(
        "Press E to open the door." to intArrayOf(14951, 616, 9, 1555, 3, 4127, 2, 0),
        "You have 50 HP remaining." to intArrayOf(92, 63, 1204, 1798, 1318, 4566, 2, 0),
        "Hello {playerName}, welcome back." to intArrayOf(3213, 34904, 30584, 661, 17839, 6422, 5, 3119, 503, 2, 0),
        "The enemy is approaching from the north." to intArrayOf(31, 9062, 33, 28739, 69, 3, 7283, 2, 0),
        "Save Game" to intArrayOf(20416, 43010, 0),
        "It's a test, isn't it?" to intArrayOf(98, 16, 18, 15, 5063, 5, 2853, 16, 55, 44, 32, 0),
        "Player_1 has 100% accuracy!" to intArrayOf(27036, 661, 4265, 1484, 95, 39083, 23949, 65, 0),
        "Press 'Space' to jump <b>now</b>!" to intArrayOf(14951, 103, 56069, 16, 9, 15486, 10994, 340, 20840, 33836, 43632, 29, 340, 20840, 65, 0),
        "x" to intArrayOf(6724, 0),
        "" to intArrayOf(0),
        "a|b" to intArrayOf(15, 1, 340, 0),
        "AT&T" to intArrayOf(26104, 33471, 1894, 0),
        "100%" to intArrayOf(39083, 0),
        // Sentinel-masked shapes (ISSUE-004): exact encoder inputs of the validated
        // protection path, dumped from HF MarianTokenizer (transformers==4.57.6).
        "You have 987654 remaining." to intArrayOf(92, 63, 7844, 5687, 6160, 4566, 2, 0),
        "Hello 987654, welcome back." to intArrayOf(3213, 7844, 5687, 6160, 5, 3119, 503, 2, 0),
        "Cost: 987651 or 987652" to intArrayOf(21563, 50, 7844, 5687, 7246, 72, 7844, 5687, 6176, 0)
    )

    @Test
    fun `test Android tokenizer matches HuggingFace token IDs exactly`() {
        val tokenizer = SentencePieceTokenizer.load(spmFile, vocabFile)

        for ((input, expected) in referenceCases) {
            val actual = tokenizer.encode(input)
            assertArrayEquals(
                "Token ID mismatch for input ${repr(input)}",
                expected,
                actual
            )
        }
    }

    @Test
    fun `test Android tokenizer round-trips representative strings`() {
        val tokenizer = SentencePieceTokenizer.load(spmFile, vocabFile)

        for ((input, _) in referenceCases) {
            if (input.isEmpty() || input == "a|b") continue
            val decoded = tokenizer.decode(tokenizer.encode(input))
            org.junit.Assert.assertEquals(input, decoded)
        }
    }

    @Test
    fun `test unknown char matches HuggingFace skip-special-tokens behavior`() {
        val tokenizer = SentencePieceTokenizer.load(spmFile, vocabFile)

        // '|' has no vocab mapping: encode emits UNK (id 1), decode skips it —
        // identical to HF decode(..., skip_special_tokens=True) which yields 'ab'.
        val decoded = tokenizer.decode(tokenizer.encode("a|b"))
        org.junit.Assert.assertEquals("ab", decoded)
    }

    private fun repr(s: String): String = "'$s'"
}
