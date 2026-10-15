package com.gametranslator.enar.translation

import com.gametranslator.enar.model.ProtectedText
import java.util.regex.Pattern

/**
 * Pre- and post-processing utility that identifies and protects game-specific variables,
 * format strings, numbers, currencies, and UI tags from being corrupted or mistranslated
 * by the Neural Machine Translation engine.
 *
 * ## Placeholder strategy (numeric sentinels — see issues.md ISSUE-004)
 *
 * The previous `VARn` scheme is RETIRED: `VAR0` tokenizes with the real
 * `Helsinki-NLP/opus-mt-en-ar` vocabulary as `['▁V', 'AR', '0']` — three ordinary
 * translatable subwords with no verbatim-copy mechanism — so the model transliterates
 * (`فارلو`), paraphrases (`في آربيلا`), or silently drops the placeholder, and
 * post-hoc restore finds nothing to restore.
 *
 * Protected spans are instead substituted with short **numeric sentinels** (`987650`,
 * `987651`, …). Measured behavior on the real INT8 ONNX model (dev machine, greedy):
 * 6-digit sentinels are copied verbatim in ~25/30 tested contexts (single, multiple,
 * repeated, start/end of sentence, adjacent to units/`%`/tags), while `VAR0` is mangled
 * or dropped in the same contexts. Pure numbers were already copied natively; sentinels
 * extend that copy behavior to every fragile span class (`{var}`, `%d`, tags, …).
 *
 * Safety rules (all enforced here, all unit-tested):
 * - Sentinels are 6 digits (`SENTINEL_BASE..SENTINEL_MAX`); 9-digit numbers were observed
 *   to be dropped by the model, so length is capped.
 * - Collision avoidance: a candidate that occurs in — or is hidden inside a digit-run
 *   of — the source text is skipped. If the range is exhausted the span is
 *   left unmasked rather than aliased.
 * - Sentinels are space-padded in masked text so they always tokenize as `▁98 76 5x`
 *   (the exact shape validated against the model).
 * - Restoration is literal and verifiable: [missingPlaceholders] reports sentinels absent
 *   from the model output so engines can fall back instead of returning silent corruption.
 *
 * Example:
 * Input:  "Give {playerName} $100 and %d potions"
 * Masked: "Give 987650 987651 and 987652 potions"
 * Trans:  "أعطِ 987650 987651 و 987652 جرعات"
 * Output: "أعطِ {playerName} $100 و %d جرعات"
 */
class TokenProtector {

    companion object {
        // Unified single-pass regex pattern for all protected tokens to prevent recursive replacement bugs.
        // NOTE: the ratio alternative (80/100) must stay BEFORE the plain-number alternative so a
        // slash-joined pair is masked as ONE sentinel (validated: "HP 987650" copies verbatim,
        // "HP 987650 / 987651" degenerates).
        private val UNIFIED_PATTERN = Pattern.compile(
            // 1. Format specifiers: %d, %s, %.2f, %1$s, %02d, etc.
            "%(?:\\d+\\$)?[+-]?(?:\\d+)?(?:\\.\\d+)?[sdfxXeEgGcqbB%]" +
            // 2. Curly bracket variables: {player}, {0}, {item_name}, {COUNT}
            "|\\{[a-zA-Z0-9_.-]+\\}" +
            // 3. XML/HTML style game tags: <color=#FFF>, </color>, <b>, </b>, <size=20>, etc.
            "|<[/]?[a-zA-Z0-9_#=.-]+>" +
            // 4. Currency amounts: $100, €50, £20, ¥500
            "|[$€£¥₩₹]\\s*[0-9]+(?:\\.[0-9]+)?" +
            // 5. Ratios: 80/100, 3 / 5 (masked as a single span)
            "|\\b[0-9]+\\s*/\\s*[0-9]+\\b" +
            // 6. Units and numbers: 100x, +500, -10, 50%, 100HP, 50XP, 20G.
            // Trailing (?!\w) (not \b): a span ending in "%" ("100% ") has no word
            // boundary before the space, so \b would split it into "100"+"%" and leave
            // a stray "%" glued to the sentinel — an unvalidated model shape.
            "|\\b[+-]?[0-9]+(?:\\.[0-9]+)?(?:%|x|X|HP|XP|MP|G|K|M|B)?(?!\\w)" +
            // 7. Hash identifiers: #1, #99
            "|#[0-9]+"
        )

        /** First numeric sentinel. Far from plausible game values; bumped on collision. */
        const val SENTINEL_BASE = 987650
        /** Sentinels stay ≤ 6 digits (longer runs were observed dropped by the model). */
        const val SENTINEL_MAX = 999999

        private val DIGIT_RUN = Regex("\\d+")
        private val WHITESPACE_RUN = Regex("\\s+")
    }

