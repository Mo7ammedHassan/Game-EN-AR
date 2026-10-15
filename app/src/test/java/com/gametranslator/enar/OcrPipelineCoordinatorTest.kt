package com.gametranslator.enar

import com.gametranslator.enar.detection.OcrPipelineCoordinator
import com.gametranslator.enar.detection.OcrResultStabilizer
import com.gametranslator.enar.detection.RegionTracker
import com.gametranslator.enar.engine.OcrEngine
import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.EngineMetadata
import com.gametranslator.enar.model.FrameBuffer
import com.gametranslator.enar.model.OcrResult
import com.gametranslator.enar.model.TextRegion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Phase 7 [OcrPipelineCoordinator] extensions: optional spatial
 * tracking (stable trackIds downstream — Part D) and the recognition rescan
 * policy (Part E), plus legacy-behavior preservation.
 *
 * A scripted [OcrEngine] with call accounting stands in for real models
 * (no models on the JVM classpath); frames are synthetic metadata-only
 * buffers, exactly what the coordinator consumes.
 */
class OcrPipelineCoordinatorTest {

    private class ScriptOcrEngine : OcrEngine {
        var detections: List<TextRegion> = emptyList()
        var texts: MutableMap<String, String> = mutableMapOf()
        var defaultText: String = "TEXT"
        var detectCalls = 0
        var recognizeCalls = 0
        var failRecognizeFor: MutableSet<String> = mutableSetOf()

        override suspend fun detectRegions(frame: FrameBuffer): List<TextRegion> {
            detectCalls++
            return detections
        }

        override suspend fun recognizeRegion(crop: FrameBuffer, region: TextRegion): OcrResult {
            recognizeCalls++
            if (region.id in failRecognizeFor) throw RuntimeException("ocr boom")
            return OcrResult(
                regionId = region.id,
                rawText = texts[region.id] ?: "$defaultText:${region.id}",
                confidence = 0.9f,
                bounds = region.bounds,
                isStable = false
            )
        }

        override fun getEngineMetadata(): EngineMetadata =
            EngineMetadata("Script", "0", "TEST", false)
    }

    private fun box(l: Int, t: Int, r: Int, b: Int) = BoundingBox(l, t, r, b)

    private fun det(id: String, bounds: BoundingBox) =
        TextRegion(id = id, bounds = bounds, confidence = 0.9f)

    private fun frame(changed: Boolean = true) = FrameBuffer(
        width = 640,
        height = 480,
        stride = 2560,
        timestamp = System.nanoTime(),
        pixelFormat = 1,
        isChanged = changed,
        changeLatencyNs = 0L
    )

    private suspend fun emit(flow: MutableStateFlow<FrameBuffer?>, changed: Boolean = true, times: Int = 1) {
        repeat(times) {
            flow.value = frame(changed)
            delay(15)
        }
        delay(40) // let the coordinator worker drain
    }

    @Test
    fun `unchanged frames never reach detection`() = runBlocking {
        val engine = ScriptOcrEngine()
        val coordinator = OcrPipelineCoordinator(engine, OcrResultStabilizer(), maxOcrFps = 1000)
        val flow = MutableStateFlow<FrameBuffer?>(null)
        coordinator.start(flow, this)

        emit(flow, changed = false, times = 3)

        assertEquals(0, engine.detectCalls)
        coordinator.stop()
    }

    @Test
    fun `legacy path keeps engine ids when tracker absent`() = runBlocking {
        val engine = ScriptOcrEngine()
        engine.detections = listOf(det("engine-7", box(0, 0, 100, 20)))
        engine.texts["engine-7"] = "HELLO"
        val coordinator = OcrPipelineCoordinator(engine, OcrResultStabilizer(), maxOcrFps = 1000)
        val flow = MutableStateFlow<FrameBuffer?>(null)
        coordinator.start(flow, this)

        emit(flow, times = 4)

        // Stabilized under the volatile engine id (legacy contract).
        val results = coordinator.ocrResults.value
        assertEquals(1, results.size)
        assertEquals("engine-7", results[0].regionId)
        assertTrue(results[0].isStable)
        assertEquals(4, engine.recognizeCalls) // every cycle (legacy)
        coordinator.stop()
    }

