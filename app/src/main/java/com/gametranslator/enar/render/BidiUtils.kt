package com.gametranslator.enar.render

import java.text.Bidi

/**
 * Unicode Bidirectional Algorithm inspection for overlay text (pure JVM via
 * `java.text.Bidi` — no Android dependency, fully unit-testable).
 *
 * Role in the architecture: this object DESCRIBES directionality (base
 * direction, directional runs) so pipeline stages can reason about text
 * (e.g. choosing alignment defaults, detecting mixed content for tests). It
 * never reorders or reshapes anything — actual rendering direction comes from
 * the platform (`StaticLayout` + `TextDirectionHeuristics.FIRSTSTRONG_LTR` in
 * `ArabicTextLayout`), which implements the same first-strong rule verified here.
 */
object BidiUtils {

    /** Paragraph base direction under the first-strong rule (UBA P2–P3). */
    enum class BaseDirection {
        LTR,
        RTL,
        /** No strong directional character (numbers/punctuation/whitespace only). */
        NEUTRAL
    }

    /**
     * Base direction of [text]: direction of the first strong character
     * (LTR incl. LRE/LRO overrides, RTL incl. Arabic letter AL, RLE/RLO),
     * [BaseDirection.NEUTRAL] when there is none (incl. empty text).
     */
    fun baseDirection(text: String): BaseDirection {
        for (codePoint in text.codePoints().iterator()) {
            when (Character.getDirectionality(codePoint)) {
                Character.DIRECTIONALITY_LEFT_TO_RIGHT,
                Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
                Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE -> return BaseDirection.LTR
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE -> return BaseDirection.RTL
                else -> continue
            }
        }
        return BaseDirection.NEUTRAL
    }

    /** True when the paragraph reads right-to-left under the first-strong rule. */
    fun isRtlParagraph(text: String): Boolean = baseDirection(text) == BaseDirection.RTL

    /**
     * Number of directional runs in [text] per the UBA (0 for empty text).
     * Mixed Arabic/Latin content yields ≥ 2 runs; single-script text normally 1
     * (trailing neutrals fold into the adjacent run). Exposes real platform
     * bidi behavior for tests and diagnostics.
     */
    fun runCount(text: String): Int {
        if (text.isEmpty()) return 0
        return Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).runCount
    }

    /**
     * True when [text] contains BOTH an RTL and an LTR strong character —
     * the mixed-content case the overlay must render without manual reversal.
     */
    fun isMixedDirection(text: String): Boolean {
        var ltr = false
        var rtl = false
        for (codePoint in text.codePoints().iterator()) {
            when (Character.getDirectionality(codePoint)) {
                Character.DIRECTIONALITY_LEFT_TO_RIGHT,
                Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
                Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE -> ltr = true
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE -> rtl = true
                else -> continue
            }
            if (ltr && rtl) return true
        }
        return false
    }
}
