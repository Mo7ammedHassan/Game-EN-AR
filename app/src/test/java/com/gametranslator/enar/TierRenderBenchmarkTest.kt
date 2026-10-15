package com.gametranslator.enar

import com.gametranslator.enar.render.DefaultOverlayTierRenderer
import com.gametranslator.enar.render.ExplicitOverlayRenderPolicy
import com.gametranslator.enar.render.OverlayRect
import com.gametranslator.enar.render.OverlayRegion
import com.gametranslator.enar.render.OverlayRenderTier
import com.gametranslator.enar.render.OverlaySink
import com.gametranslator.enar.render.OverlayStyle
import com.gametranslator.enar.render.TieredOverlaySink
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM micro-measurement for the Phase 8 tier path (policy lookup + style
 * transform over 20 synthetic regions). Measures only; claims nothing
 * comparative. Dev-machine numbers — ART will differ.
 */
class TierRenderBenchmarkTest {

    private class NoOpSink : OverlaySink {
        var last = 0
        override fun update(regions: List<OverlayRegion>, densityScale: Float): Boolean {
            last = regions.size
            return true
        }

        override fun clear(): Boolean = true
        override fun hide(): Boolean = true
    }

    @Test
    fun `tier selection plus styling cost over synthetic regions`() {
        val policy = ExplicitOverlayRenderPolicy(
            OverlayRenderTier.TIER_3_TRANSPARENT,
            mapOf(
                "region-0" to OverlayRenderTier.TIER_1_COVER,
                "region-1" to OverlayRenderTier.TIER_2_MASK
            )
        )
        val sink = TieredOverlaySink(NoOpSink(), policy, DefaultOverlayTierRenderer())
        val texts = listOf(
            "مرحبا بك في اللعبة",
            "Press E to open the door.",
            "لديك 50 HP باق",
            "Score: 12500 pts (100%)",
            "Hello {playerName}, welcome back."
        )
        val regions = List(20) { i ->
            OverlayRegion(
                "region-$i",
                OverlayRect(10f + i * 50f, 100f, 210f + i * 50f, 150f),
                texts[i % texts.size],
                OverlayStyle()
            )
        }
        // Warmup.
        repeat(200) { sink.update(regions, 3.5f) }

        val n = 2000
        val samplesNs = LongArray(n)
        repeat(n) { k ->
            val startNs = System.nanoTime()
            sink.update(regions, 3.5f)
            samplesNs[k] = System.nanoTime() - startNs
            if (sink.metrics.snapshot().forwardedRegions == 0L) throw AssertionError("unreachable")
        }

        samplesNs.sort()
        val meanNs = samplesNs.average()
        val p50Ns = samplesNs[(n * 0.50).toInt()]
        val p95Ns = samplesNs[(n * 0.95).toInt()]
        println(
            "RENDER_BENCH[tier-20-regions] n=$n " +
                "mean_ns=${"%.1f".format(meanNs)} p50_ns=$p50Ns p95_ns=$p95Ns"
        )
        // Sanity: every region forwarded on every iteration (no silent drops).
        assertEquals(20L * (200 + n), sink.metrics.snapshot().forwardedRegions)
    }
}
