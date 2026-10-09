package com.tether.app.ui

import com.tether.app.protocol.helpers.Format
import com.tether.app.protocol.model.AgentSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ta-jtfq: the session list as the screens draw it.
 *
 * While a reply streams, the server sends a `session` frame for every event (it stamps `updatedAt`, `lastMessageAt` and
 * the journal head `lastSeq` each time), so the raw list changes at the delta rate and everything that reads it
 * (the shell, the drawer and its sidebar, the composer's `@` picker) would recompose for it. What the screens draw
 * from those three fields is the age words ("now", "5m", "3h", "2d", a date), the recency order and nothing else, so
 * a new frame that changes only them, and neither the words nor the order, shows nothing new. [displayStable] keeps the
 * list it holds in that case and publishes the latest at the very instant a word would change, so no screen ever draws
 * an age or an order the raw list would not.
 */
object SessionDisplay {
    /** The one equality: [next] draws exactly what [held] draws at [now] (and, by [holdsUntil], until then). */
    fun equivalent(held: List<AgentSession>, next: List<AgentSession>, now: Long): Boolean {
        if (held === next) return true
        if (held.size != next.size) return false
        for (i in held.indices) {
            val a = held[i]
            val b = next[i]
            if (a === b) continue
            if (a.id != b.id) return false
            if (a.copy(updatedAt = b.updatedAt, lastMessageAt = b.lastMessageAt, lastSeq = b.lastSeq) != b) return false
            if (a.updatedAt != b.updatedAt && age(a.updatedAt, now) != age(b.updatedAt, now)) return false
            if (a.lastMessageAt != b.lastMessageAt) {
                val x = a.lastMessageAt
                val y = b.lastMessageAt
                if (x == null || y == null || age(x, now) != age(y, now)) return false
            }
        }
        // The list is in recency order (updatedAt) already; the "last active" order is by lastMessageAt.
        return ids(held) == ids(next)
    }

    /** The first instant at which an age word of [held]'s fields that differ from [next]'s would change; [Long.MAX_VALUE] if none. */
    fun holdsUntil(held: List<AgentSession>, next: List<AgentSession>, now: Long): Long {
        var until = Long.MAX_VALUE
        for (i in held.indices) {
            val a = held[i]
            val b = next[i]
            if (a === b) continue
            if (a.updatedAt != b.updatedAt) until = minOf(until, nextWord(a.updatedAt, now))
            val x = a.lastMessageAt
            if (x != null && x != b.lastMessageAt) until = minOf(until, nextWord(x, now))
        }
        return until
    }

    private fun ids(list: List<AgentSession>): List<String> = list.sortedByDescending { it.lastMessageAt ?: 0L }.map { it.id }

    private fun age(timestamp: Long, now: Long): String = Format.relativeTime(timestamp.toDouble(), now.toDouble())

    /** The next instant after [now] at which `relativeTime(timestamp, ·)` changes its word. */
    internal fun nextWord(timestamp: Long, now: Long): Long {
        val seconds = maxOf(0L, Math.floorDiv(now - timestamp, 1000L))
        if (seconds < 60) return timestamp + 60_000
        val minutes = seconds / 60
        if (minutes < 60) return timestamp + (minutes + 1) * 60_000
        val hours = minutes / 60
        if (hours < 24) return timestamp + (hours + 1) * 3_600_000
        val days = hours / 24
        if (days < 7) return timestamp + (days + 1) * 86_400_000
        return Long.MAX_VALUE
    }
}

/**
 * [source] as the screens draw it (see [SessionDisplay]): replaced at once by anything but a change of `updatedAt` /
 * `lastMessageAt` / `lastSeq` that keeps every age word and the order, and by the latest at the instant such a word
 * would change.
 */
fun displayStable(source: StateFlow<List<AgentSession>>, scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis): StateFlow<List<AgentSession>> {
    val out = MutableStateFlow(source.value)
    scope.launch {
        var timer: Job? = null
        var timerAt = Long.MAX_VALUE
        source.collect { next ->
            val held = out.value
            if (held === next) return@collect
            val now = clock()
            if (!SessionDisplay.equivalent(held, next, now)) {
                timer?.cancel()
                timer = null
                timerAt = Long.MAX_VALUE
                out.value = next
                return@collect
            }
            val at = SessionDisplay.holdsUntil(held, next, now)
            if (at < timerAt) {
                timer?.cancel()
                timerAt = at
                timer = launch {
                    delay(maxOf(0L, at - clock()))
                    out.value = source.value
                    timer = null
                    timerAt = Long.MAX_VALUE
                }
            }
        }
    }
    return out
}
