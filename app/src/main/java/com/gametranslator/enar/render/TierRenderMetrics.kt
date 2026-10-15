package com.gametranslator.enar.render

import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free lifetime counters for the Phase 8 tiered overlay path.
 *
 * Same discipline as [com.gametranslator.enar.pipeline.PipelineMetrics]:
 * atomics (never locks), readable from any thread, counts only — never text,
 * frames, or bitmaps.
 */
class TierRenderMetrics internal constructor() {
    private val tier1Requested = AtomicLong()
    private val tier2Requested = AtomicLong()
    private val tier3Requested = AtomicLong()
    private val policyFallbacks = AtomicLong()
    private val fallbacksToTier3 = AtomicLong()
    private val droppedRegions = AtomicLong()
    private val delegateFailures = AtomicLong()
    private val forwardedRegions = AtomicLong()
    private val updatesForwarded = AtomicLong()

    internal fun incTier1() = tier1Requested.incrementAndGet()
    internal fun incTier2() = tier2Requested.incrementAndGet()
    internal fun incTier3() = tier3Requested.incrementAndGet()
    internal fun incPolicyFallback() = policyFallbacks.incrementAndGet()
    internal fun incFallbackToTier3() = fallbacksToTier3.incrementAndGet()
    internal fun incDropped() = droppedRegions.incrementAndGet()
    internal fun incDelegateFailure() = delegateFailures.incrementAndGet()
    internal fun addForwarded(n: Int) = forwardedRegions.addAndGet(n.toLong())
    internal fun incUpdate() = updatesForwarded.incrementAndGet()

    /** Consistent-enough point-in-time read (individual atomics, no lock). */
    fun snapshot(): TierRenderSnapshot = TierRenderSnapshot(
        tier1Requested = tier1Requested.get(),
        tier2Requested = tier2Requested.get(),
        tier3Requested = tier3Requested.get(),
        policyFallbacks = policyFallbacks.get(),
        fallbacksToTier3 = fallbacksToTier3.get(),
        droppedRegions = droppedRegions.get(),
        delegateFailures = delegateFailures.get(),
        forwardedRegions = forwardedRegions.get(),
        updatesForwarded = updatesForwarded.get()
    )
}

/** Point-in-time diagnostic view of [TierRenderMetrics] (counts only). */
data class TierRenderSnapshot(
    val tier1Requested: Long,
    val tier2Requested: Long,
    val tier3Requested: Long,
    val policyFallbacks: Long,
    val fallbacksToTier3: Long,
    val droppedRegions: Long,
    val delegateFailures: Long,
    val forwardedRegions: Long,
    val updatesForwarded: Long
)
