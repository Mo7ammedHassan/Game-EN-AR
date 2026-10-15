package com.gametranslator.enar.pipeline

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free lifetime counters for the integrated pipeline (Part P).
 *
 * Updated from the single pipeline worker thread AND from translation-completion
 * hops (also worker-confined), but read from anywhere (tests, diagnostics UI):
 * hence atomics, never locks. Only counts and timings — never text, frames,
 * or bitmaps.
 */
class PipelineMetrics internal constructor() {
    private val cycles = AtomicLong()
    private val stableReceived = AtomicLong()
    private val unstableReceived = AtomicLong()
    private val blankSkipped = AtomicLong()
    private val launched = AtomicLong()
    private val completedTranslations = AtomicLong()
    private val cacheHitsObserved = AtomicLong()
    private val cacheMissesObserved = AtomicLong()
    private val staleDropped = AtomicLong()
    private val supersededMarked = AtomicLong()
    private val skippedBusy = AtomicLong()
    private val unverifiedSuppressed = AtomicLong()
    private val coldSkipped = AtomicLong()
    private val translationFailures = AtomicLong()
    private val retryLaunches = AtomicLong()
    private val overlayUpdates = AtomicLong()
    private val overlayHides = AtomicLong()
    private val overlayFailures = AtomicLong()
    private val regionsRemoved = AtomicLong()
    private val lastEndToEndNs = AtomicLong(-1L)
    private val maxEndToEndNs = AtomicLong(-1L)
    private val inFlight = AtomicInteger()
    private val trackedRegions = AtomicInteger()
    private val overlayRegions = AtomicInteger()

    internal fun incCycles() = cycles.incrementAndGet()
    internal fun incStable() = stableReceived.incrementAndGet()
    internal fun incUnstable() = unstableReceived.incrementAndGet()
    internal fun incBlank() = blankSkipped.incrementAndGet()
    internal fun incLaunched() = launched.incrementAndGet()
    internal fun incCompleted() = completedTranslations.incrementAndGet()
    internal fun incHit() = cacheHitsObserved.incrementAndGet()
    internal fun incMiss() = cacheMissesObserved.incrementAndGet()
    internal fun incStale() = staleDropped.incrementAndGet()
    internal fun incSuperseded() = supersededMarked.incrementAndGet()
    internal fun incBusySkip() = skippedBusy.incrementAndGet()
    internal fun incSuppressed() = unverifiedSuppressed.incrementAndGet()
    internal fun incColdSkip() = coldSkipped.incrementAndGet()
    internal fun incRetryLaunch() = retryLaunches.incrementAndGet()
    internal fun incFailure() = translationFailures.incrementAndGet()
    internal fun incOverlayUpdate() = overlayUpdates.incrementAndGet()
    internal fun incOverlayHide() = overlayHides.incrementAndGet()
    internal fun incOverlayFailure() = overlayFailures.incrementAndGet()
    internal fun incRemoved() = regionsRemoved.incrementAndGet()
    internal fun noteInFlight(n: Int) = inFlight.set(n)
    internal fun noteTracked(n: Int) = trackedRegions.set(n)
    internal fun noteOverlay(n: Int) = overlayRegions.set(n)

    internal fun noteEndToEnd(ns: Long) {
        lastEndToEndNs.set(ns)
        maxEndToEndNs.accumulateAndGet(ns) { cur, v -> maxOf(cur, v) }
    }

    /** Consistent-enough point-in-time read (individual atomics, no global lock). */
    fun snapshot(): PipelineSnapshot = PipelineSnapshot(
        cycles = cycles.get(),
        stableReceived = stableReceived.get(),
        unstableReceived = unstableReceived.get(),
        blankSkipped = blankSkipped.get(),
        launched = launched.get(),
        completedTranslations = completedTranslations.get(),
        cacheHitsObserved = cacheHitsObserved.get(),
        cacheMissesObserved = cacheMissesObserved.get(),
        staleDropped = staleDropped.get(),
        supersededMarked = supersededMarked.get(),
        skippedBusy = skippedBusy.get(),
        unverifiedSuppressed = unverifiedSuppressed.get(),
        coldSkipped = coldSkipped.get(),
        translationFailures = translationFailures.get(),
        retryLaunches = retryLaunches.get(),
        overlayUpdates = overlayUpdates.get(),
        overlayHides = overlayHides.get(),
        overlayFailures = overlayFailures.get(),
        regionsRemoved = regionsRemoved.get(),
        lastEndToEndNs = lastEndToEndNs.get(),
        maxEndToEndNs = maxEndToEndNs.get(),
        inFlight = inFlight.get(),
        trackedRegions = trackedRegions.get(),
        overlayRegions = overlayRegions.get()
    )
}
