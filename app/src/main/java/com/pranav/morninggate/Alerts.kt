package com.pranav.morninggate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Low-volume reminders for setup steps Android will not let us perform by
 * itself — currently re-enabling the accessibility watch before the wake time
 * when the one-time ADB grant is missing.
 */
object Alerts {

    private const val CHANNEL_ID = "morning_gate_alerts"
    private const val A11Y_ID = 2

    fun notifyAccessibilityNeeded(context: Context) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Morning Gate reminders",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                ch.description = "Reminders to switch the accessibility watch back on before the wake gate"
                ch.setSound(null, null)
                ch.enableVibration(false)
                nm.createNotificationChannel(ch)
            }
            val open = PendingIntent.getActivity(
                context,
                A11Y_ID,
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_gate)
                .setContentTitle("Turn the accessibility watch back on")
                .setContentText("The wake gate is coming. Tap to enable Morning Gate accessibility.")
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(A11Y_ID, notification)
        } catch (t: Throwable) {
            Logx.add("a11y reminder failed: ${t.javaClass.simpleName}")
        }
    }

    fun cancelAccessibilityNeeded(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java)?.cancel(A11Y_ID)
        } catch (_: Throwable) {
        }
    }
}
