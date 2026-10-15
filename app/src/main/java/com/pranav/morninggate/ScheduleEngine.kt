package com.pranav.morninggate

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Computes the morning window and keeps exact alarms armed for the next boundary.
 * (Same engine as Focus Lock's night window, just a different clock.)
 */
object ScheduleEngine {
    const val ACTION_BOUNDARY = "com.pranav.morninggate.action.BOUNDARY"
    const val ACTION_HEARTBEAT = "com.pranav.morninggate.action.HEARTBEAT"

    private const val RC_BOUNDARY = 1001
    private const val RC_HEARTBEAT = 1002
    private const val HEARTBEAT_MS = 15L * 60L * 1000L

    private fun startTime(): LocalTime = LocalTime.of(Prefs.startMinutes / 60, Prefs.startMinutes % 60)
    private fun endTime(): LocalTime = LocalTime.of(Prefs.endMinutes / 60, Prefs.endMinutes % 60)

    fun isInWindow(now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (Prefs.startMinutes == Prefs.endMinutes) return false
        val t = now.toLocalTime()
        val s = startTime()
        val e = endTime()
        return if (s <= e) {
            !t.isBefore(s) && t.isBefore(e)
        } else {
            !t.isBefore(s) || t.isBefore(e)
        }
    }

    /** End of the window that contains [now] (or the next end if outside). */
    fun windowEnd(now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val e = endTime()
        val candidate = now.toLocalDate().atTime(e)
        return if (candidate.isAfter(now)) candidate else now.toLocalDate().plusDays(1).atTime(e)
    }

    fun nextBoundary(now: LocalDateTime = LocalDateTime.now()): LocalDateTime =
        if (isInWindow(now)) windowEnd(now) else {
            val s = startTime()
            val candidate = now.toLocalDate().atTime(s)
            if (candidate.isAfter(now)) candidate else now.toLocalDate().plusDays(1).atTime(s)
        }

    private fun millis(dt: LocalDateTime): Long =
        dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun pending(context: Context, action: String, rc: Int): PendingIntent {
        val i = Intent(context, AlarmReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context, rc, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun setExact(context: Context, triggerMs: Long, pi: PendingIntent) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            }
        } catch (t: Throwable) {
            Logx.add("alarm fallback (${t.javaClass.simpleName}); scheduling inexact")
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            } catch (_: Throwable) {
            }
        }
    }

    fun scheduleNext(context: Context) {
        if (!Prefs.enabled) {
            cancelAll(context)
            Logx.add("schedule: disabled")
            return
        }
        val next = nextBoundary()
        setExact(context, millis(next), pending(context, ACTION_BOUNDARY, RC_BOUNDARY))
        val readable = next.format(DateTimeFormatter.ofPattern("EEE HH:mm"))
        Logx.add("schedule: next boundary $readable")
    }

    /** While inside the window, a repeating safety net that re-arms the service if it died. */
    fun scheduleHeartbeat(context: Context) {
        if (!Prefs.enabled || !isInWindow()) return
        setExact(
            context,
            System.currentTimeMillis() + HEARTBEAT_MS,
            pending(context, ACTION_HEARTBEAT, RC_HEARTBEAT)
        )
    }

    fun cancelHeartbeat(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(context, ACTION_HEARTBEAT, RC_HEARTBEAT))
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(context, ACTION_BOUNDARY, RC_BOUNDARY))
        am.cancel(pending(context, ACTION_HEARTBEAT, RC_HEARTBEAT))
    }

    /** End of today's window in epoch millis (used by the escape). */
    fun windowEndMs(): Long =
        windowEnd().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
