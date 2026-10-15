package com.gametranslator.enar

import com.gametranslator.enar.detection.OcrPipelineCoordinator
import com.gametranslator.enar.detection.OcrResultStabilizer
import com.gametranslator.enar.detection.RegionTracker
import com.gametranslator.enar.engine.OcrEngine
import com.gametranslator.enar.engine.TranslationEngine
import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.EngineMetadata
import com.gametranslator.enar.model.FrameBuffer
import com.gametranslator.enar.model.OcrResult
import com.gametranslator.enar.model.TextRegion
import com.gametranslator.enar.model.TranslationResult
import com.gametranslator.enar.pipeline.GameTranslatorPipeline
import com.gametranslator.enar.pipeline.Viewport
import com.gametranslator.enar.render.OverlayRegion
import com.gametranslator.enar.render.OverlaySink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration measurements for the Phase 7 pipeline (JVM dev machine —
 * ART/thermal behavior will differ; no end-to-end claims, component numbers
 * only):
 * 1. Synthetic end-to-end latency: changed-frame emission → overlay update,
 *    through the REAL coordinator (tracker + stabilizer) and the REAL
 *    pipeline (normalize → translate → overlay) with instant fakes at the
 *    model seams.
 * 2. Queue/backpressure accounting under a 10-region burst with one permit:
 *    launched/completed/skipped/stale counts and observed max concurrency.
 */
class TranslationPipelineBenchmarkTest {

    private class InstantOcr(var text: String) : OcrEngine {
        var boxes: List<BoundingBox> = emptyList()
        var detectCalls = 0
        var recognizeCalls = 0

        override suspend fun detectRegions(frame: FrameBuffer): List<TextRegion> {
            detectCalls++
            return boxes.mapIndexed { i, b -> TextRegion("det-$i", b, 0.9f) }
        }

        override suspend fun recognizeRegion(crop: FrameBuffer, region: TextRegion): OcrResult {
            recognizeCalls++
            return OcrResult(region.id, text, 0.9f, region.bounds, isStable = false)
        }

        override fun getEngineMetadata(): EngineMetadata =
            EngineMetadata("InstantOcr", "0", "TEST", false)
    }

    private class InstantNmt(private val delayMs: Long = 0L) : TranslationEngine {
        val calls = AtomicInteger(0)
        private val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        override suspend fun translate(
            text: String,
            targetBounds: BoundingBox,
            sourceLang: String,
            targetLang: String
        ): TranslationResult {
            calls.incrementAndGet()
            val c = concurrent.incrementAndGet()
            maxConcurrent.accumulateAndGet(c) { a, b -> maxOf(a, b) }
            try {
                if (delayMs > 0) delay(delayMs)
                return TranslationResult(
                    text, "AR $text", targetBounds, false, 1L,
                    engineMetadata = getEngineMetadata(),
                    protectedTokensRestored = true
                )
            } finally {
                concurrent.decrementAndGet()
            }
        }

        override fun isWarm(): Boolean = true

        override fun getEngineMetadata(): EngineMetadata =
            EngineMetadata("InstantNmt", "0", "TEST", false)
    }

    private class RecordingSink : OverlaySink {
        @Volatile
        var lastRegions: List<OverlayRegion> = emptyList()

        override fun update(regions: List<OverlayRegion>, densityScale: Float): Boolean {
            lastRegions = regions
            return true
        }

        override fun clear(): Boolean {
            lastRegions = emptyList()
            return true
        }

        override fun hide(): Boolean {
            lastRegions = emptyList()
            return true
        }
    }

    private fun frame() = FrameBuffer(
        width = 640, height = 480, stride = 2560,
        timestamp = System.nanoTime(), pixelFormat = 1,
        isChanged = true, changeLatencyNs = 0L
    )

