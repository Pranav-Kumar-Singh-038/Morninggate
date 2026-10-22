package com.pranav.morninggate

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Best-effort control of the accessibility watch.
 *
 * Android does not let a normal app turn accessibility on or off freely. Two
 * routes exist:
 *
 * - `WRITE_SECURE_SETTINGS`, which can be granted once over ADB. With it the
 *   watch can be toggled automatically at the end of the morning and shortly
 *   before the next wake time.
 * - `disableSelf()`, which an already-connected accessibility service may call
 *   on itself. This needs no extra permission, but it can only switch the
 *   watch **off**. Re-enabling it again needs the user or the ADB grant.
 *
 * When the ADB grant is missing we still switch the watch off at the end of the
 * day, and [Alerts] asks the user to switch it back on before the next wake.
 */
object A11y {

    fun canAutoToggle(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Switch the watch on, if the one-time secure-settings grant is present. */
    fun enable(context: Context): Boolean {
        if (canAutoToggle(context)) {
            if (setViaSecureSettings(context, true)) return true
        }
        if (Checks.isA11yEnabled(context)) return true
        return false
    }

    /**
     * Switch the watch off. Prefers the secure-settings route (which works even
     * if the service is not connected); otherwise the connected service turns
     * itself off.
     */
    fun disable(context: Context): Boolean {
        if (canAutoToggle(context) && setViaSecureSettings(context, false)) return true
        if (!Checks.isA11yEnabled(context)) return true
        val service = GateAccessibilityService.instance ?: return false
        return try {
            service.disableSelf()
            Logx.add("a11y: off (self-disable)")
            true
        } catch (t: Throwable) {
            Logx.add("a11y disable failed: ${t.javaClass.simpleName}")
            false
        }
    }

    private fun setViaSecureSettings(context: Context, on: Boolean): Boolean = try {
        val cr = context.contentResolver
        val me = ComponentName(context, GateAccessibilityService::class.java)
        val full = me.flattenToString()
        val short = me.flattenToShortString()
        val current = Settings.Secure.getString(
            cr,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val services = current.split(':')
            .filter { it.isNotBlank() }
            .toMutableSet()
        services.removeAll { it.equals(full, true) || it.equals(short, true) }
        if (on) services.add(full)
        Settings.Secure.putString(
            cr,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            services.joinToString(":")
        )
        Settings.Secure.putInt(
            cr,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            if (services.isEmpty()) 0 else 1
        )
        Logx.add(if (on) "a11y: on (auto)" else "a11y: off (auto)")
        true
    } catch (t: Throwable) {
        Logx.add("a11y toggle failed: ${t.javaClass.simpleName}")
        false
    }
}
