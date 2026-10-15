package com.pranav.morninggate

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Placeholder launcher screen. Replaced by the task list / gate setup UI.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
        }

        root.addView(TextView(this).apply {
            text = "Morning Gate"
            textSize = 30f
        })

        root.addView(TextView(this).apply {
            text = "v0.1 scaffold — gate engine coming next"
            textSize = 15f
        })

        setContentView(root)
    }
}
