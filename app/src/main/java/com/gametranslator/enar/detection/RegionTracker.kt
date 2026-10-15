package com.gametranslator.enar.detection

import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.TextRegion

/**
 * A detector box with a frame-persistent identity.
 *
 * @param trackId Stable identity (`track-0`, `track-1`, …) that survives small
 *   inter-frame movement. Downstream stages (recognition, stabilization,
 *   translation cache) MUST key on [trackId], never on the detector's volatile id.
 * @param region The current-frame detection with [TextRegion.id] rewritten to
 *   [trackId], so every consumer sees one consistent identifier.
 */
data class TrackedRegion(
    val trackId: String,
    val region: TextRegion
)

/**
 * Spatial ROI tracker: associates per-frame detector boxes across consecutive
 * frames by bounding-box overlap (ADR-005, planned IoU > 0.6 threshold).
 *
 * ## Matching rules (all deterministic — no randomness, no wall-clock)
 * 1. Candidate pair = (live track, current detection) with `IoU STRICTLY > threshold`
 *    (IoU == 0.6 does NOT match; boundary pinned by unit test).
 * 2. Candidates sort by IoU descending, then track creation order, then detection
 *    index; greedy assignment in that order (each track/detection used at most once).
 *    Ambiguity therefore always resolves the same way: the better overlap wins;
 *    exact ties go to the older track, then the earlier detection.
 * 3. Unmatched detections open NEW tracks in detection-index order (`track-N`).
 * 4. Unmatched tracks age one missed frame; tracks missing more than
 *    [maxMissedFrames] consecutive frames are removed (stale-track cleanup, bounded
 *    memory: live tracks ≤ total distinct regions seen within the grace window).
 * 5. Output order = track creation order (NOT detector order, which models do not
 *    guarantee stable across frames).
 *
 * ## Stated limit
 * IoU alone cannot disambiguate crossing/overlapping boxes or split/merge
 * detections (two boxes merging into one matches the better overlap; the loser
 * ages out). This is accepted for game UI (mostly static, axis-aligned boxes) and
 * documented; appearance-based re-identification is explicitly out of scope.
 *
 * ## Hot-path notes
 * Pure integer geometry — no bitmaps, no frame copies. Per update the only
 * transient allocation is the candidate-pair list, O(tracks × detections), with
 * both factors small in practice (a handful of UI boxes). Pooling it would trade
 * clarity for unmeasured gain; revisit only on profiler evidence.
 *
 * Not thread-safe: call from the single OCR worker (as with [OcrResultStabilizer]).
 */
