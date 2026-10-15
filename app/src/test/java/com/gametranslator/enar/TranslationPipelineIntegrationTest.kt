package com.gametranslator.enar

import com.gametranslator.enar.detection.OcrPipelineCoordinator
import com.gametranslator.enar.detection.OcrResultStabilizer
import com.gametranslator.enar.detection.RegionTracker
import com.gametranslator.enar.engine.CachedTranslationEngine
import com.gametranslator.enar.engine.DictionaryTranslationEngine
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end integration tests over REAL components (only the model runtimes
 * are scripted): fake capture flow → [OcrPipelineCoordinator] (tracker +
 * stabilizer) → [GameTranslatorPipeline] → REAL [CachedTranslationEngine]
 * (LRU + normalizer + protector) → fake delegate/model → recording sink.
 *
 * Nothing under test is faked: cache keys, admission, restoration, track ids,
 * and stabilization windows are all exercised for real.
 */
class TranslationPipelineIntegrationTest {

    private fun box(l: Int, t: Int, r: Int, b: Int) = BoundingBox(l, t, r, b)

    private class ScriptOcrEngine : OcrEngine {
        var detections: List<TextRegion> = emptyList()
        var texts: MutableMap<String, String> = mutableMapOf()
        var recognizeCalls = 0

        override suspend fun detectRegions(frame: FrameBuffer): List<TextRegion> = detections

        override suspend fun recognizeRegion(crop: FrameBuffer, region: TextRegion): OcrResult {
            recognizeCalls++
            return OcrResult(
                regionId = region.id,
                rawText = texts[region.id] ?: "TEXT",
                confidence = 0.9f,
                bounds = region.bounds,
                isStable = false
            )
        }

        override fun getEngineMetadata(): EngineMetadata =
            EngineMetadata("Script", "0", "TEST", false)
    }

    /** Echoing stand-in for the NMT model (copies masked text like the real one). */
    private class EchoModel(var flag: Boolean = true) : TranslationEngine {
        val calls = AtomicInteger(0)

        override suspend fun translate(
            text: String,
            targetBounds: BoundingBox,
            sourceLang: String,
            targetLang: String
        ): TranslationResult {
            calls.incrementAndGet()
            return TranslationResult(
                originalText = text,
                translatedArabicText = "AR $text",
                bounds = targetBounds,
                fromCache = false,
                latencyMs = 1L,
                engineMetadata = getEngineMetadata(),
                protectedTokensRestored = flag
            )
        }

        override fun isWarm(): Boolean = true

        override fun getEngineMetadata(): EngineMetadata =
            EngineMetadata("Echo", "0", "TEST", false)
    }

    private class RecordingSink : OverlaySink {
        @Volatile
        var updates = 0
        @Volatile
        var lastRegions: List<OverlayRegion> = emptyList()

        override fun update(regions: List<OverlayRegion>, densityScale: Float): Boolean {
            updates++
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

    private fun waitFor(timeoutMs: Long = 10000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out waiting for condition")
            }
            Thread.sleep(10)
        }
    }

    private data class Harness(
        val coordinator: OcrPipelineCoordinator,
        val pipeline: GameTranslatorPipeline,
        val frames: MutableStateFlow<FrameBuffer?>,
        val ocr: ScriptOcrEngine,
        val model: EchoModel,
        val sink: RecordingSink
    ) {
        fun emitFrame(detections: List<TextRegion>) {
            ocr.detections = detections
            frames.value = FrameBuffer(
                width = 640, height = 480, stride = 2560,
                timestamp = System.nanoTime(), pixelFormat = 1,
                isChanged = true, changeLatencyNs = 0L
            )
        }
    }

    private fun harness(
        rescanInterval: Int = 1,
        modelFlag: Boolean = true
    ): Harness {
        val ocr = ScriptOcrEngine()
        val coordinator = OcrPipelineCoordinator(
            ocr,
            OcrResultStabilizer(),
            maxOcrFps = 1000,
            tracker = RegionTracker(),
            ocrRescanIntervalCycles = rescanInterval
        )
        val model = EchoModel(modelFlag)
        val cached = CachedTranslationEngine(model)
        val sink = RecordingSink()
        val pipeline = GameTranslatorPipeline(
            translationEngine = cached,
            overlaySink = sink,
            viewport = Viewport(640, 480, densityScale = 1f)
        )
        return Harness(coordinator, pipeline, MutableStateFlow(null), ocr, model, sink)
    }