    @Test
    fun `synthetic end-to-end latency`() = runBlocking {
        val ocr = InstantOcr("Save Game")
        val coordinator = OcrPipelineCoordinator(
            ocr, OcrResultStabilizer(), maxOcrFps = 1000, tracker = RegionTracker()
        )
        val nmt = InstantNmt()
        val sink = RecordingSink()
        val pipeline = GameTranslatorPipeline(
            translationEngine = nmt,
            overlaySink = sink,
            viewport = Viewport(640, 480, densityScale = 1f)
        )
        val frames = MutableStateFlow<FrameBuffer?>(null)
        coordinator.start(frames, this)
        pipeline.start(coordinator.ocrResults)

        val box = BoundingBox(10, 20, 300, 60)
        ocr.boxes = listOf(box)
        val n = 10
        val samplesNs = LongArray(n)
        repeat(n) { round ->
            // Fresh text per round (new revision each time) + 3 stable cycles.
            // NOTE: samples include harness pacing delays (~3×8 ms) — this
            // proves end-to-end liveness/order, it is NOT a component-latency
            // claim (real models dominate by orders of magnitude).
            ocr.text = "Save Game $round"
            val startNs = System.nanoTime()
            repeat(3) {
                frames.value = frame()
                Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
                delay(8)
            }
            val deadline = System.currentTimeMillis() + 10000
            while (sink.lastRegions.none { it.text.contains("AR Save Game $round") }) {
                if (System.currentTimeMillis() > deadline) {
                    throw AssertionError("round $round: overlay never updated")
                }
                delay(10)
            }
            samplesNs[round] = System.nanoTime() - startNs
            // Blank cycle between rounds (also exercises overlay removal).
            ocr.boxes = emptyList()
            frames.value = frame()
            Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
            delay(20)
            ocr.boxes = listOf(box)
        }

        samplesNs.sort()
        val meanNs = samplesNs.average()
        val p50Ns = samplesNs[(n * 0.50).toInt()]
        println(
            "BENCH_PIPELINE[e2e-frame-to-overlay] n=$n " +
                "mean_ns=${"%.1f".format(meanNs)} p50_ns=$p50Ns " +
                "p50_ms=${"%.2f".format(p50Ns / 1_000_000.0)}"
        )
        // Sanity bound only (fake instant models): proves the loop terminates
        // promptly end-to-end; NOT a performance claim for real models.
        assertTrue("e2e p50 ${p50Ns}ns exceeds 5 s sanity bound", p50Ns < 5_000_000_000L)

        pipeline.stop()
        coordinator.stop()
    }

    @Test
    fun `burst backpressure accounting`() = runBlocking {
        val ocr = InstantOcr("Burst text")
        val coordinator = OcrPipelineCoordinator(
            ocr, OcrResultStabilizer(), maxOcrFps = 1000, tracker = RegionTracker()
        )
        val nmt = InstantNmt(delayMs = 20)
        val sink = RecordingSink()
        val pipeline = GameTranslatorPipeline(
            translationEngine = nmt,
            overlaySink = sink,
            maxConcurrentTranslations = 1,
            viewport = Viewport(640, 480, densityScale = 1f)
        )
        val frames = MutableStateFlow<FrameBuffer?>(null)
        coordinator.start(frames, this)
        pipeline.start(coordinator.ocrResults)

        // 10 regions appear at once; stabilization needs 3 consistent cycles.
        ocr.boxes = List(10) { i -> BoundingBox(10, 20 + i * 50, 300, 60 + i * 50) }
        repeat(8) {
            frames.value = frame()
            Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
            delay(25)
        }
        val deadline = System.currentTimeMillis() + 20000
        while (pipeline.metrics.snapshot().completedTranslations < 10 &&
            System.currentTimeMillis() < deadline
        ) {
            frames.value = frame() // keep feeding: shed retries need cycles
            Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
            delay(25)
        }

        val snap = pipeline.metrics.snapshot()
        println(
            "BENCH_PIPELINE[burst-10-regions-1-permit] launched=${snap.launched} " +
                "completed=${snap.completedTranslations} skippedBusy=${snap.skippedBusy} " +
                "stale=${snap.staleDropped} maxConcurrent=${nmt.maxConcurrent.get()} " +
                "overlay=${snap.overlayRegions} cycles=${snap.cycles} " +
                "stable=${snap.stableReceived} tracked=${snap.trackedRegions} " +
                "inFlight=${snap.inFlight} detect=${ocr.detectCalls} recog=${ocr.recognizeCalls}"
        )
        assertTrue("all 10 must complete, got ${snap.completedTranslations}", snap.completedTranslations >= 10)
        assertTrue("permits respected", nmt.maxConcurrent.get() <= 1)
        assertTrue("overlay shows all 10", sink.lastRegions.size == 10)

        pipeline.stop()
        coordinator.stop()
    }
}
