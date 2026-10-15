package com.gametranslator.enar.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.StaticLayout
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View

/**
 * Transparent overlay canvas hosting independently positioned laid-out regions.
 *
 * Non-interactive by construction AND by contract: not clickable, not focusable,
 * excluded from accessibility, and [onTouchEvent] unconditionally returns false
 * so every gesture falls through to whatever is underneath (the window-level
 * `FLAG_NOT_TOUCHABLE` makes this belt-and-braces). Verified by dispatched-event
 * tests, not by flag inspection alone.
 *
 * Performance contract (real-time pipeline):
 * - No bitmaps anywhere: glyphs draw straight to the canvas (vector text).
 * - Layouts are built ONLY inside [setRegions], and only when the input list
 *   actually changed (data-class equality short-circuit — repeated identical
 *   updates cost one list comparison, zero layout rebuilds, zero invalidates).
 * - `invalidate()` fires only on real content change — no per-frame redraws.
 * - One reusable [Paint] for all background boxes (no per-item allocation).
 * - Drawing is clipped to each region's bounds (overflow containment per style).
 * - Any single-region draw failure is caught, logged, and isolated — one bad
 *   region can never blank the rest or crash the pipeline.
 *
 * Main thread only (like all views). [OverlayController] marshals calls there.
 */
class OverlayRegionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "OverlayRegionView"
    }

    /** One region with its cached layouts (stroke is null unless outlined). */
    data class LaidOut(
        val region: OverlayRegion,
        val fill: ArabicTextLayout,
        val stroke: StaticLayout?
    )

    private var items: List<LaidOut> = emptyList()
    private var lastInput: List<OverlayRegion> = emptyList()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Replaces the region set (blank/unrenderable regions filtered).
     *
     * @return true when visual state changed (layouts rebuilt + invalidated).
     */
    fun setRegions(regions: List<OverlayRegion>, densityScale: Float): Boolean {
        if (regions == lastInput) return false
        lastDensityScale = densityScale
        lastInput = regions.toList()
        items = lastInput.filter { it.isRenderable() }.map { region ->
            val textWidthPx = maxOf(
                1,
                (region.boundsPx.width - 2 * region.style.paddingPx).toInt()
            )
            val fill = ArabicTextLayout.layout(region.text, textWidthPx, region.style, densityScale)
            LaidOut(region, fill, fill.buildOutline())
        }
        invalidate()
        return true
    }

    /** Removes all content (view stays attached; use controller.hide() to detach). */
    fun clear(): Boolean = setRegions(emptyList(), lastDensityScale)

    /** Laid-out (drawable) region count — test/diagnostic hook. */
    fun laidOutCount(): Int = items.size

    private var lastDensityScale: Float = 1f

    /** Explicit touch pass-through: this view never consumes gestures. */
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (item in items) {
            try {
                drawItem(canvas, item)
            } catch (t: Throwable) {
                Log.w(TAG, "Dropping failed region '${item.region.id}': ${t.message}")
            }
        }
    }

    private fun drawItem(canvas: Canvas, item: LaidOut) {
        val region = item.region
        val style = region.style
        val pad = style.paddingPx.toFloat()
        val x = region.boundsPx.left + pad
        val y = region.boundsPx.top + pad

        canvas.save()
        canvas.clipRect(
            region.boundsPx.left,
            region.boundsPx.top,
            region.boundsPx.right,
            region.boundsPx.bottom
        )
        try {
            style.backgroundColor?.let { color ->
                backgroundPaint.color = color
                // Compose, don't clobber: a translucent box color keeps its alpha.
                backgroundPaint.alpha = (android.graphics.Color.alpha(color) *
                    style.alpha.coerceIn(0f, 1f) + 0.5f).toInt()
                backgroundPaint.style = Paint.Style.FILL
                canvas.drawRect(
                    region.boundsPx.left,
                    region.boundsPx.top,
                    region.boundsPx.right,
                    region.boundsPx.bottom,
                    backgroundPaint
                )
            }
            // Outline pass UNDER the fill pass (stroke first, then fill).
            item.stroke?.let { stroke ->
                canvas.save()
                canvas.translate(x, y)
                stroke.draw(canvas)
                canvas.restore()
            }
            item.fill.draw(canvas, x, y)
        } finally {
            canvas.restore()
        }
    }
}