    @Test
    fun `tracker remaps downstream ids to stable track ids`() = runBlocking {
        val engine = ScriptOcrEngine()
        val coordinator = OcrPipelineCoordinator(
            engine,
            OcrResultStabilizer(),
            maxOcrFps = 1000,
            tracker = RegionTracker()
        )
        val flow = MutableStateFlow<FrameBuffer?>(null)
        coordinator.start(flow, this)

        // Detector ids flap every frame; box barely moves (same track).
        // NOTE: recognition sees the TRACKER id (track-0 from frame one), so
        // scripted texts are keyed by it — detector ids never reach the engine.
        engine.texts["track-0"] = "HELLO"
        val boxes = listOf(
            box(0, 0, 100, 20), box(1, 0, 101, 20), box(2, 0, 102, 20), box(3, 0, 103, 20)
        )
        for ((i, b) in boxes.withIndex()) {
            engine.detections = listOf(det("flap-$i", b))
            flow.value = frame()
            delay(15)
        }
        delay(60)

        val results = coordinator.ocrResults.value
        assertEquals(1, results.size)
        assertEquals("track-0", results[0].regionId) // Part D contract
        coordinator.stop()
    }

    @Test
    fun `rescan policy skips stable static tracks`() = runBlocking {
        val engine = ScriptOcrEngine()
        engine.texts["track-0"] = "HELLO"
        val coordinator = OcrPipelineCoordinator(
            engine,
            OcrResultStabilizer(),
            maxOcrFps = 1000,
            tracker = RegionTracker(),
            ocrRescanIntervalCycles = 10
        )
        val flow = MutableStateFlow<FrameBuffer?>(null)
        coordinator.start(flow, this)

        // Window-fill (3 cycles) recognizes each cycle; cycles 4-8 skip via
        // re-observation (stable + static + within interval).
        repeat(8) { i ->
            engine.detections = listOf(det("e$i", box(0, 0, 100, 20)))
            flow.value = frame()
            delay(15)
        }
        delay(60)

        assertEquals(3, engine.recognizeCalls)
        val results = coordinator.ocrResults.value
        assertEquals(1, results.size)
        assertEquals("track-0", results[0].regionId)
        assertTrue("re-observed text must stabilize", results[0].isStable)
        val stats = coordinator.pipelineStats.value
        org.junit.Assert.assertNotNull(stats)
        assertEquals(1, stats?.detectedRegionCount)
        assertEquals(0, stats?.recognizedRegionCount)
        coordinator.stop()
    }

    @Test
    fun `rescan re-recognizes on movement and on text change`() = runBlocking {
        val engine = ScriptOcrEngine()
        val coordinator = OcrPipelineCoordinator(
            engine,
            OcrResultStabilizer(),
            maxOcrFps = 1000,
            tracker = RegionTracker(),
            ocrRescanIntervalCycles = 10
        )
        val flow = MutableStateFlow<FrameBuffer?>(null)
        coordinator.start(flow, this)

        suspend fun cycle(id: String, bounds: BoundingBox) {
            engine.detections = listOf(det(id, bounds))
            flow.value = frame()
            delay(15)
        }
        // Stabilize a static track first (3+ consistent cycles of default text).
        repeat(4) { cycle("a$it", box(0, 0, 100, 20)) }
        delay(60)
        val baseCalls = engine.recognizeCalls
        assertTrue("must stabilize first", coordinator.ocrResults.value[0].isStable)

        // Teleport the box (same text): movement forces re-recognition.
        cycle("m", box(400, 400, 500, 420))
        delay(60)
        // New track (IoU ~0): recognized as new.
        assertTrue(engine.recognizeCalls > baseCalls)
        coordinator.stop()
    }

