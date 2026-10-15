package com.gametranslator.enar.render

/**
 * Visual integration strategy for one translated overlay region (Phase 8).
 *
 * The tier selects HOW translated text is composited over the game frame AFTER
 * translation has already produced logical-order Arabic. It never affects what
 * is translated, where the region sits ([OverlayRegion.boundsPx] is untouched —
 * coordinates still flow exclusively through [CoordinateMapper]), or how glyphs
 * shape (still native `StaticLayout` in `ArabicTextLayout`).
 *
 * - [TIER_1_COVER]: simple UI / dialogue replacement. The original English box
 *   is covered with an opaque solid background and the Arabic is rendered in
 *   the same logical region. Preferred when the region is suitable (dialogue
 *   boxes, subtitles, menus, fixed panels). Cheapest readable option: one
 *   `drawRect` + normal text passes, region-limited, no image processing.
 * - [TIER_2_MASK]: lightweight masking for moderately complex regions where a
 *   flat cover looks wrong but full transparency is unreadable. A translucent
 *   mask box is drawn behind the text (same rect, no sampling, no blur, no
 *   inpainting). Strictly region-limited and deterministic.
 * - [TIER_3_TRANSPARENT]: complex game-world backgrounds where covering anything
 *   is unsafe. The game stays fully visible; Arabic is drawn above it with
 *   outline/shadow for readability. This preserves every Phase 6 guarantee
 *   (transparent margins, touch passthrough, outline/shadow readability).
 *
 * Explicitly OUT of scope: automatic background-complexity detection (a later
 * phase may add a detector that feeds the policy — the [OverlayRenderPolicy]
 * seam already accepts it), full-screen processing, AI inpainting, per-frame
 * image generation, game-specific hacks.
 */
enum class OverlayRenderTier {
    /** Opaque cover + Arabic in the same logical region. */
    TIER_1_COVER,

    /** Translucent region-limited mask + Arabic. */
    TIER_2_MASK,

    /** Fully transparent, game visible, outlined/shadowed Arabic above it. */
    TIER_3_TRANSPARENT;

    companion object {
        /**
         * Parses user/config input into a tier, or null when unsupported.
         *
         * Accepted (case-insensitive, trimmed): enum names (`TIER_1_COVER`),
         * short aliases (`1`, `COVER`, `T1`; `2`, `MASK`, `T2`; `3`,
         * `TRANSPARENT`, `T3`). Anything else — including null/blank — yields
         * null so callers apply an explicit fallback (default Tier 3) and can
         * record the misconfiguration instead of crashing.
         */
        fun parseOrNull(raw: String?): OverlayRenderTier? {
            if (raw.isNullOrBlank()) return null
            return when (raw.trim().uppercase()) {
                "TIER_1_COVER", "TIER1_COVER", "TIER1", "T1", "1", "COVER", "OPAQUE", "SOLID" ->
                    TIER_1_COVER
                "TIER_2_MASK", "TIER2_MASK", "TIER2", "T2", "2", "MASK", "MASKED", "TRANSLUCENT" ->
                    TIER_2_MASK
                "TIER_3_TRANSPARENT", "TIER3_TRANSPARENT", "TIER3", "T3", "3",
                "TRANSPARENT", "OUTLINE", "NONE" ->
                    TIER_3_TRANSPARENT
                else -> null
            }
        }

        /**
         * Parsing with a guaranteed result: unknown input falls back to
         * [fallback] (default Tier 3 — the always-safe transparent strategy).
         */
        fun parseOrFallback(
            raw: String?,
            fallback: OverlayRenderTier = TIER_3_TRANSPARENT
        ): OverlayRenderTier = parseOrNull(raw) ?: fallback
    }
}
