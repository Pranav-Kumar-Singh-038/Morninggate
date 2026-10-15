package com.pranav.morninggate

/**
 * Policy for what may stay in the foreground while the gate is up.
 *
 * Calls always work (dialer/telecom), and app-time criteria grant scoped access
 * to the apps they list while they are still incomplete. Everything else is
 * covered by the gate overlay, and the accessibility watcher bounces it back.
 */
object AllowedApps {
    val MANDATORY: Set<String> = setOf(
        "com.google.android.dialer",   // Pixel dialer + in-call UI
        "com.android.dialer",          // AOSP dialer
        "com.android.incallui",        // older in-call UI
        "com.android.phone",           // telephony UI
        "com.android.server.telecom"   // telecom (never foreground UI, kept for safety)
    )

    fun isMandatory(pkg: String?): Boolean = pkg != null && pkg in MANDATORY
}
