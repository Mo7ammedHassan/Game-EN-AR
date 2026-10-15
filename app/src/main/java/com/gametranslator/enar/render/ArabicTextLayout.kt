package com.gametranslator.enar.render

import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.graphics.Canvas

/**
 * Native Android Arabic-capable text layout (ADR-004).
 *
 * Builds [StaticLayout] around LOGICAL-order text and lets the platform do ALL
 * shaping (HarfBuzz glyph joining/ligatures) and bidirectional reordering
 * (Unicode Bidi Algorithm, FIRSTSTRONG paragraph direction — the same rule
 * [BidiUtils] verifies on the JVM). This codebase contains NO string reversal
 * and NO custom shaping, by architectural decision.
 *
 * Paragraph direction: [TextDirectionHeuristics.FIRSTSTRONG_LTR] — an
 * Arabic-first paragraph lays out right-to-left, an English-first one
 * left-to-right, mixed runs reorder per the UBA. [OverlayStyle.alignment]
 * maps to [Layout.Alignment] NORMAL/OPPOSITE/CENTER, which follow the resolved
 * paragraph direction (no manual mirroring).
 *
 * Cost model: building a layout runs line-breaking (the expensive part);
 * drawing is cheap. The renderer ([OverlayRegionView]) caches built layouts and
 * rebuilds ONLY when region content changes. When [OverlayStyle.hasOutline] is
 * set, [buildOutline] produces a SECOND layout sharing every metric with a
 * stroke paint (2× build cost, zero correctness risk vs manual per-run drawing,
 * which would bypass the platform bidi runs). Prefer [OverlayStyle.hasShadow]
 * (single-pass) where the design allows.
 *
 * @param densityScale `DisplayMetrics.scaledDensity` (explicit parameter — no
 *   hidden `Resources` dependency, deterministic construction).
 */
class ArabicTextLayout private constructor(
    val layout: StaticLayout,
    val text: String,
    val widthPx: Int,
    private val style: OverlayStyle,
    private val densityScale: Float
) {

    /** Laid-out line count (0 for empty input — see [layout]). */
    val lineCount: Int get() = layout.lineCount

    /** Total laid-out height in PX (0 for empty input). */
    val heightPx: Int get() = layout.height

    /** Draws the fill pass at ([x], [y]) — the layout's top-left corner. */
    fun draw(canvas: Canvas, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }

    /**
     * Builds the outline pass sharing this layout's metrics (stroke paint),
     * or null when the style needs no outline.
     */
    fun buildOutline(): StaticLayout? {
        if (!style.hasOutline()) return null
        val overlay = style
        val strokePaint = makePaint(overlay, densityScale).apply {
            overlay.outlineColor?.let { color = it }
            this.style = android.graphics.Paint.Style.STROKE
            strokeWidth = overlay.outlineWidthPx
        }
        return buildStaticLayout(text, widthPx, style, strokePaint)
    }

    companion object {
        /**
         * Lays out [text] in a [availableWidthPx]-wide paragraph.
         *
         * @throws IllegalArgumentException for empty text (blank regions are
         *   filtered upstream by [OverlayRegion.isRenderable]; an empty layout
         *   has no measurable lines and must never be drawn) or non-positive width
         *   (`StaticLayout` requires width > 0).
         */
        fun layout(
            text: String,
            availableWidthPx: Int,
            style: OverlayStyle,
            densityScale: Float
        ): ArabicTextLayout {
            require(text.isNotEmpty()) { "Cannot lay out empty text (filter blank regions first)" }
            require(availableWidthPx > 0) { "availableWidthPx must be > 0, got $availableWidthPx" }
            require(densityScale > 0f && densityScale.isFinite()) {
                "densityScale must be positive finite, got $densityScale"
            }
            val paint = makePaint(style, densityScale)
            val built = buildStaticLayout(text, availableWidthPx, style, paint)
            return ArabicTextLayout(built, text, availableWidthPx, style, densityScale)
        }

        private fun makePaint(style: OverlayStyle, densityScale: Float): TextPaint {
            return TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                color = style.textColor
                textSize = style.textSizeSp * densityScale
                // Master multiplier COMPOSES with the color's own alpha (setAlpha
                // would otherwise clobber a translucent text color to opaque).
                alpha = (android.graphics.Color.alpha(style.textColor) *
                    style.alpha.coerceIn(0f, 1f) + 0.5f).toInt()
                if (style.hasShadow()) {
                    setShadowLayer(style.shadowRadiusPx, 0f, 0f, style.shadowColor)
                }
            }
        }

        private fun buildStaticLayout(
            text: String,
            widthPx: Int,
            style: OverlayStyle,
            paint: TextPaint
        ): StaticLayout {
            val alignment = when (style.alignment) {
                TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                TextAlignment.START -> Layout.Alignment.ALIGN_NORMAL
                TextAlignment.END -> Layout.Alignment.ALIGN_OPPOSITE
            }
            val builder = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, widthPx)
                .setAlignment(alignment)
                .setLineSpacing(style.lineSpacingAddPx, style.lineSpacingMult)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setIncludePad(true)
                .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            // setMaxLines(MAX_VALUE) is the "no limit" default — only constrain
            // when the style actually bounds lines (avoids edge behavior).
            if (style.maxLines < Int.MAX_VALUE) {
                builder.setMaxLines(style.maxLines)
                if (style.ellipsize) {
                    builder.setEllipsize(TextUtils.TruncateAt.END)
                }
            }
            return builder.build()
        }

        /** API floor check (minSdk 29 ≥ Builder's API 23 requirement). */
        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }
}
