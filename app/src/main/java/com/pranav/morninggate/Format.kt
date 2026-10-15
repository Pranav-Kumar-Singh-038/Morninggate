package com.pranav.morninggate

import java.time.LocalTime
import java.time.format.DateTimeFormatter

object Format {
    private val hma = DateTimeFormatter.ofPattern("h:mm a")

    /** Minutes-since-midnight -> "7:00 AM" */
    fun minutes(m: Int): String {
        val t = LocalTime.of((m / 60) % 24, m % 60)
        return t.format(hma)
    }

    /** Millis -> "2h 41m", "41m", "18s" */
    fun hm(ms: Long): String {
        val totalSec = (ms + 999) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    /** Seconds -> "8:12" (m:ss) */
    fun mmss(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }
}
