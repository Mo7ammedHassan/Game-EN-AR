package com.gametranslator.enar

import com.gametranslator.enar.render.OverlayPermission

/** Controllable permission probe for overlay tests (no system settings touched). */
class FakeOverlayPermission(var granted: Boolean) : OverlayPermission {
    override fun canDrawOverlays(): Boolean = granted
}
