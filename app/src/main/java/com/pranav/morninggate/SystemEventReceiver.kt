package com.pranav.morninggate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Boot, time change and app-update events: keep the schedule armed and the gate correct. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        Gate.init(context)
        val action = intent.action ?: return
        Logx.add("system: ${action.substringAfterLast('.')}")

        ScheduleEngine.scheduleNext(context)

        if (Gate.activeNow()) {
            try {
                GateService.start(context)
            } catch (t: Throwable) {
                Logx.add("FGS start blocked: ${t.javaClass.simpleName}")
            }
        } else {
            GateService.stop(context)
        }
    }
}
