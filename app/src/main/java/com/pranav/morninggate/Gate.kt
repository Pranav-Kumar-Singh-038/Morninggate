package com.pranav.morninggate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * The gate's model layer: the task list plus today's progress, both backed by
 * [Prefs] so receivers, activities and a freshly restarted service all agree.
 * All keys are "taskId:criterionIndex".
 */
object Gate {

    private var taskCache: MutableList<Task>? = null
    private val done = HashSet<String>()
    private val dwell = HashMap<String, Int>()
    private val appTime = HashMap<String, Long>()
    private var loaded = false

    fun init(context: Context) {
        Prefs.init(context)
        load()
        ensureDay()
        ensureDefaults()
    }

    /** First run: seed the "Wake up" alarm task so the morning has a start. */
    private fun ensureDefaults() {
        if (Prefs.wakeSeeded) return
        Prefs.wakeSeeded = true
        if (tasks.isEmpty()) {
            saveTasks(listOf(Model.defaultWakeTask()))
            Logx.add("seeded default 'Wake up' alarm task")
        }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        taskCache = Model.tasksFromJson(Prefs.tasksJson)
        parseProgress(Prefs.progressJson)
    }

    var tasks: MutableList<Task>
        get() {
            load()
            return taskCache ?: mutableListOf<Task>().also { taskCache = it }
        }
        set(v) {
            load()
            taskCache = v
            Prefs.tasksJson = Model.tasksToJson(v)
        }

    fun saveTasks(v: List<Task>) {
        tasks = v.toMutableList()
    }

    /** Fresh progress at midnight; stale keys from edited tasks are harmless. */
    fun ensureDay() {
        load()
        val today = LocalDate.now().toString()
        if (Prefs.day != today) {
            Prefs.day = today
            done.clear()
            dwell.clear()
            appTime.clear()
            if (Prefs.alarmRinging) {
                Prefs.alarmRinging = false
                AlarmRinger.stop()
            }
            persist()
        }
    }

    // ---------- arming ----------

    /** The gate only runs after the user taps Start (or the wake alarm is up). */
    fun arm() {
        Prefs.armedDay = LocalDate.now().toString()
    }

    fun disarm() {
        Prefs.armedDay = ""
    }

    fun armedToday(): Boolean = Prefs.armedDay == LocalDate.now().toString()

    // ---------- wake alarm ----------

    fun alarmTasks(): List<Task> = tasks.filter { it.autoStart }

    /** True while any auto-start task still needs verification. */
    fun alarmPending(): Boolean = alarmTasks().any { !taskDone(it) }

    fun alarmPendingNames(): String =
        alarmTasks().filter { !taskDone(it) }.joinToString(", ") { it.name }.ifEmpty { "Wake up" }

    // ---------- progress plumbing ----------

    private fun parseProgress(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            val o = JSONObject(json)
            val da = o.optJSONArray("done") ?: JSONArray()
            for (i in 0 until da.length()) da.optString(i).takeIf { it.isNotBlank() }?.let { done.add(it) }
            o.optJSONObject("dwell")?.let { dw ->
                val it = dw.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    dwell[k] = dw.optInt(k, 0)
                }
            }
            o.optJSONObject("apptime")?.let { aw ->
                val it = aw.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    appTime[k] = aw.optLong(k, 0L)
                }
            }
        } catch (t: Throwable) {
            Logx.add("progress parse failed: ${t.javaClass.simpleName}")
        }
    }

    private fun encodeProgress(): String {
        val o = JSONObject()
        val da = JSONArray()
        done.sorted().forEach { da.put(it) }
        o.put("done", da)
        val dw = JSONObject()
        dwell.forEach { (k, v) -> dw.put(k, v) }
        o.put("dwell", dw)
        val aw = JSONObject()
        appTime.forEach { (k, v) -> aw.put(k, v) }
        o.put("apptime", aw)
        return o.toString()
    }

    fun persist() {
        Prefs.progressJson = encodeProgress()
    }

    // ---------- criteria state ----------

    fun key(taskId: String, idx: Int): String = "$taskId:$idx"

    fun isDone(taskId: String, idx: Int): Boolean = done.contains(key(taskId, idx))

    fun markDone(taskId: String, idx: Int) {
        if (done.add(key(taskId, idx))) persist()
    }

    fun doneCrits(): Int = tasks.sumOf { t ->
        t.criteria.withIndex().count { isDone(t.id, it.index) }
    }

    fun totalCrits(): Int = tasks.sumOf { it.criteria.size }

    fun taskDone(t: Task): Boolean =
        t.criteria.isNotEmpty() && t.criteria.withIndex().all { isDone(t.id, it.index) }

    fun allDone(): Boolean = tasks.isNotEmpty() && tasks.all { taskDone(it) }

    fun dwellSec(taskId: String, idx: Int): Int = dwell[key(taskId, idx)] ?: 0

    fun addDwell(taskId: String, idx: Int, sec: Int) {
        dwell[key(taskId, idx)] = dwellSec(taskId, idx) + sec
    }

    /** Pickup / screen-on resets every dwell clock that is still running. */
    fun resetDwell() {
        if (dwell.isNotEmpty()) {
            dwell.clear()
            persist()
        }
    }

    fun appTimeMs(taskId: String, idx: Int): Long = appTime[key(taskId, idx)] ?: 0L

    fun addAppTime(taskId: String, idx: Int, ms: Long) {
        appTime[key(taskId, idx)] = appTimeMs(taskId, idx) + ms
    }

    /** Apps the gate may release into right now: the user's allowed list plus the
     *  apps that incomplete app-time tasks still need. */
    fun allowedPackages(): Set<String> {
        val out = HashSet<String>()
        out.addAll(Prefs.allowedApps)
        for (t in tasks) {
            t.criteria.withIndex().forEach { (i, c) ->
                if (c.type == CType.APP_TIME && !isDone(t.id, i)) out.addAll(c.packages)
            }
        }
        return out
    }

    /**
     * Whether the gate should be running right now (ignoring preview/force).
     *
     * Nothing starts on its own: either the user armed it for today, or the
     * wake alarm is ringing. Dwell can therefore never accrue while asleep.
     */
    fun activeNow(): Boolean {
        if (!Prefs.enabled) return false
        if (Prefs.startMinutes == Prefs.endMinutes) return false
        if (System.currentTimeMillis() < Prefs.releaseUntilMs) return false
        ScheduleEngine.sessionEnd() ?: return false
        ensureDay()
        if (tasks.isEmpty()) return false
        if (allDone()) return false
        return armedToday() || Prefs.alarmRinging
    }
}
