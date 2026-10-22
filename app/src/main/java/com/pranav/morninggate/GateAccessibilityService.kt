package com.pranav.morninggate

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Watches which app is in the foreground. Only raises/lowers the gate overlay;
 * all policy lives in [GateService].
 */
class GateAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GateAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Prefs.init(this)
        Logx.add("a11y: connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        Logx.add("a11y: disconnected")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        GateService.onForegroundPackage(pkg)

        // Best-effort: collapse the notification shade / recents while the gate is up.
        if (pkg == "com.android.systemui") {
            val cls = e.className?.toString()?.lowercase() ?: ""
            if (cls.contains("shade") || cls.contains("recents")) {
                handler.postDelayed({
                    if (GateService.isRunning()) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }, 120)
            }
        }
    }

    override fun onInterrupt() {}
}
