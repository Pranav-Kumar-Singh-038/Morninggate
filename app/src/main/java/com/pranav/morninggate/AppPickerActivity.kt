package com.pranav.morninggate

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Multi-select app picker used by app-time checks. Returns the chosen packages
 * as an ArrayList in the result intent.
 */
class AppPickerActivity : Activity() {

    companion object {
        const val EXTRA_SELECTED = "selected"
        const val EXTRA_RESULT = "result"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
    }

    private data class Entry(val pkg: String, val label: String, val icon: Drawable?)

    private val all = ArrayList<Entry>()
    private val chosen = HashSet<String>()
    private lateinit var listColumn: LinearLayout

    private val notRecommended = setOf(
        "com.android.settings",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        intent.getStringArrayListExtra(EXTRA_SELECTED)?.let { chosen.addAll(it) }
        setContentView(buildUi())
        loadApps()
        rebuildRows("")
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(0xFF0B0F14.toInt())
        }

        root.addView(TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE) ?: "Choose apps"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = intent.getStringExtra(EXTRA_SUBTITLE)
                ?: "Time in these apps counts toward the check, and the gate lets you into them while the check is unfinished."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF8A94A6.toInt())
            setLineSpacing(0f, 1.2f)
        })

        val search = EditText(this).apply {
            hint = "Search apps"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF5A6472.toInt())
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    rebuildRows(s?.toString() ?: "")
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        val slp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        slp.topMargin = dp(14)
        root.addView(search, slp)

        listColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        scroll.addView(
            listColumn,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(Button(this).apply {
            text = "Done"
            setOnClickListener {
                setResult(
                    RESULT_OK,
                    Intent().putStringArrayListExtra(EXTRA_RESULT, ArrayList(chosen))
                )
                finish()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        return root
    }

    private fun loadApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || pkg in AllowedApps.MANDATORY) continue
            if (!seen.add(pkg)) continue
            all.add(
                Entry(
                    pkg,
                    ri.loadLabel(pm).toString(),
                    runCatching { ri.loadIcon(pm) }.getOrNull()
                )
            )
        }
        all.sortBy { it.label.lowercase() }
    }

    private fun rebuildRows(query: String) {
        listColumn.removeAllViews()
        val q = query.trim().lowercase()
        for (e in all) {
            if (q.isNotEmpty() && !e.label.lowercase().contains(q)) continue
            listColumn.addView(buildRow(e))
        }
    }

    private fun buildRow(e: Entry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
        }
        val box = CheckBox(this).apply {
            isChecked = chosen.contains(e.pkg)
        }
        val icon = ImageView(this).apply {
            e.icon?.let { setImageDrawable(it) }
        }
        val labelCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labelCol.addView(TextView(this).apply {
            text = e.label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        labelCol.addView(TextView(this).apply {
            text = if (e.pkg in notRecommended) "⚠ allowing this can weaken the gate" else e.pkg
            setTextColor(if (e.pkg in notRecommended) 0xFFE5534B.toInt() else 0xFF5A6472.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })

        row.addView(box)
        val ilp = LinearLayout.LayoutParams(dp(40), dp(40))
        ilp.marginStart = dp(6)
        ilp.marginEnd = dp(12)
        row.addView(icon, ilp)
        row.addView(labelCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.setOnClickListener { toggle(e.pkg, box) }
        box.setOnClickListener { toggle(e.pkg, box) }
        return row
    }

    private fun toggle(pkg: String, box: CheckBox) {
        if (chosen.contains(pkg)) {
            chosen.remove(pkg)
            box.isChecked = false
        } else {
            chosen.add(pkg)
            box.isChecked = true
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
