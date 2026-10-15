package com.gametranslator.enar.detection

import android.media.Image
import android.util.Log
import com.gametranslator.enar.model.ChangeDetectionResult
import com.gametranslator.enar.model.FrameBuffer
import java.nio.ByteBuffer

/**
 * Level 1: Cheap frame change detector.
 *
 * Operates directly on an Android [Image] plane buffer (RGBA_8888) to avoid any
 * full-resolution memory copies. Downscales the frame by [downscaleFactor] (default 4×)
 * in-place by striding over the source buffer, producing a compact greyscale luminance
 * block hash that is compared against the previous keyframe hash using Hamming distance.
 *
 * Thread safety: Instances must be called from a single thread (the CaptureThread).
 * The [detect] method does not retain any reference to the [Image] after returning.
 *
 * Performance classification: BENCHMARK REQUIRED on physical ARM64 hardware.
 * Expected: < 5 ms per frame at 4× downscale on mid-range SoC. Not yet measured.
 */
class FrameChangeDetector(
    private val downscaleFactor: Int = 4,
    private val hashBlocksX: Int = 16,
    private val hashBlocksY: Int = 16,
    private val hammingThreshold: Int = 8
) {

    companion object {
        private const val TAG = "FrameChangeDetector"
    }

    // Previous frame's compact block hash (hashBlocksX * hashBlocksY bits, stored as longs)
    private var previousHash: LongArray = LongArray(0)
    private var frameCount: Long = 0L
    private var changesDetected: Long = 0L

    /**
     * Analyses the image plane buffer of [image] (must be RGBA_8888) without copying
     * the full-resolution data. Returns a [ChangeDetectionResult] indicating whether
     * the frame content has changed meaningfully from the previous keyframe.
     *
     * IMPORTANT: The caller must keep [image] open for the duration of this call and
     * close it afterwards. This method does not call [Image.close].
     */
    fun detect(image: Image, frameBuffer: FrameBuffer): ChangeDetectionResult {
        val startNs = System.nanoTime()
        frameCount++

        val planes = image.planes
        if (planes.isEmpty()) {
            return ChangeDetectionResult.NO_DATA
        }

        val plane = planes[0]
        val pixelStride = plane.pixelStride   // Always 4 for RGBA_8888
        val rowStride = plane.rowStride        // May be > width * pixelStride (padding)
        val buffer: ByteBuffer = plane.buffer

        val srcWidth = frameBuffer.width
        val srcHeight = frameBuffer.height

        // Downscaled dimensions
        val dstWidth = (srcWidth / downscaleFactor).coerceAtLeast(1)
        val dstHeight = (srcHeight / downscaleFactor).coerceAtLeast(1)

        // Compute luminance mean over the downscaled grid using strided reads.
        // We sample one pixel per (downscaleFactor × downscaleFactor) block.
        // Luminance approximation: Y ≈ 0.299R + 0.587G + 0.114B (integer fast path).
        val blockW = dstWidth / hashBlocksX
        val blockH = dstHeight / hashBlocksY
        if (blockW < 1 || blockH < 1) {
            // Frame too small for meaningful hashing; report changed to avoid suppression.
            return ChangeDetectionResult.CHANGED(latencyNs = System.nanoTime() - startNs)
        }

        val totalBlocks = hashBlocksX * hashBlocksY
        val blockLuminance = IntArray(totalBlocks)

        for (blockRow in 0 until hashBlocksY) {
            for (blockCol in 0 until hashBlocksX) {
                var sum = 0
                var count = 0
                for (dy in 0 until blockH) {
                    val srcY = (blockRow * blockH + dy) * downscaleFactor
                    if (srcY >= srcHeight) break
                    val rowBase = srcY * rowStride
                    for (dx in 0 until blockW) {
                        val srcX = (blockCol * blockW + dx) * downscaleFactor
                        if (srcX >= srcWidth) break
                        val pos = rowBase + srcX * pixelStride
                        if (pos + 2 >= buffer.limit()) break
                        val r = buffer.get(pos).toInt() and 0xFF
                        val g = buffer.get(pos + 1).toInt() and 0xFF
                        val b = buffer.get(pos + 2).toInt() and 0xFF
                        // Fixed-point luminance: (77R + 150G + 29B) >> 8
                        sum += (77 * r + 150 * g + 29 * b) shr 8
                        count++
                    }
                }
                blockLuminance[blockRow * hashBlocksX + blockCol] = if (count > 0) sum / count else 0
            }
        }

        // Compute global mean luminance for this frame.
        val globalMean = blockLuminance.average().toInt()

        // Build compact block hash: 1 bit per block (above/below global mean).
        // Store as LongArray, 64 bits per long.
        val numLongs = (totalBlocks + 63) / 64
        val currentHash = LongArray(numLongs)
        for (i in 0 until totalBlocks) {
            if (blockLuminance[i] >= globalMean) {
                currentHash[i / 64] = currentHash[i / 64] or (1L shl (i % 64))
            }
        }

        val latencyNs = System.nanoTime() - startNs

        // On first frame or after a reset, treat as changed to seed the keyframe.
        if (previousHash.size != numLongs) {
            previousHash = currentHash
            return ChangeDetectionResult.CHANGED(latencyNs = latencyNs)
        }

        // Compute Hamming distance between current and previous hash.
        var hamming = 0
        for (i in 0 until numLongs) {
            hamming += java.lang.Long.bitCount(currentHash[i] xor previousHash[i])
        }

        return if (hamming > hammingThreshold) {
            previousHash = currentHash
            changesDetected++
            Log.v(TAG, "Change detected: hamming=$hamming, latency=${latencyNs / 1_000_000.0f}ms")
            ChangeDetectionResult.CHANGED(latencyNs = latencyNs, hammingDistance = hamming)
        } else {
            ChangeDetectionResult.NO_CHANGE(latencyNs = latencyNs, hammingDistance = hamming)
        }
    }

    /**
     * Resets the stored keyframe hash. Call on capture restart or orientation change.
     */
    fun reset() {
        previousHash = LongArray(0)
        frameCount = 0L
        changesDetected = 0L
        Log.d(TAG, "FrameChangeDetector reset.")
    }

    /** Returns the ratio of changed / total frames processed (diagnostic metric). */
    fun changeRate(): Float =
        if (frameCount == 0L) 0f else changesDetected.toFloat() / frameCount.toFloat()

    /** Total frames submitted to the detector since last reset. */
    fun frameCount(): Long = frameCount
}
