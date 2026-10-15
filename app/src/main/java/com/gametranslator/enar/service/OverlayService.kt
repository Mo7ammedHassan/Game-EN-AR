package com.gametranslator.enar.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.gametranslator.enar.render.OverlayController
import com.gametranslator.enar.render.SystemOverlayPermission

/**
 * Thin lifecycle host for the overlay window.
 *
 * All rendering state lives in [OverlayController] (service-scoped, reachable
 * in-process via `instance?.controller` for tests and — in Phase 7 — the
 * pipeline); this service only translates lifecycle intents into attach/detach
 * calls and guarantees cleanup on destruction. No regions flow through intents
 * (same-process design — region updates go straight to the controller,
 * avoiding Parcelable plumbing and binder size limits).
 *
 * Isolation: service lifecycle failures (missing permission, WindowManager
 * errors) are contained here and can never reach capture/translation — the
 * controller degrades to log + false.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"

        const val ACTION_SHOW = "com.gametranslator.enar.action.OVERLAY_SHOW"
        const val ACTION_HIDE = "com.gametranslator.enar.action.OVERLAY_HIDE"
        const val ACTION_STOP = "com.gametranslator.enar.action.OVERLAY_STOP"

        /** Last service instance (test/diagnostic hook; cleared on destroy). */
        @Volatile
        var instance: OverlayService? = null
            private set
    }

    /**
     * Service-scoped controller (NOT static: it holds a Context and must die
     * with the service — a static reference would be a StaticFieldLeak).
     * Reach it in-process via [instance]`?.controller` with a null-check.
     */
    lateinit var controller: OverlayController
        private set

    override fun onCreate() {
        super.onCreate()
        // Application context: the controller must never pin this Service instance.
        controller = OverlayController(applicationContext, SystemOverlayPermission(this))
        instance = this
        Log.i(TAG, "OverlayService created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> controller.show()
            ACTION_HIDE -> controller.hide()
            ACTION_STOP -> {
                controller.hide()
                stopSelf()
            }
            else -> Log.v(TAG, "Ignoring unknown/null intent action.")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            if (::controller.isInitialized) {
                controller.hide()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error during overlay teardown: ${t.message}")
        } finally {
            if (instance === this) {
                instance = null
            }
            Log.i(TAG, "OverlayService destroyed.")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
