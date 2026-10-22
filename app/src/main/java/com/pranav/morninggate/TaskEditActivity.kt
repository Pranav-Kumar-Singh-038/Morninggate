package com.pranav.morninggate

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Task editor: name plus one or more completion checks. Two or more checks on
 * one task is "hard mode". Config is only editable while the gate is idle.
 */
class TaskEditActivity : Activity() {

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        private const val REQ_NFC = 21
        private const val REQ_APPS = 22
        private const val REQ_SCAN = 23
        private const val MAX_CHECKS = 6
        private const val KEY_NAME = "edit_name"
        private const val KEY_DRAFTS = "edit_drafts"
        private const val KEY_AUTO = "edit_auto"
        private const val KEY_PENDING_NFC = "edit_pending_nfc"
        private const val KEY_PENDING_APPS = "edit_pending_apps"
        private const val KEY_PENDING_SCAN = "edit_pending_scan"
    }

    private var taskId: String? = null
    private lateinit var nameEdit: EditText
    private lateinit var autoSwitch: Switch
    private lateinit var critsColumn: LinearLayout
    private val drafts = ArrayList<Draft>()
    private var pendingNfc = -1
    private var pendingApps = -1
    private var pendingScan = -1

    private inner class Draft(var crit: Criterion) {
        var minutesEdit: EditText? = null
        var stepsEdit: EditText? = null
        var windowEdit: EditText? = null
        var statusView: TextView? = null

        fun collect() {
            minutesEdit?.let { e ->
                e.text.toString().toIntOrNull()?.let { crit = crit.copy(minutes = it) }
            }
            stepsEdit?.let { e ->
                e.text.toString().toIntOrNull()?.let { crit = crit.copy(steps = it) }
            }
            windowEdit?.let { e ->
                e.text.toString().toIntOrNull()?.let { crit = crit.copy(windowMin = it) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Gate.init(this)

        taskId = intent.getStringExtra(EXTRA_TASK_ID)
        val existing = Gate.tasks.firstOrNull { it.id == taskId }

        // Survive recreation (rotation, memory) with the in-progress edits intact.
        val restored = savedInstanceState?.let { saved ->
            Model.tasksFromJson(saved.getString(KEY_DRAFTS)).firstOrNull()?.criteria
        }
        if (restored != null) {
            drafts.addAll(restored.map { Draft(it) })
        } else if (existing != null) {
            drafts.addAll(existing.criteria.map { Draft(it) })
        }

        setContentView(buildUi(existing))
        if (restored != null) {
            nameEdit.setText(savedInstanceState.getString(KEY_NAME).orEmpty())
            autoSwitch.isChecked = savedInstanceState.getBoolean(KEY_AUTO, existing?.autoStart ?: false)
            pendingNfc = savedInstanceState.getInt(KEY_PENDING_NFC, -1)
            pendingApps = savedInstanceState.getInt(KEY_PENDING_APPS, -1)
            pendingScan = savedInstanceState.getInt(KEY_PENDING_SCAN, -1)
        }
        rebuildCrits()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveDraftValues()
        outState.putString(KEY_NAME, nameEdit.text.toString())
        outState.putBoolean(KEY_AUTO, autoSwitch.isChecked)
        outState.putString(
            KEY_DRAFTS,
            Model.tasksToJson(listOf(Task("draft", "", drafts.map { it.crit })))
        )
        outState.putInt(KEY_PENDING_NFC, pendingNfc)
        outState.putInt(KEY_PENDING_APPS, pendingApps)
        outState.putInt(KEY_PENDING_SCAN, pendingScan)
    }

    // ---------------- UI ----------------

    private fun buildUi(existing: Task?): View {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(0xFF0B0F14.toInt())
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(30), dp(22), dp(44))
        }
        scroll.addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        col.addView(TextView(this).apply {
            text = if (existing != null) "Edit task" else "New task"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })

        nameEdit = EditText(this).apply {
            hint = "Task name"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF5A6472.toInt())
            setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        }
        if (existing != null) nameEdit.setText(existing.name)
        val nlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        nlp.topMargin = dp(14)
        col.addView(nameEdit, nlp)

        col.addView(header("Wake alarm"))
        autoSwitch = Switch(this).apply {
            text = "Auto start (ring at the wake time)"
            setTextColor(Color.WHITE)
            isChecked = existing?.autoStart ?: false
        }
        val aslp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        aslp.topMargin = dp(4)
        col.addView(autoSwitch, aslp)
        col.addView(
            body(
                "When on, the phone rings loudly at the wake time (${Format.minutes(Prefs.startMinutes)}) " +
                    "and only stops once this task's checks are verified. Dwell can't be used here — " +
                    "you'd be asleep, which is the whole point. The gate itself arms automatically " +
                    "at the wake time."
            )
        )

        col.addView(header("Completion checks"))
        col.addView(body("Every check must be verified in the morning. Two or more checks on one task = hard mode."))

        critsColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(critsColumn)

        col.addView(header("Add a check"))
        val addRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addRow1.addView(addButton("Dwell") { addCriterion(Criterion(CType.DWELL, minutes = 2)) }, weight())
        addRow1.addView(addButton("Steps") { addCriterion(Criterion(CType.STEPS, steps = 300, windowMin = 5)) }, weight())
        col.addView(addRow1)
        val addRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addRow2.addView(addButton("NFC tag") { addCriterion(Criterion(CType.NFC)) }, weight())
        addRow2.addView(addButton("App time") { addCriterion(Criterion(CType.APP_TIME, minutes = 25)) }, weight())
        col.addView(addRow2)
        val addRow3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addRow3.addView(addButton("QR code") { addCriterion(Criterion(CType.QR)) }, weight())
        addRow3.addView(addButton("Barcode") { addCriterion(Criterion(CType.BARCODE)) }, weight())
        col.addView(addRow3)
        val alp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        alp.topMargin = dp(6)
        col.addView(
            TextView(this).apply {
                text = "Codes work best when they live where the task happens — mirror, fridge, gym locker."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(0xFF5A6472.toInt())
            },
            alp
        )

        col.addView(Button(this).apply {
            text = "Save task"
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(26) })

        if (existing != null) {
            col.addView(Button(this).apply {
                text = "Delete task"
                setOnClickListener { confirmDelete() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }

        return scroll
    }

    private fun rebuildCrits() {
        critsColumn.removeAllViews()
        if (drafts.isEmpty()) {
            critsColumn.addView(body("No checks yet — add at least one below."))
            return
        }
        drafts.forEachIndexed { i, d ->
            critsColumn.addView(buildCard(i, d), cardLp())
        }
    }

    private fun buildCard(i: Int, d: Draft): View {
        d.minutesEdit = null
        d.stepsEdit = null
        d.windowEdit = null
        d.statusView = null

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(this).apply {
            text = typeTitle(d.crit.type)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(Button(this).apply {
            text = "Remove"
            setOnClickListener {
                saveDraftValues()
                drafts.removeAt(i)
                rebuildCrits()
            }
        })
        card.addView(headerRow)

        when (d.crit.type) {
            CType.DWELL -> {
                card.addView(paramRow("Minutes", d.crit.minutes) { d.minutesEdit = it })
                card.addView(
                    statusLine(
                        "The timer starts when you tap Start dwell on the gate screen. It counts " +
                            "clock time even while you keep using the phone, and only one dwell " +
                            "task can run at a time."
                    )
                )
            }
            CType.STEPS -> {
                card.addView(paramRow("Steps", d.crit.steps) { d.stepsEdit = it })
                card.addView(paramRow("Within minutes", d.crit.windowMin) { d.windowEdit = it })
            }
            CType.NFC -> {
                d.statusView = statusLine(
                    if (d.crit.tagId.isBlank()) "No tag bound yet."
                    else "Bound tag: ${d.crit.tagId}"
                )
                card.addView(d.statusView)
                card.addView(Button(this).apply {
                    text = if (d.crit.tagId.isBlank()) "Bind tag" else "Re-bind tag"
                    setOnClickListener {
                        saveDraftValues()
                        pendingNfc = i
                        startActivityForResult(
                            Intent(this@TaskEditActivity, TapActivity::class.java)
                                .putExtra(TapActivity.EXTRA_MODE, TapActivity.MODE_BIND),
                            REQ_NFC
                        )
                    }
                }, btnLp())
            }
            CType.APP_TIME -> {
                card.addView(paramRow("Minutes", d.crit.minutes) { d.minutesEdit = it })
                d.statusView = statusLine(appsLine(d.crit))
                card.addView(d.statusView)
                card.addView(Button(this).apply {
                    text = "Choose apps"
                    setOnClickListener {
                        saveDraftValues()
                        pendingApps = i
                        startActivityForResult(
                            Intent(this@TaskEditActivity, AppPickerActivity::class.java)
                                .putStringArrayListExtra(
                                    AppPickerActivity.EXTRA_SELECTED,
                                    ArrayList(d.crit.packages)
                                ),
                            REQ_APPS
                        )
                    }
                }, btnLp())
            }
            CType.QR, CType.BARCODE -> {
                d.statusView = statusLine(codeBoundLine(d.crit))
                card.addView(d.statusView)
                card.addView(Button(this).apply {
                    text = if (d.crit.payload.isBlank()) "Scan to bind" else "Scan again"
                    setOnClickListener {
                        saveDraftValues()
                        pendingScan = i
                        startActivityForResult(
                            Intent(this@TaskEditActivity, ScanActivity::class.java)
                                .putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_BIND)
                                .putExtra(
                                    ScanActivity.EXTRA_KIND,
                                    if (d.crit.type == CType.QR) ScanActivity.KIND_QR
                                    else ScanActivity.KIND_BARCODE
                                ),
                            REQ_SCAN
                        )
                    }
                }, btnLp())
                if (d.crit.payload.isNotBlank()) {
                    card.addView(Button(this).apply {
                        text = "Rename code"
                        setOnClickListener {
                            saveDraftValues()
                            askCodeName(i, d.crit.payload)
                        }
                    }, btnLp())
                }
            }
        }
        return card
    }

    private fun appsLine(c: Criterion): String =
        if (c.packages.isEmpty()) "No apps chosen yet."
        else "Apps: " + c.packages.joinToString(", ") { Apps.label(this, it) }

    private fun codeBoundLine(c: Criterion): String {
        if (c.payload.isBlank()) return "No code bound yet — the first scan fills it in."
        val pay = if (c.payload.length <= 36) c.payload else c.payload.take(36) + "…"
        return if (c.label.isBlank()) "Bound: $pay"
        else "Bound: \"${c.label}\" · $pay"
    }

    // ---------------- data ----------------

    private fun addCriterion(c: Criterion) {
        saveDraftValues()
        if (drafts.size >= MAX_CHECKS) {
            toast("Max $MAX_CHECKS checks per task")
            return
        }
        drafts.add(Draft(c))
        rebuildCrits()
    }

    private fun saveDraftValues() {
        drafts.forEach { it.collect() }
    }

    private fun save() {
        saveDraftValues()
        val name = nameEdit.text.toString().trim().ifEmpty { "Task" }
        if (drafts.isEmpty()) {
            toast("Add at least one completion check")
            return
        }
        val auto = autoSwitch.isChecked
        for (d in drafts) {
            val c = d.crit
            when (c.type) {
                CType.DWELL -> if (c.minutes < 1) {
                    toast("Dwell needs at least 1 minute")
                    return
                }
                CType.STEPS -> if (c.steps < 1 || c.windowMin < 1) {
                    toast("Steps needs a count and a window")
                    return
                }
                CType.APP_TIME -> {
                    if (c.minutes < 1) {
                        toast("App time needs at least 1 minute")
                        return
                    }
                    if (c.packages.isEmpty()) {
                        toast("Choose at least one app for app time")
                        return
                    }
                }
                else -> {}
            }
        }
        if (auto && drafts.any { it.crit.type == CType.DWELL }) {
            toast("Auto-start tasks can't use dwell — swap it for steps, a tag or a code")
            return
        }
        val id = taskId ?: Model.newId()
        val task = Task(id, name, drafts.map { it.crit }, autoStart = auto)
        val list = Gate.tasks.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list[idx] = task else list.add(task)
        Gate.saveTasks(list)
        Logx.add("task saved: $name (${task.criteria.size} checks${if (auto) ", auto-start" else ""})")
        finish()
    }

    private fun confirmDelete() {
        val id = taskId ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete this task?")
            .setMessage("This removes the task and its checks.")
            .setPositiveButton("Delete") { _, _ ->
                Gate.saveTasks(Gate.tasks.filterNot { it.id == id })
                Logx.add("task deleted: $id")
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_NFC -> {
                val uid = data?.getStringExtra(TapActivity.EXTRA_UID) ?: return
                saveDraftValues()
                drafts.getOrNull(pendingNfc)?.let { it.crit = it.crit.copy(tagId = uid) }
                pendingNfc = -1
                rebuildCrits()
                toast("Tag bound")
            }
            REQ_APPS -> {
                val pkgs = data?.getStringArrayListExtra(AppPickerActivity.EXTRA_RESULT) ?: return
                saveDraftValues()
                drafts.getOrNull(pendingApps)?.let { it.crit = it.crit.copy(packages = pkgs) }
                pendingApps = -1
                rebuildCrits()
            }
            REQ_SCAN -> {
                val payload = data?.getStringExtra(ScanActivity.EXTRA_PAYLOAD) ?: return
                val i = pendingScan
                if (i < 0) return
                saveDraftValues()
                askCodeName(i, payload)
            }
        }
    }

    /**
     * After a code is scanned, ask what it is. The name is shown on the gate
     * screen so the demand is identifiable ("scan the meds box").
     */
    private fun askCodeName(i: Int, payload: String) {
        val d = drafts.getOrNull(i) ?: return
        val old = d.crit.label
        val edit = EditText(this).apply {
            setText(old)
            hint = "e.g. Meds box, fridge door…"
            setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(
                edit,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        fun apply(label: String) {
            saveDraftValues()
            drafts.getOrNull(i)?.let { it.crit = it.crit.copy(payload = payload, label = label) }
            pendingScan = -1
            rebuildCrits()
            toast(if (label.isBlank()) "Code bound" else "Code bound: $label")
        }
        AlertDialog.Builder(this)
            .setTitle("Name this code")
            .setMessage("The gate shows this name, so you know which code it's demanding.")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ -> apply(edit.text.toString().trim()) }
            .setNegativeButton("Skip") { _, _ -> apply(old) }
            .setOnCancelListener { apply(old) }
            .show()
    }

    // ---------------- widgets ----------------

    private fun addButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun paramRow(label: String, value: Int, assign: (EditText) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF9AA4B2.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val edit = EditText(this).apply {
            setInputType(InputType.TYPE_CLASS_NUMBER)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        edit.setText(value.toString())
        assign(edit)
        val lp = LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        row.addView(edit, lp)
        return row
    }

    private fun statusLine(s: String): TextView = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(0xFF9AA4B2.toInt())
        setPadding(0, dp(8), 0, 0)
    }

    private fun typeTitle(t: CType): String = when (t) {
        CType.DWELL -> "Phone-away dwell"
        CType.STEPS -> "Steps"
        CType.NFC -> "NFC tag"
        CType.QR -> "QR code"
        CType.BARCODE -> "Barcode"
        CType.APP_TIME -> "Time in app"
    }

    private fun header(s: String) = TextView(this).apply {
        text = s.uppercase()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(0xFF5A6472.toInt())
        letterSpacing = 0.25f
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun body(s: String) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(0xFF8A94A6.toInt())
        setLineSpacing(0f, 1.2f)
    }

    private fun cardBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(14).toFloat()
        setColor(0xFF141A22.toInt())
    }

    private fun cardLp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(10) }

    private fun btnLp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
