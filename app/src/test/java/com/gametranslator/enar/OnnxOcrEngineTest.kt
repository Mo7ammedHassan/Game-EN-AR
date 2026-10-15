package com.gametranslator.enar

import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.FrameBuffer
import com.gametranslator.enar.model.OcrResult
import com.gametranslator.enar.model.TextRegion
import com.gametranslator.enar.ocr.DetOutput
import com.gametranslator.enar.ocr.DetSession
import com.gametranslator.enar.ocr.OcrSnapshot
import com.gametranslator.enar.ocr.OcrSessionProvider
import com.gametranslator.enar.ocr.OnnxOcrEngine
import com.gametranslator.enar.ocr.RecOutput
import com.gametranslator.enar.ocr.RecSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9 engine tests: cold/no-snapshot degradation, detection mapping +
 * sorting + filtering, recognition decode + score gate, malformed-output
 * isolation, lifecycle. Inference is scripted through fakes (no natives).
 */
class OnnxOcrEngineTest {

    private class FakeDet(
        var output: DetOutput? = null,
        var throwIt: Boolean = false
    ) : DetSession {
        var calls = 0
        var lastDims = Triple(0, 0, 0)
        override fun run(input: FloatArray, batch: Int, channels: Int, height: Int, width: Int): DetOutput {
            calls++
            lastDims = Triple(batch, height, width)
            if (throwIt) throw RuntimeException("det boom")
            return output ?: throw IllegalStateException("no scripted det output")
        }

        override fun close() {}
    }

    private class FakeRec(
        var output: RecOutput? = null,
        var throwIt: Boolean = false
    ) : RecSession {
        var calls = 0
        var lastWidth = 0
        override fun run(input: FloatArray, batch: Int, channels: Int, height: Int, width: Int): RecOutput {
            calls++
            lastWidth = width
            if (throwIt) throw RuntimeException("rec boom")
            return output ?: throw IllegalStateException("no scripted rec output")
        }

        override fun close() {}
    }

    private class FakeProvider(
        val det: FakeDet = FakeDet(),
        val rec: FakeRec = FakeRec(),
        var dict: List<String> = listOf("a", "b"),
        var loadThrow: Boolean = false
    ) : OcrSessionProvider {
        private var loaded = false
        var releases = 0
        override fun load() {
            if (loadThrow) throw IllegalStateException("assets missing")
            loaded = true
        }

        override fun isLoaded(): Boolean = loaded
        override fun detector(): DetSession = det
        override fun recognizer(): RecSession = rec
        override fun characters(): List<String> = dict
        override fun release() {
            releases++
            loaded = false
        }
    }

    private fun frame(w: Int = 200, h: Int = 100) =
        FrameBuffer(w, h, w * 4, 0L, 1, isChanged = true)

    private fun snap(w: Int = 100, h: Int = 50): OcrSnapshot =
        OcrSnapshot(w, h, ByteArray(w * h * 3) { 200.toByte() }, 200, 100, 7L)

    /** Solid-hot prob map over rect (x0..x1, y0..y1) in a WxH map. */
    private fun hotMap(w: Int, h: Int, boxes: List<IntArray>, v: Float = 0.95f): DetOutput {
        val prob = FloatArray(w * h)
        for (b in boxes) {
            for (y in b[1]..b[3]) {
                for (x in b[0]..b[2]) prob[y * w + x] = v
            }
        }
        return DetOutput(prob, h, w)
    }

    /** Logits emitting class sequence (blank=0; dict a=1,b=2,space=3). */
    private fun seqLogits(seq: List<Int>, classes: Int = 4): RecOutput {
        val out = FloatArray(seq.size * classes) { 0.01f }
        seq.forEachIndexed { t, c -> out[t * classes + c] = 0.9f }
        return RecOutput(out, 1, seq.size, classes)
    }

    // ---- Lifecycle / degradation ----

