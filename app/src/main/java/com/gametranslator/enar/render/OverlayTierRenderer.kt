package com.gametranslator.enar.render

/**
 * Outcome of styling one region for a tier (Phase 8 renderer side).
 *
 * The renderer is a PURE style transform: no bitmaps, no sampling, no I/O, no
 * Android classes — fully JVM-testable. Either the region comes out with a
 * tier-appropriate style ([Rendered], same id/bounds/text, byte-identical) or
 * it is cleanly refused ([Dropped], with a machine-readable reason). Unexpected
 * throwables are never swallowed here; the caller ([TieredOverlaySink])
 * converts them into the Tier 3 fallback / safe drop.
 */
sealed interface TierRenderOutcome {
    /** Successfully styled region ready for the overlay view. */
    data class Rendered(
        val region: OverlayRegion,
        val appliedTier: OverlayRenderTier
    ) : TierRenderOutcome

    /** Region refused (e.g. blank text / degenerate bounds — Tier 3 cannot
     * fix those either, so no fallback applies). */
    data class Dropped(val reason: String) : TierRenderOutcome
}

/**
 * Implements the visual strategy for a tier (companion to [OverlayRenderPolicy]).
 */
interface OverlayTierRenderer {
    fun render(source: OverlayRegion, tier: OverlayRenderTier): TierRenderOutcome
}

/**
 * Default tier styling as pure [OverlayStyle] transforms.
 *
 * Mechanism (why style-only is sufficient): every tier differs ONLY in how the
 * text rectangle behind the glyphs is treated — opaque cover (Tier 1),
 * translucent mask (Tier 2), or nothing (Tier 3) — plus the readability aids
 * Tier 3 needs when the game shows through (outline/shadow). All of that is
 * already expressible in [OverlayStyle] and already drawn region-locally by
 * `OverlayRegionView` (one `drawRect` + cached layout passes). No pixels are
 * read, no second coordinate system is introduced, and per-region clip bounds
 * keep work strictly region-limited.
 *
 * Preservation contract (Phase 6 untouched):
 * - id, boundsPx, and text pass through byte-identical on every tier;
 * - text size, color, alpha, padding, spacing, alignment, maxLines/ellipsis
 *   are never altered — only background/outline/shadow fields may change, and
 *   only as documented per tier below;
 * - unrenderable input ([OverlayRegion.isRenderable] == false) is [Dropped]
 *   on ALL tiers, including Tier 3.
 */
class DefaultOverlayTierRenderer(
    /** Opaque cover color for Tier 1 (must be fully opaque). */
    private val tier1Background: Int = TIER_1_DEFAULT_BACKGROUND,
    /** Translucent mask color for Tier 2 (must be translucent). */
    private val tier2Background: Int = TIER_2_DEFAULT_MASK,
    /** Readability outline applied by Tier 3 only when the style has neither
     * outline nor shadow (otherwise the author's style is kept as-is). */
    private val tier3FallbackOutlineColor: Int = TIER_3_FALLBACK_OUTLINE,
    private val tier3FallbackOutlineWidthPx: Float = 3f
) : OverlayTierRenderer {

    companion object {
        /** Near-black opaque dialogue cover. */
        const val TIER_1_DEFAULT_BACKGROUND: Int = 0xFF141414.toInt()

        /** Same hue at ~70% alpha: dims the game without hiding it. */
        const val TIER_2_DEFAULT_MASK: Int = 0xB3141414.toInt()

        /** Black readability outline for bare Tier 3 styles. */
        const val TIER_3_FALLBACK_OUTLINE: Int = 0xFF000000.toInt()
    }

    init {
        require(alphaOf(tier1Background) == 0xFF) {
            "tier1Background must be fully opaque, got ${tier1Background.toUInt().toString(16)}"
        }
        // Plain-int alpha checks only (no android.graphics.Color): this class
        // must stay JVM-testable without the Android runtime.
        require(isTranslucent(tier2Background)) {
            "tier2Background must be translucent (alpha 0x01..0xFE)"
        }
        require(tier3FallbackOutlineWidthPx >= 0f && tier3FallbackOutlineWidthPx.isFinite()) {
            "tier3FallbackOutlineWidthPx must be finite >= 0"
        }
    }

    override fun render(source: OverlayRegion, tier: OverlayRenderTier): TierRenderOutcome {
        if (!source.isRenderable()) {
            return TierRenderOutcome.Dropped(
                "unrenderable(blank=${source.text.isBlank()},area=${source.boundsPx.hasArea()})"
            )
        }
        val styled = when (tier) {
            OverlayRenderTier.TIER_1_COVER -> source.copy(
                style = source.style.copy(backgroundColor = coverBackground(source.style))
            )
            OverlayRenderTier.TIER_2_MASK -> source.copy(
                style = source.style.copy(backgroundColor = tier2Background)
            )
            OverlayRenderTier.TIER_3_TRANSPARENT -> source.copy(
                style = transparentReadable(source.style)
            )
        }
        return TierRenderOutcome.Rendered(styled, tier)
    }

    /**
     * Tier 1 keeps an author-provided background when it already covers
     * (fully opaque); otherwise the deterministic opaque default applies. An
     * author translucent box would let English glyphs show through — the one
     * thing Tier 1 must never do — so translucency is upgraded, never kept.
     */
    private fun coverBackground(style: OverlayStyle): Int {
        val bg = style.backgroundColor
        if (bg != null && alphaOf(bg) == 0xFF) return bg
        return tier1Background
    }

    /**
     * Tier 3 strips any background (game must stay visible) and guarantees a
     * readability aid: styles that already carry an outline or a shadow are
     * kept byte-identical; bare styles gain the fallback outline. The default
     * [OverlayStyle] already has an outline, so steady-state Tier 3 rendering
     * is the identity transform.
     */
    private fun transparentReadable(style: OverlayStyle): OverlayStyle {
        var out = style.copy(backgroundColor = null)
        if (!out.hasOutline() && !out.hasShadow()) {
            out = out.copy(
                outlineColor = tier3FallbackOutlineColor,
                outlineWidthPx = tier3FallbackOutlineWidthPx
            )
        }
        return out
    }

    private fun isTranslucent(color: Int): Boolean {
        val alpha = alphaOf(color)
        return alpha in 1..0xFE
    }

    private fun alphaOf(color: Int): Int = (color ushr 24) and 0xFF
}
