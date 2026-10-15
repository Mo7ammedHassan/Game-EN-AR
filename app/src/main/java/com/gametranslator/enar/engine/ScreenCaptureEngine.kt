package com.gametranslator.enar.engine

import com.gametranslator.enar.model.FrameBuffer
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstract Screen Capture Engine interface.
 * Exposes non-blocking captured frame buffers via Kotlin StateFlow.
 */
interface ScreenCaptureEngine {
    /**
     * Observable stream of captured frame buffers.
     */
    val frameFlow: StateFlow<FrameBuffer?>

    /**
     * Observable capture status (IDLE, CAPTURING, STOPPED, ERROR).
     */
    val captureStatus: StateFlow<CaptureStatus>

    enum class CaptureStatus {
        IDLE,
        CAPTURING,
        STOPPED,
        ERROR
    }
}
