package com.pranav.morninggate

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var enableSwitch: Switch
    private lateinit var startButton: Button
    private lateinit var endButton: Button
    private lateinit var tasksColumn: LinearLayout
    private lateinit var permsColumn: LinearLayout
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Gate.init(this)
        setContentView(buildUi())
        ScheduleEngine.scheduleNext(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(0xFF0B0F14.toInt())
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(30), dp(22), dp(44))
        }
        scroll.addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        col.addView(title("Morning Gate"))
        col.addView(body("Blocks every app from 7:00 AM until your morning tasks are each verified. Releases at 11:00 AM no matter what — and the phone always works for calls.", 14f, 0xFF8A94A6.toInt()))

        statusView = body("", 16f, Color.WHITE)
        val slp = lp()
        slp.topMargin = dp(18)
        col.addView(statusView, slp)

        enableSwitch = Switch(this).apply {
            text = "Morning schedule"
            setTextColor(Color.WHITE)
            isChecked = Prefs.enabled
            setOnCheckedChangeListener { _, checked ->
                Prefs.enabled = checked
                ScheduleEngine.scheduleNext(this@MainActivity)
                if (!checked) GateService.stop(this@MainActivity)
                refresh()
            }
        }
        val elp = lp()
        elp.topMargin = dp(12)
        col.addView(enableSwitch, elp)

        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = Button(this).apply { setOnClickListener { pickTime(true) } }
        endButton = Button(this).apply { setOnClickListener { pickTime(false) } }
        times.addView(startButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        times.addView(endButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val tlp = lp()
        tlp.topMargin = dp(8)
        col.addView(times, tlp)

        val tests = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val preview = Button(this).apply {
            text = "Preview 2 min"
            setOnClickListener {
                when {
                    !Checks.hasOverlay(this@MainActivity) -> toast("Grant 'Display over other apps' first")
                    Gate.tasks.isEmpty() -> toast("Add at least one task first")
                    else -> {
                        GateService.start(this@MainActivity, force = true, autoStopMs = 120_000L)
                        toast("Preview started")
                    }
                }
            }
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener { GateService.stop(this@MainActivity) }
        }
        tests.addView(preview, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tests.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        tests.addView(stop, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val tslp = lp()
        tslp.topMargin = dp(4)
        col.addView(tests, tslp)

        col.addView(header("Morning tasks"))
        tasksColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tasksColumn)

        col.addView(header("Leaving the gate early"))
        col.addView(
            body(
                "No PIN: on the gate screen, tap the empty screen " +
                    "${GateShellView.TAPS_TO_EXIT} times in a row, never pausing more than " +
                    "${GateShellView.TAP_RESET_MS / 1000} seconds. Any longer pause resets the count to zero.",
                12f,
                0xFF5A6472.toInt()
            )
        )

        col.addView(header("Permissions"))
        permsColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(permsColumn)

        col.addView(header("Activity log"))
        logView = body("", 11f, 0xFF6B7686.toInt()).apply { typeface = Typeface.MONOSPACE }
        col.addView(logView)

        return scroll
    }

    private fun refresh() {
        val running = GateService.isRunning()
        val releasedToday = System.currentTimeMillis() < Prefs.releaseUntilMs
        val inWindow = Prefs.enabled && ScheduleEngine.isInWindow()
        val next = ScheduleEngine.nextBoundary().format(DateTimeFormatter.ofPattern("EEE h:mm a"))
        val doneTasks = Gate.tasks.count { Gate.taskDone(it) }
        statusView.text = when {
            running -> "● Gate ACTIVE — $doneTasks of ${Gate.tasks.size} tasks done"
            inWindow && releasedToday -> "Released for today — next gate: $next"
            inWindow -> "Waiting to arm…"
            else -> "Idle — next gate: $next"
        }
        statusView.setTextColor(if (running) 0xFF66D19E.toInt() else 0xFF9AA4B2.toInt())
        startButton.text = "From: ${Format.minutes(Prefs.startMinutes)}"
        endButton.text = "To: ${Format.minutes(Prefs.endMinutes)}"

        refreshTasks()
        refreshPerms()
        logView.text = Logx.snapshot().takeLast(30).joinToString("\n").ifEmpty { "No events yet." }
    }

    private fun refreshTasks() {
        tasksColumn.removeAllViews()
        val locked = GateService.isRunning()
        val tasks = Gate.tasks

        if (tasks.isEmpty()) {
            tasksColumn.addView(body("No tasks yet — add one below or tap a preset.", 13f, 0xFF5A6472.toInt()))
        }
        for (t in tasks) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBg()
                setPadding(dp(16), dp(12), dp(16), dp(12))
                isClickable = !locked
            }
            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            nameRow.addView(TextView(this).apply {
                text = t.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (!locked) {
                nameRow.addView(Button(this).apply {
                    text = "×"
                    setOnClickListener { confirmDelete(t) }
                })
            }
            row.addView(nameRow)
            row.addView(body(Model.summary(this, t), 12f, 0xFF9AA4B2.toInt()))
            if (t.criteria.any { it.type == CType.NFC && it.tagId.isBlank() }) {
                row.addView(body("⚠ No tag bound yet — tap to edit, or bind one from the gate screen.", 11f, 0xFFE5534B.toInt()))
            }
            if (!locked) {
                row.setOnClickListener {
                    startActivity(
                        Intent(this, TaskEditActivity::class.java)
                            .putExtra(TaskEditActivity.EXTRA_TASK_ID, t.id)
                    )
                }
            }
            tasksColumn.addView(row, cardLp())
        }

        if (locked) {
            tasksColumn.addView(body("🔒 Task setup is locked while the gate is active.", 12f, 0xFF5A6472.toInt()))
            return
        }

        tasksColumn.addView(Button(this).apply {
            text = "New task"
            setOnClickListener { startActivity(Intent(this@MainActivity, TaskEditActivity::class.java)) }
        }, btnLp())

        val presets = Model.presets()
        var i = 0
        while (i < presets.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            var j = i
            while (j < presets.size && j < i + 3) {
                val (name, crits) = presets[j]
                row.addView(Button(this).apply {
                    text = name
                    setOnClickListener { addPreset(name, crits) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                })
                j++
            }
            tasksColumn.addView(row, btnLp())
            i += 3
        }
    }

    private fun addPreset(name: String, crits: List<Criterion>) {
        val list = Gate.tasks.toMutableList()
        if (list.any { it.name.equals(name, ignoreCase = true) }) {
            toast("'$name' is already in the list")
            return
        }
        list.add(Task(Model.newId(), name, crits))
        Gate.saveTasks(list)
        Logx.add("preset added: $name")
        refresh()
        toast("$name added — tap it to tweak or bind a tag")
    }

    private fun confirmDelete(t: Task) {
        AlertDialog.Builder(this)
            .setTitle("Delete '${t.name}'?")
            .setMessage("This removes the task and its checks.")
            .setPositiveButton("Delete") { _, _ ->
                Gate.saveTasks(Gate.tasks.filterNot { it.id == t.id })
                Logx.add("task deleted: ${t.name}")
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshPerms() {
        permsColumn.removeAllViews()
        addPerm("Display over other apps", Checks.hasOverlay(this)) {
            startActivity(Checks.overlaySettingsIntent(this))
        }
        addPerm("Accessibility watch", Checks.isA11yEnabled(this)) {
            toast("If the toggle is greyed out: App info → ⋮ → Allow restricted settings")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addPerm("Usage access (fallback)", Checks.hasUsageAccess(this)) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        addPerm("Notifications", hasNotif()) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
        addPerm("Ignore battery optimization", ignoringBattery()) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Throwable) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        if (Build.VERSION.SDK_INT in 31..32) {
            addPerm("Exact alarms", canExact()) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
        addPerm("Physical activity (steps)", hasActivity()) {
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 101)
        }
        addPerm("NFC reader (tag checks)", Checks.isNfcEnabled(this)) {
            startActivity(Checks.nfcSettingsIntent())
        }
    }

    private fun addPerm(name: String, ok: Boolean, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = if (ok) "✓  " else "✗  "
        row.addView(
            body(
                mark + name, 14f,
                if (ok) 0xFF66D19E.toInt() else 0xFFE5534B.toInt()
            ),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (!ok) {
            row.addView(Button(this).apply {
                text = "Grant"
                setOnClickListener { action() }
            })
        }
        val rlp = lp()
        rlp.topMargin = dp(2)
        permsColumn.addView(row, rlp)
    }

    // ---------------- checks ----------------

    private fun hasNotif(): Boolean = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun hasActivity(): Boolean = Build.VERSION.SDK_INT < 29 ||
        checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun ignoringBattery(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun canExact(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    // ---------------- actions ----------------

    private fun pickTime(start: Boolean) {
        val cur = if (start) Prefs.startMinutes else Prefs.endMinutes
        TimePickerDialog(this, { _, h, m ->
            if (start) Prefs.startMinutes = h * 60 + m else Prefs.endMinutes = h * 60 + m
            ScheduleEngine.scheduleNext(this)
            refresh()
        }, cur / 60, cur % 60, false).show()
    }

    // ---------------- helpers ----------------

    private fun title(s: String) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun header(s: String) = TextView(this).apply {
        text = s.uppercase()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(0xFF5A6472.toInt())
        letterSpacing = 0.25f
        setPadding(0, dp(26), 0, dp(6))
    }

    private fun body(s: String, sp: Float, c: Int) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(c)
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
        LinearLayout.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(4) }

    private fun lp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
