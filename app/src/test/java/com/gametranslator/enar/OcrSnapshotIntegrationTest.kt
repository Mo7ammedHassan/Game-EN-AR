package com.gametranslator.enar

import com.gametranslator.enar.detection.OcrPipelineCoordinator
import com.gametranslator.enar.detection.OcrResultStabilizer
import com.gametranslator.enar.detection.RegionTracker
import com.gametranslator.enar.model.FrameBuffer
import com.gametranslator.enar.ocr.DetOutput
import com.gametranslator.enar.ocr.DetSession
import com.gametranslator.enar.ocr.OcrSnapshot
import com.gametranslator.enar.ocr.OcrSessionProvider
import com.gametranslator.enar.ocr.OnnxOcrEngine
import com.gametranslator.enar.ocr.RecOutput
import com.gametranslator.enar.ocr.RecSession
import com.gametranslator.enar.ocr.SnapshotStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9 integration: snapshot slot → real [OnnxOcrEngine] (scripted ORT
 * sessions) → real [OcrPipelineCoordinator] + [RegionTracker] +
 * [OcrResultStabilizer] → stable, track-keyed OCR results.
 *
 * Sessions are scripted (no natives on the JVM), but EVERYTHING else is the
 * production path: real snapshot bytes through real preprocess, real DB
 * decode, real CTC decode, real tracking/stabilization state machines.
 */
class OcrSnapshotIntegrationTest {

    private class ScriptDet(var boxes: List<IntArray> = emptyList()) : DetSession {
        var lastDims = Triple(0, 0, 0)
        var calls = 0

        override fun run(input: FloatArray, batch: Int, channels: Int, height: Int, width: Int): DetOutput {
            calls++
            lastDims = Triple(batch, height, width)
            // Hot blob per scripted box on the (real-dimensioned) map.
            val prob = FloatArray(width * height)
            for (b in boxes) {
                for (y in b[1]..b[3]) {
                    for (x in b[0]..b[2]) prob[y * width + x] = 0.95f
                }
            }
            return DetOutput(prob, height, width)
        }

        override fun close() {}
    }

    private class ScriptRec : RecSession {
        var calls = 0

        override fun run(input: FloatArray, batch: Int, channels: Int, height: Int, width: Int): RecOutput {
            calls++
            // classes: blank=0,h=1,i=2,space=3 → "hi".
            val seq = listOf(1, 1, 0, 2)
            val out = FloatArray(seq.size * 4) { 0.01f }
            seq.forEachIndexed { t, c -> out[t * 4 + c] = 0.9f }
            return RecOutput(out, 1, seq.size, 4)
        }

        override fun close() {}
    }

    private class ScriptProvider(val det: ScriptDet, val rec: ScriptRec) : OcrSessionProvider {
        private var loaded = false
        override fun load() {
            loaded = true
        }

        override fun isLoaded(): Boolean = loaded
        override fun detector(): DetSession = det
        override fun recognizer(): RecSession = rec
        override fun characters(): List<String> = listOf("h", "i")
        override fun release() {
            loaded = false
        }
    }

    // Frame 400x200, snapshot 200x100 (scale 0.5, uniform white).
    private fun snap(): OcrSnapshot =
        OcrSnapshot(200, 100, ByteArray(200 * 100 * 3) { 255.toByte() }, 400, 200, 1L)

    private fun frame() = FrameBuffer(400, 200, 1600, System.nanoTime(), 1, isChanged = true)

    private suspend fun emit(flow: MutableStateFlow<FrameBuffer?>, times: Int) {
        repeat(times) {
            flow.value = frame()
            delay(15)
        }
        delay(60)
    }

    @Test
    fun `snapshot flows to stable track-keyed text`() = runBlocking {
        val store = SnapshotStore(minIntervalMs = 0L)
        store.publish(snap(), 0L)
        val det = ScriptDet(listOf(intArrayOf(20, 10, 60, 30)))
        val rec = ScriptRec()
        val engine = OnnxOcrEngine(store::acquire, ScriptProvider(det, rec))
        engine.loadModels()

        val coordinator = OcrPipelineCoordinator(
            engine,
            OcrResultStabilizer(windowSize = 3, maxMissedFrames = 3),
            maxOcrFps = 1000,
            tracker = RegionTracker(maxMissedFrames = 3)
        )
        val flow = MutableStateFlow<FrameBuffer?>(null)
        coordinator.start(flow, this)

        emit(flow, times = 5)

        val results = coordinator.ocrResults.value
        assertEquals("expected 1 region, got $results", 1, results.size)
        val r = results[0]
        assertTrue("tracker id expected, got ${r.regionId}", r.regionId.startsWith("track-"))
        assertEquals("hi", r.rawText)
        assertTrue("expected stable, got $r", r.isStable)
        // Real preprocess fed the session: snapshot 200x100 → model 192x96.
        assertEquals(Triple(1, 96, 192), det.lastDims)
        assertTrue(rec.calls >= 3)
        coordinator.stop()
    }

    @Test
    fun `cleared snapshot ages tracks out without crashing`() = runBlocking {
        val store = SnapshotStore(minIntervalMs = 0L)
        store.publish(snap(), 0L)
        val det = ScriptDet(listOf(intArrayOf(20, 10, 60, 30)))
        val engine = OnnxOcrEngine(store::acquire, ScriptProvider(det, ScriptRec()))
        engine.loadModels()
        val coordinator = OcrPipelineCoordinator(
            engine,
            OcrResultStabilizer(windowSize = 3, maxMissedFrames = 3),
            maxOcrFps = 1000,
            tracker = RegionTracker(maxMissedFrames = 3)
        )
        val flow = MutableStateFlow<FrameBuffer?>(null)
        coordinator.start(flow, this)

        emit(flow, times = 5)
        assertEquals(1, coordinator.ocrResults.value.size)

        // Pixels vanish (capture stopped upstream): engine degrades to empty,
        // tracks age through grace, results drain — no crash, no stuck overlay.
        store.clear()
        emit(flow, times = 8)
        assertTrue(
            "results should drain after grace, got ${coordinator.ocrResults.value}",
            coordinator.ocrResults.value.isEmpty()
        )
        coordinator.stop()
    }

    @Test
    fun `rapid start-stop is leak-free`() = runBlocking {
        val store = SnapshotStore(minIntervalMs = 0L)
        val engine = OnnxOcrEngine(store::acquire, ScriptProvider(ScriptDet(), ScriptRec()))
        engine.loadModels()
        val coordinator = OcrPipelineCoordinator(engine, OcrResultStabilizer(), maxOcrFps = 1000)
        val flow = MutableStateFlow<FrameBuffer?>(null)
        repeat(5) {
            store.publish(snap(), it.toLong())
            coordinator.start(flow, this)
            emit(flow, times = 1)
            coordinator.stop()
        }
        engine.release()
        assertTrue(coordinator.ocrResults.value.isEmpty())
    }
}
