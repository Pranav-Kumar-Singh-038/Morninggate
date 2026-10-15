package com.pranav.morninggate

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * NFC tag screen. Two modes:
 *  - bind: return the tapped tag's UID to the task editor.
 *  - use:  satisfy a gate check; binds the tag on first tap if it wasn't bound.
 */
class TapActivity : Activity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_USE = "use"
        const val MODE_BIND = "bind"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_INDEX = "index"
        const val EXTRA_UID = "uid"
    }

    private var mode = MODE_USE
    private var taskId = ""
    private var index = -1
    private var reading = false
    private var handled = false

    private lateinit var statusView: TextView
    private lateinit var nfcButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Gate.init(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_USE
        taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: ""
        index = intent.getIntExtra(EXTRA_INDEX, -1)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        GateService.onAuxResume()
        handled = false
        when {
            !Checks.isNfcAvailable(this) -> setStatus("This phone has no NFC reader.")
            !Checks.isNfcEnabled(this) -> {
                setStatus("NFC is off. Turn it on to read the tag.")
                nfcButton.visibility = View.VISIBLE
            }
            else -> {
                nfcButton.visibility = View.GONE
                enableReader()
            }
        }
    }

    override fun onPause() {
        disableReader()
        GateService.onAuxPause()
        super.onPause()
    }

    override fun onDestroy() {
        GateService.onAuxDestroy()
        super.onDestroy()
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(90), dp(28), dp(40))
            setBackgroundColor(0xFF0B0F14.toInt())
        }

        col.addView(TextView(this).apply {
            text = "Tap NFC tag"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTextColor(Color.WHITE)
        })

        col.addView(TextView(this).apply {
            text = if (mode == MODE_BIND) {
                "Hold the phone against the tag you want to bind to this check."
            } else {
                "Hold the phone against the bound tag to verify this check."
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF8A94A6.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        })

        statusView = TextView(this).apply {
            setTextColor(0xFFFFC46B.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
        }
        val slp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        slp.topMargin = dp(30)
        col.addView(statusView, slp)

        nfcButton = Button(this).apply {
            text = "Open NFC settings"
            visibility = View.GONE
            setOnClickListener { GateService.openSettings(this@TapActivity, Checks.nfcSettingsIntent()) }
        }
        val nlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        nlp.topMargin = dp(20)
        col.addView(nfcButton, nlp)

        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        col.addView(TextView(this).apply {
            text = "The tag UID is set at the factory and can't be edited — that's what makes it proof of place."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xFF5A6472.toInt())
            gravity = Gravity.CENTER
        })

        col.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) })

        return col
    }

    private fun setStatus(s: String) {
        statusView.text = s
    }

    // ---------------- NFC ----------------

    private fun enableReader() {
        if (reading) return
        try {
            val adapter = NfcAdapter.getDefaultAdapter(this)
            if (adapter == null) {
                setStatus("NFC is unavailable.")
                return
            }
            adapter.enableReaderMode(
                this,
                { tag -> onTag(tag) },
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null
            )
            reading = true
            setStatus("Hold the phone against the tag…")
        } catch (t: Throwable) {
            setStatus("NFC error: ${t.javaClass.simpleName}")
        }
    }

    private fun disableReader() {
        if (!reading) return
        try {
            NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        } catch (_: Throwable) {
        }
        reading = false
    }

    private fun onTag(tag: Tag) {
        val uid = try {
            tag.id?.joinToString("") { String.format("%02X", it) }
        } catch (_: Throwable) {
            null
        } ?: return
        runOnUiThread { handleUid(uid) }
    }

    private fun handleUid(uid: String) {
        if (handled) return
        if (mode == MODE_BIND) {
            handled = true
            Logx.add("tag bound: $uid")
            setResult(RESULT_OK, Intent().putExtra(EXTRA_UID, uid))
            finish()
            return
        }

        val ti = Gate.tasks.indexOfFirst { it.id == taskId }
        val task = if (ti >= 0) Gate.tasks.getOrNull(ti) else null
        val crit = task?.criteria?.getOrNull(index)
        if (task == null || crit == null) {
            setStatus("This check no longer exists.")
            return
        }

        when {
            crit.tagId.isBlank() -> {
                // First tap binds the tag and satisfies today's check.
                val newCrits = task.criteria.toMutableList()
                newCrits[index] = crit.copy(tagId = uid)
                val newTasks = Gate.tasks.toMutableList()
                newTasks[ti] = task.copy(criteria = newCrits)
                Gate.saveTasks(newTasks)
                Gate.markDone(taskId, index)
                handled = true
                Logx.add("tag bound + done: $uid")
                toast("Tag bound — check complete")
                GateService.requestRefresh()
                finish()
            }
            crit.tagId.equals(uid, ignoreCase = true) -> {
                Gate.markDone(taskId, index)
                handled = true
                Logx.add("tag matched: $uid")
                toast("Tag matched — check complete")
                GateService.requestRefresh()
                finish()
            }
            else -> {
                setStatus("Wrong tag (${uid.take(8)}…). Try again.")
                Logx.add("tag mismatch: $uid")
            }
        }
    }

    // ---------------- helpers ----------------

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
