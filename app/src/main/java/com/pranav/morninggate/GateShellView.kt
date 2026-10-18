package com.pranav.morninggate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The morning gate screen: task list with live verification progress, a phone
 * button (calls always work) and the way into the emergency bypass screen.
 */
class GateShellView(context: Context) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())

    private val mainColumn: LinearLayout
    private val warningView: TextView
    private val summaryView: TextView
    private val tasksColumn: LinearLayout
    private val countdownView: TextView
    private val hintView: TextView

    private var rowRefs = ArrayList<TaskRow>()
    private var lastKey: String? = null

    private var batteryRegistered = false
    private var batteryPct: Int? = null

    private class TaskRow(val nameView: TextView, val statuses: List<TextView>)

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            readBattery(i)
            updateHint()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000L)
        }
    }

    init {
        Prefs.init(context)
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true

        mainColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(76), dp(22), dp(30))
        }
        addView(mainColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        mainColumn.addView(
            label(
                "MORNING GATE · ${Format.minutes(Prefs.startMinutes)} – ${Format.minutes(Prefs.endMinutes)}",
                12f
            )
        )

        warningView = TextView(context).apply {
            setTextColor(0xFFE5534B.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val wlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        wlp.topMargin = dp(12)
        mainColumn.addView(warningView, wlp)

        summaryView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 27f)
            gravity = Gravity.CENTER
        }
        val slp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        slp.topMargin = dp(16)
        mainColumn.addView(summaryView, slp)

        tasksColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(context)
        scroll.addView(
            tasksColumn,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        val scrollLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        scrollLp.topMargin = dp(18)
        mainColumn.addView(scroll, scrollLp)

        countdownView = TextView(context).apply {
            setTextColor(0xFF9AA4B2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }
        val clp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        clp.topMargin = dp(6)
        mainColumn.addView(countdownView, clp)

        // Phone button — calls always get through.
        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            setOnClickListener { openDialer() }
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_phone)
            setColorFilter(Color.BLACK)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        circle.addView(
            icon,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val circleLp = LinearLayout.LayoutParams(dp(76), dp(76))
        circleLp.topMargin = dp(18)
        mainColumn.addView(circle, circleLp)

        hintView = TextView(context).apply {
            setTextColor(0xFF5A6472.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val hlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        hlp.topMargin = dp(10)
        mainColumn.addView(hintView, hlp)
        updateHint()

        // Emergency bypass: a separate screen where 1000 deliberate taps
        // release the gate and silence the alarm. Deliberately understated.
        val bypass = TextView(context).apply {
            text = "Emergency bypass"
            setTextColor(0xFF3F4753.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            setOnClickListener { openBypass() }
        }
        val blp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        blp.topMargin = dp(6)
        mainColumn.addView(bypass, blp)

        render()
    }

    // ------- lifecycle -------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try {
            val sticky = context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryRegistered = true
            readBattery(sticky)
            updateHint()
        } catch (t: Throwable) {
            Logx.add("battery: ${t.javaClass.simpleName}")
        }
        handler.post(ticker)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        if (batteryRegistered) {
            try {
                context.unregisterReceiver(batteryReceiver)
            } catch (_: Throwable) {
            }
            batteryRegistered = false
        }
        super.onDetachedFromWindow()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true // this screen is the gate
        return super.onKeyDown(keyCode, event)
    }

    // ------- rendering -------

    /** Re-render immediately (called by the service after state changes). */
    fun refresh() {
        render()
    }

    private fun render() {
        val snap = GateService.snapshotNow() ?: return
        val left = (snap.releaseAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
        countdownView.text =
            "Auto-release at ${Format.minutes(Prefs.endMinutes)} · ${Format.hm(left)} left"
        summaryView.text = "${snap.doneTasks} of ${snap.totalTasks} tasks done"
        summaryView.setTextColor(
            if (snap.totalTasks > 0 && snap.doneTasks == snap.totalTasks) 0xFF66D19E.toInt()
            else Color.WHITE
        )
        val w = snap.warning
        if (w.isNullOrBlank()) {
            warningView.visibility = View.GONE
        } else {
            warningView.text = w.trim()
            warningView.visibility = View.VISIBLE
        }
        val key = buildKey(snap)
        if (key != lastKey) {
            lastKey = key
            rebuildTasks(snap)
        }
        updateDynamic(snap)
    }

    private fun buildKey(snap: GateSnapshot): String {
        val sb = StringBuilder()
        snap.tasks.forEach { t ->
            sb.append(t.id).append(t.done).append('|')
            t.crits.forEach { c ->
                sb.append(c.index).append(c.type).append(c.done).append('|')
                c.actions.forEach { a -> sb.append(a.label).append(',') }
                sb.append(';')
            }
        }
        sb.append(snap.warning.isNullOrBlank()).append(snap.totalTasks)
        return sb.toString()
    }

    private fun rebuildTasks(snap: GateSnapshot) {
        tasksColumn.removeAllViews()
        rowRefs = ArrayList()
        if (snap.tasks.isEmpty()) {
            tasksColumn.addView(
                hintText("No tasks configured — set them up in Morning Gate before tomorrow.")
            )
            return
        }
        for (t in snap.tasks) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBg()
                setPadding(dp(18), dp(14), dp(18), dp(14))
            }
            val nameView = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            row.addView(nameView)

            val statuses = ArrayList<TextView>()
            for (c in t.crits) {
                val status = TextView(context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(0xFF9AA4B2.toInt())
                }
                val slp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                slp.topMargin = dp(5)
                row.addView(status, slp)
                statuses.add(status)

                if (c.actions.isNotEmpty()) {
                    val actionsRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    for (a in c.actions) {
                        actionsRow.addView(Button(context).apply {
                            text = a.label
                            setOnClickListener {
                                when {
                                    a.scan -> GateService.launchAux(
                                        context,
                                        Intent(context, ScanActivity::class.java)
                                            .putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_USE)
                                            .putExtra(ScanActivity.EXTRA_TASK_ID, t.id)
                                            .putExtra(ScanActivity.EXTRA_INDEX, c.index)
                                    )
                                    a.tapTag -> GateService.launchAux(
                                        context,
                                        Intent(context, TapActivity::class.java)
                                            .putExtra(TapActivity.EXTRA_MODE, TapActivity.MODE_USE)
                                            .putExtra(TapActivity.EXTRA_TASK_ID, t.id)
                                            .putExtra(TapActivity.EXTRA_INDEX, c.index)
                                    )
                                    a.pkg != null -> if (!Apps.launch(context, a.pkg)) {
                                        Logx.add("launch failed: ${a.pkg}")
                                    }
                                }
                            }
                        })
                    }
                    val alp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    alp.topMargin = dp(8)
                    row.addView(actionsRow, alp)
                }
            }

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10)
            tasksColumn.addView(row, lp)
            rowRefs.add(TaskRow(nameView, statuses))
        }
    }

    private fun updateDynamic(snap: GateSnapshot) {
        if (rowRefs.size != snap.tasks.size) return
        snap.tasks.forEachIndexed { ti, t ->
            val row = rowRefs[ti]
            row.nameView.text = (if (t.done) "✓  " else "") + t.name
            row.nameView.setTextColor(if (t.done) 0xFF66D19E.toInt() else Color.WHITE)
            t.crits.forEachIndexed { ci, c ->
                row.statuses.getOrNull(ci)?.let { tv ->
                    tv.text = (if (c.done) "✓  " else "") + c.text
                    tv.setTextColor(if (c.done) 0xFF66D19E.toInt() else 0xFF9AA4B2.toInt())
                }
            }
        }
    }

    // ------- actions -------

    private fun openDialer() {
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Logx.add("dialer launch failed: ${t.message}")
        }
    }

    private fun openBypass() {
        GateService.launchAux(context, Intent(context, BypassActivity::class.java))
    }

    private fun readBattery(intent: Intent?) {
        batteryPct = if (intent == null) {
            null
        } else {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) level * 100 / scale else null
        }
    }

    private fun updateHint() {
        val b = batteryPct
        hintView.text = "Emergency calls always work" + (b?.let { " · $it%" } ?: "")
    }

    // ------- helpers -------

    private fun cardBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(0xFF141A22.toInt())
    }

    private fun label(s: String, sp: Float): TextView = TextView(context).apply {
        text = s
        setTextColor(0xFF5A6472.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        gravity = Gravity.CENTER
        letterSpacing = 0.25f
    }

    private fun hintText(s: String): TextView = TextView(context).apply {
        text = s
        setTextColor(0xFF5A6472.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
