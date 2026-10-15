package com.gametranslator.enar.render

import com.gametranslator.enar.model.BoundingBox

/**
 * Canonical coordinate system (documented once, used everywhere):
 *
 * > Overlay coordinates are PHYSICAL DISPLAY PIXELS in the CURRENT orientation.
 * > The overlay view is full-screen (`MATCH_PARENT`), so overlay pixel (x, y) is
 * > display pixel (x, y). Capture-space boxes (integer OCR pixels) map into this
 * > system via [CoordinateMapper]: uniform scale per axis + translation offset.
 *
 * The capture service builds its `VirtualDisplay` from current display metrics,
 * so in the steady state scale ≈ 1 and offset ≈ 0 — but the renderer MUST NOT
 * assume that (downscaled capture, letterboxing, stale frames across rotation).
 *
 * @param frameWidth Width of the captured frame in OCR pixels (> 0).
 * @param frameHeight Height of the captured frame in OCR pixels (> 0).
 * @param displayWidth Overlay/display width in physical pixels (> 0).
 * @param displayHeight Overlay/display height in physical pixels (> 0).
 * @param offsetXPx Translation applied after scaling (letterbox bars, crop; default 0).
 * @param offsetYPx Translation applied after scaling (default 0).
 * @param rotationClockwiseDeg Clockwise rotation of the DISPLAY relative to the
 *   CAPTURE frame, one of {0, 90, 180, 270} (default 0). Covers stale frames
 *   captured just before a rotation is applied to the VirtualDisplay.
 */
data class CaptureGeometry(
    val frameWidth: Int,
    val frameHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val offsetXPx: Float = 0f,
    val offsetYPx: Float = 0f,
    val rotationClockwiseDeg: Int = 0
) {
    init {
        require(frameWidth > 0) { "frameWidth must be > 0, got $frameWidth" }
        require(frameHeight > 0) { "frameHeight must be > 0, got $frameHeight" }
        require(displayWidth > 0) { "displayWidth must be > 0, got $displayWidth" }
        require(displayHeight > 0) { "displayHeight must be > 0, got $displayHeight" }
        require(offsetXPx.isFinite() && offsetYPx.isFinite()) {
            "offsets must be finite, got ($offsetXPx, $offsetYPx)"
        }
        require(rotationClockwiseDeg == 0 || rotationClockwiseDeg == 90 ||
            rotationClockwiseDeg == 180 || rotationClockwiseDeg == 270) {
            "rotation must be one of {0, 90, 180, 270}, got $rotationClockwiseDeg"
        }
    }
}

/**
 * Pure, stateless capture→overlay coordinate transform (fully JVM-testable).
 *
 * Rotation semantics (clockwise display-vs-capture), verified corner by corner
 * in unit tests:
 * - 0:   display = (x·sx + ox, y·sy + oy), sx = dw/cw, sy = dh/ch.
 * - 90:  display = (ox + (ch−y)·dw/ch, oy + x·dh/cw).
 * - 180: display = (ox + (cw−x)·dw/cw, oy + (ch−y)·dh/ch).
 * - 270: display = (ox + y·dw/ch, oy + (cw−x)·dh/cw).
 */
class CoordinateMapper(private val geometry: CaptureGeometry) {

    /** Horizontal pixels of overlay per capture pixel (rotation-aware). */
    fun scaleX(): Float = when (geometry.rotationClockwiseDeg) {
        90, 270 -> geometry.displayWidth.toFloat() / geometry.frameHeight.toFloat()
        else -> geometry.displayWidth.toFloat() / geometry.frameWidth.toFloat()
    }

    /** Vertical pixels of overlay per capture pixel (rotation-aware). */
    fun scaleY(): Float = when (geometry.rotationClockwiseDeg) {
        90, 270 -> geometry.displayHeight.toFloat() / geometry.frameWidth.toFloat()
        else -> geometry.displayHeight.toFloat() / geometry.frameHeight.toFloat()
    }

    /** Maps one capture-space point into overlay pixels. */
    fun toOverlay(x: Int, y: Int): Pair<Float, Float> {
        val sx = scaleX()
        val sy = scaleY()
        val cw = geometry.frameWidth.toFloat()
        val ch = geometry.frameHeight.toFloat()
        val ox = geometry.offsetXPx
        val oy = geometry.offsetYPx
        return when (geometry.rotationClockwiseDeg) {
            90 -> Pair(ox + (ch - y) * sx, oy + x * sy)
            180 -> Pair(ox + (cw - x) * sx, oy + (ch - y) * sy)
            270 -> Pair(ox + y * sx, oy + (cw - x) * sy)
            else -> Pair(ox + x * sx, oy + y * sy)
        }
    }

    /**
     * Maps a capture-space box into overlay pixels. Corners are transformed
     * independently and re-normalized (min/max), so rotated boxes stay valid
     * axis-aligned rects. Zero-area capture boxes map to zero-area overlay
     * rects (downstream [OverlayRegion.isRenderable] filters them).
     */
    fun toOverlay(box: BoundingBox): OverlayRect {
        val (x0, y0) = toOverlay(box.left, box.top)
        val (x1, y1) = toOverlay(box.right, box.bottom)
        return OverlayRect(
            left = minOf(x0, x1),
            top = minOf(y0, y1),
            right = maxOf(x0, x1),
            bottom = maxOf(y0, y1)
        )
    }
}
