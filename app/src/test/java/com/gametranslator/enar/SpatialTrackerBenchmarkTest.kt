package com.gametranslator.enar

import com.gametranslator.enar.detection.RegionTracker
import com.gametranslator.enar.detection.OcrResultStabilizer
import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.OcrResult
import com.gametranslator.enar.model.TextRegion
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hot-path cost measurement for one full tracker + stabilizer cycle over a
 * synthetic 12-region frame (JVM dev machine — ART numbers will differ).
 *
 * This measures; it does not compare against a baseline and claims no
 * improvement. The generous 5 ms budget documents hot-path suitability
 * (typical game UI carries far fewer than 12 live regions per OCR cycle,
 * and OCR itself runs at most 5×/s).
 */
class SpatialTrackerBenchmarkTest {

    @Test
    fun `tracker plus stabilizer frame cost`() {
        val tracker = RegionTracker()
        val stabilizer = OcrResultStabilizer(windowSize = 3, maxEditDistance = 1, maxMissedFrames = 2)

        val baseBoxes = List(12) { i ->
            BoundingBox(10 + i * 60, 20 + (i % 3) * 50, 110 + i * 60, 44 + (i % 3) * 50)
        }
        val n = 1000
        val samplesNs = LongArray(n)
        repeat(n) { frame ->
            // Deterministic ±2 px jitter (no Random: fully reproducible).
            val dx = (frame % 5) - 2
            val detections = baseBoxes.mapIndexed { i, b ->
                TextRegion(
                    id = "det-$i",
                    bounds = BoundingBox(b.left + dx, b.top, b.right + dx, b.bottom),
                    confidence = 0.9f
                )
            }
            val startNs = System.nanoTime()
            val tracked = tracker.update(detections)
            stabilizer.pruneStaleRegions(tracked.map { it.trackId }.toSet())
            for (t in tracked) {
                stabilizer.stabilize(
                    OcrResult(
                        regionId = t.trackId,
                        rawText = "Dialogue line for region ${t.trackId}",
                        confidence = 0.9f,
                        bounds = t.region.bounds
                    )
                )
            }
            samplesNs[frame] = System.nanoTime() - startNs
        }

        samplesNs.sort()
        val meanNs = samplesNs.average()
        val p50Ns = samplesNs[(n * 0.50).toInt()]
        val p95Ns = samplesNs[(n * 0.95).toInt()]
        println(
            "TRACKER_BENCH[update+stabilize-12-regions] n=$n " +
                "mean_ns=${"%.1f".format(meanNs)} p50_ns=$p50Ns p95_ns=$p95Ns"
        )
        assertTrue(
            "Median frame cost $p50Ns ns exceeds the 5 ms hot-path budget",
            p50Ns < 5_000_000L
        )
    }
}
