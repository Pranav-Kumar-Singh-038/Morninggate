package com.pranav.morninggate

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Full-screen wake alarm. The ringtone is driven by [GateService] through
 * [AlarmRinger]; this screen shows the mission behind every auto-start task
 * and stays on top of the lock screen. It cannot be dismissed while ringing —
 * only verifying the mission (or the 1000-tap bypass) silences the alarm.
 */
class AlarmActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val clockFmt = DateTimeFormatter.ofPattern("h:mm a")

    private lateinit var clockView: TextView
    private lateinit var statusView: TextView
    private lateinit var missionColumn: LinearLayout
    private lateinit var startButton: Button
    private lateinit var doneButton: Button

    private class Row(val nameView: TextView, val statuses: List<TextView>)

    private var rowRefs = ArrayList<Row>()
    private var lastKey: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Gate.init(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        GateService.onAuxResume()
        // Fallback: if the service couldn't start from the alarm broadcast, ring
        // from here and start it again now that a foreground activity is up.
        if (Prefs.alarmRinging && !GateService.isRunning()) {
            GateService.startAlarm(this)
        }
        lastKey = null
        render()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        GateService.onAuxPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        GateService.onAuxDestroy()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (Prefs.alarmRinging) return // only the mission (or the bypass) stops the alarm
        super.onBackPressed()
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(52), dp(22), dp(26))
            setBackgroundColor(0xFF120A0A.toInt())
        }

        clockView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 46f)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        root.addView(clockView)

        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.2f
        }
        root.addView(statusView, topMargin(4))

        root.addView(TextView(this).apply {
            text = "The alarm keeps ringing until every check below is verified."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF8A94A6.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        }, topMargin(8))

        missionColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        scroll.addView(
            missionColumn,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val slp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        slp.topMargin = dp(14)
        root.addView(scroll, slp)

        startButton = Button(this).apply {
            text = "Start the gate"
            setOnClickListener {
                Gate.arm()
                GateService.start(this@AlarmActivity)
                Logx.add("gate armed from alarm screen")
                toast("Gate started")
                finish()
            }
        }
        doneButton = Button(this).apply {
            text = "Still ringing"
            setOnClickListener { finish() }
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(startButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        buttons.addView(doneButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons, topMargin(10))

        root.addView(TextView(this).apply {
            text = "Emergency bypass"
            setTextColor(0xFF5A6472.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(4))
            isClickable = true
            setOnClickListener {
                GateService.launchAux(this@AlarmActivity, Intent(this@AlarmActivity, BypassActivity::class.java))
            }
        }, topMargin(2))

        return root
    }

    // ---------------- rendering ----------------

    private fun render() {
        clockView.text = LocalTime.now().format(clockFmt)
        val ringing = Prefs.alarmRinging
        if (ringing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            statusView.text = "⏰  WAKE UP"
            statusView.setTextColor(0xFFE5534B.toInt())
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            statusView.text = "Alarm off — good morning"
            statusView.setTextColor(0xFF66D19E.toInt())
        }

        val tasks = Gate.tasks.filter { it.autoStart }
        val snap = GateService.snapshotNow()
        val key = buildKey(tasks, snap, ringing)
        if (key != lastKey) {
            lastKey = key
            rebuild(tasks, snap)
        }
        updateDynamic(tasks, snap)

        startButton.visibility = if (canStartGate()) View.VISIBLE else View.GONE
        doneButton.isEnabled = !ringing
        doneButton.text = if (ringing) "Mission required" else "Done"
    }

    private fun buildKey(tasks: List<Task>, snap: GateSnapshot?, ringing: Boolean): String {
        val sb = StringBuilder().append(ringing)
        for (t in tasks) {
            sb.append(t.id).append(Gate.taskDone(t)).append('|')
            val tv = snap?.tasks?.firstOrNull { it.id == t.id }
            t.criteria.withIndex().forEach { (i, c) ->
                sb.append(i).append(c.type)
                tv?.crits?.getOrNull(i)?.actions?.forEach { a -> sb.append(a.label).append(',') }
                sb.append(';')
            }
        }
        return sb.toString()
    }

    private fun rebuild(tasks: List<Task>, snap: GateSnapshot?) {
        missionColumn.removeAllViews()
        rowRefs = ArrayList()
        if (tasks.isEmpty()) {
            missionColumn.addView(hintText("No auto-start task — the alarm has nothing to verify."))
            return
        }
        for (t in tasks) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBg()
                setPadding(dp(18), dp(14), dp(18), dp(14))
            }
            val nameView = TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            row.addView(nameView)

            val statuses = ArrayList<TextView>()
            val tv = snap?.tasks?.firstOrNull { it.id == t.id }
            t.criteria.withIndex().forEach { (i, c) ->
                val status = TextView(this).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(0xFF9AA4B2.toInt())
                }
                val slp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                slp.topMargin = dp(5)
                row.addView(status, slp)
                statuses.add(status)

                val actions = tv?.crits?.getOrNull(i)?.actions ?: emptyList()
                if (actions.isNotEmpty()) {
                    val actionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    for (a in actions) {
                        actionsRow.addView(Button(this).apply {
                            text = a.label
                            setOnClickListener { runAction(t.id, i, a) }
                        })
                    }
                    val alp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    alp.topMargin = dp(8)
                    row.addView(actionsRow, alp)
                }
            }
            missionColumn.addView(row, cardLp())
            rowRefs.add(Row(nameView, statuses))
        }
    }

    private fun updateDynamic(tasks: List<Task>, snap: GateSnapshot?) {
        if (rowRefs.size != tasks.size) return
        tasks.forEachIndexed { ti, t ->
            val row = rowRefs[ti]
            val taskDone = Gate.taskDone(t)
            row.nameView.text = (if (taskDone) "✓  " else "") + t.name
            row.nameView.setTextColor(if (taskDone) 0xFF66D19E.toInt() else Color.WHITE)
            val tv = snap?.tasks?.firstOrNull { it.id == t.id }
            t.criteria.forEachIndexed { ci, c ->
                row.statuses.getOrNull(ci)?.let { status ->
                    val done = Gate.isDone(t.id, ci)
                    status.text = (if (done) "✓  " else "") +
                        (tv?.crits?.getOrNull(ci)?.text ?: Model.critSummary(this, c))
                    status.setTextColor(if (done) 0xFF66D19E.toInt() else 0xFF9AA4B2.toInt())
                }
            }
        }
    }

    private fun canStartGate(): Boolean =
        !Prefs.alarmRinging && Prefs.enabled &&
            ScheduleEngine.sessionEnd() != null &&
            System.currentTimeMillis() >= Prefs.releaseUntilMs &&
            !GateService.isRunning() && Gate.tasks.isNotEmpty()

    // ---------------- actions ----------------

    private fun runAction(taskId: String, index: Int, a: CritAction) {
        when {
            a.scan -> GateService.launchAux(
                this,
                Intent(this, ScanActivity::class.java)
                    .putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_USE)
                    .putExtra(ScanActivity.EXTRA_TASK_ID, taskId)
                    .putExtra(ScanActivity.EXTRA_INDEX, index)
            )
            a.tapTag -> GateService.launchAux(
                this,
                Intent(this, TapActivity::class.java)
                    .putExtra(TapActivity.EXTRA_MODE, TapActivity.MODE_USE)
                    .putExtra(TapActivity.EXTRA_TASK_ID, taskId)
                    .putExtra(TapActivity.EXTRA_INDEX, index)
            )
            a.pkg != null -> if (!Apps.launch(this, a.pkg)) {
                Logx.add("launch failed: ${a.pkg}")
            }
        }
    }

    // ---------------- helpers ----------------

    private fun hintText(s: String): TextView = TextView(this).apply {
        text = s
        setTextColor(0xFF5A6472.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
    }

    private fun cardBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(0xFF1C1214.toInt())
    }

    private fun cardLp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(10) }

    private fun topMargin(v: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(v) }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
