package com.pranav.morninggate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The access lifecycle after a morning session.
 *
 * Camera and physical-activity are only needed while a QR/barcode or steps
 * check is actually being completed. At the end of a session we ask Android to
 * revoke them once the app is safely in the background. Everything needed to
 * bring the next wake alarm alive is deliberately kept:
 *
 * - exact alarms / boot receiver — schedule the wake time;
 * - notifications — show the wake mission;
 * - overlay + usage access — run the gate and block apps;
 * - WRITE_SECURE_SETTINGS — switch the accessibility watch back on.
 *
 * Android only permits an app to revoke its own runtime permissions (Android
 * 13+). It cannot revoke overlay, usage access, notification or secure-settings
 * grants itself; those remain in place and are documented in the README.
 */
object Access {

    private val SESSION_ONLY = listOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACTIVITY_RECOGNITION
    )

    /**
     * Schedule revocation of the permissions that only matter during a check.
     * The system performs the revoke asynchronously when the process is in the
     * background, then kills the process and the next request shows the normal
     * grant dialog again.
     */
    fun revokeSessionOnly(context: Context) {
        if (Build.VERSION.SDK_INT < 33) {
            Logx.add("access: automatic revoke needs Android 13+")
            return
        }
        val granted = SESSION_ONLY.filter {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted.isEmpty()) return
        try {
            context.revokeSelfPermissionsOnKill(granted)
            Logx.add("access: camera/activity revoke scheduled")
        } catch (t: Throwable) {
            Logx.add("access cleanup failed: ${t.javaClass.simpleName}")
        }
    }
}
