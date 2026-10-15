package com.gametranslator.enar.render

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Process-local overlay window controller (the unit the service and tests drive).
 *
 * Owns exactly one full-screen transparent [OverlayRegionView] added to
 * [WindowManager] with the NON-INTERACTIVE configuration the architecture
 * mandates:
 * ```
 * TYPE_APPLICATION_OVERLAY
 * FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS
 * PixelFormat.TRANSLUCENT
 * ```
 * Outside rendered regions every pixel stays transparent (view background is
 * TRANSLUCENT, regions draw only inside their own bounds).
 *
 * Threading: every public method is safe from any thread — work marshals onto
 * the main looper and CALLERS BLOCK until applied (with a timeout), so tests
 * and pipeline stages observe deterministic state without polling sleeps.
 * A timed-out marshal fails closed (returns false, logs) rather than hanging
 * the caller forever.
 *
 * Failure isolation: permission absence refuses with `false` (never throws);
 * every `WindowManager` call is guarded (bad tokens, dead system service,
 * double-add/remove races all degrade to log + false). Rendering can never
 * crash the capture/translation pipeline through this class.
 *
 * Density: [update] takes an explicit `densityScale` (caller's
 * `DisplayMetrics.scaledDensity`) — no hidden Resources reads, deterministic.
 */
class OverlayController(
    private val context: Context,
    private val permission: OverlayPermission,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) : OverlaySink {

    companion object {
        private const val TAG = "OverlayController"
        private const val MARSHAL_TIMEOUT_MS = 5000L

        /**
         * The exact non-interactive window configuration. Public so device
         * tests assert the REAL attached params (type + every flag + format),
         * not a copy of the constants.
         */
        fun overlayLayoutParams(): WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).also {
                // Window opacity must be deterministic full: the framework default
                // is not pinned across builds, and any dimming here would silently
                // dull every rendered region (observed 0.8 in one dumpsys — whether
                // default or dream-state dimming, explicit 1.0 removes the doubt).
                it.alpha = 1.0f
            }
    }

    private var view: OverlayRegionView? = null

    /** True while the overlay window is attached (volatile: set on main, read anywhere). */
    @Volatile
    var isShowing: Boolean = false
        private set

    /** Attaches the (initially empty) overlay. False when permission is absent. */
    fun show(): Boolean = runOnMain("show", false) {
        if (!permission.canDrawOverlays()) {
            Log.w(TAG, "show() refused: overlay permission absent (no crash, no window).")
            false
        } else if (view != null) {
            true // idempotent: already attached
        } else {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val v = OverlayRegionView(context.applicationContext)
                wm.addView(v, overlayLayoutParams())
                view = v
                isShowing = true
                true
            } catch (t: Throwable) {
                Log.w(TAG, "show() failed: ${t.message}")
                view = null
                isShowing = false
                false
            }
        }
    }

    /**
     * Replaces overlay content (attaches first when permitted but absent).
     * Layouts rebuild only for actually-changed input (view-level short-circuit).
     */
    override fun update(regions: List<OverlayRegion>,
        densityScale: Float): Boolean = runOnMain("update", false) {
        if (!permission.canDrawOverlays()) {
            Log.w(TAG, "update() refused: overlay permission absent.")
            false
        } else {
            val v = view ?: run {
                if (!showInternal()) return@runOnMain false
                view!!
            }
            try {
                v.setRegions(regions, densityScale)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "update() failed: ${t.message}")
                false
            }
        }
    }

    /** Clears content but stays attached (no re-add flicker). False when absent. */
    override fun clear(): Boolean = runOnMain("clear", false) {
        val v = view ?: return@runOnMain false
        try {
            v.clear()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "clear() failed: ${t.message}")
            false
        }
    }

    /** Detaches the window. Idempotent: false when already absent. */
    override fun hide(): Boolean = runOnMain("hide", false) {
        val v = view ?: return@runOnMain false
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(v)
            view = null
            isShowing = false
            true
        } catch (t: Throwable) {
            Log.w(TAG, "hide() failed (treating as detached): ${t.message}")
            view = null
            isShowing = false
            false
        }
    }

    /** Laid-out region count, or -1 when detached (test/diagnostic hook). */
    fun currentCount(): Int = runOnMain("currentCount", -1) { view?.laidOutCount() ?: -1 }

    /** Direct view access for attached-param/event-flow tests (null when detached). */
    fun attachedView(): View? = view

    // ---- internals (all execute on the main thread) ----

    private fun showInternal(): Boolean {
        if (view != null) return true
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = OverlayRegionView(context.applicationContext)
            wm.addView(v, overlayLayoutParams())
            view = v
            isShowing = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "attach failed: ${t.message}")
            view = null
            isShowing = false
            false
        }
    }

    /**
     * Runs [block] on the main thread and returns its result (direct call when
     * already on main; posted + latched otherwise). Never throws: timeout or
     * interruption yields [fallback].
     */
    private fun <T> runOnMain(op: String, fallback: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "$op failed on main: ${t.message}")
                fallback
            }
        }
        var result: T = fallback
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                Log.w(TAG, "$op failed on main: ${t.message}")
                result = fallback
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(MARSHAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "$op timed out waiting for main thread.")
        }
        return result
    }
}
