package com.gametranslator.enar

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gametranslator.enar.render.OverlayController
import com.gametranslator.enar.render.OverlayRect
import com.gametranslator.enar.render.OverlayRegion
import com.gametranslator.enar.render.OverlayRegionView
import com.gametranslator.enar.service.OverlayService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device overlay lifecycle verification (HONOR physical device).
 *
 * Window-attaching tests require the SYSTEM_ALERT_WINDOW grant
 * (`adb shell appops set <pkg> android:system_alert_window allow`, applied
 * before the run); they `assumeTrue`-skip otherwise instead of silently
 * passing. The permission-refusal path uses [FakeOverlayPermission] and always
 * runs. No fake window behavior: real `WindowManager` add/remove, real
 * attached `LayoutParams` asserted field-by-field.
 */
@RunWith(AndroidJUnit4::class)
class OverlayServiceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val density: Float by lazy { context.resources.displayMetrics.scaledDensity }

    private fun granted(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context)

    private fun sampleRegions() = listOf(
        OverlayRegion("r1", OverlayRect(10f, 20f, 500f, 120f), "لديك 50 HP باق"),
        OverlayRegion("r2", OverlayRect(10f, 150f, 500f, 250f), "Press E")
    )

    private fun pollTrue(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (cond()) return
            SystemClock.sleep(100)
        }
        fail("timed out waiting for: $what")
    }

    @After
    fun tearDown() {
        try {
            OverlayService.instance?.controller?.hide()
        } catch (_: Throwable) {
        }
        try {
            context.stopService(Intent(context, OverlayService::class.java))
        } catch (_: Throwable) {
        }
    }

    @Test
    fun testRefusalWithoutPermission() {
        val controller = OverlayController(context, FakeOverlayPermission(false))
        assertFalse("show() must refuse without permission", controller.show())
        assertFalse(controller.isShowing)
        assertFalse("update() must refuse without permission", controller.update(sampleRegions(), density))
        assertFalse(controller.hide()) // nothing attached: idempotent false
    }

    @Test
    fun testServiceShowHideLifecycle() {
        assumeTrue("Requires SYSTEM_ALERT_WINDOW grant on device", granted())

        context.startService(Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW
        })
        pollTrue(5000, "service instance", { OverlayService.instance != null })
        val controller = OverlayService.instance!!.controller
        pollTrue(5000, "overlay attached", { controller.isShowing })
        assertTrue(controller.isShowing)

        context.startService(Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_HIDE
        })
        pollTrue(5000, "overlay detached", { !controller.isShowing })

        context.startService(Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        })
        pollTrue(5000, "service stopped", { OverlayService.instance == null })
    }

    @Test
    fun testAttachedWindowIsNonInteractive() {
        assumeTrue("Requires SYSTEM_ALERT_WINDOW grant on device", granted())

        val controller = OverlayController(context, FakeOverlayPermission(true))
        assertTrue(controller.show())
        try {
            val view = controller.attachedView() as? OverlayRegionView
            assertNotNull("overlay view must be attached", view)
            val params = view!!.layoutParams as WindowManager.LayoutParams
            val expected = OverlayController.overlayLayoutParams()

            assertEquals(
                "must be an application overlay window",
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type
            )
            assertEquals("width must match factory", expected.width, params.width)
            assertEquals("height must match factory", expected.height, params.height)
            assertEquals("format must match factory", expected.format, params.format)
            assertEquals("window alpha must be fully opaque", 1.0f, params.alpha)
            val required = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            assertEquals(
                "NOT_FOCUSABLE|NOT_TOUCHABLE must be set",
                required, params.flags and required
            )
            assertFalse("view must not be clickable", view.isClickable)
            assertFalse("view must not be focusable", view.isFocusable)
        } finally {
            controller.hide()
        }
        assertFalse(controller.isShowing)
    }

    @Test
    fun testUpdateClearAndCounts() {
        assumeTrue("Requires SYSTEM_ALERT_WINDOW grant on device", granted())

        val controller = OverlayController(context, FakeOverlayPermission(true))
        assertTrue(controller.show())
        try {
            assertTrue(controller.update(sampleRegions(), density))
            // update() marshals to main and blocks: count is already settled.
            assertEquals(2, controller.currentCount())

            assertTrue(controller.clear())
            assertEquals(0, controller.currentCount())
            assertTrue("clear() keeps the window attached", controller.isShowing)
        } finally {
            controller.hide()
        }
    }

    @Test
    fun testRepeatedShowHideAndRapidUpdates() {
        assumeTrue("Requires SYSTEM_ALERT_WINDOW grant on device", granted())

        val controller = OverlayController(context, FakeOverlayPermission(true))
        try {
            repeat(3) {
                assertTrue(controller.show())
                assertTrue(controller.show()) // idempotent re-show
                assertTrue(controller.hide())
                assertFalse(controller.hide()) // idempotent re-hide
            }
            assertTrue(controller.show())
            val alt = listOf(
                OverlayRegion("r9", OverlayRect(0f, 0f, 300f, 80f), "نص سريع")
            )
            val updateStartNs = System.nanoTime()
            repeat(50) { i ->
                assertTrue(controller.update(if (i % 2 == 0) sampleRegions() else alt, density))
            }
            val updateTotalNs = System.nanoTime() - updateStartNs
            android.util.Log.i(
                "RENDER_BENCH",
                "multi-region-update x50 total_ns=$updateTotalNs " +
                    "mean_ns=${updateTotalNs / 50} final_count=${controller.currentCount()}"
            )
            assertEquals(1, controller.currentCount())
        } finally {
            controller.hide()
        }
    }
}