    @Test
    fun `fake capture to overlay with protected values restored`() = runBlocking {
        val h = harness()
        h.coordinator.start(h.frames, this)
        h.pipeline.start(h.coordinator.ocrResults)

        // One dialogue box, same content: 4 OCR cycles → stable → translate → overlay.
        // (Texts keyed by TRACK id, set before emitting: recognition sees track ids.)
        h.ocr.texts["track-0"] = "You have 50 HP!"
        repeat(4) {
            h.emitFrame(listOf(TextRegion("e$it", box(10, 20, 300, 60), 0.9f)))
            delay(20)
        }
        println("DEBUG-INT ocrResults=" + h.coordinator.ocrResults.value)
        println("DEBUG-INT snap=" + h.pipeline.metrics.snapshot() + " modelCalls=" + h.model.calls.get())
        waitFor { h.sink.lastRegions.isNotEmpty() }

        val shown = h.sink.lastRegions.single()
        assertEquals("track-0", shown.id) // tracker id end-to-end
        assertTrue("protected value must restore, got '${shown.text}'", shown.text.contains("50 HP"))
        assertFalse("no sentinel residue may leak", Regex("98\\d{4}").containsMatchIn(shown.text))
        // Overlay bounds follow the OCR box 1:1 in this viewport.
        assertEquals(10f, shown.boundsPx.left, 0.01f)

        h.pipeline.stop()
        h.coordinator.stop()
    }

    @Test
    fun `rescan policy holds end-to-end with single recognition`() = runBlocking {
        val h = harness(rescanInterval = 10)
        h.coordinator.start(h.frames, this)
        h.pipeline.start(h.coordinator.ocrResults)
        h.ocr.texts["track-0"] = "Save Game"
        // First window-fill (3 cycles) recognizes each time; cycles 4-8 skip.
        repeat(8) {
            h.emitFrame(listOf(TextRegion("e$it", box(10, 20, 300, 60), 0.9f)))
            delay(20)
        }
        waitFor { h.sink.lastRegions.isNotEmpty() }

        assertEquals(3, h.ocr.recognizeCalls) // 3 to fill the window, then re-observe
        assertTrue(h.sink.lastRegions[0].text.isNotBlank())

        h.pipeline.stop()
        h.coordinator.stop()
    }

    @Test
    fun `ocr jitter never reaches translation`() = runBlocking {
        val h = harness()
        h.coordinator.start(h.frames, this)
        h.pipeline.start(h.coordinator.ocrResults)

        // Flapping text through the REAL stabilizer: never 3-consistent.
        // (Text set BEFORE emitting each frame — the worker reads track ids.)
        val variants = listOf("Save", "Save Ga", "Save", "Save Gam", "Save", "Save Game!")
        for ((i, v) in variants.withIndex()) {
            h.ocr.texts["track-0"] = v
            h.emitFrame(listOf(TextRegion("e$i", box(10, 20, 300, 60), 0.9f)))
            delay(20)
        }
        Thread.sleep(300)

        assertEquals(0, h.model.calls.get())
        assertTrue(h.sink.lastRegions.isEmpty())

        h.pipeline.stop()
        h.coordinator.stop()
    }

    @Test
    fun `flag-false model output suppressed end-to-end`() = runBlocking {
        val h = harness(modelFlag = false) // simulates sentinel-loss retry output
        h.coordinator.start(h.frames, this)
        h.pipeline.start(h.coordinator.ocrResults)
        h.ocr.texts["track-0"] = "You have 50 HP!"

        repeat(4) {
            h.emitFrame(listOf(TextRegion("e$it", box(10, 20, 300, 60), 0.9f)))
            delay(20)
        }
        Thread.sleep(400)

        assertTrue("model ran (spans translated)", h.model.calls.get() > 0)
        assertTrue("unsafe output must not overlay", h.sink.lastRegions.isEmpty())
        assertTrue(h.pipeline.metrics.snapshot().unverifiedSuppressed >= 1)

        h.pipeline.stop()
        h.coordinator.stop()
    }

    @Test
    fun `dictionary engine serves known phrases end-to-end`() = runBlocking {
        val ocr = ScriptOcrEngine()
        val coordinator = OcrPipelineCoordinator(
            ocr, OcrResultStabilizer(), maxOcrFps = 1000, tracker = RegionTracker()
        )
        val sink = RecordingSink()
        val pipeline = GameTranslatorPipeline(
            translationEngine = CachedTranslationEngine(DictionaryTranslationEngine()),
            overlaySink = sink,
            viewport = Viewport(640, 480, densityScale = 1f)
        )
        val frames = MutableStateFlow<FrameBuffer?>(null)
        coordinator.start(frames, this)
        pipeline.start(coordinator.ocrResults)
        ocr.texts["track-0"] = "Save Game"

        repeat(4) {
            ocr.detections = listOf(TextRegion("e$it", box(10, 20, 300, 60), 0.9f))
            frames.value = frame()
            Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
            delay(20)
        }
        waitFor { sink.lastRegions.isNotEmpty() }

        assertEquals("حفظ اللعبة", sink.lastRegions.single().text)

        pipeline.stop()
        coordinator.stop()
    }
}
