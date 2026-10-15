package com.gametranslator.enar.render

/**
 * Minimal window-sink abstraction for overlay content (Phase 7 integration seam).
 *
 * Implemented by [OverlayController] (production) and by recording fakes in
 * tests. Lets the translation pipeline drive overlay content without depending
 * on `WindowManager`, threads, or permissions — all of which stay inside the
 * controller implementation.
 */
interface OverlaySink {
    /**
     * Replaces overlay content (attaches first when permitted but absent).
     * @return true when the request was accepted (not a draw guarantee).
     */
    fun update(regions: List<OverlayRegion>, densityScale: Float): Boolean

    /**
     * Clears content but stays attached (no re-add flicker).
     * @return false when already detached.
     */
    fun clear(): Boolean

    /**
     * Detaches the window. Idempotent: false when already absent.
     */
    fun hide(): Boolean
}
