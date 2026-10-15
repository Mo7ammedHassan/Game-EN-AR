package com.gametranslator.enar.translation

import java.text.Normalizer

/**
 * Deterministic normalization layer for OCR output, applied BEFORE cache lookup
 * and translation.
 *
 * Pipeline position:
 * ```
 * OCR/raw text → TextNormalizer → cache key → translation
 * ```
 *
 * Scope discipline (deliberately conservative — do NOT chase cache hits):
 * - Line breaks, tabs, repeated/unicode whitespace → single spaces (OCR line layout
 *   carries no meaning for MT; harmless spacing differences must not fork cache keys).
 * - NFKC Unicode normalization. This is NOT an arbitrary choice: the Marian model
 *   applies `nmt_nfkc` preprocessing internally, so the cache key reflects what the
 *   model actually consumes. Fullwidth/ligature/compatibility variants of the same
 *   text therefore share one key AND one model input.
 * - ASCII control garbage (OCR artifacts) removed; curly quotes unified (mirrors
 *   `TokenProtector` so the normalizer → protector chain is idempotent).
 *
 * Explicitly OUT of scope (meaning must never change for hits):
 * - No case folding (proper nouns, `Press E`, glossary behavior).
 * - No punctuation stripping or diacritic removal.
 * - No stemming, stop-word or article removal.
 * - Protected spans (`{var}`, `%d`, `$100`, tags, numbers, ratios) pass through
 *   byte-identical — verified by unit tests — so downstream masking/restoration
 *   sees the exact original values.
 *
 * The RAW text is preserved by callers (`TranslationResult.originalText` keeps it);
 * only the cache key and the model input use the normalized form.
 */
class TextNormalizer {

    /**
     * Returns the deterministic canonical form of [rawText].
     * Idempotent: `normalize(normalize(x)) == normalize(x)`.
     */
    fun normalize(rawText: String): String {
        if (rawText.isEmpty()) return ""
        // 1. Fold line breaks and tabs (OCR layout artifacts, never semantic here).
        var s = rawText
            .replace("\r\n", " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
        // 2. NFKC (matches the model's own nmt_nfkc preprocessing).
        s = Normalizer.normalize(s, Normalizer.Form.NFKC)
        // 3. Drop remaining ASCII control characters (OCR garbage, never game text).
        s = CONTROL_CHARS.matcher(s).replaceAll("")
        // 4. Unicode spaces (incl. NBSP, ZWSP range, BOM) → plain space.
        s = UNICODE_SPACES.matcher(s).replaceAll(" ")
        // 5. Curly quotes → straight (same mapping as TokenProtector).
        s = CURLY_DOUBLE_QUOTES.matcher(s).replaceAll("\"")
        s = CURLY_SINGLE_QUOTES.matcher(s).replaceAll("'")
        // 6. Collapse runs and trim.
        s = WHITESPACE_RUN.matcher(s).replaceAll(" ").trim()
        return s
    }

    companion object {
        private val CONTROL_CHARS =
            Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]").toPattern()
        private val UNICODE_SPACES =
            Regex("[\\u00A0\\u2000-\\u200B\\uFEFF]").toPattern()
        private val CURLY_DOUBLE_QUOTES = Regex("[“”]").toPattern()
        private val CURLY_SINGLE_QUOTES = Regex("[‘’]").toPattern()
        private val WHITESPACE_RUN = Regex("\\s+").toPattern()
    }
}
