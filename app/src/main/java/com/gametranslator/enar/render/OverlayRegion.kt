package com.gametranslator.enar.render

/**
 * Axis-aligned rectangle in OVERLAY (physical display pixel) coordinates.
 *
 * Float precision: overlay/view drawing ([android.graphics.Canvas],
 * `StaticLayout` advances) operates in floats. Capture-space boxes are integer
 * ([com.gametranslator.enar.model.BoundingBox]) and MUST be converted via
 * [CoordinateMapper] — never cast silently. The distinct types make unit mix-ups
 * a compile error instead of a rendering bug.
 */
data class OverlayRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** True when the rect can host text (strictly positive area, all finite). */
    fun hasArea(): Boolean =
        width > 0f && height > 0f &&
            left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()
}

/**
 * One independently rendered overlay item: translated text + where + how.
 *
 * @param id Stable identity (translation-cache key / track id downstream).
 * @param boundsPx Position in the canonical overlay coordinate system
 *   (physical display pixels, current orientation — see [CoordinateMapper]).
 * @param text LOGICAL-order text. NEVER reversed, reshaped, or bidi-processed
 *   by producers: shaping and direction come exclusively from the platform
 *   layout engine ([android.text.StaticLayout] via `ArabicTextLayout`).
 *   May contain any mix of Arabic, English, numbers, placeholders.
 * @param style Visual configuration (decoupled value object).
 */
data class OverlayRegion(
    val id: String,
    val boundsPx: OverlayRect,
    val text: String,
    val style: OverlayStyle = OverlayStyle()
) {
    /**
     * True when this region is worth laying out and drawing: non-blank text in
     * a non-degenerate box. Blank text never produces glyphs; degenerate boxes
     * cannot host a `StaticLayout` (width must be > 0).
     */
    fun isRenderable(): Boolean = text.isNotBlank() && boundsPx.hasArea()
}
