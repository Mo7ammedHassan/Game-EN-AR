package com.gametranslator.enar.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import com.gametranslator.enar.engine.FrameRateLimiter
import com.gametranslator.enar.engine.ScreenCaptureEngine
import com.gametranslator.enar.detection.FrameChangeDetector
import com.gametranslator.enar.model.ChangeDetectionResult
import com.gametranslator.enar.model.FrameBuffer
import com.gametranslator.enar.ocr.SnapshotDownscaler
import com.gametranslator.enar.ocr.SnapshotStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 14/15 Compliant Screen Capture Service using MediaProjection API.
 * Operates asynchronously on a dedicated HandlerThread at a max target rate of 30 FPS.
 */
class ScreenCaptureService : Service(), ScreenCaptureEngine {

    companion object {
        private const val TAG = "ScreenCaptureService"
        const val CHANNEL_ID = "game_translator_capture_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.gametranslator.enar.START_CAPTURE"
        const val ACTION_STOP = "com.gametranslator.enar.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"

        // Shared static instance for engine observers
        private val _frameFlow = MutableStateFlow<FrameBuffer?>(null)
        val sharedFrameFlow: StateFlow<FrameBuffer?> = _frameFlow.asStateFlow()

        private val _captureStatus = MutableStateFlow(ScreenCaptureEngine.CaptureStatus.IDLE)
        val sharedCaptureStatus: StateFlow<ScreenCaptureEngine.CaptureStatus> = _captureStatus.asStateFlow()

        /**
         * Bounded latest-only pixel slot for OCR (Phase 9 transport).
         *
         * The metadata [_frameFlow] never carries pixels (ADR-011). Changed
         * frames additionally publish ONE pre-scaled snapshot here (≤ 5/s,
         * latest-wins, CaptureThread never blocks). The OCR engine consumes
         * via [SnapshotStore.acquire]; [SnapshotStore.clear] runs on every
         * stop/start so a restarted session can never read stale pixels.
         */
        val sharedSnapshotStore = SnapshotStore()
    }

    override val frameFlow: StateFlow<FrameBuffer?> get() = sharedFrameFlow
    override val captureStatus: StateFlow<ScreenCaptureEngine.CaptureStatus> get() = sharedCaptureStatus

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val frameRateLimiter = FrameRateLimiter(targetFps = 30)

    // Level 1 change detector — runs on CaptureThread, reads Image planes directly.
    private val changeDetector = FrameChangeDetector()

    // Atomic guard to prevent concurrent or duplicate teardown execution
    private val isCapturingActive = AtomicBoolean(false)

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection session stopped by system/user.")
            stopCaptureInternal()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startCaptureForeground(resultCode, resultData)
                } else {
                    Log.e(TAG, "Invalid resultCode or resultData for MediaProjection.")
                    _captureStatus.value = ScreenCaptureEngine.CaptureStatus.ERROR
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopCaptureInternal()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCaptureForeground(resultCode: Int, resultData: Intent) {
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Game Translator Active")
            .setContentText("Capturing screen for real-time translation...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(mediaProjectionCallback, Handler(mainLooper))

            setupCapturePipeline()
            sharedSnapshotStore.clear() // fresh session: no pre-start pixels
            isCapturingActive.set(true)
            _captureStatus.value = ScreenCaptureEngine.CaptureStatus.CAPTURING
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting MediaProjection service", e)
            _captureStatus.value = ScreenCaptureEngine.CaptureStatus.ERROR
            stopCaptureInternal()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection capture pipeline", e)
            _captureStatus.value = ScreenCaptureEngine.CaptureStatus.ERROR
            stopCaptureInternal()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Game Translator Capture Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification channel for real-time screen capture service"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun setupCapturePipeline() {
        val metrics: DisplayMetrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        // Dedicated background thread for non-blocking image acquisition
        captureThread = HandlerThread("CaptureThread").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        // ImageReader with 2 maxBuffers for fast frame swapping
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            onImageAvailable(reader)
        }, captureHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "GameTranslatorCapture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler
        )
    }

    private fun onImageAvailable(reader: ImageReader) {
        if (!isCapturingActive.get()) return
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return

            // Cap processing at target 30 FPS. If arriving faster, drop image immediately.
            if (!frameRateLimiter.shouldProcessFrame()) {
                image.close()
                return
            }

            val planes = image.planes
            if (planes.isNotEmpty()) {
                val plane = planes[0]

                // Build a preliminary metadata-only FrameBuffer for the detector.
                val metaBuffer = FrameBuffer(
                    width = image.width,
                    height = image.height,
                    stride = plane.rowStride,
                    timestamp = image.timestamp,
                    pixelFormat = image.format
                )

                // Level 1 change detection — runs here on CaptureThread while Image is still
                // open. Reads pixel planes in-place; does NOT copy any full-resolution data.
                val detectionResult = changeDetector.detect(image, metaBuffer)

                val isChanged = detectionResult is ChangeDetectionResult.CHANGED
                val changeLatencyNs = when (detectionResult) {
                    is ChangeDetectionResult.CHANGED -> detectionResult.latencyNs
                    is ChangeDetectionResult.NO_CHANGE -> detectionResult.latencyNs
                    is ChangeDetectionResult.NO_DATA -> 0L
                }

                // Publish enriched FrameBuffer. Downstream OCR coordinator reads isChanged
                // to decide whether to invoke OcrEngine. Image is closed immediately below.
                _frameFlow.value = metaBuffer.copy(
                    isChanged = isChanged,
                    changeLatencyNs = changeLatencyNs
                )

                // Phase 9 pixel path: ONLY changed frames produce a snapshot,
                // and only when the store is due (≤ 5/s). The downscale reads
                // plane bytes in place — no full-resolution copy — while the
                // Image is still open. Failures degrade to metadata-only
                // (engine sees no fresh snapshot and yields empty results).
                if (isChanged && sharedSnapshotStore.isDue(SystemClock.elapsedRealtime())) {
                    try {
                        val snapshot = SnapshotDownscaler.downscale(
                            plane.buffer,
                            image.width,
                            image.height,
                            plane.rowStride,
                            plane.pixelStride,
                            image.timestamp
                        )
                        sharedSnapshotStore.publish(snapshot, SystemClock.elapsedRealtime())
                    } catch (e: Exception) {
                        Log.w(TAG, "Snapshot publish failed (metadata-only fallback)", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error acquiring frame image buffer", e)
        } finally {
            image?.close()
        }
    }


    private fun stopCaptureInternal() {
        // Guarantee atomic single execution of teardown sequence
        if (!isCapturingActive.compareAndSet(true, false) && _captureStatus.value == ScreenCaptureEngine.CaptureStatus.STOPPED) {
            return
        }

        try {
            // Detach image listener to prevent callbacks during cleanup
            imageReader?.setOnImageAvailableListener(null, null)

            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.close()
            imageReader = null

            mediaProjection?.unregisterCallback(mediaProjectionCallback)
            mediaProjection?.stop()
            mediaProjection = null

            captureThread?.quitSafely()
            captureThread = null
            captureHandler = null
            frameRateLimiter.reset()
            changeDetector.reset()
            // Drop any published pixels: a restarted session must never read
            // a pre-restart frame (stale-pixel safety).
            sharedSnapshotStore.clear()

            _captureStatus.value = ScreenCaptureEngine.CaptureStatus.STOPPED
            _frameFlow.value = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capture service", e)
        } finally {
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopCaptureInternal()
        super.onDestroy()
    }
}
