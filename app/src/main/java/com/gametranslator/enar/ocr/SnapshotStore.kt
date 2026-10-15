package com.gametranslator.enar.ocr

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded latest-only pixel slot between capture and OCR (Phase 9 transport).
 *
 * MECHANISM: a single [AtomicReference] holds at most one [OcrSnapshot].
 * Publishing swaps the reference (lock-free CAS — the CaptureThread NEVER
 * blocks, waits, or allocates beyond the snapshot itself). The previous
 * snapshot becomes garbage immediately: with at most one holder in the store
 * plus one in the engine's active cycle, live pixel memory is bounded at
 * ~2 × 1.3 MB by construction. There is deliberately NO queue — a newer frame
 * discards an older one (`overwritten` counts it), implementing the
 * "latest frame over all frames" rule from the Phase 9 brief.
 *
 * Publish gating: [publish] refuses snapshots more often than
 * [minIntervalMs] (`throttled` counts them), so a 30 Hz changed-frame stream
 * degrades to ≤ 5 snapshots/s aligned with the coordinator's OCR cadence.
 * The clock is caller-supplied (`nowMs`) — no wall-clock reads inside, fully
 * deterministic under JVM test. Callers MUST pass a monotonic clock
 * (`SystemClock.elapsedRealtime()` on Android); wall-clock jumps backwards
 * would throttle until the clock catches up.
 *
 * Lifecycle: [clear] drops the slot (service stop). Counters are lifetime
 * diagnostics (counts only, never pixels).
 */
class SnapshotStore(
    private val minIntervalMs: Long = OcrConfig.SNAPSHOT_MIN_INTERVAL_MS
) {
    private data class Slot(val snapshot: OcrSnapshot, val publishedMs: Long)

    private val slot = AtomicReference<Slot?>(null)
    private val nextGeneration = AtomicLong(1L)

    private val _published = AtomicLong()
    private val _overwritten = AtomicLong()
    private val _throttled = AtomicLong()

    /**
     * Publishes [snapshot], stamping a monotonic generation.
     * @return true when stored; false when throttled (too soon after the
     *   previous publish — caller keeps its snapshot droppable, nothing leaks
     *   because the caller simply drops its own reference).
     */
    fun publish(snapshot: OcrSnapshot, nowMs: Long): Boolean {
        while (true) {
            val cur = slot.get()
            val lastMs = cur?.publishedMs
            if (lastMs != null && nowMs - lastMs < minIntervalMs) {
                _throttled.incrementAndGet()
                return false
            }
            val stamped = snapshot.copy(generation = nextGeneration.getAndIncrement())
            if (slot.compareAndSet(cur, Slot(stamped, nowMs))) {
                _published.incrementAndGet()
                if (cur != null) _overwritten.incrementAndGet()
                return true
            }
        }
    }

    /** Returns the latest snapshot without removing it (latest-frame read).
     * Null when no snapshot was ever published or after [clear]. */
    fun acquire(): OcrSnapshot? = slot.get()?.snapshot

    /**
     * True when a publish at [nowMs] would be stored (not throttled).
     * Lets the producer skip wasted conversion work; [publish] re-checks
     * atomically, so a race here only costs one redundant snapshot build.
     */
    fun isDue(nowMs: Long): Boolean {
        val last = slot.get()?.publishedMs ?: return true
        return nowMs - last >= minIntervalMs
    }

    /** Drops the slot (capture stop / pipeline teardown). */
    fun clear() {
        slot.set(null)
    }

    /** Point-in-time diagnostic counters. */
    fun stats(): SnapshotStats = SnapshotStats(
        published = _published.get(),
        overwritten = _overwritten.get(),
        throttled = _throttled.get(),
        hasSnapshot = slot.get() != null
    )
}

/** Counts-only view of [SnapshotStore] (no pixels, safe to log). */
data class SnapshotStats(
    val published: Long,
    val overwritten: Long,
    val throttled: Long,
    val hasSnapshot: Boolean
)
