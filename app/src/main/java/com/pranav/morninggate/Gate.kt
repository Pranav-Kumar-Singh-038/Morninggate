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
    private val appTime = HashMap<String, Long>()
    /**
     * One dwell timer runs at a time. These fields persist its start so the
     * countdown survives a service restart. The elapsed time is clock time:
     * dwell no longer needs the screen off or the phone still.
     */
    private var dwellTaskId: String? = null
    private var dwellStartMs: Long = 0L
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
            appTime.clear()
            dwellTaskId = null
            dwellStartMs = 0L
            if (Prefs.alarmRinging) {
                Prefs.alarmRinging = false
                AlarmRinger.stop()
            }
            persist()
        }
    }

    // ---------- arming ----------

    /** The gate arms itself at the wake time; this marks today's automatic session. */
    fun arm() {
        Prefs.armedDay = LocalDate.now().toString()
    }

    fun disarm() {
        Prefs.armedDay = ""
        clearDwellStarts()
    }

    /** Stop the running dwell timer, e.g. when the gate stops or a preview ends. */
    fun clearDwellStarts() {
        if (dwellTaskId == null && dwellStartMs == 0L) return
        dwellTaskId = null
        dwellStartMs = 0L
        persist()
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
            o.optJSONObject("apptime")?.let { aw ->
                val it = aw.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    appTime[k] = aw.optLong(k, 0L)
                }
            }
            o.optString("dwell_task", "").takeIf { it.isNotBlank() }?.let { t ->
                dwellTaskId = t
                dwellStartMs = o.optLong("dwell_since", System.currentTimeMillis())
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
        val aw = JSONObject()
        appTime.forEach { (k, v) -> aw.put(k, v) }
        o.put("apptime", aw)
        dwellTaskId?.let {
            o.put("dwell_task", it)
            o.put("dwell_since", dwellStartMs)
        }
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

    /** Elapsed seconds of the single running dwell timer for [taskId] (0 when it is not running). */
    fun dwellSec(taskId: String): Int {
        if (dwellTaskId != taskId || dwellStartMs <= 0L) return 0
        return ((System.currentTimeMillis() - dwellStartMs) / 1000L).toInt().coerceAtLeast(0)
    }

    /** The task whose dwell timer is running right now, if any. */
    fun activeDwellTaskId(): String? = dwellTaskId

    /** Whether this particular task is the one running its dwell timer. */
    fun isTaskStarted(taskId: String): Boolean = dwellTaskId == taskId

    fun hasPendingDwell(task: Task): Boolean =
        task.criteria.withIndex().any { (i, c) -> c.type == CType.DWELL && !isDone(task.id, i) }

    /**
     * Start the clock for [taskId]. Only one task may run a dwell timer at a
     * time: starting another task replaces (and resets) the previous one, so
     * two morning tasks can never collect dwell credit together.
     */
    fun startTask(taskId: String) {
        val task = tasks.firstOrNull { it.id == taskId } ?: return
        if (!hasPendingDwell(task)) return
        if (dwellTaskId == taskId) return
        val previous = dwellTaskId
        dwellTaskId = taskId
        dwellStartMs = System.currentTimeMillis()
        persist()
        if (previous != null) {
            val prevName = tasks.firstOrNull { it.id == previous }?.name ?: previous
            Logx.add("dwell switched: $prevName -> ${task.name} (previous timer reset)")
        } else {
            Logx.add("dwell started: ${task.name}")
        }
    }

    fun appTimeMs(taskId: String, idx: Int): Long = appTime[key(taskId, idx)] ?: 0L

    fun addAppTime(taskId: String, idx: Int, ms: Long) {
        appTime[key(taskId, idx)] = appTimeMs(taskId, idx) + ms
    }

    /** Apps the gate may release into right now: the user's allowed list plus
     *  the apps that incomplete app-time tasks still need. */
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
     * The wake receiver arms the day automatically, so the gate comes up with
     * the alarm. Dwell still can't accrue until each task is started by hand.
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
