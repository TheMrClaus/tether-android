package com.tether.app.ui.overview

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

/**
 * T15.2: components/overview/overview-format.ts — small, pure display helpers. No clock reads:
 * callers pass `now`.
 */
object OverviewFormat {

    /** overview-format.ts:4 — compact elapsed time: "<1m", "4m", "2h 14m", "3d". */
    fun duration(ms: Long): String {
        if (ms < 60_000) return "<1m"
        val minutes = ms / 60_000
        if (minutes < 60) return "${minutes}m"
        val hours = minutes / 60
        if (hours < 24) return if (minutes % 60 != 0L) "${hours}h ${minutes % 60}m" else "${hours}h"
        return "${hours / 24}d"
    }

    /** overview-format.ts:14 — the spoken form of [duration], for TalkBack. */
    fun describeDuration(ms: Long): String {
        if (ms < 60_000) return "less than a minute"
        val minutes = ms / 60_000
        if (minutes < 60) return "$minutes minute${if (minutes == 1L) "" else "s"}"
        val hours = minutes / 60
        if (hours < 24) {
            val rest = minutes % 60
            return "$hours hour${if (hours == 1L) "" else "s"}" + (if (rest != 0L) " $rest minute${if (rest == 1L) "" else "s"}" else "")
        }
        val days = hours / 24
        return "$days day${if (days == 1L) "" else "s"}"
    }

    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    /** overview-format.ts:27 — `toLocaleTimeString([], {hour: "2-digit", minute: "2-digit", hourCycle: "h23"})`. */
    fun clock(ts: Long, zone: ZoneId = ZoneId.systemDefault()): String = clock.format(Instant.ofEpochMilli(ts).atZone(zone))

    /** overview-format.ts:32 — "just now" / "3m ago"; "" when unknown. */
    fun ago(ts: Long?, now: Long): String {
        if (ts == null || ts == 0L) return ""
        val delta = max(0L, now - ts)
        if (delta < 60_000) return "just now"
        return "${duration(delta)} ago"
    }

    /** overview-format.ts:57. */
    fun plural(count: Int, one: String, many: String = "${one}s"): String = "$count ${if (count == 1) one else many}"
}
