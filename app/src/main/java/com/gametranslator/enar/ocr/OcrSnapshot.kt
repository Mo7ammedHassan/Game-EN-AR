package com.gametranslator.enar.ocr

/**
 * One bounded, immutable, pre-scaled pixel snapshot for OCR (Phase 9 pixel
 * transport payload).
 *
 * WHY this shape: the `FrameBuffer` flow is metadata-only by design (ADR-011 —
 * a `StateFlow` retaining byte arrays would pin megabytes across collectors).
 * Pixels therefore travel through a side-channel single slot ([SnapshotStore])
 * as an immutable value: the producer publishes a NEW instance per OCR cycle
 * and drops its reference; the consumer holds at most one. No native handles,
 * no close protocol — ownership is "single holder, drop to release", enforced
 * by immutability (a `ByteArray` is never mutated after construction, so
 * producer/consumer can never race on contents).
 *
 * @param width Snapshot width in px (≤ [OcrConfig.SNAPSHOT_MAX_SIDE]).
 * @param height Snapshot height in px.
 * @param rgb Row-major RGB888 bytes (`width * height * 3`), no stride.
 * @param srcWidth Original frame width in px (maps back to [BoundingBox] space).
 * @param srcHeight Original frame height in px.
 * @param timestampNs Source frame timestamp (diagnostic correlation only).
 * @param generation Monotonic id assigned by [SnapshotStore] (staleness metric).
 */
data class OcrSnapshot(
    val width: Int,
    val height: Int,
    val rgb: ByteArray,
    val srcWidth: Int,
    val srcHeight: Int,
    val timestampNs: Long,
    val generation: Long = 0L
) {
    init {
        require(width > 0 && height > 0) { "snapshot dims must be > 0" }
        require(srcWidth > 0 && srcHeight > 0) { "source dims must be > 0" }
        require(rgb.size == width * height * 3) {
            "rgb must be exactly w*h*3, got ${rgb.size} for ${width}x$height"
        }
    }

    /** Uniform snapshot/src scale (aspect is always preserved). */
    fun scale(): Float = width.toFloat() / srcWidth.toFloat()

    /** Snapshot → frame pixel coordinate (inverse of the downscale). */
    fun toFrameX(x: Float): Float = x / scale()

    /** Snapshot → frame pixel coordinate (inverse of the downscale). */
    fun toFrameY(y: Float): Float = y / scale()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OcrSnapshot) return false
        return width == other.width && height == other.height &&
            srcWidth == other.srcWidth && srcHeight == other.srcHeight &&
            timestampNs == other.timestampNs && generation == other.generation &&
            rgb.contentEquals(other.rgb)
    }

    override fun hashCode(): Int {
        var h = width * 31 + height
        h = h * 31 + srcWidth
        h = h * 31 + srcHeight
        h = h * 31 + timestampNs.hashCode()
        h = h * 31 + generation.hashCode()
        h = h * 31 + rgb.contentHashCode()
        return h
    }
}
