package com.gametranslator.enar.detection

import com.gametranslator.enar.model.OcrResult

/**
 * Lifecycle of one tracked OCR region.
 *
 * ```
 * NEW ──► OBSERVING ──► STABLE ◄──► CHANGED
 *   │          │            │           │
 *   │          └─────┬──────┴─────┬─────┘
 *   │                ▼            │ (absent 1..maxMissedFrames frames)
 *   │          DISAPPEARED ◄──────┘
 *   │                │ (reappears: resume with frozen window)
 *   │                ▼ (absent > maxMissedFrames frames)
 *   └──────────► REMOVED (record dropped; reappearance starts at NEW)
 * ```
 */
enum class TrackState {
    /** Seen, but without enough consistent evidence yet. Output not stable. */
    OBSERVING,

    /** Last [OcrResultStabilizer window][OcrResultStabilizer] of observations is
     * mutually consistent — output may be translated and shown. */
    STABLE,

    /** Text changed beyond tolerance after stability: a FRESH observation window
     * is filling for the new text. Previous stable text is superseded only once
     * the new window proves itself (bounded delay of `windowSize` frames). */
    CHANGED,

    /** Box absent (or only blank/weak observations) within the grace window.
     * Observation window is FROZEN — not advanced, not discarded. */
    DISAPPEARED
}

/**
 * Track-aware temporal OCR stabilizer (hardened Phase 5 implementation of the
 * Phase 3 minimal ring buffer; ADR-005).
 *
 * Keys EVERYTHING on the caller-supplied region id. In production (Phase 7) that
 * id is a [RegionTracker] `trackId`, so stabilization operates on tracked spatial
 * regions rather than independent per-frame strings; in legacy/test use it may be
 * any stable-per-location identifier. Different ids NEVER share state
 * (cross-region isolation by construction).
 *
 * ## Evidence rules
 * - STABLE requires a full window (`windowSize` observations) mutually consistent
 *   within `maxEditDistance` of the window reference (first entry). One-frame
 *   corruption can never stabilize (window cannot fill consistently); two-frame
 *   corruption inside a size-3 window likewise fails — the third frame decides.
 * - Small variations (edit distance ≤ tolerance) are tolerated AND keep a STABLE
 *   track stable (`stableText` advances to the latest observation, so legitimate
 *   drift such as score counters eventually replaces the old text without a
 *   CHANGED round-trip).
 * - A beyond-tolerance change discards the old window and restarts observation at
 *   the new text (CHANGED if the track was ever stable, else OBSERVING).
 * - Empty text and below-[minConfidence] observations are DROPPED: they neither
 *   advance nor corrupt the window (transient glare/blank frames freeze output
 *   instead of flickering it). Trade-off, documented: a box whose text is
 *   permanently cleared but keeps being detected holds its last stable text until
 *   the TRACK disappears (detectors normally drop empty boxes, which the
 *   disappearance path handles).
 * - Absence is grace-counted via [pruneStaleRegions]: a track missing up to
 *   [maxMissedFrames] consecutive frames goes DISAPPEARED with its window intact
 *   (reappearance resumes — no re-observation penalty); longer absence REMOVES the
 *   record, so a later look-alike starts at NEW and stale text cannot resurrect.
 *   Pair with the same `maxMissedFrames` as [RegionTracker] so both layers expire
 *   a track on the same frame.
 *
 * ## Compatibility
 * Default parameters (`maxMissedFrames = 0`, `minConfidence = 0`) reproduce the
 * exact Phase 3 observable behavior (immediate prune, all confidences accepted),
 * so existing callers (`OcrPipelineCoordinator`, `MainActivity`) and tests are
 * unaffected until they opt into the new knobs.
 *
 * ## Thread safety
 * Not thread-safe. Must be called from a single coroutine/worker (the
 * OcrPipelineCoordinator's worker coroutine on Dispatchers.Default).
 *
 * @param windowSize Consecutive consistent observations required for stability (≥ 1).
 * @param maxEditDistance Character-level tolerance for "same text" (≥ 0).
 * @param maxMissedFrames Consecutive absences tolerated before removal (≥ 0;
 *   0 = legacy immediate removal).
 * @param minConfidence Observations below this confidence are dropped (0..1;
 *   0 = accept everything, legacy behavior).
 */
