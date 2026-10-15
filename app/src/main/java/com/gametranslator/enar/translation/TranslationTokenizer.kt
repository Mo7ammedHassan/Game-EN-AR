package com.gametranslator.enar.translation

/**
 * Abstract tokenizer interface for Sequence-to-Sequence NMT models.
 * Decouples model inference from specific tokenization schemes (SentencePiece SPM, BPE, WordPiece).
 */
interface TranslationTokenizer {
    /**
     * Converts a source text string into token IDs.
     */
    fun encode(text: String): IntArray

    /**
     * Converts token IDs back into target text.
     */
    fun decode(tokenIds: IntArray): String

    /**
     * Returns the special Beginning-Of-Sequence (BOS) token ID, or -1 if not applicable.
     */
    fun getBosTokenId(): Int = -1

    /**
     * Returns the special End-Of-Sequence (EOS) token ID.
     */
    fun getEosTokenId(): Int

    /**
     * Returns the special Padding (PAD) token ID.
     */
    fun getPadTokenId(): Int = 0

    /**
     * Total vocabulary size.
     */
    fun getVocabSize(): Int
}
