package com.gametranslator.enar.render

import android.util.Log

/**
 * Phase 8 integration seam: policy + renderer in front of any [OverlaySink].
 *
 * Position in the pipeline (unchanged flow, one decoration step added):
 * ```
 * GameTranslatorPipeline → TieredOverlaySink → OverlayController → view
 * ```
 * The pipeline keeps emitting canonical [OverlayRegion]s (logical-order Arabic
 * + overlay-space bounds via [CoordinateMapper] — NO second coordinate system
 * is introduced here; bounds pass through untouched). This sink resolves one
 * tier per region through [policy], styles it through [renderer], and forwards
 * only successfully styled regions to [delegate].
 *
 * Failure contract (a tier must never break the pipeline):
 * - Policy throw → Tier 3 requested instead (+ `policyFallbacks`); the
 *   translated text is preserved.
 * - Tier 1/2 renderer throw → retried once as Tier 3 (+ `fallbacksToTier3`);
 *   text preserved, diagnostic counted.
 * - Tier 3 throw, or unrenderable input ([TierRenderOutcome.Dropped] — blank
 *   text / degenerate bounds, which no tier can fix) → region skipped
 *   (+ `droppedRegions`); sibling regions still render.
 * - Delegate throw/refusal → counted (+ `delegateFailures`), returned as false;
 *   never rethrown.
 *
 * Bounds: stateless per call — no retained region lists, no caches, no
 * bitmaps. Per-region work is one map lookup plus one style copy (µs scale);
 * redraw behavior is unchanged (the view still short-circuits identical input
 * and `invalidate()`s only on real change). `clear()`/`hide()` pass straight
 * through to the delegate (no tier applies to lifecycle ops).
 */
class TieredOverlaySink(
    private val delegate: OverlaySink,
    private val policy: OverlayRenderPolicy =
        ExplicitOverlayRenderPolicy(OverlayRenderTier.TIER_3_TRANSPARENT),
    private val renderer: OverlayTierRenderer = DefaultOverlayTierRenderer()
) : OverlaySink {

    companion object {
        private const val TAG = "TieredOverlaySink"
    }

    val metrics = TierRenderMetrics()

    override fun update(regions: List<OverlayRegion>, densityScale: Float): Boolean {
        val styled = ArrayList<OverlayRegion>(regions.size)
        for (region in regions) {
            val out = renderOne(region)
            if (out != null) styled.add(out)
        }
        metrics.addForwarded(styled.size)
        return try {
            val ok = delegate.update(styled, densityScale)
            if (ok) {
                metrics.incUpdate()
                // Phase-10 diagnostics: count + endpoint for overlay update rate
                // and end-to-end latency analysis (Verbose only, zero behavior).
                Log.v(TAG, "sink update ok regions=${styled.size}")
            } else {
                metrics.incDelegateFailure()
            }
            ok
        } catch (t: Throwable) {
            metrics.incDelegateFailure()
            Log.w(TAG, "Delegate update failed (isolated): ${t.message}")
            false
        }
    }

    override fun clear(): Boolean = try {
        delegate.clear()
    } catch (t: Throwable) {
        metrics.incDelegateFailure()
        Log.w(TAG, "Delegate clear failed (isolated): ${t.message}")
        false
    }

    override fun hide(): Boolean = try {
        delegate.hide()
    } catch (t: Throwable) {
        metrics.incDelegateFailure()
        Log.w(TAG, "Delegate hide failed (isolated): ${t.message}")
        false
    }

    /**
     * Styles one region: resolve tier → render → fallback-or-drop.
     * Returns null when the region must not reach the delegate.
     */
    private fun renderOne(region: OverlayRegion): OverlayRegion? {
        val requested = try {
            policy.tierFor(region.id)
        } catch (t: Throwable) {
            metrics.incPolicyFallback()
            Log.w(TAG, "Policy failed for '${region.id}', using Tier 3: ${t.message}")
            OverlayRenderTier.TIER_3_TRANSPARENT
        }
        countRequested(requested)
        try {
            when (val outcome = renderer.render(region, requested)) {
                is TierRenderOutcome.Rendered -> return outcome.region
                is TierRenderOutcome.Dropped -> {
                    // Unrenderable input is unfixable by any tier (no fallback:
                    // Tier 3 would drop it identically) — skip with a count.
                    metrics.incDropped()
                    Log.w(TAG, "Dropping region '${region.id}' (${outcome.reason}).")
                    return null
                }
            }
        } catch (t: Throwable) {
            if (requested == OverlayRenderTier.TIER_3_TRANSPARENT) {
                metrics.incDropped()
                Log.w(TAG, "Dropping region '${region.id}' (Tier 3 failed): ${t.message}")
                return null
            }
            // Tier 1/2 failure → preserve the translated text via Tier 3.
            metrics.incFallbackToTier3()
            Log.w(TAG, "Tier $requested failed for '${region.id}', falling back to Tier 3.")
            return try {
                when (val retry = renderer.render(region, OverlayRenderTier.TIER_3_TRANSPARENT)) {
                    is TierRenderOutcome.Rendered -> retry.region
                    is TierRenderOutcome.Dropped -> {
                        metrics.incDropped()
                        null
                    }
                }
            } catch (t2: Throwable) {
                metrics.incDropped()
                Log.w(TAG, "Dropping region '${region.id}' (Tier 3 retry failed): ${t2.message}")
                null
            }
        }
    }

    private fun countRequested(tier: OverlayRenderTier) = when (tier) {
        OverlayRenderTier.TIER_1_COVER -> metrics.incTier1()
        OverlayRenderTier.TIER_2_MASK -> metrics.incTier2()
        OverlayRenderTier.TIER_3_TRANSPARENT -> metrics.incTier3()
    }
}
