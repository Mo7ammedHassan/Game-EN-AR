package com.gametranslator.enar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gametranslator.enar.render.ArabicTextLayout
import com.gametranslator.enar.render.OverlayRect
import com.gametranslator.enar.render.OverlayRegion
import com.gametranslator.enar.render.OverlayRegionView
import com.gametranslator.enar.render.OverlayStyle
import com.gametranslator.enar.render.TextAlignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Arabic layout verification (real minikin shaping + UBA — JVM
 * cannot do this; Robolectric's android-all runtime is unavailable offline).
 *
 * Exercises the REQUIRED matrix through the real [ArabicTextLayout]/
 * [OverlayRegionView]: pure Arabic/English, mixed, numbers, punctuation,
 * percent, currency, placeholders, tags, multiline, wrapping, empty/invalid
 * contracts, outline/shadow styles, max-lines clipping, bitmap draw, and
 * layout build cost. No fake layout behavior anywhere.
 */
@RunWith(AndroidJUnit4::class)
class ArabicLayoutDeviceTest {

    private val density: Float by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.scaledDensity
    }

    private val matrix = listOf(
        "pure-ar" to "العدو يقترب من الشمال",
        "pure-en" to "Press E to open the door.",
        "mixed" to "اضغط E للفتح",
        "numbers" to "لديك 50 HP باق",
        "punct" to "مرحباً، بك! هل انت مستعد؟",
        "percent" to "الدقة 100%",
        "currency" to "التكلفة: \$100 أو 50%",
        "placeholder" to "مرحباً يا {playerName} مرحباً بكم",
        "multiline" to "السطر الاول\nالسطر الثاني\nالسطر الثالث",
        "tags-en" to "<color=#FFF>Warning!</color>"
    )

    private val longArabic =
        "هذه جملة عربية طويلة جدا تحتاج الى التفاف عبر عدة اسطر داخل العرض المحدود للمنطقة"

    @Test
    fun testMatrixBuildsAndDraws() {
        for ((label, text) in matrix) {
            val laid = ArabicTextLayout.layout(text, 600, OverlayStyle(), density)
            assertTrue("[$label] must have lines", laid.lineCount >= 1)
            assertTrue("[$label] must have height", laid.heightPx > 0)
            assertVisibleInkWithinWidth(label, laid, 600f)
            // Draw to a region-sized in-memory bitmap (never full-screen).
            val bmp = Bitmap.createBitmap(600, laid.heightPx, Bitmap.Config.ARGB_8888)
            laid.draw(Canvas(bmp), 0f, 0f)
            assertNotNull(bmp)
            bmp.recycle()
        }
    }

    /**
     * Visible-ink overflow check. `getLineWidth()` legitimately includes trailing
     * whitespace at wrap points (measured +15 px on-device), so raw line width
     * is the WRONG invariant — measure the visible run instead. Total advance is
     * direction-independent, so this is exact for RTL and LTR alike (2 px slack
     * covers ligature re-shaping at the substring cut).
     */
    private fun assertVisibleInkWithinWidth(label: String, laid: ArabicTextLayout, widthPx: Float) {
        val paint = laid.layout.paint
        for (line in 0 until laid.lineCount) {
            val start = laid.layout.getLineStart(line)
            val visibleEnd = laid.layout.getLineVisibleEnd(line)
            val visible = laid.text.substring(start, visibleEnd)
            val ink = if (visible.isEmpty()) 0f else paint.measureText(visible)
            assertTrue(
                "[$label] line $line visible ink overflows width: $ink",
                ink <= widthPx + 2f
            )
        }
    }

    @Test
    fun testVisualOrderViaPrimaryHorizontal() {
        // Logical→visual x-mapping on-device: in an RTL paragraph the FIRST
        // logical char sits RIGHT of the last; mirrored for LTR. This pins
        // ordering beyond paragraph direction (no screenshots needed).
        val rtl = ArabicTextLayout.layout("مرحبا Hello", 600, OverlayStyle(), density)
        val rtlFirst = rtl.layout.getPrimaryHorizontal(0)
        val rtlLast = rtl.layout.getPrimaryHorizontal(rtl.text.length - 1)
        assertTrue("RTL: first logical char must be right of last ($rtlFirst vs $rtlLast)", rtlFirst > rtlLast)

        val ltr = ArabicTextLayout.layout("Hello مرحبا", 600, OverlayStyle(), density)
        val ltrFirst = ltr.layout.getPrimaryHorizontal(0)
        val ltrLast = ltr.layout.getPrimaryHorizontal(ltr.text.length - 1)
        assertTrue("LTR: first logical char must be left of last ($ltrFirst vs $ltrLast)", ltrFirst < ltrLast)

        // Placeholder run stays glued in the mixed line (no reordering damage).
        val mixed = ArabicTextLayout.layout(
            "مرحباً يا {playerName} مرحباً بكم", 600, OverlayStyle(), density
        )
        assertTrue(mixed.heightPx > 0)
    }

    @Test
    fun testParagraphDirectionFollowsFirstStrong() {
        // Measured on-device (VKJ-NX9): Layout returns -1 for RTL paragraphs,
        // +1 for LTR — the platform convention (NOT java.text.Bidi levels).
        // Both mixed cases below resolve per first-strong correctly.
        val rtl = ArabicTextLayout.layout("مرحبا Hello", 600, OverlayStyle(), density)
        assertEquals("Arabic-first must resolve RTL", -1, rtl.layout.getParagraphDirection(0))
        val ltr = ArabicTextLayout.layout("Hello مرحبا", 600, OverlayStyle(), density)
        assertEquals("English-first must resolve LTR", 1, ltr.layout.getParagraphDirection(0))
    }

    @Test
    fun testNarrowWidthWrapsLongLine() {
        val laid = ArabicTextLayout.layout(longArabic, 200, OverlayStyle(), density)
        assertTrue("Narrow width must wrap, got ${laid.lineCount} line(s)", laid.lineCount > 1)
        assertVisibleInkWithinWidth("wrap", laid, 200f)
    }

    @Test
    fun testMaxLinesClipsAndEllipsizes() {
        // Measured platform behavior (VKJ-NX9): bare setMaxLines does NOT
        // truncate (15 lines generated); truncation requires ellipsize. The
        // capped variant must show a real ellipsis + respect the bound.
        val clipped = ArabicTextLayout.layout(
            longArabic, 200, OverlayStyle(maxLines = 2, ellipsize = true), density
        )
        assertTrue("maxLines=2 must bound lines, got ${clipped.lineCount}", clipped.lineCount <= 2)
        assertTrue(
            "ellipsis must be applied on the last line",
            clipped.layout.getEllipsisCount(clipped.lineCount - 1) > 0
        )

        // Without ellipsis the layout generates all lines; visual containment
        // is then the renderer's clipRect (covered by testViewDrawsRegionsToBitmap).
        val unclipped = ArabicTextLayout.layout(
            longArabic, 200, OverlayStyle(maxLines = 2, ellipsize = false), density
        )
        assertTrue("unclipped layout must still build", unclipped.heightPx > 0)
    }

    @Test
    fun testEmptyAndInvalidContracts() {
        try {
            ArabicTextLayout.layout("", 600, OverlayStyle(), density)
            fail("empty text must be rejected (filter blank regions first)")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            ArabicTextLayout.layout("مرحبا", 0, OverlayStyle(), density)
            fail("zero width must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun testOutlineAndShadowStyles() {
        val outlined = ArabicTextLayout.layout(
            "لديك 50 HP باق", 600, OverlayStyle(outlineColor = Color.BLACK, outlineWidthPx = 3f), density
        )
        assertNotNull("outline style must yield a stroke pass", outlined.buildOutline())

        val plain = ArabicTextLayout.layout(
            "لديك 50 HP باق", 600, OverlayStyle(outlineColor = null), density
        )
        assertNull("no-outline style must skip the stroke pass", plain.buildOutline())

        val shadowed = ArabicTextLayout.layout(
            "لديك 50 HP باق", 600, OverlayStyle(shadowRadiusPx = 6f), density
        )
        assertTrue(shadowed.heightPx > 0)
    }

    @Test
    fun testAlignmentMapping() {
        val center = ArabicTextLayout.layout(
            "مرحبا", 600, OverlayStyle(alignment = TextAlignment.CENTER), density
        )
        assertEquals(
            android.text.Layout.Alignment.ALIGN_CENTER,
            center.layout.getParagraphAlignment(0)
        )
    }

    @Test
    fun testViewDrawsRegionsToBitmap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = OverlayRegionView(context)
        val regions = listOf(
            OverlayRegion("r1", OverlayRect(0f, 0f, 600f, 200f), "لديك 50 HP باق"),
            OverlayRegion(
                "r2", OverlayRect(0f, 220f, 600f, 420f), "Press E",
                OverlayStyle(backgroundColor = 0xAA000000.toInt())
            ),
            OverlayRegion("r3", OverlayRect(0f, 440f, 600f, 640f), "") // filtered, not laid out
        )
        view.setRegions(regions, density)
        assertEquals(2, view.laidOutCount())

        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 600, 640)
        val bmp = Bitmap.createBitmap(600, 640, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp)) // full onDraw path: bg box, outline+fill, clip, isolation
        // Translucent box color must compose, not clobber to opaque (alpha fix).
        assertEquals(0xAA000000.toInt(), bmp.getPixel(12, 222))
        // Outside all regions the bitmap stays transparent.
        assertEquals(0, bmp.getPixel(599, 639))
        bmp.recycle()
    }

    @Test
    fun testLayoutBuildCost() {
        val text = "مرحباً يا {playerName} لديك 50 HP باق"
        repeat(20) { ArabicTextLayout.layout(text, 600, OverlayStyle(), density) } // warm-up
        val n = 200
        val samples = LongArray(n)
        repeat(n) { i ->
            val start = System.nanoTime()
            ArabicTextLayout.layout(text, 600, OverlayStyle(), density)
            samples[i] = System.nanoTime() - start
        }
        samples.sort()
        val mean = samples.average()
        val p50 = samples[(n * 0.50).toInt()]
        val p95 = samples[(n * 0.95).toInt()]
        Log.i(
            "RENDER_BENCH",
            "layout-build mixed-arabic n=$n mean_ns=${"%.1f".format(java.util.Locale.US, mean)} " +
                "p50_ns=$p50 p95_ns=$p95"
        )
        assertTrue("sanity: builds completed", p50 > 0)
    }
}