    @Test
    fun `cold engine degrades to empty without throwing`() = runBlocking {
        val provider = FakeProvider(loadThrow = true)
        val engine = OnnxOcrEngine({ snap() }, provider)
        engine.loadModels() // throws internally → cold
        assertFalse(engine.isWarm())
        assertTrue(engine.detectRegions(frame()).isEmpty())
        val r = TextRegion("x", BoundingBox(0, 0, 10, 10), 1f)
        assertEquals("", engine.recognizeRegion(frame(), r).rawText)
        assertTrue(engine.getEngineMetadata().executionBackend.contains("NONE"))
    }

    @Test
    fun `no snapshot degrades to empty`() = runBlocking {
        val engine = OnnxOcrEngine({ null }, FakeProvider())
        engine.loadModels()
        assertTrue(engine.isWarm())
        assertTrue(engine.detectRegions(frame()).isEmpty())
    }

    @Test
    fun `release is idempotent and cools the engine`() = runBlocking {
        val provider = FakeProvider()
        val engine = OnnxOcrEngine({ snap() }, provider)
        engine.loadModels()
        assertTrue(engine.isWarm())
        engine.release()
        engine.release()
        assertEquals(2, provider.releases)
        assertFalse(engine.isWarm())
        assertTrue(engine.detectRegions(frame()).isEmpty())
    }

    // ---- Detection ----

    @Test
    fun `detect maps snapshot boxes to frame coords with ids and scores`() = runBlocking {
        // Snapshot 100x50 of frame 200x100 (scale 0.5).
        // Hot blob snap (10..30, 5..20) → frame (20..60+, 10..40+).
        val det = FakeDet(hotMap(100, 50, listOf(intArrayOf(10, 5, 30, 20))))
        val engine = OnnxOcrEngine({ snap() }, FakeProvider(det = det))
        engine.loadModels()
        val regions = engine.detectRegions(frame())
        assertEquals(1, regions.size)
        val r = regions[0]
        assertEquals("det-0", r.id)
        // Unclip expands: blob 21x16 → d = 336*1.6/74 ≈ 7.3 → snap ≈ (3,0,37,27)
        // → frame ≈ (6,0,74,54). Assert containment of the hot area instead.
        assertTrue("bounds=${r.bounds}", r.bounds.left <= 20 && r.bounds.top <= 10)
        assertTrue("bounds=${r.bounds}", r.bounds.right >= 60 && r.bounds.bottom >= 40)
        assertTrue("conf=${r.confidence}", r.confidence > 0.5f)
    }

    @Test
    fun `detect sorts reading order and drops tiny boxes`() = runBlocking {
        // Two blobs: lower one first in scan order; tiny sliver ignored.
        // Map 100x50; blob A (top, y 4..12), blob B (bottom, y 30..40), sliver 1px tall.
        val det = FakeDet(
            hotMap(
                100, 50,
                listOf(
                    intArrayOf(10, 30, 50, 40), // bottom (scanned later, emitted first?)
                    intArrayOf(10, 4, 50, 12), // top
                    intArrayOf(60, 4, 90, 4) // 1px sliver → dropped
                )
            )
        )
        val engine = OnnxOcrEngine({ snap() }, FakeProvider(det = det))
        engine.loadModels()
        val regions = engine.detectRegions(frame())
        assertEquals(2, regions.size)
        assertTrue("top box first: ${regions.map { it.bounds }}", regions[0].bounds.top < regions[1].bounds.top)
        assertEquals("det-0", regions[0].id)
        assertEquals("det-1", regions[1].id)
    }

    @Test
    fun `malformed det output and det throw degrade to empty`() = runBlocking {
        val bad = FakeDet(DetOutput(FloatArray(10), 5, 5)) // 10 != 25
        val engine = OnnxOcrEngine({ snap() }, FakeProvider(det = bad))
        engine.loadModels()
        assertTrue(engine.detectRegions(frame()).isEmpty())

        val boom = FakeDet(throwIt = true)
        val engine2 = OnnxOcrEngine({ snap() }, FakeProvider(det = boom))
        engine2.loadModels()
        assertTrue(engine2.detectRegions(frame()).isEmpty())
    }

