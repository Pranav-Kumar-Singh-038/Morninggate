package com.pranav.morninggate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** What a criterion checks. */
enum class CType {
    DWELL,
    STEPS,
    NFC,
    QR,
    BARCODE,
    APP_TIME
}

/**
 * One completion check inside a task.
 *
 * - DWELL:    [minutes] of clock time after the user taps Start on the gate
 *             screen. No screen-off or still-phone requirement.
 * - STEPS:    [steps] steps within any [windowMin]-minute window.
 * - NFC:      the bound tag's UID must be tapped ([tagId] empty = not bound yet).
 * - QR/BC:    scanned payload must equal [payload] (empty = not bound yet);
 *             [label] is the human name asked when the code is bound.
 * - APP_TIME: [minutes] total time spent in any of [packages].
 */
data class Criterion(
    val type: CType,
    val minutes: Int = 0,
    val steps: Int = 0,
    val windowMin: Int = 0,
    val tagId: String = "",
    val payload: String = "",
    /** Human name for a bound QR / barcode, shown wherever its check is demanded. */
    val label: String = "",
    val packages: List<String> = emptyList()
)

/** A morning task: every criterion must be verified for the task to be done. */
data class Task(
    val id: String,
    val name: String,
    val criteria: List<Criterion>,
    /**
     * Auto-start: at the wake time the phone rings until this task is verified.
     * The gate itself always arms at wake time; this flag only controls the
     * ring. The seeded "Wake up" task has it on.
     */
    val autoStart: Boolean = false
)

object Model {

    fun newId(): String = java.util.UUID.randomUUID().toString().substring(0, 8)

    fun tasksToJson(tasks: List<Task>): String {
        val arr = JSONArray()
        for (t in tasks) {
            val to = JSONObject()
            to.put("id", t.id)
            to.put("name", t.name)
            val ca = JSONArray()
            for (c in t.criteria) {
                val co = JSONObject()
                co.put("type", c.type.name)
                co.put("minutes", c.minutes)
                co.put("steps", c.steps)
                co.put("window", c.windowMin)
                co.put("tag", c.tagId)
                co.put("payload", c.payload)
                co.put("label", c.label)
                val pa = JSONArray()
                c.packages.forEach { pa.put(it) }
                co.put("pkgs", pa)
                ca.put(co)
            }
            to.put("crits", ca)
            to.put("auto", t.autoStart)
            arr.put(to)
        }
        return arr.toString()
    }

    fun tasksFromJson(json: String?): MutableList<Task> {
        val out = mutableListOf<Task>()
        if (json.isNullOrBlank()) return out
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val to = arr.optJSONObject(i) ?: continue
                val crits = mutableListOf<Criterion>()
                val ca = to.optJSONArray("crits") ?: JSONArray()
                for (j in 0 until ca.length()) {
                    val co = ca.optJSONObject(j) ?: continue
                    val type = runCatching {
                        CType.valueOf(co.optString("type", "DWELL"))
                    }.getOrDefault(CType.DWELL)
                    val pkgs = mutableListOf<String>()
                    val pa = co.optJSONArray("pkgs") ?: JSONArray()
                    for (k in 0 until pa.length()) {
                        pa.optString(k)?.takeIf { it.isNotBlank() }?.let { pkgs.add(it) }
                    }
                    crits.add(
                        Criterion(
                            type = type,
                            minutes = co.optInt("minutes", 0),
                            steps = co.optInt("steps", 0),
                            windowMin = co.optInt("window", 0),
                            tagId = co.optString("tag", ""),
                            payload = co.optString("payload", ""),
                            label = co.optString("label", ""),
                            packages = pkgs
                        )
                    )
                }
                out.add(
                    Task(
                        id = to.optString("id", newId()),
                        name = to.optString("name", "Task"),
                        criteria = crits,
                        autoStart = to.optBoolean("auto", false)
                    )
                )
            }
        } catch (t: Throwable) {
            Logx.add("tasks parse failed: ${t.javaClass.simpleName}")
        }
        return out
    }

    fun critSummary(context: Context, c: Criterion): String = when (c.type) {
        CType.DWELL -> "${c.minutes} min dwell timer"
        CType.STEPS -> "${c.steps} steps within ${c.windowMin} min"
        CType.NFC -> if (c.tagId.isBlank()) "NFC tag (not bound)" else "NFC tag"
        CType.QR -> if (c.payload.isBlank()) "QR code (not set)" else "QR code · ${codeRef(c)}"
        CType.BARCODE -> if (c.payload.isBlank()) "barcode (not set)" else "barcode · ${codeRef(c)}"
        CType.APP_TIME -> "${c.minutes} min in " +
            (if (c.packages.isEmpty()) "(no app chosen)"
            else c.packages.joinToString(", ") { Apps.label(context, it) })
    }

    fun summary(context: Context, task: Task): String =
        task.criteria.joinToString("  +  ") { critSummary(context, it) }

    /** How a bound code is referred to in the UI: its name if it has one, else a payload preview. */
    fun codeRef(c: Criterion): String =
        if (c.label.isNotBlank()) "\"${c.label}\"" else shortPay(c.payload)

    private fun shortPay(s: String): String = if (s.length <= 28) s else s.take(28) + "…"

    /** Quick-add presets. NFC / code criteria start unbound; bind one during setup. */
    fun presets(): List<Pair<String, List<Criterion>>> = listOf(
        "Bath" to listOf(Criterion(CType.NFC), Criterion(CType.DWELL, minutes = 12)),
        "Brush teeth" to listOf(Criterion(CType.DWELL, minutes = 2)),
        "Meds" to listOf(Criterion(CType.NFC), Criterion(CType.DWELL, minutes = 1)),
        "Meds (scan)" to listOf(Criterion(CType.BARCODE), Criterion(CType.DWELL, minutes = 1)),
        "Walk" to listOf(Criterion(CType.STEPS, steps = 300, windowMin = 5)),
        "Study" to listOf(Criterion(CType.APP_TIME, minutes = 25))
    )

    /**
     * The seeded first task: a wake-up alarm. At the wake time the phone rings
     * with the alarm sound and only stops once its checks are verified. The
     * default mission is getting out of bed; edit it like any other task.
     */
    fun defaultWakeTask(): Task = Task(
        id = newId(),
        name = "Wake up",
        criteria = listOf(Criterion(CType.STEPS, steps = 50, windowMin = 10)),
        autoStart = true
    )
}
