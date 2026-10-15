package com.gametranslator.enar.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * Non-blocking frame rate limiter to cap capture processing at a target FPS (e.g. 30 FPS).
 */
class FrameRateLimiter(targetFps: Int = 30) {
    private val minFrameIntervalNs = (1_000_000_000L / targetFps.coerceAtLeast(1))
    private val lastFrameTimeNs = AtomicLong(0L)

    /**
     * Returns true if the frame should be processed based on target FPS interval,
     * false if it should be immediately dropped.
     */
    fun shouldProcessFrame(currentNanos: Long = System.nanoTime()): Boolean {
        val last = lastFrameTimeNs.get()
        if (currentNanos - last >= minFrameIntervalNs) {
            return lastFrameTimeNs.compareAndSet(last, currentNanos)
        }
        return false
    }

    fun reset() {
        lastFrameTimeNs.set(0L)
    }
}