    // ---- Recognition ----

    private suspend fun warmedEngine(
        provider: FakeProvider,
        snapshot: OcrSnapshot = snap()
    ): OnnxOcrEngine {
        val engine = OnnxOcrEngine({ snapshot }, provider)
        engine.loadModels()
        // Prime the active snapshot via a (possibly empty) detection.
        engine.detectRegions(frame())
        return engine
    }

    @Test
    fun `recognize without prior detect yields empty (no active snapshot)`() = runBlocking {
        val provider = FakeProvider(rec = FakeRec(seqLogits(listOf(1))))
        val engine = OnnxOcrEngine({ snap() }, provider)
        engine.loadModels()
        val r = TextRegion("det-0", BoundingBox(0, 0, 40, 20), 0.9f)
        assertEquals("", engine.recognizeRegion(frame(), r).rawText)
        assertEquals(0, provider.rec.calls)
    }

    @Test
    fun `recognize decodes text with score`() = runBlocking {
        // classes: blank=0,a=1,b=2,space=3 → "ab".
        val provider = FakeProvider(rec = FakeRec(seqLogits(listOf(1, 1, 0, 2, 2))))
        val engine = warmedEngine(provider)
        val r = TextRegion("det-0", BoundingBox(10, 10, 100, 40), 0.9f)
        val out = engine.recognizeRegion(frame(), r)
        assertEquals("det-0", out.regionId)
        assertEquals("ab", out.rawText)
        assertTrue("conf=${out.confidence}", out.confidence >= 0.5f)
        assertEquals(r.bounds, out.bounds)
        assertEquals(1, provider.rec.calls)
        assertTrue("recWidth=${provider.rec.lastWidth}", provider.rec.lastWidth > 0)
    }

    @Test
    fun `low-score text is dropped to empty (freeze, not corrupt)`() = runBlocking {
        // All-blank logits → "" with ~0 score.
        val provider = FakeProvider(rec = FakeRec(seqLogits(listOf(0, 0, 0))))
        val engine = warmedEngine(provider)
        val r = TextRegion("det-0", BoundingBox(10, 10, 100, 40), 0.9f)
        val out = engine.recognizeRegion(frame(), r)
        assertEquals("", out.rawText)
    }

    @Test
    fun `class mismatch and rec throw degrade to empty`() = runBlocking {
        val mismatch = FakeProvider(rec = FakeRec(RecOutput(FloatArray(2 * 97) { 0.1f }, 1, 2, 97)))
        val engine = warmedEngine(mismatch)
        val r = TextRegion("det-0", BoundingBox(10, 10, 100, 40), 0.9f)
        assertEquals("", engine.recognizeRegion(frame(), r).rawText)

        val boom = FakeProvider(rec = FakeRec(throwIt = true))
        val engine2 = warmedEngine(boom)
        assertEquals("", engine2.recognizeRegion(frame(), r).rawText)
    }

    @Test
    fun `out-of-frame region degrades to empty`() = runBlocking {
        val provider = FakeProvider(rec = FakeRec(seqLogits(listOf(1))))
        val engine = warmedEngine(provider)
        val r = TextRegion("det-9", BoundingBox(500, 500, 600, 600), 0.9f)
        assertEquals("", engine.recognizeRegion(frame(), r).rawText)
        assertEquals(0, provider.rec.calls)
    }

    @Test
    fun `metadata reports warm backend`() {
        val provider = FakeProvider()
        val engine = OnnxOcrEngine({ snap() }, provider)
        engine.loadModels()
        val meta = engine.getEngineMetadata()
        assertEquals("OnnxOcrEngine", meta.engineName)
        assertTrue(meta.executionBackend.contains("CPU_ORT"))
        assertFalse(meta.isQuantized)
    }
}
