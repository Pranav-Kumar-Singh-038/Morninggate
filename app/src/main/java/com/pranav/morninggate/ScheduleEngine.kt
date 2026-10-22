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
 * Computes the morning window and keeps exact alarms armed for the wake time,
 * the release time and the in-window heartbeat.
 *
 * At the wake time the receiver arms the gate automatically and rings the
 * alarm until the auto-start tasks are verified. Dwell checks still only run
 * after the user starts them by hand, so an untouched phone at wake-up earns
 * nothing.
 */
object ScheduleEngine {
    const val ACTION_WAKE = "com.pranav.morninggate.action.WAKE"
    const val ACTION_RELEASE = "com.pranav.morninggate.action.RELEASE"
    const val ACTION_HEARTBEAT = "com.pranav.morninggate.action.HEARTBEAT"
    const val ACTION_A11Y_ON = "com.pranav.morninggate.action.A11Y_ON"

    private const val RC_WAKE = 1001
    private const val RC_RELEASE = 1002
    private const val RC_HEARTBEAT = 1003
    private const val RC_A11Y = 1004
    private const val HEARTBEAT_MS = 15L * 60L * 1000L

    /** Re-enable the accessibility watch a few minutes before the gate arms. */
    private const val A11Y_LEAD_MS = 5L * 60L * 1000L

    private fun startTime(): LocalTime = LocalTime.of(Prefs.startMinutes / 60, Prefs.startMinutes % 60)
    private fun endTime(): LocalTime = LocalTime.of(Prefs.endMinutes / 60, Prefs.endMinutes % 60)

    private fun millis(dt: LocalDateTime): Long =
        dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Next occurrence of the wake time. */
    fun nextWake(now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val candidate = now.toLocalDate().atTime(startTime())
        return if (candidate.isAfter(now)) candidate else now.toLocalDate().plusDays(1).atTime(startTime())
    }

    /** Next occurrence of the release time. */
    fun nextRelease(now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val candidate = now.toLocalDate().atTime(endTime())
        return if (candidate.isAfter(now)) candidate else now.toLocalDate().plusDays(1).atTime(endTime())
    }

    /**
     * End of the session that contains [now]: today's release time while the
     * day still has a session ahead, null once it has passed. Handles windows
     * that cross midnight (start > end).
     */
    fun sessionEnd(now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        if (Prefs.startMinutes == Prefs.endMinutes) return null
        val s = startTime()
        val e = endTime()
        val t = now.toLocalTime()
        return if (s < e) {
            if (t.isBefore(e)) now.toLocalDate().atTime(e) else null
        } else {
            when {
                !t.isBefore(s) -> now.toLocalDate().plusDays(1).atTime(e)
                t.isBefore(e) -> now.toLocalDate().atTime(e)
                else -> null
            }
        }
    }

    fun sessionEndMs(): Long? = sessionEnd()?.let { millis(it) }

    /** True while [now] is inside the configured window (status text only). */
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
        val wake = nextWake()
        val release = nextRelease()
        val wakeMs = millis(wake)
        setExact(context, wakeMs, pending(context, ACTION_WAKE, RC_WAKE))
        setExact(context, millis(release), pending(context, ACTION_RELEASE, RC_RELEASE))

        val a11yPi = pending(context, ACTION_A11Y_ON, RC_A11Y)
        val a11yMs = wakeMs - A11Y_LEAD_MS
        if (Prefs.manageA11y && a11yMs > System.currentTimeMillis()) {
            setExact(context, a11yMs, a11yPi)
        } else {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(a11yPi)
        }

        val f = DateTimeFormatter.ofPattern("EEE HH:mm")
        Logx.add("schedule: wake ${wake.format(f)} · release ${release.format(f)}")
    }

    /** While a gate session is up, a repeating safety net that re-arms the service if it died. */
    fun scheduleHeartbeat(context: Context) {
        if (!Prefs.enabled) return
        if (!Gate.activeNow() && !Prefs.alarmRinging) return
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
        am.cancel(pending(context, ACTION_WAKE, RC_WAKE))
        am.cancel(pending(context, ACTION_RELEASE, RC_RELEASE))
        am.cancel(pending(context, ACTION_HEARTBEAT, RC_HEARTBEAT))
        am.cancel(pending(context, ACTION_A11Y_ON, RC_A11Y))
    }
}
