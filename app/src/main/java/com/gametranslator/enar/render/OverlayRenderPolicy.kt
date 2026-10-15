package com.gametranslator.enar.render

/**
 * Decides WHICH rendering tier a region wants (Phase 8 policy side).
 *
 * Deliberately separated from [OverlayTierRenderer] (the implementation side):
 * the policy answers "which strategy?" in O(1) map-lookup time with no Android
 * dependency, while the renderer answers "how to draw it?". A future automatic
 * background-complexity detector plugs in here as another implementation —
 * no renderer or pipeline changes required.
 *
 * No automatic detection exists yet: selection is explicit/configurable only.
 */
interface OverlayRenderPolicy {
    /** Tier requested for [regionId] (never throws — returns a valid tier). */
    fun tierFor(regionId: String): OverlayRenderTier
}

/**
 * Explicit, configurable policy: one default tier plus optional per-region
 * overrides (e.g. dialogue track → Tier 1, world-space track → Tier 3).
 *
 * - Lookup cost is one `HashMap` get (cheap enough for the ≤5 Hz overlay path).
 * - Unknown/blank ids yield [defaultTier] (never throws on the hot path).
 * - [overrides] are defensively copied; later caller mutation cannot change
 *   behavior. Empty ids in the override map are ignored at construction.
 */
class ExplicitOverlayRenderPolicy(
    private val defaultTier: OverlayRenderTier = OverlayRenderTier.TIER_3_TRANSPARENT,
    overrides: Map<String, OverlayRenderTier> = emptyMap()
) : OverlayRenderPolicy {

    private val perRegion: Map<String, OverlayRenderTier> =
        overrides.filterKeys { it.isNotEmpty() }.toMap()

    override fun tierFor(regionId: String): OverlayRenderTier =
        perRegion[regionId] ?: defaultTier

    /** The configured default (diagnostics/tests). */
    fun default(): OverlayRenderTier = defaultTier

    /** Region ids carrying an explicit override (diagnostics/tests). */
    fun overriddenIds(): Set<String> = perRegion.keys

    companion object {
        /**
         * Builds a policy from raw config strings (settings file, intent extra,
         * remote-config snapshot). Every unparseable value — bad default, bad
         * per-region entry — falls back to [fallbackTier] (default Tier 3, the
         * always-safe choice) instead of throwing, so a typo can never break
         * the overlay. Use [TieredOverlaySink.metrics] `policyFallbacks` to
         * observe how often fallback was applied.
         *
         * @return pair of (policy, number of entries that needed fallback).
         */
        fun fromConfig(
            defaultRaw: String?,
            overridesRaw: Map<String, String?> = emptyMap(),
            fallbackTier: OverlayRenderTier = OverlayRenderTier.TIER_3_TRANSPARENT
        ): Pair<ExplicitOverlayRenderPolicy, Int> {
            var fallbacks = 0
            val default = OverlayRenderTier.parseOrNull(defaultRaw)
                ?: run { fallbacks++; fallbackTier }
            val overrides = mutableMapOf<String, OverlayRenderTier>()
            for ((id, raw) in overridesRaw) {
                if (id.isEmpty()) {
                    fallbacks++
                    continue
                }
                val tier = OverlayRenderTier.parseOrNull(raw)
                if (tier == null) {
                    fallbacks++
                    overrides[id] = fallbackTier
                } else {
                    overrides[id] = tier
                }
            }
            return ExplicitOverlayRenderPolicy(default, overrides) to fallbacks
        }
    }
}
