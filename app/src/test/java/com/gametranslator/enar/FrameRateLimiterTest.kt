package com.gametranslator.enar

import com.gametranslator.enar.engine.FrameRateLimiter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateLimiterTest {

    @Test
    fun testFrameRateLimiterRateControl() {
        val limiter = FrameRateLimiter(targetFps = 30) // ~33.3ms per frame
        val startTime = 1_000_000_000L // 1 sec in nanos

        // First frame should be accepted
        assertTrue(limiter.shouldProcessFrame(startTime))

        // Frame arriving 10ms later should be dropped (< 33.3ms)
        assertFalse(limiter.shouldProcessFrame(startTime + 10_000_000L))

        // Frame arriving 20ms later should be dropped (< 33.3ms)
        assertFalse(limiter.shouldProcessFrame(startTime + 20_000_000L))

        // Frame arriving 34ms later should be accepted (> 33.3ms)
        assertTrue(limiter.shouldProcessFrame(startTime + 34_000_000L))
    }
}
