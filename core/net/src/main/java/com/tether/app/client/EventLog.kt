package com.tether.app.client

import com.tether.app.protocol.LogEntry
import com.tether.app.protocol.ServerMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The in-UI operational event log (use-tether.ts:274-277, 1047-1059): the entries of every `log`
 * batch, deduped by the server's monotonic `seq` (the tail is re-sent on every connect), emptied
 * when the server restarted (a new `bootId`), and capped at [LIMIT], oldest dropped.
 */
data class EventLog(val entries: List<LogEntry> = emptyList(), val bootId: String? = null) {

    /** This log after [message]; `this` when the batch adds nothing (the web's `return current`). */
    fun accept(message: ServerMessage.Log): EventLog {
        val restarted = bootId != null && bootId != message.bootId
        val base = if (restarted) emptyList() else entries
        val lastSeq = base.lastOrNull()?.seq ?: 0
        val fresh = message.entries.filter { it.seq > lastSeq }
        if (fresh.isEmpty() && !restarted) return if (bootId == message.bootId) this else copy(bootId = message.bootId)
        val next = base + fresh
        return EventLog(if (next.size > LIMIT) next.subList(next.size - LIMIT, next.size) else next, message.bootId)
    }

    companion object {
        /** use-tether.ts:1056 `LOG_LIMIT`. */
        const val LIMIT = 500
    }
}

/** An entry that is not `info` (the web's `entry.level !== "info"`: warn, error, or no level). */
val LogEntry.isWarning: Boolean get() = level != "info"

/**
 * The fields of GET /api/stats (server.mjs computeStats) the log dialog reads. The web types the
 * response loosely and reads it with `?.` / `??`; a missing or wrongly typed field here reads as
 * its zero value (or null where the web tests for presence).
 */
data class ServerStats(
    val uptimeMs: Long,
    val pid: Long,
    val protocolVersion: Long,
    /** `headless.mode`: comma-joined engine modes; null or "" reads "terminal only". */
    val headlessMode: String?,
    val headlessPersistent: Boolean,
    val sessionsTotal: Long,
    /** `sessions.byMode.headless`, null when absent (the web prints `?? 0`). */
    val sessionsHeadless: Long?,
    /** `headlessRuntime`, null when the server reports none. */
    val runtime: Runtime?,
    val memoryRss: Long,
    val memoryHeapUsed: Long,
    val clients: Long,
) {
    data class Runtime(val warm: Long, val activeTurns: Long, val maxConcurrentTurns: Long)

    companion object {
        fun fromJson(o: JsonObject): ServerStats {
            val headless = o["headless"] as? JsonObject
            val sessions = o["sessions"] as? JsonObject
            val byMode = sessions?.get("byMode") as? JsonObject
            val runtime = o["headlessRuntime"] as? JsonObject
            val memory = o["memory"] as? JsonObject
            return ServerStats(
                uptimeMs = o.number("uptimeMs") ?: 0,
                pid = o.number("pid") ?: 0,
                protocolVersion = o.number("protocolVersion") ?: 0,
                headlessMode = (headless?.get("mode") as? JsonPrimitive)?.takeIf { it.isString }?.content,
                headlessPersistent = (headless?.get("persistent") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true,
                sessionsTotal = sessions?.number("total") ?: 0,
                sessionsHeadless = byMode?.number("headless"),
                runtime = runtime?.let {
                    Runtime(
                        warm = it.number("warm") ?: 0,
                        activeTurns = it.number("activeTurns") ?: 0,
                        maxConcurrentTurns = it.number("maxConcurrentTurns") ?: 0,
                    )
                },
                memoryRss = memory?.number("rss") ?: 0,
                memoryHeapUsed = memory?.number("heapUsed") ?: 0,
                clients = o.number("clients") ?: 0,
            )
        }

        private fun JsonObject.number(key: String): Long? =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }?.toLong()
    }
}

/** log-dialog.tsx's copy when the fetch failed without an HTTP status. */
internal const val STATS_FALLBACK_ERROR = "Could not load stats."

/** The outcome of one GET /api/stats. */
sealed interface StatsResult {
    data class Loaded(val stats: ServerStats) : StatsResult

    /** [message] is shown as-is (log-dialog.tsx: `stats request failed (<status>)`, or the fetch error). */
    data class Failed(val message: String) : StatsResult
}
