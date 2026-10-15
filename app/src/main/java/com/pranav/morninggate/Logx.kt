package com.pranav.morninggate

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Tiny in-memory ring buffer so the settings screen can show what happened. */
object Logx {
    private const val MAX = 120
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(msg: String) {
        lines.addLast("${fmt.format(Date())}  $msg")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
