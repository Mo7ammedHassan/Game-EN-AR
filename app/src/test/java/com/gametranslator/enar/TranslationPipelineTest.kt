package com.gametranslator.enar

import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.EngineMetadata
import com.gametranslator.enar.model.OcrResult
import com.gametranslator.enar.model.TranslationResult
import com.gametranslator.enar.engine.TranslationEngine
import com.gametranslator.enar.pipeline.GameTranslatorPipeline
import com.gametranslator.enar.pipeline.Viewport
import com.gametranslator.enar.render.OverlayRegion
import com.gametranslator.enar.render.OverlaySink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Core tests for [GameTranslatorPipeline]: revision/supersession/stale-drop
 * semantics, bounded backpressure, publish gate, disappearance, lifecycle,
 * and failure isolation — with scripted fake engines (never the logic under
 * test) and a recording sink.
 */
class TranslationPipelineTest {

    private val boundsA = BoundingBox(10, 20, 300, 60)
    private val boundsB = BoundingBox(10, 200, 300, 240)

    private fun ocr(id: String, text: String, stable: Boolean, bounds: BoundingBox = boundsA) =
        OcrResult(
            regionId = id,
            rawText = text,
            confidence = 0.9f,
            bounds = bounds,
            isStable = stable
        )

    /** Instant scripted engine with full call/concurrency accounting. */
    private open class ScriptEngine(
        var warm: Boolean = true,
        var flag: Boolean = true,
        var delayMs: Long = 0L,
        var fail: Boolean = false,
        var transform: (String) -> String = { "AR[$it]" }
    ) : TranslationEngine {
        val calls = AtomicInteger(0)
        val inputs = Collections.synchronizedList(mutableListOf<String>())
        private val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        override suspend fun translate(
            text: String,
            targetBounds: BoundingBox,
            sourceLang: String,
            targetLang: String
        ): TranslationResult {
            calls.incrementAndGet()
            inputs.add(text)
            val c = concurrent.incrementAndGet()
            maxConcurrent.accumulateAndGet(c) { a, b -> maxOf(a, b) }
            try {
                if (delayMs > 0) delay(delayMs)
                if (fail) throw RuntimeException("nmt boom for $text")
                return TranslationResult(
                    originalText = text,
                    translatedArabicText = transform(text),
                    bounds = targetBounds,
                    fromCache = false,
                    latencyMs = 1L,
                    engineMetadata = EngineMetadata("Script", "0", "TEST", false),
                    protectedTokensRestored = flag
                )
            } finally {
                concurrent.decrementAndGet()
            }
        }

        override fun isWarm(): Boolean = warm

        override fun getEngineMetadata(): EngineMetadata =
            EngineMetadata("Script", "0", "TEST", false)
    }

    /** Gate-controlled engine for supersession/stale timing tests. */
    private class GateEngine(var flag: Boolean = true) : TranslationEngine {
        val gates = ConcurrentHashMap<String, CompletableDeferred<String>>()
        val calls = AtomicInteger(0)

        override suspend fun translate(
            text: String,
            targetBounds: BoundingBox,
            sourceLang: String,
            targetLang: String
        ): TranslationResult {
            calls.incrementAndGet()
            val out = gates.getOrPut(text) { CompletableDeferred() }.await()
            return TranslationResult(
                originalText = text,
                translatedArabicText = out,
                bounds = targetBounds,
                fromCache = false,
                latencyMs = 1L,
                engineMetadata = getEngineMetadata(),
                protectedTokensRestored = flag
            )
        }

        override fun isWarm(): Boolean = true

        override fun getEngineMetadata(): EngineMetadata =
            EngineMetadata("Gate", "0", "TEST", false)
    }

    private class RecordingSink(var failOnUpdate: Boolean = false) : OverlaySink {
        @Volatile
        var updates = 0
        @Volatile
        var hides = 0
        @Volatile
        var lastRegions: List<OverlayRegion> = emptyList()

