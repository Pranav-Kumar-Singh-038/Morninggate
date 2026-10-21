package com.pranav.morninggate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the exact alarms: wake time, release time and the in-session heartbeat. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        Gate.init(context)
        val action = intent.action ?: return
        Logx.add("alarm: ${action.substringAfterLast('.')}")

        ScheduleEngine.scheduleNext(context)

        when (action) {
            ScheduleEngine.ACTION_WAKE -> wakeAlarm(context)
            ScheduleEngine.ACTION_RELEASE -> releaseAlarm(context)
            ScheduleEngine.ACTION_HEARTBEAT -> heartbeat(context)
        }
    }

    /** The wake alarm: the gate arms itself and the phone rings until the auto-start mission is verified. */
    private fun wakeAlarm(context: Context) {
        ScheduleEngine.cancelHeartbeat(context)
        val released = System.currentTimeMillis() < Prefs.releaseUntilMs
        if (!Prefs.enabled || released || ScheduleEngine.sessionEnd() == null) {
            Logx.add("wake alarm skipped (inactive)")
            return
        }

        // The gate starts with the morning — no manual Start needed. Dwell
        // checks still require a deliberate per-task Start on the gate screen,
        // so any start from an earlier preview expires with the wake alarm.
        Gate.arm()
        Gate.clearDwellStarts()
        Logx.add("wake: gate auto-armed")

        if (!Gate.alarmPending()) {
            Logx.add("wake: no pending alarm mission — gate up without ringing")
            try {
                GateService.start(context)
            } catch (t: Throwable) {
                Logx.add("gate start blocked: ${t.javaClass.simpleName}")
            }
            return
        }

        Prefs.alarmRinging = true
        GateService.startAlarm(context)
    }

    private fun releaseAlarm(context: Context) {
        Prefs.alarmRinging = false
        AlarmRinger.stop()
        ScheduleEngine.cancelHeartbeat(context)
        GateService.stop(context)
        Logx.add("released (window end)")
    }

    /** Re-arms the service if it died mid-session. */
    private fun heartbeat(context: Context) {
        if (Gate.activeNow() || Prefs.alarmRinging) {
            try {
                if (Prefs.alarmRinging) GateService.startAlarm(context)
                else GateService.start(context)
            } catch (t: Throwable) {
                Logx.add("FGS start blocked: ${t.javaClass.simpleName}")
            }
            ScheduleEngine.scheduleHeartbeat(context)
        } else {
            ScheduleEngine.cancelHeartbeat(context)
        }
    }
}
