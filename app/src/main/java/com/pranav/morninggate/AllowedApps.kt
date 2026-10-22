package com.pranav.morninggate

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * Policy for what may stay in the foreground while the gate is up.
 *
 * Calls always work (dialer/telecom), keyboard windows stay usable, and app-time
 * criteria grant scoped access to the apps they list while they are still
 * incomplete. Everything else is covered by the gate overlay, and the
 * accessibility watcher bounces it back.
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

    /**
     * Keyboards are separate foreground windows. Without this, opening the
     * keyboard inside an allowed app made the gate think an unknown app had
     * taken over and threw the overlay back over the note being typed.
     */
    fun inputMethodPackages(context: Context): Set<String> {
        val out = HashSet<String>()
        try {
            context.getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.forEach { out.add(it.packageName) }
        } catch (_: Throwable) {
        }
        try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )?.substringBefore('/')?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        } catch (_: Throwable) {
        }
        return out
    }
}
