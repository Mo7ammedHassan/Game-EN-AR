package com.gametranslator.enar

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gametranslator.enar.detection.OcrPipelineCoordinator
import com.gametranslator.enar.detection.OcrResultStabilizer
import com.gametranslator.enar.detection.RegionTracker
import com.gametranslator.enar.engine.CachedTranslationEngine
import com.gametranslator.enar.engine.DictionaryTranslationEngine
import com.gametranslator.enar.engine.ScreenCaptureEngine
import com.gametranslator.enar.ocr.OnnxOcrEngine
import com.gametranslator.enar.ocr.OrtOcrSessionProvider
import com.gametranslator.enar.pipeline.GameTranslatorPipeline
import com.gametranslator.enar.pipeline.Viewport
import com.gametranslator.enar.render.OverlayController
import com.gametranslator.enar.render.SystemOverlayPermission
import com.gametranslator.enar.render.TieredOverlaySink
import com.gametranslator.enar.service.ScreenCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var tvStatus: TextView
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnToggleTranslation: Button
    private var isCapturing = false

    /**
     * Phase 9 real OCR: ONNX DB detector + CTC recognizer (PP-OCR EN) behind
     * the unchanged [OcrEngine] contract, fed by the bounded snapshot slot in
     * [ScreenCaptureService]. Cold until [OnnxOcrEngine.loadModels] finishes
     * on IO (started with capture); cold/no-snapshot cycles degrade to empty
     * results exactly like the previous NoOp engine — the coordinator,
     * tracker, stabilizer, and translation pipeline needed zero changes.
     *
     * Tracker + stabilizer grace are paired at 3 (ADR-009 rule).
     * Recognition rescan runs at interval 4 (Phase 10, ENTRY 011): STABLE,
     * near-static tracks skip re-recognition and re-observe last text, with
     * a bounded re-verify every 4th cycle; new/changed/unstable/moved tracks
     * are still recognized every cycle. Worst case for in-place text change
     * in a static box is ~4 cycles + stabilization window before the overlay
     * refreshes — accepted for ~4x steady-state recognizer savings.
     */
    private val ocrSessions by lazy { OrtOcrSessionProvider(applicationContext) }

    private val onnxOcrEngine by lazy {
        OnnxOcrEngine(ScreenCaptureService.sharedSnapshotStore::acquire, ocrSessions)
    }

    private val ocrCoordinator by lazy {
        OcrPipelineCoordinator(
            ocrEngine = onnxOcrEngine,
            stabilizer = OcrResultStabilizer(windowSize = 3, maxMissedFrames = 3),
            maxOcrFps = 5,
            tracker = RegionTracker(maxMissedFrames = 3),
            ocrRescanIntervalCycles = 4
        )
    }

    /**
     * Phase 7 integrated pipeline: stabilized OCR → normalize → bounded cache →
     * translation → overlay. Fed by [ocrCoordinator]'s results flow; renders
     * through a process-local [OverlayController].
     *
     * Current engine is the instant offline dictionary (safe idle: with a cold
     * OCR engine there are no regions, so the pipeline idles with zero
     * overlay activity). Swap in the ONNX engine (with its loadModels
     * lifecycle) in Phase 9 — the seam is the [TranslationEngine] constructor
     * argument, nothing else changes.
     */
    private val overlayController by lazy {
        OverlayController(applicationContext, SystemOverlayPermission(applicationContext))
    }

    private val translationPipeline by lazy {
        val metrics = resources.displayMetrics
        GameTranslatorPipeline(
            translationEngine = CachedTranslationEngine(DictionaryTranslationEngine()),
            // Phase 8 tiered sink (default: Tier 3 transparent for every region —
            // visually identical to driving the controller directly, since the
            // default style already carries an outline and no background).
            // Per-region tiers become configurable here once a selection source
            // exists (explicit config first; auto-detection is a later phase).
            overlaySink = TieredOverlaySink(overlayController),
            viewport = Viewport(
                displayWidthPx = metrics.widthPixels,
                displayHeightPx = metrics.heightPixels,
                densityScale = metrics.scaledDensity
            )
        )
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionUi()
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            updatePermissionUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        btnToggleTranslation = findViewById(R.id.btnToggleTranslation)

        btnGrantOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }
        }

        btnToggleTranslation.setOnClickListener {
            if (isCapturing) {
                stopCaptureService()
            } else {
                requestMediaProjectionConsent()
            }
        }

        observeCaptureStatus()
        observeOcrPipeline()
        updatePermissionUi()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUi()
    }

    private fun requestMediaProjectionConsent() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isCapturing = true
        btnToggleTranslation.text = getString(R.string.btn_stop_translation)

        // Warm the OCR sessions off the main thread (model copy + ORT graph
        // init; idempotent). Until warm, the engine yields empty results and
        // the pipeline idles exactly as before.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                onnxOcrEngine.loadModels()
            } catch (e: Exception) {
                Log.w(TAG, "OCR warm-up failed (pipeline idles): ${e.message}")
            }
        }

        // Start the OCR coordinator consuming the shared frame flow, then the
        // translation pipeline consuming stabilized OCR results.
        ocrCoordinator.start(ScreenCaptureService.sharedFrameFlow, lifecycleScope)
        translationPipeline.start(ocrCoordinator.ocrResults)
        Log.i(TAG, "OCR pipeline coordinator + translation pipeline started.")
    }

    private fun stopCaptureService() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(intent)
        isCapturing = false
        btnToggleTranslation.text = getString(R.string.btn_start_translation)

        // Stop the translation stack before the frame flow goes idle.
        stopTranslationStack()
        Log.i(TAG, "OCR pipeline coordinator stopped.")
    }

    private fun observeCaptureStatus() {
        lifecycleScope.launch {
            ScreenCaptureService.sharedCaptureStatus.collectLatest { status ->
                when (status) {
                    ScreenCaptureEngine.CaptureStatus.CAPTURING -> {
                        isCapturing = true
                        tvStatus.text = getString(R.string.status_capturing)
                        btnToggleTranslation.text = getString(R.string.btn_stop_translation)
                    }
                    ScreenCaptureEngine.CaptureStatus.STOPPED,
                    ScreenCaptureEngine.CaptureStatus.IDLE -> {
                        isCapturing = false
                        tvStatus.text = getString(R.string.status_ready)
                        btnToggleTranslation.text = getString(R.string.btn_start_translation)
                        // Ensure the whole stack is stopped if service was killed externally.
                        stopTranslationStack()
                    }
                    ScreenCaptureEngine.CaptureStatus.ERROR -> {
                        isCapturing = false
                        tvStatus.text = getString(R.string.status_error)
                        btnToggleTranslation.text = getString(R.string.btn_start_translation)
                        stopTranslationStack()
                    }
                }
            }
        }
    }

    /**
     * Observes the OCR pipeline's instrumentation outputs for diagnostic logging.
     * With a cold engine (or no snapshots yet), ocrResults stays empty and the
     * downstream pipeline idles.
     */
    private fun observeOcrPipeline() {
        lifecycleScope.launch {
            ocrCoordinator.pipelineStats.collectLatest { stats ->
                stats ?: return@collectLatest
                Log.v(
                    TAG,
                    "PipelineStats: " +
                    "changeDetNs=${stats.changeDetectionLatencyNs} " +
                    "ocrDetNs=${stats.ocrDetectionLatencyNs} " +
                    "ocrRecNs=${stats.ocrRecognitionLatencyNs} " +
                    "regions=${stats.detectedRegionCount} " +
                    "recognized=${stats.recognizedRegionCount} " +
                    "skip=${stats.skippedReason}"
                )
            }
        }

        lifecycleScope.launch {
            ocrCoordinator.ocrResults.collectLatest { results ->
                if (results.isNotEmpty()) {
                    Log.d(TAG, "OCR results: ${results.size} recognized region(s).")
                }
            }
        }

        // Phase-10 diagnostics: periodic Verbose dump of the counters the
        // architecture already maintains (translation attempts/hits/stale,
        // overlay updates, end-to-end ns, snapshot published/throttled).
        // Read-only, 30 s cadence, zero behavior change — exists so live
        // scheduling rates are measurable without profilers.
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000L)
                if (!isCapturing) continue
                try {
                    val snap = translationPipeline.metrics.snapshot()
                    val store = ScreenCaptureService.sharedSnapshotStore.stats()
                    Log.v(
                        TAG,
                        "Phase10Diag pipeline(cycles=${snap.cycles} launched=${snap.launched} " +
                        "completed=${snap.completedTranslations} hits=${snap.cacheHitsObserved} " +
                        "miss=${snap.cacheMissesObserved} stale=${snap.staleDropped} " +
                        "superseded=${snap.supersededMarked} busySkip=${snap.skippedBusy} " +
                        "failures=${snap.translationFailures} retry=${snap.retryLaunches} " +
                        "overlayUpd=${snap.overlayUpdates} overlayHide=${snap.overlayHides} " +
                        "lastE2eNs=${snap.lastEndToEndNs} maxE2eNs=${snap.maxEndToEndNs}) " +
                        "snapshots(pub=${store.published} over=${store.overwritten} " +
                        "throttled=${store.throttled} has=${store.hasSnapshot})"
                    )
                } catch (e: Exception) {
                    Log.v(TAG, "Phase10Diag skipped: ${e.message}")
                }
            }
        }
    }

    /**
     * Stops the full translation stack (pipeline first so no late overlay
     * writes land after the coordinator is gone, then the coordinator).
     * Idempotent; also detaches any overlay content.
     */
    private fun stopTranslationStack() {
        try {
            translationPipeline.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping translation pipeline: ${e.message}")
        }
        try {
            ocrCoordinator.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping OCR coordinator: ${e.message}")
        }
        // Deterministic OCR teardown: drop sessions + active snapshot so a
        // later session reloads cleanly (release is idempotent and safe
        // pre-load by contract, so unconditional call is correct).
        try {
            onnxOcrEngine.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing OCR engine: ${e.message}")
        }
    }

    override fun onDestroy() {
        stopTranslationStack()
        super.onDestroy()
    }

    private fun updatePermissionUi() {        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        if (hasOverlay) {
            btnGrantOverlay.isEnabled = false
            btnGrantOverlay.text = "Overlay Permission Granted"
            btnToggleTranslation.isEnabled = true
        } else {
            btnGrantOverlay.isEnabled = true
            btnGrantOverlay.text = getString(R.string.btn_grant_overlay)
            btnToggleTranslation.isEnabled = false
            tvStatus.text = getString(R.string.status_idle)
        }
    }
}