    /**
     * Normalizes text (trims whitespace, unifies unicode spaces/quotes) and masks all
     * protected spans with numeric sentinels in a single non-overlapping pass.
     */
    fun protect(rawText: String): ProtectedText {
        if (rawText.isBlank()) {
            return ProtectedText(rawText.trim(), emptyMap())
        }

        val normalized = normalizeText(rawText)
        val digitRuns = DIGIT_RUN.findAll(normalized).map { it.value }.toList()
        val tokenMap = mutableMapOf<String, String>()
        var nextSentinel = SENTINEL_BASE

        val matcher = UNIFIED_PATTERN.matcher(normalized)
        val sb = StringBuffer()
        while (matcher.find()) {
            val matchedValue = matcher.group()
            // Avoid masking plain words or single characters that are not numbers
            if (matchedValue.length == 1 && matchedValue[0].isLetter()) {
                matcher.appendReplacement(sb, MatcherQuoteReplacement(matchedValue))
                continue
            }
            val sentinel = claimSentinel(normalized, digitRuns, tokenMap.keys, nextSentinel)
            if (sentinel == null) {
                // Sentinel space exhausted: leave the span unmasked rather than aliasing.
                matcher.appendReplacement(sb, MatcherQuoteReplacement(matchedValue))
                continue
            }
            nextSentinel = sentinel + 1
            tokenMap[sentinel.toString()] = matchedValue
            // Space-pad so the sentinel always tokenizes standalone (▁98 76 5x).
            matcher.appendReplacement(sb, MatcherQuoteReplacement(" $sentinel "))
        }
        matcher.appendTail(sb)

        val masked = WHITESPACE_RUN.replace(sb.toString(), " ").trim()
        return ProtectedText(masked, tokenMap)
    }

    /**
     * Restores protected variables back into the translated Arabic text.
     * Replacement is literal (exact digit-string match). Sentinels the model dropped or
     * rewrote are left untouched here — use [missingPlaceholders] to detect that case.
     */
    fun restore(translatedText: String, tokenMap: Map<String, String>): String {
        if (tokenMap.isEmpty() || translatedText.isBlank()) {
            return translatedText.trim()
        }

        var restored = translatedText
        // Longest-first so sentinel prefixes can never shadow each other.
        for ((placeholder, originalValue) in tokenMap.entries.sortedByDescending { it.key.length }) {
            restored = restored.replace(placeholder, originalValue)
        }

        return cleanArabicPostprocessing(restored)
    }

    /**
     * Returns the subset of placeholders that do NOT occur verbatim in [translatedText].
     * A non-empty result means the model mangled or dropped a protected span and the
     * caller must fall back (re-translate unmasked / dictionary / source text) instead of
     * presenting silently corrupted output. See issues.md ISSUE-004.
     */
    fun missingPlaceholders(translatedText: String, tokenMap: Map<String, String>): Set<String> {
        if (tokenMap.isEmpty()) return emptySet()
        return tokenMap.keys.filterTo(mutableSetOf()) { !translatedText.contains(it) }
    }

    /**
     * Claims the next sentinel >= [start] that cannot be confused with any number already
     * in the source text (or previously issued), or null if the range is exhausted.
     */
    private fun claimSentinel(
        normalized: String,
        digitRuns: List<String>,
        issued: Set<String>,
        start: Int
    ): Int? {
        var candidate = start
        while (candidate <= SENTINEL_MAX) {
            val s = candidate.toString()
            // Reject only true aliasing risks: the sentinel already issued, occurring
            // anywhere in the source text, or hidden inside one of its digit-runs
            // (restoring there would corrupt the longer number). The reverse direction
            // (a source run being a substring of the sentinel) is harmless because
            // restore() replaces the exact 6-digit string only.
            val collides = issued.contains(s) ||
                normalized.contains(s) ||
                digitRuns.any { it.contains(s) }
            if (!collides) return candidate
            candidate++
        }
        return null
    }

    /**
     * Normalizes English input before translation.
     */
    private fun normalizeText(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace(Regex("[\\u00A0\\u2000-\\u200B]"), " ") // non-breaking / unicode spaces
            .replace(Regex("[“”]"), "\"")
            .replace(Regex("[‘’]"), "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Cleans up translated Arabic text (spacing around punctuation, double spaces).
     */
    private fun cleanArabicPostprocessing(text: String): String {
        return text
            .replace(Regex("\\s+([،.!?؛:])"), "$1") // attach punctuation to previous word
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun MatcherQuoteReplacement(s: String): String {
        return java.util.regex.Matcher.quoteReplacement(s)
    }
}
