package com.gametranslator.enar

import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.render.BidiUtils
import com.gametranslator.enar.render.CaptureGeometry
import com.gametranslator.enar.render.CoordinateMapper
import org.junit.Test

/**
 * JVM micro-measurement for the pure render-model hot path (coordinate mapping
 * + bidi analysis over synthetic OCR output). Measures only; claims nothing
 * comparative. Dev-machine numbers — ART will differ.
 */
class RenderModelBenchmarkTest {

    @Test
    fun `mapper plus bidi cost over synthetic ocr output`() {
        val mapper = CoordinateMapper(CaptureGeometry(1080, 2400, 1080, 2400))
        val boxes = List(20) { i ->
            BoundingBox(10 + i * 50, 100 + (i % 4) * 120, 210 + i * 50, 140 + (i % 4) * 120)
        }
        val texts = listOf(
            "مرحبا بك في اللعبة",
            "Press E to open the door.",
            "لديك 50 HP باق",
            "Score: 12500 pts (100%)",
            "Hello {playerName}, welcome back."
        )
        val n = 5000
        val samplesNs = LongArray(n)
        repeat(n) { k ->
            val startNs = System.nanoTime()
            for (i in boxes.indices) {
                val rect = mapper.toOverlay(boxes[i])
                val text = texts[(i + k) % texts.size]
                BidiUtils.baseDirection(text)
                BidiUtils.runCount(text)
                // Touch the values so nothing is dead-code eliminated.
                if (!rect.hasArea() || text.isEmpty()) throw AssertionError("unreachable")
            }
            samplesNs[k] = System.nanoTime() - startNs
        }

        samplesNs.sort()
        val meanNs = samplesNs.average()
        val p50Ns = samplesNs[(n * 0.50).toInt()]
        val p95Ns = samplesNs[(n * 0.95).toInt()]
        println(
            "RENDER_BENCH[mapper+bidi-20-regions] n=$n " +
                "mean_ns=${"%.1f".format(meanNs)} p50_ns=$p50Ns p95_ns=$p95Ns"
        )
    }
}
