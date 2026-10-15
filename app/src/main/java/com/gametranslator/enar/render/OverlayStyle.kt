package com.gametranslator.enar.render

/**
 * Paragraph alignment choice for overlay text (logical, direction-aware).
 *
 * Mapping to [android.text.Layout.Alignment] is done by the Android layout layer:
 * START → ALIGN_NORMAL, END → ALIGN_OPPOSITE, CENTER → ALIGN_CENTER. With the
 * FIRSTSTRONG paragraph direction this follows native platform behavior for both
 * RTL and LTR paragraphs — no manual mirroring anywhere in this codebase.
 */
enum class TextAlignment {
    START,
    CENTER,
    END
}

/**
 * Complete visual configuration for one overlay text region.
 *
 * Deliberately decoupled from rendering logic: the renderer consumes this value
 * object, and styles can be swapped per region (dialogue vs HUD vs menu) without
 * touching draw code. All invariants are validated at construction.
 *
 * Conventions: colors are ARGB Ints (`0xAARRGGBB`); sizes in SP for text and PX
 * for geometry (density conversion happens once in the Android layout layer).
 *
 * @param textSizeSp Glyph size in scale-independent pixels (> 0).
 * @param textColor ARGB fill color of the glyphs.
 * @param alpha Master opacity multiplier applied to the whole region [0, 1].
 * @param backgroundColor Optional solid box behind the text (null = none,
 *   overlay stays transparent).
 * @param outlineColor Optional glyph outline color (null = no outline). Outline
 *   requires a second layout pass in the renderer (documented cost, cached).
 * @param outlineWidthPx Stroke width for the outline pass (≥ 0).
 * @param shadowRadiusPx Blur radius for a drop shadow (0 = none; single-pass).
 * @param shadowColor ARGB shadow color (used only when [shadowRadiusPx] > 0).
 * @param paddingPx Inner padding between region bounds and text (≥ 0).
 * @param lineSpacingAddPx Extra interline space in PX (any finite value).
 * @param lineSpacingMult Line-height multiplier (> 0).
 * @param alignment Logical paragraph alignment (default CENTER: deterministic
 *   for both RTL and LTR game strings).
 * @param maxLines Line cap enforced by the layout engine (≥ 1). MEASURED
 *   platform behavior: truncation happens ONLY together with [ellipsize] = true
 *   (bare `setMaxLines` generates all lines — verified on-device). Without
 *   ellipsis, over-long text is visually contained by the renderer's per-region
 *   clipRect instead. Prefer the capped+ellipsized combo for bounded boxes.
 * @param ellipsize Append the platform ellipsis when [maxLines] truncates
 *   (required for the cap to take effect — see above).
 */
data class OverlayStyle(
    val textSizeSp: Float = 18f,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val alpha: Float = 1f,
    val backgroundColor: Int? = null,
    val outlineColor: Int? = 0xFF000000.toInt(),
    val outlineWidthPx: Float = 3f,
    val shadowRadiusPx: Float = 0f,
    val shadowColor: Int = 0xFF000000.toInt(),
    val paddingPx: Int = 8,
    val lineSpacingAddPx: Float = 0f,
    val lineSpacingMult: Float = 1f,
    val alignment: TextAlignment = TextAlignment.CENTER,
    val maxLines: Int = Int.MAX_VALUE,
    val ellipsize: Boolean = false
) {
    init {
        require(textSizeSp > 0f && textSizeSp.isFinite()) {
            "textSizeSp must be positive finite, got $textSizeSp"
        }
        require(alpha in 0f..1f) { "alpha must be in [0, 1], got $alpha" }
        require(outlineWidthPx >= 0f && outlineWidthPx.isFinite()) {
            "outlineWidthPx must be finite >= 0, got $outlineWidthPx"
        }
        require(shadowRadiusPx >= 0f && shadowRadiusPx.isFinite()) {
            "shadowRadiusPx must be finite >= 0, got $shadowRadiusPx"
        }
        require(paddingPx >= 0) { "paddingPx must be >= 0, got $paddingPx" }
        require(lineSpacingAddPx.isFinite()) {
            "lineSpacingAddPx must be finite, got $lineSpacingAddPx"
        }
        require(lineSpacingMult > 0f && lineSpacingMult.isFinite()) {
            "lineSpacingMult must be positive finite, got $lineSpacingMult"
        }
        require(maxLines >= 1) { "maxLines must be >= 1, got $maxLines" }
    }

    /** True when any text-background box must be drawn. */
    fun hasBackground(): Boolean = backgroundColor != null

    /** True when the two-pass outline renderer is needed. */
    fun hasOutline(): Boolean = outlineColor != null && outlineWidthPx > 0f

    /** True when the single-pass shadow renderer is needed. */
    fun hasShadow(): Boolean = shadowRadiusPx > 0f
}