        override fun update(regions: List<OverlayRegion>, densityScale: Float): Boolean {
            if (failOnUpdate) throw RuntimeException("overlay boom")
            updates++
            lastRegions = regions
            return true
        }

        override fun clear(): Boolean {
            lastRegions = emptyList()
            return true
        }

        override fun hide(): Boolean {
            hides++
            lastRegions = emptyList()
            return true
        }
    }

    // Owned single-thread worker + pool (production threading); all
    // assertions poll — nothing assumes inline execution.
    private fun pipeline(
        engine: TranslationEngine,
        sink: RecordingSink,
        permits: Int = 1
    ) = GameTranslatorPipeline(
        translationEngine = engine,
        overlaySink = sink,
        maxConcurrentTranslations = permits,
        viewport = Viewport(1080, 2400, densityScale = 1f)
    )

    private fun waitFor(timeoutMs: Long = 8000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for condition")
            Thread.sleep(10)
        }
    }

    /** waitFor that reports the pipeline snapshot on timeout (diagnoses stuck stage). */
    private fun waitFor(
        pipe: GameTranslatorPipeline,
        timeoutMs: Long = 15000,
        cond: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for condition; snapshot=" + pipe.metrics.snapshot())
            }
            Thread.sleep(10)
        }
    }

    // ---- basic flow ----

    @Test
    fun `stable result translates and overlays`() = runBlocking {
        val engine = ScriptEngine()
        val sink = RecordingSink()
        val flow = MutableStateFlow<List<OcrResult>>(emptyList())
        val pipe = pipeline(engine, sink)
        pipe.start(flow)
        Thread.sleep(200) // let the owned worker start collecting (no conflation loss)

        flow.value = listOf(ocr("r1", "Save Game", true))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { sink.lastRegions.size == 1 }

        assertEquals(1, engine.calls.get())
        assertEquals("AR[Save Game]", sink.lastRegions[0].text)
        assertEquals("r1", sink.lastRegions[0].id)
        val snap = pipe.metrics.snapshot()
        assertEquals(1, snap.launched)
        assertEquals(1, snap.completedTranslations)
        assertEquals(1, snap.overlayUpdates)
        pipe.stop()
    }

    @Test
    fun `unstable results never launch translation`() {
        val engine = ScriptEngine()
        val sink = RecordingSink()
        val flow = MutableStateFlow<List<OcrResult>>(emptyList())
        val pipe = pipeline(engine, sink)
        pipe.start(flow)
        Thread.sleep(200) // let the owned worker start collecting (no conflation loss)

        flow.value = listOf(ocr("r1", "Save", false))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        flow.value = listOf(ocr("r1", "Save Ga", false))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        flow.value = listOf(ocr("r1", "Save Gam", false))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        Thread.sleep(200)

        assertEquals(0, engine.calls.get())
        assertEquals(0, pipe.metrics.snapshot().launched)
        assertTrue(sink.lastRegions.isEmpty())
        pipe.stop()
    }

    @Test
    fun `duplicate stable text translates once`() = runBlocking {
        val engine = ScriptEngine()
        val sink = RecordingSink()
        val flow = MutableStateFlow<List<OcrResult>>(emptyList())
        val pipe = pipeline(engine, sink)
        pipe.start(flow)
        Thread.sleep(200) // let the owned worker start collecting (no conflation loss)

        val stable = listOf(ocr("r1", "Save Game", true))
        flow.value = stable
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { sink.lastRegions.isNotEmpty() }
        // NOTE: StateFlow conflates structurally-equal emissions, so an
        // unrelated companion region forces the re-emissions below.
        flow.value = stable + ocr("r2", "x", false)
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        flow.value = stable
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { pipe.metrics.snapshot().cycles >= 3 }
        Thread.sleep(200)

        assertEquals(1, engine.calls.get())
        pipe.stop()
    }

    // ---- supersession & staleness ----

    @Test
    fun `changed text supersedes in-flight, stale dropped, latest wins`() {
        val engine = GateEngine()
        val sink = RecordingSink()
        val flow = MutableStateFlow<List<OcrResult>>(emptyList())
        val pipe = pipeline(engine, sink)
        pipe.start(flow)
        Thread.sleep(200) // let the owned worker start collecting (no conflation loss)

        flow.value = listOf(ocr("r1", "Hello", true)) // rev 1, launched, gated
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { engine.calls.get() == 1 }
        flow.value = listOf(ocr("r1", "Hello!", true)) // rev 2, supersedes (pending)
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { pipe.metrics.snapshot().supersededMarked >= 1 }

        engine.gates["Hello"]!!.complete("AR-OLD")
        // Stale rev-1 completion drops AND immediately launches rev-2.
        waitFor(pipe) { engine.calls.get() == 2 }
        engine.gates["Hello!"]!!.complete("AR-NEW")
        waitFor(pipe) { sink.lastRegions.any { it.text == "AR-NEW" } }

        assertEquals("AR-NEW", sink.lastRegions.single().text)
        val snap = pipe.metrics.snapshot()
        assertEquals(1, snap.staleDropped)
        assertEquals(1, snap.completedTranslations) // only rev-2 counts as completed
        assertTrue(sink.lastRegions.none { it.text == "AR-OLD" })
        pipe.stop()
    }

    @Test
    fun `removed region drops late completion`() {
        val engine = GateEngine()
        val sink = RecordingSink()
        val flow = MutableStateFlow<List<OcrResult>>(emptyList())
        val pipe = pipeline(engine, sink)
        pipe.start(flow)
        Thread.sleep(200) // let the owned worker start collecting (no conflation loss)

        flow.value = listOf(ocr("r1", "Hello", true))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { engine.calls.get() == 1 }
        flow.value = emptyList() // region gone before completion
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        // Nothing was ever published, so there is nothing to count as removed;
        // the late completion must simply drop (stale) without overlaying.
        engine.gates["Hello"]!!.complete("AR-LATE")

        Thread.sleep(200)
        assertTrue(sink.lastRegions.isEmpty())
        assertEquals(1, pipe.metrics.snapshot().staleDropped)
        pipe.stop()
    }

    // ---- backpressure ----

    @Test
    fun `busy permits shed without queue or overlap`() {
        val engine = ScriptEngine(delayMs = 80)
        val sink = RecordingSink()
        val flow = MutableStateFlow<List<OcrResult>>(emptyList())
        val pipe = pipeline(engine, sink, permits = 1)
        pipe.start(flow)

        val batch = (0 until 5).map { ocr("r$it", "Text number $it", true) }
        flow.value = batch
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { engine.calls.get() == 1 }

        // Only one flight; the rest shed (no queue forms).
        waitFor(pipe) { pipe.metrics.snapshot().skippedBusy >= 4 }
        assertEquals(1, engine.calls.get())
        assertTrue(engine.maxConcurrent.get() <= 1)

        // Re-emitting retries the shed latest texts until all complete.
        // (Alternate with a perturbed emission: StateFlow conflates equal lists.)
        repeat(12) {
            flow.value = batch + ocr("rx", "z-$it", false)
            Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
            Thread.sleep(30)
            flow.value = batch
            Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
            Thread.sleep(60)
        }
        waitFor(15000) { pipe.metrics.snapshot().completedTranslations >= 5 }
        waitFor(pipe) { sink.lastRegions.size == 5 }
        assertTrue(engine.maxConcurrent.get() <= 1)
        pipe.stop()
    }

    // ---- publish gate & failures ----

    @Test
    fun `translation failure keeps previous and recovers`() {
        val engine = ScriptEngine(fail = true)
        val sink = RecordingSink()
        val flow = MutableStateFlow<List<OcrResult>>(emptyList())
        val pipe = pipeline(engine, sink)
        pipe.start(flow)
        Thread.sleep(200) // let the owned worker start collecting (no conflation loss)

        flow.value = listOf(ocr("r1", "Save Game", true))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { pipe.metrics.snapshot().translationFailures >= 1 }
        assertTrue(sink.lastRegions.isEmpty())

        engine.fail = false // transient passes; next stable cycle retries
        // Companion forces reprocessing: identical content would conflate.
        flow.value = listOf(ocr("r1", "Save Game", true), ocr("r2", "x", false))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { sink.lastRegions.isNotEmpty() }
        assertEquals("AR[Save Game]", sink.lastRegions[0].text)
        assertTrue(pipe.isRunning())
        pipe.stop()
    }

    @Test
    fun `flag-false with spans is suppressed, never shown`() {
        val engine = ScriptEngine(flag = false)
        val sink = RecordingSink()
        val flow = MutableStateFlow<List<OcrResult>>(emptyList())
        val pipe = pipeline(engine, sink)
        pipe.start(flow)
        Thread.sleep(200) // let the owned worker start collecting (no conflation loss)

        flow.value = listOf(ocr("r1", "You have 50 HP!", true))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { engine.calls.get() == 1 }
        Thread.sleep(200)

        assertTrue("unsafe output must not reach overlay", sink.lastRegions.isEmpty())
        assertEquals(1, pipe.metrics.snapshot().unverifiedSuppressed)
        pipe.stop()
    }

    @Test
    fun `flag-false without spans is published`() = runBlocking {
        val engine = ScriptEngine(flag = false) // plain text: vacuous safety
        val sink = RecordingSink()
        val flow = MutableStateFlow<List<OcrResult>>(emptyList())
        val pipe = pipeline(engine, sink)
        pipe.start(flow)
        Thread.sleep(200) // let the owned worker start collecting (no conflation loss)

        flow.value = listOf(ocr("r1", "Save Game", true))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { sink.lastRegions.isNotEmpty() }
        assertEquals("AR[Save Game]", sink.lastRegions[0].text)
        pipe.stop()
    }

    @Test
    fun `retry pump launches without new emissions`() {
        val engine = ScriptEngine(warm = false)
        val sink = RecordingSink()
        val flow = MutableStateFlow<List<OcrResult>>(emptyList())
        val pipe = pipeline(engine, sink)
        pipe.start(flow)
        Thread.sleep(200) // let the owned worker start collecting (no conflation loss)

        flow.value = listOf(ocr("r1", "Save Game", true))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { pipe.metrics.snapshot().coldSkipped >= 1 }
        assertEquals(0, engine.calls.get())

        engine.warm = true
        // Deliberately NO further emission: StateFlow would conflate static
        // content anyway. The 200 ms retry pump must still launch.
        waitFor(pipe) { sink.lastRegions.isNotEmpty() }

        assertEquals("AR[Save Game]", sink.lastRegions[0].text)
        assertTrue(pipe.metrics.snapshot().retryLaunches >= 1)
        pipe.stop()
    }

    // ---- disappearance & bounds ----

    @Test
    fun `disappearance removes overlay`() = runBlocking {
        val engine = ScriptEngine()
        val sink = RecordingSink()
        val flow = MutableStateFlow<List<OcrResult>>(emptyList())
        val pipe = pipeline(engine, sink)
        pipe.start(flow)
        Thread.sleep(200) // let the owned worker start collecting (no conflation loss)

        flow.value = listOf(ocr("r1", "Save Game", true))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { sink.lastRegions.isNotEmpty() }
        flow.value = emptyList()
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { sink.hides >= 1 }

        assertTrue(sink.lastRegions.isEmpty())
        assertEquals(1, pipe.metrics.snapshot().regionsRemoved)
        pipe.stop()
    }

    @Test
    fun `empty and blank inputs skipped`() {
        val engine = ScriptEngine()
        val sink = RecordingSink()
        val flow = MutableStateFlow<List<OcrResult>>(emptyList())
        val pipe = pipeline(engine, sink)
        pipe.start(flow)
        Thread.sleep(200) // let the owned worker start collecting (no conflation loss)

        flow.value = listOf(ocr("r1", "", true))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        flow.value = listOf(ocr("r1", "   ", true))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        Thread.sleep(200)

        assertEquals(0, engine.calls.get())
        assertEquals(2, pipe.metrics.snapshot().blankSkipped)
        pipe.stop()
    }

    @Test
    fun `small box jitter does not rebuild overlay, big move does`() = runBlocking {
        val engine = ScriptEngine()
        val sink = RecordingSink()
        val flow = MutableStateFlow<List<OcrResult>>(emptyList())
        val pipe = pipeline(engine, sink)
        pipe.start(flow)
        Thread.sleep(200) // let the owned worker start collecting (no conflation loss)

        val box = BoundingBox(10, 20, 300, 60)
        flow.value = listOf(ocr("r1", "Save Game", true, box))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { sink.lastRegions.isNotEmpty() }
        assertEquals(1, sink.updates)

        // +2 px jitter: same text, snapped bounds → no overlay rebuild.
        flow.value = listOf(ocr("r1", "Save Game", true, BoundingBox(12, 22, 302, 62)))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        Thread.sleep(200)
        assertEquals(1, sink.updates)
        assertEquals(10f, sink.lastRegions[0].boundsPx.left, 0.01f)

        // +50 px move: overlay follows with fresh bounds.
        flow.value = listOf(ocr("r1", "Save Game", true, BoundingBox(60, 20, 350, 60)))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { sink.updates >= 2 }
        assertEquals(60f, sink.lastRegions[0].boundsPx.left, 0.01f)
        // No re-translation for pure moves.
        assertEquals(1, engine.calls.get())
        pipe.stop()
    }

    // ---- lifecycle ----

    @Test
    fun `stop clears overlay and blocks late callbacks`() {
        val engine = GateEngine()
        val sink = RecordingSink()
        val flow = MutableStateFlow<List<OcrResult>>(emptyList())
        val pipe = pipeline(engine, sink)
        pipe.start(flow)
        Thread.sleep(200) // let the owned worker start collecting (no conflation loss)

        flow.value = listOf(ocr("r1", "Hello", true))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { engine.calls.get() == 1 }
        pipe.stop()

        assertFalse(pipe.isRunning())
        assertEquals(1, sink.hides)
        engine.gates["Hello"]!!.complete("AR-LATE")
        Thread.sleep(200)
        assertTrue("late completion must never touch overlay", sink.lastRegions.isEmpty())
    }

    @Test
    fun `rapid start stop cycles do not leak or break`() {
        val engine = ScriptEngine()
        val sink = RecordingSink()
        val flow = MutableStateFlow<List<OcrResult>>(emptyList())
        val pipe = pipeline(engine, sink)

        repeat(10) {
            assertTrue(pipe.start(flow))
            assertFalse(pipe.start(flow)) // double-start rejected
            pipe.stop()
            pipe.stop() // double-stop safe
        }
        assertFalse(pipe.isRunning())

        // Still fully functional afterwards.
        pipe.start(flow)
        flow.value = listOf(ocr("r1", "Save Game", true))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { sink.lastRegions.isNotEmpty() }
        pipe.stop()
    }

    @Test
    fun `sink failure isolated, pipeline survives and retries`() {
        val engine = ScriptEngine()
        val sink = RecordingSink(failOnUpdate = true)
        val flow = MutableStateFlow<List<OcrResult>>(emptyList())
        val pipe = pipeline(engine, sink)
        pipe.start(flow)
        Thread.sleep(200) // let the owned worker start collecting (no conflation loss)

        flow.value = listOf(ocr("r1", "Save Game", true))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { pipe.metrics.snapshot().overlayFailures >= 1 }

        sink.failOnUpdate = false
        // Companion forces reprocessing: identical content would conflate.
        flow.value = listOf(ocr("r1", "Save Game", true), ocr("r2", "x", false))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { sink.lastRegions.isNotEmpty() }
        assertEquals("AR[Save Game]", sink.lastRegions[0].text)
        pipe.stop()
    }

    // ---- viewport & metrics ----

    @Test
    fun `viewport update remaps overlay coordinates`() = runBlocking {
        val engine = ScriptEngine()
        val sink = RecordingSink()
        val flow = MutableStateFlow<List<OcrResult>>(emptyList())
        val pipe = pipeline(engine, sink)
        pipe.start(flow)
        Thread.sleep(200) // let the owned worker start collecting (no conflation loss)

        flow.value = listOf(ocr("r1", "Save Game", true, BoundingBox(10, 20, 110, 40)))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { sink.lastRegions.isNotEmpty() }
        assertEquals(10f, sink.lastRegions[0].boundsPx.left, 0.01f)

        // Double the DISPLAY size while capture stays 1080x2400: scale becomes 2.
        pipe.updateViewport(Viewport(2160, 4800, 1080, 2400, densityScale = 1f))
        // Nudge beyond snap so bounds refresh under the new mapper.
        flow.value = listOf(ocr("r1", "Save Game", true, BoundingBox(70, 20, 170, 40)))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { sink.lastRegions[0].boundsPx.left > 100f }
        assertEquals(140f, sink.lastRegions[0].boundsPx.left, 0.01f)
        pipe.stop()
    }

    @Test
    fun `metrics snapshot is internally consistent`() = runBlocking {
        val engine = ScriptEngine()
        val sink = RecordingSink()
        val flow = MutableStateFlow<List<OcrResult>>(emptyList())
        val pipe = pipeline(engine, sink)
        pipe.start(flow)
        Thread.sleep(200) // let the owned worker start collecting (no conflation loss)

        flow.value = listOf(
            ocr("r1", "Save Game", true),
            ocr("r2", "working", false)
        )
        waitFor(pipe) { sink.lastRegions.isNotEmpty() }
        val snap = pipe.metrics.snapshot()

        assertEquals(1, snap.launched)
        assertEquals(1, snap.completedTranslations)
        assertEquals(0, snap.staleDropped)
        assertEquals(0, snap.inFlight)
        assertEquals(2, snap.trackedRegions) // r1 stable + r2 unstable both tracked
        assertEquals(1, snap.overlayRegions)
        assertTrue(snap.lastEndToEndNs >= 0)
        pipe.stop()
    }

    @Test
    fun `cache hit observed without delegate translation`() {
        val delegate = ScriptEngine(flag = true)
        val cached = com.gametranslator.enar.engine.CachedTranslationEngine(delegate)
        val sink = RecordingSink()
        val flow = MutableStateFlow<List<OcrResult>>(emptyList())
        val pipe = pipeline(cached, sink)
        pipe.start(flow)

        // Visit "Score 50" (miss), move away, then revisit: the revisit has a
        // NEW revision (so the pipeline launches) but the SAME normalized key
        // (so the cache serves it without the delegate). A byte-identical
        // repeat would correctly no-op instead — no hit is observable then.
        flow.value = listOf(ocr("r1", "Score   50", true))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { sink.lastRegions.any { it.text.contains("Score 50") } }
        flow.value = listOf(ocr("r1", "Score 51", true))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { sink.lastRegions.any { it.text.contains("Score 51") } }
        flow.value = listOf(ocr("r1", "Score  50", true))
        Thread.sleep(150) // pacing: worker drains each emission (no conflation loss)
        waitFor(pipe) { sink.lastRegions.any { it.text == "AR[Score 50]" } }

        assertEquals(2, delegate.calls.get()) // third visit served from cache
        assertEquals(1, pipe.metrics.snapshot().cacheHitsObserved)
        pipe.stop()
    }
}
