package com.pranav.morninggate

import android.content.Context
import android.content.SharedPreferences

/**
 * All app state lives in device-protected storage so the gate service can read it
 * even after a reboot before the first unlock (direct boot).
 */
object Prefs {
    private const val FILE = "morninggate"

    @Volatile
    private var sp: SharedPreferences? = null

    fun init(context: Context): SharedPreferences {
        sp?.let { return it }
        synchronized(this) {
            sp?.let { return it }
            val ctx = context.createDeviceProtectedStorageContext()
            val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            sp = p
            return p
        }
    }

    private fun s(): SharedPreferences =
        sp ?: throw IllegalStateException("Prefs.init() was not called")

    var enabled: Boolean
        get() = s().getBoolean("enabled", true)
        set(v) {
            s().edit().putBoolean("enabled", v).apply()
        }

    var startMinutes: Int
        get() = s().getInt("start_min", 7 * 60)
        set(v) {
            s().edit().putInt("start_min", v).apply()
        }

    var endMinutes: Int
        get() = s().getInt("end_min", 11 * 60)
        set(v) {
            s().edit().putInt("end_min", v).apply()
        }

    /** While now < releaseUntilMs the gate will not start (used by the tap escape). */
    var releaseUntilMs: Long
        get() = s().getLong("release_until", 0L)
        set(v) {
            s().edit().putLong("release_until", v).apply()
        }

    /** JSON array of tasks. */
    var tasksJson: String
        get() = s().getString("tasks", "") ?: ""
        set(v) {
            s().edit().putString("tasks", v).apply()
        }

    /** JSON progress blob for the current day. */
    var progressJson: String
        get() = s().getString("progress", "") ?: ""
        set(v) {
            s().edit().putString("progress", v).apply()
        }

    /**
     * Calendar day (yyyy-MM-dd) for which the user manually armed the gate.
     * The gate never starts on its own — dwell must not run while asleep.
     */
    var armedDay: String
        get() = s().getString("armed_day", "") ?: ""
        set(v) {
            s().edit().putString("armed_day", v).apply()
        }

    /**
     * True while the wake alarm is ringing. Persisted so a service restart
     * mid-alarm resumes the ring instead of losing it.
     */
    var alarmRinging: Boolean
        get() = s().getBoolean("alarm_ringing", false)
        set(v) {
            s().edit().putBoolean("alarm_ringing", v).apply()
        }

    /** Apps the user allows while the gate is up (calls always work anyway). */
    var allowedApps: Set<String>
        get() = s().getStringSet("allowed_apps", null)?.toSet() ?: emptySet()
        set(v) {
            s().edit().putStringSet("allowed_apps", HashSet(v)).apply()
        }

    /** The default "Wake up" alarm task is seeded exactly once. */
    var wakeSeeded: Boolean
        get() = s().getBoolean("wake_seeded", false)
        set(v) {
            s().edit().putBoolean("wake_seeded", v).apply()
        }

    /** Calendar day (yyyy-MM-dd) the progress blob belongs to. */
    var day: String
        get() = s().getString("day", "") ?: ""
        set(v) {
            s().edit().putString("day", v).apply()
        }
}