class OcrResultStabilizer(
    private val windowSize: Int = 3,
    private val maxEditDistance: Int = 1,
    private val maxMissedFrames: Int = 0,
    private val minConfidence: Float = 0f
) {
    init {
        require(windowSize >= 1) { "windowSize must be >= 1, got $windowSize" }
        require(maxEditDistance >= 0) { "maxEditDistance must be >= 0, got $maxEditDistance" }
        require(maxMissedFrames >= 0) { "maxMissedFrames must be >= 0, got $maxMissedFrames" }
        require(minConfidence in 0f..1f) { "minConfidence must be in [0, 1], got $minConfidence" }
    }

    private data class Record(
        val window: ArrayDeque<String>,
        var state: TrackState,
        var stableText: String?,
        var missed: Int
    )

    // Insertion-ordered map: region/track id → observation record.
    private val records: MutableMap<String, Record> = mutableMapOf()

    /**
     * Submits an [OcrResult] and returns a copy with [OcrResult.isStable] set
     * according to the track state machine (`true` iff [TrackState.STABLE]).
     *
     * Blank or below-confidence observations freeze the record and report the
     * stored stability (a STABLE track stays STABLE through transient blanks).
     */
    fun stabilize(result: OcrResult): OcrResult {
        val existing = records[result.regionId]
        if (result.rawText.isEmpty() || result.confidence < minConfidence) {
            return result.copy(isStable = existing?.state == TrackState.STABLE)
        }

        val record = existing ?: Record(ArrayDeque(windowSize + 1), TrackState.OBSERVING, null, 0)
            .also { records[result.regionId] = it }
        record.missed = 0

        val window = record.window
        if (window.isEmpty()) {
            window.addLast(result.rawText)
            record.state = TrackState.OBSERVING
            return result.copy(isStable = false)
        }

        if (levenshtein(window.first(), result.rawText) <= maxEditDistance) {
            window.addLast(result.rawText)
            if (window.size > windowSize) {
                window.removeFirst()
            }
            return if (window.size >= windowSize && isConsistent(window)) {
                record.state = TrackState.STABLE
                record.stableText = result.rawText
                result.copy(isStable = true)
            } else {
                // Filling, or consistent-so-far after a change: hold prior label.
                if (record.state != TrackState.CHANGED) {
                    record.state = TrackState.OBSERVING
                }
                result.copy(isStable = false)
            }
        }

        // Beyond tolerance: restart observation at the new text.
        window.clear()
        window.addLast(result.rawText)
        record.state = if (record.stableText != null) TrackState.CHANGED else TrackState.OBSERVING
        return result.copy(isStable = false)
    }

    /**
     * Ages tracks absent from [activeRegionIds] (call once per OCR cycle with the
     * tracker's live set, BEFORE feeding observations). Tracks absent up to
     * [maxMissedFrames] consecutive frames go [TrackState.DISAPPEARED] with frozen
     * windows; longer absence removes the record. Present tracks reset their
     * miss counter (state resumes on the next observation).
     */
    fun pruneStaleRegions(activeRegionIds: Set<String>) {
        val iterator = records.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in activeRegionIds) {
                entry.value.missed++
                if (entry.value.missed > maxMissedFrames) {
                    iterator.remove()
                } else if (entry.value.state != TrackState.DISAPPEARED) {
                    entry.value.state = TrackState.DISAPPEARED
                }
            } else {
                entry.value.missed = 0
            }
        }
    }

    /** Clears all track records (call on pipeline reset or capture restart). */
    fun reset() {
        records.clear()
    }

    /** Returns how many track records are currently retained (incl. DISAPPEARED). */
    fun trackedRegionCount(): Int = records.size

    /** Returns the current lifecycle state of a track, or null if unknown/removed. */
    fun getState(regionId: String): TrackState? = records[regionId]?.state

    /** Returns the last proven-stable text of a track, or null if never stabilized. */
    fun getStableText(regionId: String): String? = records[regionId]?.stableText

    // ---- Private helpers ----

    private fun isConsistent(window: ArrayDeque<String>): Boolean {
        val reference = window.first()
        return window.all { levenshtein(reference, it) <= maxEditDistance }
    }

    /**
     * Computes the Levenshtein edit distance between two strings.
     * Uses a two-row DP approach (O(min(m,n)) space, no per-call matrix alloc).
     */
    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val (shorter, longer) = if (a.length <= b.length) Pair(a, b) else Pair(b, a)
        var prev = IntArray(shorter.length + 1) { it }
        var curr = IntArray(shorter.length + 1)

        for (i in 1..longer.length) {
            curr[0] = i
            for (j in 1..shorter.length) {
                val cost = if (longer[i - 1] == shorter[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,        // insertion
                    prev[j] + 1,             // deletion
                    prev[j - 1] + cost       // substitution
                )
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[shorter.length]
    }
}