class RegionTracker(
    private val iouThreshold: Double = DEFAULT_IOU_THRESHOLD,
    private val maxMissedFrames: Int = DEFAULT_MAX_MISSED_FRAMES
) {

    companion object {
        /** Planned ADR-005 association threshold (strictly-greater comparison). */
        const val DEFAULT_IOU_THRESHOLD = 0.6

        /** Consecutive absences tolerated before a track is dropped. */
        const val DEFAULT_MAX_MISSED_FRAMES = 3

        /**
         * Intersection-over-union of two boxes in [0, 1].
         * Degenerate (zero-area) inputs yield 0.0 — never NaN, never a match.
         */
        fun iou(a: BoundingBox, b: BoundingBox): Double {
            val ix0 = maxOf(a.left, b.left)
            val iy0 = maxOf(a.top, b.top)
            val ix1 = minOf(a.right, b.right)
            val iy1 = minOf(a.bottom, b.bottom)
            val iw = ix1 - ix0
            val ih = iy1 - iy0
            if (iw <= 0 || ih <= 0) return 0.0
            val intersection = iw.toDouble() * ih.toDouble()
            val areaA = (a.right - a.left).toDouble() * (a.bottom - a.top).toDouble()
            val areaB = (b.right - b.left).toDouble() * (b.bottom - b.top).toDouble()
            val union = areaA + areaB - intersection
            if (union <= 0.0) return 0.0
            return (intersection / union).coerceIn(0.0, 1.0)
        }
    }

    init {
        require(iouThreshold > 0.0 && iouThreshold <= 1.0) {
            "IoU threshold must be in (0, 1], got $iouThreshold"
        }
        require(maxMissedFrames >= 0) {
            "maxMissedFrames must be >= 0, got $maxMissedFrames"
        }
    }

    private data class Track(var bounds: BoundingBox, var missed: Int)

    private data class Candidate(val trackId: String, val detectionIndex: Int, val iou: Double)

    // Insertion-ordered: iteration order == track creation order (deterministic).
    private val tracks = LinkedHashMap<String, Track>()
    private var nextTrackNumber = 0

    /**
     * Associates [detections] with live tracks and returns the currently observed
     * set in track-creation order. Absent tracks age; tracks missing more than
     * [maxMissedFrames] consecutive frames are removed and forgotten (a later
     * reappearance opens a NEW track — by design, stale text must not resurrect).
     */
    fun update(detections: List<TextRegion>): List<TrackedRegion> {
        // 1. Candidate pairs above threshold.
        val candidates = ArrayList<Candidate>()
        for ((trackId, track) in tracks) {
            for ((index, detection) in detections.withIndex()) {
                val overlap = iou(track.bounds, detection.bounds)
                if (overlap > iouThreshold) {
                    candidates.add(Candidate(trackId, index, overlap))
                }
            }
        }
        // 2. Best overlap first; ties → older track, then earlier detection.
        candidates.sortWith(
            compareByDescending<Candidate> { it.iou }
                .thenBy { it.trackId }
                .thenBy { it.detectionIndex }
        )

        // 3. Greedy one-to-one assignment. (Check-then-add: marking a track
        // used must happen ONLY on a successful pair, otherwise a losing
        // candidate would poison the used-sets and crash step 5.)
        val usedTracks = HashSet<String>()
        val usedDetections = HashSet<Int>()
        // Stable assignment: trackId -> detection index (matched AND new tracks).
        val assignedDetection = HashMap<String, Int>()
        for (candidate in candidates) {
            if (candidate.trackId !in usedTracks && candidate.detectionIndex !in usedDetections) {
                usedTracks.add(candidate.trackId)
                usedDetections.add(candidate.detectionIndex)
                assignedDetection[candidate.trackId] = candidate.detectionIndex
            }
        }

        // 4. New tracks for unmatched detections, in detection order.
        for ((index, detection) in detections.withIndex()) {
            if (index !in usedDetections) {
                val trackId = "track-${nextTrackNumber++}"
                tracks[trackId] = Track(detection.bounds, 0)
                assignedDetection[trackId] = index
            }
        }

        // 5. Refresh matched tracks.
        for (trackId in usedTracks) {
            val track = tracks[trackId]!!
            track.bounds = detections[assignedDetection[trackId]!!].bounds
            track.missed = 0
        }

        // 6. Age unmatched tracks; drop the long-gone.
        val observed = assignedDetection.keys
        val iterator = tracks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in observed) {
                entry.value.missed++
                if (entry.value.missed > maxMissedFrames) {
                    iterator.remove()
                }
            }
        }

        // 7. Emit the observed set in track-creation order with unified ids.
        // Numeric order (track-2 before track-10): ids are append-only integers.
        val result = ArrayList<TrackedRegion>(observed.size)
        for ((trackId, _) in tracks) {
            val detectionIndex = assignedDetection[trackId] ?: continue
            result.add(TrackedRegion(trackId, detections[detectionIndex].copy(id = trackId)))
        }
        return result
    }

    /** Number of live tracks (observed + grace-aged, not yet removed). */
    fun activeTrackCount(): Int = tracks.size

    /** Drops all tracks and resets id numbering (pipeline reset / capture restart). */
    fun reset() {
        tracks.clear()
        nextTrackNumber = 0
    }
}
