package com.gametranslator.enar

import android.app.ActivityManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.FrameBuffer
import com.gametranslator.enar.ocr.OcrSnapshot
import com.gametranslator.enar.ocr.OnnxOcrEngine
import com.gametranslator.enar.ocr.OrtOcrSessionProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 9 on-device OCR gate (HONOR VKJ-NX9 class hardware).
 *
 * Runs the REAL PP-OCR ONNX graphs through the REAL Kotlin
 * preprocess/postprocess on ART: cold load, warm detect+recognize on the
 * game-like corpus PNGs (androidTest assets), empty-image behavior,
 * repeated-run stability, PSS footprint, lifecycle release.
 *
 * Reference expectations come from the dev-machine run of the SAME models
 * (`models/ocr/reference_run.json`): hud_numbers.png → 3 boxes →
 * {"Press E to open", "50 HP", "100%"}; empty_dark.png → 0 boxes.
 * Text assertions are EXACT-SET equality: three independent VKJ-NX9 runs
 * produced byte-identical boxes, texts, and scores (deterministic ORT CPU),
 * so the sets below are observed device behavior, not desktop assumptions.
 * (Scores stay at presence level: ≥ 0.5 gate; exact values vary by < 0.005
 * across runs and are logged, not asserted.)
 */
@RunWith(AndroidJUnit4::class)
class OcrOnDeviceTest {

    private lateinit var appContext: Context
    private lateinit var testContext: Context

    @Before
    fun setUp() {
        val instr = InstrumentationRegistry.getInstrumentation()
        appContext = instr.targetContext
        testContext = instr.context
    }

    private fun loadPngSnapshot(asset: String): OcrSnapshot {
        testContext.assets.open(asset).use { stream ->
            val bmp = BitmapFactory.decodeStream(stream)
                ?: throw IllegalStateException("cannot decode $asset")
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            val rgb = ByteArray(w * h * 3)
            for (i in px.indices) {
                rgb[i * 3] = ((px[i] shr 16) and 0xFF).toByte()
                rgb[i * 3 + 1] = ((px[i] shr 8) and 0xFF).toByte()
                rgb[i * 3 + 2] = (px[i] and 0xFF).toByte()
            }
            return OcrSnapshot(w, h, rgb, w, h, System.nanoTime())
        }
    }

    private fun pssKb(): Int {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getProcessMemoryInfo(intArrayOf(Process.myPid()))[0].totalPss
    }

    private fun meta(w: Int, h: Int) = FrameBuffer(w, h, w * 4, 0L, 1, isChanged = true)

    @Test
    fun testOcrColdLoadWarmDetectRecognizeLifecycle() = runBlocking {
        val pssBefore = pssKb()
        val engine = OnnxOcrEngine({ null }, OrtOcrSessionProvider(appContext))

        val coldStart = System.nanoTime()
        engine.loadModels()
        val coldMs = (System.nanoTime() - coldStart) / 1_000_000.0
        Log.i("OCR_DEVICE", "COLD_LOAD_MS=$coldMs")
        Log.i("OCR_DEVICE", "PSS_BEFORE_KB=$pssBefore PSS_AFTER_LOAD_KB=${pssKb()}")
        assertTrue("OCR engine should be warm after load", engine.isWarm())
        assertTrue(
            "backend must be ORT CPU",
            engine.getEngineMetadata().executionBackend.contains("CPU_ORT")
        )

        // Warm-up detect (excluded from stability asserts).
        var snap = loadPngSnapshot("ocr/hud_numbers.png")
        var liveSnap: OcrSnapshot? = snap
        val live = OnnxOcrEngine({ liveSnap }, OrtOcrSessionProvider(appContext))
        live.loadModels()

        val detStart = System.nanoTime()
        var regions = live.detectRegions(meta(snap.width, snap.height))
        val detMs = (System.nanoTime() - detStart) / 1_000_000.0
        Log.i("OCR_DEVICE", "WARM_DET_MS=$detMs REGIONS=${regions.size}")
        for (r in regions) Log.i("OCR_DEVICE", "BOX=${r.bounds} CONF=${r.confidence}")

        assertEquals("hud_numbers must yield 3 regions (reference parity)", 3, regions.size)

        // Repeated stability: same count across runs.
        repeat(2) {
            val again = live.detectRegions(meta(snap.width, snap.height))
            assertEquals("unstable region count on repeat $it", 3, again.size)
        }

        // Recognize every region; log text/score/latency.
        val texts = mutableListOf<String>()
        var recTotalMs = 0.0
        for (r in regions) {
            val t0 = System.nanoTime()
            val res = live.recognizeRegion(meta(snap.width, snap.height), r)
            recTotalMs += (System.nanoTime() - t0) / 1_000_000.0
            Log.i("OCR_DEVICE", "TEXT='${res.rawText}' SCORE=${res.confidence}")
            assertTrue("empty text for $r", res.rawText.isNotEmpty())
            assertTrue("low score ${res.confidence} for '${res.rawText}'", res.confidence >= 0.5f)
            texts.add(res.rawText)
        }
        Log.i("OCR_DEVICE", "WARM_REC_TOTAL_MS=$recTotalMs")
        assertEquals(
            "hud_numbers texts must exactly match the observed device set",
            setOf("100%", "50 HP", "Press E to open"),
            texts.toSet()
        )
        // Coordinate sanity: inside the frame, reading order.
        for (r in regions) {
            val b: BoundingBox = r.bounds
            assertTrue("box out of frame: $b", b.left >= 0 && b.top >= 0 && b.right <= snap.width && b.bottom <= snap.height)
        }
        val tops = regions.map { it.bounds.top }
        assertEquals("regions not top-sorted: $tops", tops.sorted(), tops)

        // Empty image → no regions (no false positives on blank frames).
        val empty = loadPngSnapshot("ocr/empty_dark.png")
        liveSnap = empty
        val none = live.detectRegions(meta(empty.width, empty.height))
        assertTrue("blank frame must yield no regions, got ${none.size}", none.isEmpty())

        // Second corpus image (menu list) — informational + count gate.
        val menu = loadPngSnapshot("ocr/menu_list.png")
        liveSnap = menu
        val menuRegions = live.detectRegions(meta(menu.width, menu.height))
        Log.i("OCR_DEVICE", "MENU_REGIONS=${menuRegions.size}")
        assertEquals("menu_list must yield 4 regions (reference parity)", 4, menuRegions.size)
        val menuTexts = mutableListOf<String>()
        for (r in menuRegions) {
            val res = live.recognizeRegion(meta(menu.width, menu.height), r)
            Log.i("OCR_DEVICE", "MENU_TEXT='${res.rawText}' SCORE=${res.confidence}")
            menuTexts.add(res.rawText)
        }
        assertEquals(
            "menu_list texts must exactly match the observed device set",
            setOf("New Game", "Save Game", "Inventory", "Press Enter"),
            menuTexts.toSet()
        )

        Log.i("OCR_DEVICE", "PSS_AFTER_INFER_KB=${pssKb()}")

        // Lifecycle: release cools the engine; post-release calls stay empty.
        live.release()
        assertTrue("engine should be cold after release", !live.isWarm())
        assertTrue(live.detectRegions(meta(10, 10)).isEmpty())

        engine.release()
    }
}
