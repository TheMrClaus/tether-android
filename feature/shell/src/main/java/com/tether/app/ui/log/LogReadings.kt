package com.tether.app.ui.log

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tether.app.client.ServerStats
import com.tether.app.client.StatsResult
import com.tether.app.client.isWarning
import com.tether.app.protocol.LogEntry
import com.tether.app.protocol.fold.numberToString
import com.tether.app.protocol.model.AgentSession
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

/*
 * The pure half of components/log-dialog.tsx (tether @ PARITY_BASE 7d65611): what each row, tile
 * and meta line prints, and which entries show under the filters. The composables in LogDialog.kt
 * only lay these strings out.
 */

/** The level filter (log-dialog.tsx `levelFilter`: "all" | "warn"). */
enum class LogLevelFilter { All, Warnings }

/** The session filter's "every session" value (log-dialog.tsx `sessionFilter` "all"). */
const val AllSessions = "all"

/**
 * What the web keeps in the always-mounted `<dialog>`'s own state, so it survives a close and
 * reopen exactly as there: both filters, the last stats snapshot and the last stats error.
 */
@Stable
class LogDialogState(
    level: LogLevelFilter = LogLevelFilter.All,
    session: String = AllSessions,
    stats: ServerStats? = null,
    statsError: String = "",
) {
    var level by mutableStateOf(level)
    var session by mutableStateOf(session)
    var stats by mutableStateOf(stats)
        private set
    var statsError by mutableStateOf(statsError)
        private set

    /** refreshStats: a success replaces the snapshot and clears the error; a failure keeps the old snapshot. */
    fun onStats(result: StatsResult) {
        when (result) {
            is StatsResult.Loaded -> {
                stats = result.stats
                statsError = ""
            }
            is StatsResult.Failed -> statsError = result.message
        }
    }
}

/** One session the log mentions, for the filter menu. */
data class LoggedSession(val id: String, val name: String)

object LogReadings {

    /** log-dialog.tsx `EVENT_LABEL`: prose for the structured event slugs. */
    val EventLabels: Map<String, String> = mapOf(
        "turn.start" to "Turn started",
        "turn.end" to "Turn ended",
        "turn.error" to "Turn error",
        "session.evict" to "Session evicted",
        "session.recycle" to "Session recycled",
        "background.abandoned" to "Background work abandoned",
        "background.interrupted" to "Background work interrupted",
        "watchdog.fired" to "Watchdog interrupted turn",
        "lease.collision" to "Lease collision",
    )

    const val EmptyText = "No events logged yet. Turn and lifecycle events appear here as they happen."

    /** `EVENT_LABEL[entry.event] || entry.event`. */
    fun label(entry: LogEntry): String = EventLabels[entry.event] ?: entry.event

    /** log-dialog.tsx `entryDetail`: the event-specific trailing detail, " · " separated. */
    fun detail(entry: LogEntry): String {
        val parts = mutableListOf<String>()
        if (entry.event == "turn.end") {
            entry.outcome?.let(parts::add)
            entry.durationMs?.let { parts.add("${toFixed1(it / 1000)}s") }
            if (entry.continuation) parts.add("continuation")
        } else if (entry.event == "turn.start" && entry.continuation) {
            parts.add("continuation")
        } else if (entry.reason != null) {
            parts.add(entry.reason!!)
        }
        entry.outstanding?.let { parts.add("${numberToString(it)} outstanding") }
        entry.message?.let(parts::add)
        return parts.joinToString(" · ")
    }

    /** `shortId`: the first 8 characters and an ellipsis when longer. */
    fun shortId(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return if (value.length > 8) "${value.take(8)}…" else value
    }

    /** `uptime`: 45s, 12m, 3h 7m, 2d 5h. */
    fun uptime(ms: Long): String {
        val seconds = max(0L, floor(ms / 1000.0).toLong())
        if (seconds < 60) return "${seconds}s"
        val minutes = seconds / 60
        if (minutes < 60) return "${minutes}m"
        val hours = minutes / 60
        if (hours < 24) return "${hours}h ${minutes % 60}m"
        return "${hours / 24}d ${hours % 24}h"
    }

    /** `mib`: `Math.round(bytes / 2^20)` MB. */
    fun mib(bytes: Long): String = "${floor(bytes / (1024.0 * 1024.0) + 0.5).toLong()} MB"

    /**
     * `clockTime`: `toLocaleTimeString(undefined, { hour/minute/second: "2-digit" })`, the en-US
     * form "09:05:03 AM" — the same fixed pattern the lib/format.ts port uses for its clock time.
     */
    fun clockTime(ts: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("hh:mm:ss a", locale).format(Instant.ofEpochMilli(ts).atZone(zone))

    /** `nameBySid`: sid → session name. */
    fun namesById(sessions: List<AgentSession>): Map<String, String> = sessions.associate { it.id to it.name }

    /** A row's session label: its name, else its short id (`nameBySid.get(sid) || shortId(sid)`). */
    fun sessionName(sid: String, names: Map<String, String>): String = names[sid]?.takeIf { it.isNotEmpty() } ?: shortId(sid)

    /** `loggedSessions`: every sid the log mentions, in first-seen order, for the filter menu. */
    fun loggedSessions(entries: List<LogEntry>, names: Map<String, String>): List<LoggedSession> =
        entries.mapNotNull { it.sid?.takeIf(String::isNotEmpty) }.distinct().map { LoggedSession(it, sessionName(it, names)) }

    /** `filtered`: the entries the filters let through, newest first. */
    fun filtered(entries: List<LogEntry>, level: LogLevelFilter, session: String): List<LogEntry> =
        entries.filter { entry ->
            (level != LogLevelFilter.Warnings || entry.isWarning) && (session == AllSessions || entry.sid == session)
        }.asReversed()

    /** `warnCount`: every entry that is not `info`. */
    fun warnCount(entries: List<LogEntry>): Int = entries.count { it.isWarning }

    /** The Engine meta line: `mode · persistent`, or "terminal only" without a mode. */
    fun engine(stats: ServerStats): String =
        stats.headlessMode?.takeIf { it.isNotEmpty() }?.let { if (stats.headlessPersistent) "$it · persistent" else it } ?: "terminal only"

    fun sessions(stats: ServerStats): String = "${stats.sessionsTotal} total · ${stats.sessionsHeadless ?: 0} chat"

    fun memory(stats: ServerStats): String = "${mib(stats.memoryRss)} rss · ${mib(stats.memoryHeapUsed)} heap"

    /** The Active-turns tile caption: "Active turns", plus " / max" when the server caps concurrency. */
    fun activeTurnsCaption(stats: ServerStats): String {
        val cap = stats.runtime?.maxConcurrentTurns ?: 0
        return if (stats.runtime != null && cap > 0) "Active turns / $cap" else "Active turns"
    }

    /** JS `Number.prototype.toFixed(1)` for a finite double (exact decimal, ties away from zero). */
    private fun toFixed1(value: Double): String =
        if (value.isFinite()) BigDecimal(value).setScale(1, RoundingMode.HALF_UP).toPlainString() else numberToString(value)
}
