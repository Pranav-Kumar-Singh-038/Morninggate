package com.pranav.morninggate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** What a criterion checks. QR / BARCODE scanning lands in the next build. */
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
 * - DWELL:    [minutes] of phone-away dwell (screen off, untouched, still).
 * - STEPS:    [steps] steps within any [windowMin]-minute window.
 * - NFC:      the bound tag's UID must be tapped ([tagId] empty = not bound yet).
 * - QR/BC:    scanned payload must equal [payload] (empty = not bound yet).
 * - APP_TIME: [minutes] total time spent in any of [packages].
 */
data class Criterion(
    val type: CType,
    val minutes: Int = 0,
    val steps: Int = 0,
    val windowMin: Int = 0,
    val tagId: String = "",
    val payload: String = "",
    val packages: List<String> = emptyList()
)

/** A morning task: every criterion must be verified for the task to be done. */
data class Task(
    val id: String,
    val name: String,
    val criteria: List<Criterion>
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
                val pa = JSONArray()
                c.packages.forEach { pa.put(it) }
                co.put("pkgs", pa)
                ca.put(co)
            }
            to.put("crits", ca)
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
                            packages = pkgs
                        )
                    )
                }
                out.add(
                    Task(
                        id = to.optString("id", newId()),
                        name = to.optString("name", "Task"),
                        criteria = crits
                    )
                )
            }
        } catch (t: Throwable) {
            Logx.add("tasks parse failed: ${t.javaClass.simpleName}")
        }
        return out
    }

    fun critSummary(context: Context, c: Criterion): String = when (c.type) {
        CType.DWELL -> "${c.minutes} min phone-away"
        CType.STEPS -> "${c.steps} steps within ${c.windowMin} min"
        CType.NFC -> if (c.tagId.isBlank()) "NFC tag (not bound)" else "NFC tag"
        CType.QR -> if (c.payload.isBlank()) "QR code (not set)" else "QR code"
        CType.BARCODE -> if (c.payload.isBlank()) "barcode (not set)" else "barcode"
        CType.APP_TIME -> "${c.minutes} min in " +
            (if (c.packages.isEmpty()) "(no app chosen)"
            else c.packages.joinToString(", ") { Apps.label(context, it) })
    }

    fun summary(context: Context, task: Task): String =
        task.criteria.joinToString("  +  ") { critSummary(context, it) }

    /** Quick-add presets. NFC criteria start unbound; bind a tag during setup. */
    fun presets(): List<Pair<String, List<Criterion>>> = listOf(
        "Bath" to listOf(Criterion(CType.NFC), Criterion(CType.DWELL, minutes = 12)),
        "Brush teeth" to listOf(Criterion(CType.DWELL, minutes = 2)),
        "Meds" to listOf(Criterion(CType.NFC), Criterion(CType.DWELL, minutes = 1)),
        "Walk" to listOf(Criterion(CType.STEPS, steps = 300, windowMin = 5)),
        "Study" to listOf(Criterion(CType.APP_TIME, minutes = 25))
    )
}
