package com.gametranslator.enar

import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gametranslator.enar.render.OverlayController
import com.gametranslator.enar.render.OverlayRect
import com.gametranslator.enar.render.OverlayRegion
import com.gametranslator.enar.render.OverlayRegionView
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Touch pass-through verification on the HONOR device.
 *
 * This does NOT merely inspect flags: real [MotionEvent] tap and swipe streams
 * are dispatched through the REAL attached [OverlayRegionView], and every
 * dispatch must report unconsumed. (The window-level `FLAG_NOT_TOUCHABLE` is
 * asserted separately in [OverlayServiceTest]; the remaining human gate —
 * tapping a real game behind the overlay — is documented as a manual
 * procedure in `docs/testing.md` and stays pending until run.)
 */
@RunWith(AndroidJUnit4::class)
class OverlayTouchTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val density: Float by lazy { context.resources.displayMetrics.scaledDensity }

    private var controller: OverlayController? = null

    private fun granted(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context)

    private fun showWithRegion(): OverlayRegionView {
        assumeTrue("Requires SYSTEM_ALERT_WINDOW grant on device", granted())
        val c = OverlayController(context, FakeOverlayPermission(true))
        controller = c
        if (!c.show()) throw AssertionError("overlay show() failed on device")
        val ok = c.update(
            listOf(OverlayRegion("r1", OverlayRect(10f, 20f, 500f, 220f), "لديك 50 HP باق")),
            density
        )
        if (!ok) throw AssertionError("overlay update() failed on device")
        return c.attachedView() as? OverlayRegionView
            ?: throw AssertionError("overlay view not attached")
    }

    private fun dispatchSync(view: OverlayRegionView, event: MotionEvent): Boolean {
        var consumed = true
        instrumentation.runOnMainSync {
            consumed = view.dispatchTouchEvent(event)
        }
        event.recycle()
        return consumed
    }

    private fun down(x: Float, y: Float): MotionEvent {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
    }

    @After
    fun tearDown() {
        try {
            controller?.hide()
        } catch (_: Throwable) {
        }
        controller = null
    }

    @Test
    fun testTapPassesThrough() {
        val view = showWithRegion()
        val now = SystemClock.uptimeMillis()

        assertFalse("DOWN must not be consumed", dispatchSync(view, down(100f, 100f)))
        val up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 100f, 100f, 0)
        try {
            var consumed = true
            instrumentation.runOnMainSync { consumed = view.dispatchTouchEvent(up) }
            assertFalse("UP must not be consumed", consumed)
        } finally {
            up.recycle()
        }
    }

    @Test
    fun testSwipePassesThrough() {
        val view = showWithRegion()
        val now = SystemClock.uptimeMillis()

        assertFalse("DOWN must not be consumed", dispatchSync(view, down(100f, 300f)))
        for (step in 1..3) {
            val move = MotionEvent.obtain(
                now, now + step, MotionEvent.ACTION_MOVE, 100f + step * 50f, 300f, 0
            )
            assertFalse("MOVE $step must not be consumed", dispatchSync(view, move))
        }
        val up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, 250f, 300f, 0)
        try {
            var consumed = true
            instrumentation.runOnMainSync { consumed = view.dispatchTouchEvent(up) }
            assertFalse("UP must not be consumed", consumed)
        } finally {
            up.recycle()
        }
    }

    @Test
    fun testViewContractIsNonInteractive() {
        val view = showWithRegion()
        assertNotNull(view.parent) // attached to WindowManager
        assertFalse("must not be clickable", view.isClickable)
        assertFalse("must not be focusable", view.isFocusable)
        assertFalse("must not hold focus", view.isFocused)
        var direct = true
        val ev = down(50f, 50f)
        try {
            instrumentation.runOnMainSync { direct = view.onTouchEvent(ev) }
        } finally {
            ev.recycle()
        }
        assertFalse("onTouchEvent must never consume", direct)
    }
}
