package com.gametranslator.enar.engine

import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.EngineMetadata
import com.gametranslator.enar.model.TranslationResult
import com.gametranslator.enar.translation.LruTranslationCache
import com.gametranslator.enar.translation.TextNormalizer
import com.gametranslator.enar.translation.TokenProtector
import com.gametranslator.enar.translation.TranslationCache

/**
 * Transparent caching decorator for any [TranslationEngine].
 *
 * Flow per request:
 * ```
 * raw OCR text → TextNormalizer → cache lookup
 *   → HIT:  return cached Arabic (fromCache=true, bounds of THIS request)
 *   → MISS: delegate.translate(normalized) → admission check → maybe cache → return
 * ```
 *
 * The delegate always receives the NORMALIZED text (canonical model input; the
 * normalizer → protector chain is idempotent, so validated model behavior is
 * unchanged). `TranslationResult.originalText` keeps the RAW caller text for
 * display/traceability — the normalized form is never silently substituted there.
 *
 * ## Cache admission policy (ISSUE-004 / ADR-008)
 *
 * `protectedTokensRestored=false` on a span-bearing result means a correctness
 * concern (sentinel loss → unmasked-retry output, or a degraded echo). Such results
 * are NEVER cached: a later identical request re-translates instead of reusing a
 * potentially unsafe output, and a future engine/model fix is not poisoned by a
 * cemented bad entry.
 *
 * A result enters the cache only if ALL hold:
 * 1. the delegate `isWarm()` (a cold engine's echo/original-text fallback is not a
 *    translation and must not poison the cache — e.g. ONNX fallback echoes input),
 * 2. `result.protectedTokensRestored == true` (every sentinel verified restored), OR
 *    the normalized input contains no protected spans at all (vacuous safety —
 *    plain-text translations such as "Press E to open the door." are the main cache
 *    beneficiaries and carry no restoration concern).
 *
 * Consequences: cache hits by construction carry no restoration concern, so hit
 * results report `protectedTokensRestored=true` with `fromCache=true`. The cache key
 * is the normalized text (English→Arabic scope; language pair is fixed by the
 * pipeline and passed through to the delegate).
 *
 * Wiring note: pipeline integration (capture → … → cache → engine → overlay)
 * belongs to Phase 7; until then this decorator is exercised by unit/integration
 * tests and is ready to wrap the production engine.
 */
class CachedTranslationEngine(
    private val delegate: TranslationEngine,
    private val cache: TranslationCache = LruTranslationCache(),
    private val normalizer: TextNormalizer = TextNormalizer(),
    private val tokenProtector: TokenProtector = TokenProtector()
) : TranslationEngine {

    override suspend fun translate(
        text: String,
        targetBounds: BoundingBox,
        sourceLang: String,
        targetLang: String
    ): TranslationResult {
        val normalized = normalizer.normalize(text)

        val hitStartNs = System.nanoTime()
        val cached = cache.get(normalized)
        if (cached != null) {
            val lookupNs = System.nanoTime() - hitStartNs
            return TranslationResult(
                originalText = text,
                translatedArabicText = cached,
                bounds = targetBounds,
                fromCache = true,
                latencyMs = lookupNs / 1_000_000L,
                latencyNs = lookupNs,
                sourceLang = sourceLang,
                targetLang = targetLang,
                engineMetadata = delegate.getEngineMetadata(),
                // Admission (below) guarantees cached outputs are safe.
                protectedTokensRestored = true
            )
        }

        val result = delegate.translate(normalized, targetBounds, sourceLang, targetLang)

        if (isCacheable(normalized, result)) {
            cache.put(normalized, result.translatedArabicText)
        }

        return result.copy(originalText = text)
    }

    override fun isWarm(): Boolean = delegate.isWarm()

    override fun getEngineMetadata(): EngineMetadata = delegate.getEngineMetadata()

    /**
     * Admission predicate extracted for testability.
     */
    internal fun isCacheable(normalizedText: String, result: TranslationResult): Boolean {
        if (!delegate.isWarm()) return false
        if (result.protectedTokensRestored) return true
        // Vacuous safety: nothing was masked, so there is nothing unrestored.
        // (Runs only when the flag is false; a cheap regex pass next to model call.)
        return tokenProtector.protect(normalizedText).tokenMap.isEmpty()
    }
}
