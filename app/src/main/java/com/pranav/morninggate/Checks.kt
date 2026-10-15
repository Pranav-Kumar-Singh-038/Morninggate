package com.pranav.morninggate

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Process
import android.provider.Settings

/** Small permission / system-state checks shared by the setup screen and the gate service. */
object Checks {

    fun hasOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isA11yEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(context, GateAccessibilityService::class.java)
        return enabled.split(':').any {
            it.equals(me.flattenToString(), true) || it.equals(me.flattenToShortString(), true)
        }
    }

    fun isNfcAvailable(context: Context): Boolean =
        NfcAdapter.getDefaultAdapter(context) != null

    fun isNfcEnabled(context: Context): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return false
        return adapter.isEnabled
    }

    fun nfcSettingsIntent(): Intent = Intent(Settings.ACTION_NFC_SETTINGS)

    fun overlaySettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )
}
