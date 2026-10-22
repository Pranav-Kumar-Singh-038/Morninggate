package com.pranav.morninggate

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
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

class MainActivity : Activity() {

    private companion object {
        const val REQ_ALLOWED = 11
    }

    private lateinit var statusView: TextView
    private lateinit var enableSwitch: Switch
    private lateinit var manageA11ySwitch: Switch
    private lateinit var gateToggle: Button
    private lateinit var startButton: Button
    private lateinit var endButton: Button
    private lateinit var tasksColumn: LinearLayout
    private lateinit var allowedView: TextView
    private lateinit var introView: TextView
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
        // Recover a gate that should be up: an armed auto-session, or a
        // ringing alarm whose service couldn't start from the broadcast.
        if (!GateService.isRunning()) {
            when {
                Prefs.alarmRinging -> GateService.startAlarm(this)
                Gate.activeNow() -> GateService.start(this)
            }
        }
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
        introView = body("", 14f, 0xFF8A94A6.toInt())
        col.addView(introView)

        statusView = body("", 16f, Color.WHITE)
        val slp = lp()
        slp.topMargin = dp(18)
        col.addView(statusView, slp)

        gateToggle = Button(this).apply {
            setOnClickListener { toggleGate() }
        }
        val glp = lp()
        glp.topMargin = dp(12)
        col.addView(gateToggle, glp)

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
        val testAlarm = Button(this).apply {
            text = "Test alarm"
            setOnClickListener {
                when {
                    !Checks.hasOverlay(this@MainActivity) -> toast("Grant 'Display over other apps' first")
                    !Prefs.enabled -> toast("Enable the morning schedule first")
                    Gate.tasks.none { it.autoStart } ->
                        toast("No auto-start task — the Wake up task rings at the wake time")
                    else -> {
                        Prefs.alarmRinging = true
                        GateService.startAlarm(this@MainActivity)
                        toast("Wake alarm ringing — complete the mission to silence it")
                    }
                }
            }
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener { GateService.stop(this@MainActivity) }
        }
        tests.addView(preview, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tests.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        tests.addView(testAlarm, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tests.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        tests.addView(stop, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val tslp = lp()
        tslp.topMargin = dp(4)
        col.addView(tests, tslp)

        col.addView(header("Morning tasks"))
        tasksColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tasksColumn)

        col.addView(header("Allowed apps"))
        col.addView(
            body(
                "These apps stay usable while the gate is up — music, maps, whatever you actually need. Calls always work.",
                12f,
                0xFF5A6472.toInt()
            )
        )
        allowedView = body("", 14f, Color.WHITE)
        val alv = lp()
        alv.topMargin = dp(8)
        col.addView(allowedView, alv)
        val allowedButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        allowedButtons.addView(Button(this).apply {
            text = "Choose allowed apps"
            setOnClickListener { pickAllowedApps() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        allowedButtons.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        allowedButtons.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener {
                if (Prefs.allowedApps.isEmpty()) {
                    toast("The allowed list is already empty")
                } else {
                    Prefs.allowedApps = emptySet()
                    Logx.add("allowed apps cleared")
                    refresh()
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(allowedButtons, btnLp())

        col.addView(header("Leaving the gate early"))
        col.addView(
            body(
                "No PIN: while the gate (or the wake alarm) is up, tap \"Emergency bypass\", " +
                    "then tap that screen ${BypassActivity.TAPS_TO_EXIT} times in a row, never " +
                    "pausing more than ${BypassActivity.TAP_RESET_MS / 1000} seconds. Any longer " +
                    "pause resets the count to zero. Completing it releases the gate and silences " +
                    "the alarm until tomorrow.",
                12f,
                0xFF5A6472.toInt()
            )
        )

        col.addView(header("Accessibility watch"))
        manageA11ySwitch = Switch(this).apply {
            text = "Turn accessibility off after the morning"
            setTextColor(Color.WHITE)
            isChecked = Prefs.manageA11y
            setOnCheckedChangeListener { _, checked ->
                Prefs.manageA11y = checked
                if (!checked) Alerts.cancelAccessibilityNeeded(this@MainActivity)
                ScheduleEngine.scheduleNext(this@MainActivity)
                refresh()
            }
        }
        col.addView(manageA11ySwitch, lp())
        col.addView(
            body(
                "When every task is verified (or the gate is bypassed/released for the day), " +
                    "the accessibility watch is switched off so banking and payment apps stop " +
                    "seeing it. Five minutes before the wake time the app tries to switch it " +
                    "back on. Android only permits that automatic re-enable if you grant the " +
                    "one-time ADB permission below; without it you still get a reminder " +
                    "notification and a button in the gate screen.",
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
        if (manageA11ySwitch.isChecked != Prefs.manageA11y) {
            manageA11ySwitch.isChecked = Prefs.manageA11y
        }
        val running = GateService.isRunning()
        val ringing = Prefs.alarmRinging
        val releasedToday = System.currentTimeMillis() < Prefs.releaseUntilMs
        val sessionOpen = ScheduleEngine.sessionEnd() != null
        val wake = Format.minutes(Prefs.startMinutes)
        val doneTasks = Gate.tasks.count { Gate.taskDone(it) }
        val allDone = Gate.tasks.isNotEmpty() && Gate.tasks.all { Gate.taskDone(it) }

        introView.text = "At the wake time the gate comes up on its own and the alarm rings " +
            "until the mission is done. Every app stays blocked until each task is verified. " +
            "Dwell tasks are one-at-a-time timers: tap Start on the gate screen and the clock " +
            "runs until the task is complete, even while you use the phone. Releases at " +
            "${Format.minutes(Prefs.endMinutes)} no matter what — and the phone always works " +
            "for calls."

        statusView.text = when {
            ringing -> "⏰  WAKE ALARM — finish: ${Gate.alarmPendingNames()}"
            running -> "● Gate ACTIVE — $doneTasks of ${Gate.tasks.size} tasks done"
            !Prefs.enabled -> "Morning schedule off — the gate won't start"
            releasedToday -> "Released for today — next wake alarm at $wake"
            allDone -> "All tasks done — good morning!"
            !sessionOpen -> "Window closed — the gate returns tomorrow at $wake"
            else -> "Gate off — it starts automatically at $wake"
        }
        statusView.setTextColor(
            when {
                ringing -> 0xFFE5534B.toInt()
                running -> 0xFF66D19E.toInt()
                else -> 0xFF9AA4B2.toInt()
            }
        )
        startButton.text = "Wake alarm: ${Format.minutes(Prefs.startMinutes)}"
        endButton.text = "Release: ${Format.minutes(Prefs.endMinutes)}"

        gateToggle.text = when {
            running -> "Stop the gate for today"
            !Prefs.enabled -> "Enable the morning schedule to start the gate"
            releasedToday -> "Released for today — the gate stays off until tomorrow"
            !sessionOpen -> "Gate window closed — next start at $wake"
            else -> "Gate starts automatically at $wake"
        }
        gateToggle.isEnabled = running && !ringing

        val allowed = Prefs.allowedApps
        allowedView.text = if (allowed.isEmpty()) {
            "None — everything is blocked except calls."
        } else {
            allowed.joinToString(", ") { Apps.label(this, it) } +
                "\nShown as small shortcuts on the gate screen."
        }
        allowedView.setTextColor(if (allowed.isEmpty()) 0xFF5A6472.toInt() else Color.WHITE)

        refreshTasks()
        refreshPerms()
        logView.text = Logx.snapshot().takeLast(30).joinToString("\n").ifEmpty { "No events yet." }
    }

    private fun toggleGate() {
        if (!GateService.isRunning()) {
            toast(
                "The gate starts on its own at ${Format.minutes(Prefs.startMinutes)} — " +
                    "use Preview to try it now"
            )
            return
        }
        Gate.disarm()
        if (!GateService.isPreview() && Prefs.manageA11y) A11y.disable(this)
        GateService.stop(this)
        ScheduleEngine.cancelHeartbeat(this)
        Logx.add("gate stopped by user")
        refresh()
    }

    private fun pickAllowedApps() {
        startActivityForResult(
            Intent(this, AppPickerActivity::class.java)
                .putStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED, ArrayList(Prefs.allowedApps))
                .putExtra(AppPickerActivity.EXTRA_TITLE, "Allowed apps")
                .putExtra(
                    AppPickerActivity.EXTRA_SUBTITLE,
                    "These apps stay usable while the gate is up. Everything else is blocked."
                ),
            REQ_ALLOWED
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ALLOWED && resultCode == RESULT_OK) {
            val pkgs = data?.getStringArrayListExtra(AppPickerActivity.EXTRA_RESULT) ?: return
            Prefs.allowedApps = pkgs.toSet()
            Logx.add("allowed apps: ${pkgs.size}")
            refresh()
        }
    }

    private fun refreshTasks() {
        tasksColumn.removeAllViews()
        val locked = GateService.isRunning()
        val tasks = Gate.tasks

        if (tasks.isEmpty()) {
            tasksColumn.addView(body("No tasks yet — add one below or tap a preset.", 13f, 0xFF5A6472.toInt()))
        }
        for ((index, t) in tasks.withIndex()) {
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
                nameRow.addView(moveControl("↑", index > 0) { moveTask(t, -1) })
                nameRow.addView(moveControl("↓", index < tasks.lastIndex) { moveTask(t, 1) })
                nameRow.addView(Button(this).apply {
                    text = "×"
                    setOnClickListener { confirmDelete(t) }
                })
            }
            row.addView(nameRow)
            row.addView(body(Model.summary(this, t), 12f, 0xFF9AA4B2.toInt()))
            if (t.autoStart) {
                row.addView(
                    body(
                        "⏰ Fires the wake alarm at ${Format.minutes(Prefs.startMinutes)} — the ring " +
                            "stops only when this task's checks are verified.",
                        11f,
                        0xFFFFC46B.toInt()
                    )
                )
            }
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

    private fun moveControl(label: String, enabled: Boolean, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(if (enabled) 0xFFE6EDF3.toInt() else 0xFF3F4753.toInt())
            setPadding(dp(10), dp(2), dp(10), dp(2))
            isClickable = enabled
            isEnabled = enabled
            setOnClickListener { action() }
        }

    /** Move a task up/down; this is the custom order the gate screen also uses. */
    private fun moveTask(task: Task, delta: Int) {
        val list = Gate.tasks.toMutableList()
        val from = list.indexOfFirst { it.id == task.id }
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        val moved = list.removeAt(from)
        list.add(to, moved)
        Gate.saveTasks(list)
        Logx.add("task order: ${task.name} ${if (delta < 0) "up" else "down"}")
        refresh()
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
        addPerm("Auto-re-enable accessibility (ADB)", A11y.canAutoToggle(this)) {
            showA11yGrantDialog()
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
        addPerm("Camera (QR / barcode)", hasCamera()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 102)
        }
    }

    private fun showA11yGrantDialog() {
        val cmd = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        AlertDialog.Builder(this)
            .setTitle("Optional: automatic accessibility toggle")
            .setMessage(
                "Android does not let an app switch accessibility on by itself.\n\n" +
                    "With USB debugging enabled, run this once from a computer:\n\n" +
                    cmd +
                    "\n\nWithout the grant, Morning Gate still switches the watch off when the " +
                    "day's tasks are done and posts a reminder to switch it back on before the " +
                    "wake time. The gate itself keeps working through usage access."
            )
            .setPositiveButton("Copy command") { _, _ ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("ADB command", cmd))
                toast("ADB command copied")
            }
            .setNegativeButton("Close", null)
            .show()
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

    private fun hasCamera(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

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
