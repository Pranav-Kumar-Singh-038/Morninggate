package com.pranav.morninggate

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Dedicated emergency bypass screen: [TAPS_TO_EXIT] deliberate taps release the
 * gate and silence the wake alarm for the rest of the day. A pause longer than
 * [TAP_RESET_MS] between taps resets the streak to zero.
 *
 * Opens over the gate (or the alarm) via [GateService.launchAux]; backing out
 * simply returns to the lock.
 */
class BypassActivity : Activity() {

    companion object {
        /** How many taps open the gate early. Deliberately tedious. */
        const val TAPS_TO_EXIT = 1000

        /** A pause longer than this between taps resets the streak to zero. */
        const val TAP_RESET_MS = 2_000L
    }

    private lateinit var countView: TextView
    private lateinit var hintView: TextView
    private lateinit var progress: ProgressBar

    private var tapCount = 0
    private var lastTapMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Gate.init(this)
        setContentView(buildUi())
        render(false)
    }

    override fun onResume() {
        super.onResume()
        GateService.onAuxResume()
    }

    override fun onPause() {
        GateService.onAuxPause()
        super.onPause()
    }

    override fun onDestroy() {
        GateService.onAuxDestroy()
        super.onDestroy()
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(72), dp(28), dp(32))
            setBackgroundColor(0xFF07090C.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { countTap() }
        }

        root.addView(TextView(this).apply {
            text = "EMERGENCY BYPASS"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFFE5534B.toInt())
            letterSpacing = 0.3f
        })

        root.addView(TextView(this).apply {
            text = "Releases the gate and silences the wake alarm until tomorrow."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF8A94A6.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        }, topMargin(10))

        countView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        root.addView(countView, topMargin(30))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = TAPS_TO_EXIT
            progress = 0
        }
        val plp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10))
        plp.topMargin = dp(20)
        root.addView(progress, plp)

        hintView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xFFFFC46B.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        }
        root.addView(hintView, topMargin(24))

        root.addView(TextView(this).apply {
            text = "1000 taps is meant to be painful. Pausing more than " +
                "${TAP_RESET_MS / 1000} seconds between taps resets the count."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF5A6472.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        }, topMargin(10))

        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        root.addView(TextView(this).apply {
            text = "Not now — back to the lock"
            setTextColor(0xFF9AA4B2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isClickable = true
            setOnClickListener { finish() }
        })

        return root
    }

    private fun render(reset: Boolean) {
        countView.text = "$tapCount / $TAPS_TO_EXIT"
        progress.progress = tapCount
        hintView.text = when {
            reset && tapCount == 1 -> "Count reset — keep a steady rhythm."
            tapCount == 0 -> "Tap anywhere on the screen to start."
            else -> "Keep tapping anywhere on the screen."
        }
    }

    private fun countTap() {
        val now = SystemClock.uptimeMillis()
        val reset = lastTapMs != 0L && now - lastTapMs > TAP_RESET_MS
        if (reset) tapCount = 0
        lastTapMs = now
        tapCount++
        if (tapCount % 10 == 0) {
            window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        render(reset)
        if (tapCount >= TAPS_TO_EXIT) {
            Logx.add("bypass: $TAPS_TO_EXIT taps")
            GateService.bypassToday(this)
            Toast.makeText(
                this,
                "Released — gate off and alarm silenced until tomorrow",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    // ---------------- helpers ----------------

    private fun topMargin(v: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(v) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