    @Test
    fun `interval one always recognizes even with tracker`() = runBlocking {
        val engine = ScriptOcrEngine()
        val coordinator = OcrPipelineCoordinator(
            engine,
            OcrResultStabilizer(),
            maxOcrFps = 1000,
            tracker = RegionTracker(),
            ocrRescanIntervalCycles = 1
        )
        val flow = MutableStateFlow<FrameBuffer?>(null)
        coordinator.start(flow, this)

        repeat(4) { i ->
            engine.detections = listOf(det("e$i", box(0, 0, 100, 20)))
            flow.value = frame()
            delay(15)
        }
        delay(60)

        assertEquals(4, engine.recognizeCalls)
        coordinator.stop()
    }

    @Test
    fun `recognize failure does not crash the pipeline`() = runBlocking {
        val engine = ScriptOcrEngine()
        engine.detections = listOf(det("e1", box(0, 0, 100, 20)))
        // NOTE: the engine sees TRACKER ids — fail the track, not the detector id.
        engine.failRecognizeFor.add("track-0")
        val coordinator = OcrPipelineCoordinator(
            engine,
            OcrResultStabilizer(),
            maxOcrFps = 1000,
            tracker = RegionTracker()
        )
        val flow = MutableStateFlow<FrameBuffer?>(null)
        coordinator.start(flow, this)

        emit(flow, times = 2)
        // No crash; nothing published for the failed region.
        assertTrue(coordinator.ocrResults.value.isEmpty())

        // Recovery: failure cleared, next cycle flows again.
        engine.failRecognizeFor.clear()
        emit(flow, times = 4)
        assertTrue(coordinator.ocrResults.value.isNotEmpty())
        coordinator.stop()
    }

    @Test
    fun `stop clears results and rescan memory`() = runBlocking {
        val engine = ScriptOcrEngine()
        engine.detections = listOf(det("e1", box(0, 0, 100, 20)))
        engine.texts["e1"] = "HELLO"
        val coordinator = OcrPipelineCoordinator(
            engine,
            OcrResultStabilizer(),
            maxOcrFps = 1000,
            tracker = RegionTracker(),
            ocrRescanIntervalCycles = 10
        )
        val flow = MutableStateFlow<FrameBuffer?>(null)
        coordinator.start(flow, this)

        emit(flow, times = 2)
        coordinator.stop()
        assertTrue(coordinator.ocrResults.value.isEmpty())

        // Restart works from a clean slate (no stale tracks/ids).
        coordinator.start(flow, this)
        coordinator.stop()
    }

    @Test
    fun `rescan tolerates realistic detector jitter with default iou threshold`() = runBlocking {
        val engine = ScriptOcrEngine()
        engine.texts["track-0"] = "PLAY"
        val coordinator = OcrPipelineCoordinator(
            engine,
            OcrResultStabilizer(),
            maxOcrFps = 1000,
            tracker = RegionTracker(),
            ocrRescanIntervalCycles = 5,
            rescanStableIou = 0.90
        )
        val flow = MutableStateFlow<FrameBuffer?>(null)
        coordinator.start(flow, this)

        // 3 cycles to stabilize with exact box (0, 0, 100, 30)
        repeat(3) { i ->
            engine.detections = listOf(det("e$i", box(0, 0, 100, 30)))
            flow.value = frame()
            delay(15)
        }
        delay(60)
        assertEquals(3, engine.recognizeCalls)

        // Cycle 4: 1px jitter box (1, 1, 101, 31).
        // IoU: intersection 99*29 = 2871, union 3000 + 3000 - 2871 = 3129 -> IoU = 0.9175 > 0.90
        // Skips re-recognition!
        engine.detections = listOf(det("e3", box(1, 1, 101, 31)))
        flow.value = frame()
        delay(60)
        assertEquals("1px jitter (IoU ~0.92) must skip re-recognition", 3, engine.recognizeCalls)
        val stats = coordinator.pipelineStats.value
        assertEquals(0, stats?.recognizedRegionCount)

        coordinator.stop()
    }
}
