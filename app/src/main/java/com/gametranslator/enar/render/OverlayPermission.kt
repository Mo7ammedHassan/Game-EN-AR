package com.gametranslator.enar.render

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Overlay permission probe (extracted interface so JVM tests and alternative
 * hosts can substitute a fake; production checks the platform setting).
 */
interface OverlayPermission {
    fun canDrawOverlays(): Boolean
}

/** Production probe backed by [Settings.canDrawOverlays] (API 23+). */
class SystemOverlayPermission(private val context: Context) : OverlayPermission {
    override fun canDrawOverlays(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return Settings.canDrawOverlays(context)
    }
}
