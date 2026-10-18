package com.tether.app.ui.chat

import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr

/**
 * W50 (ta-d3tj.8): the session's turn-less errors as timeline rows (web 19c56e25, chat-view.tsx:3853-3875).
 * Everything here is pure: the interleave, the lastError fallback and the history gate are unit-tested
 * (SessionErrorsTest), so no logic lives in the large composables.
 *
 * The entries are read from the projection TREE (the live fold keeps the snapshot's `sessionErrors`
 * raw there) so a fractional journal `ts` stays a Double: the typed projection's `ts` is a Long and
 * truncates it (ta-28ll is the precedent). With no tree the typed projection stands in.
 */
internal data class SessionErrorEntry(val seq: Long?, val ts: Double?, val message: String)

/** `state.sessionErrors ?? []`: absent, null or a non-array = none; a row that is not an object is skipped. */
internal fun sessionErrorEntries(projection: SessionProjection, tree: JsObj?): List<SessionErrorEntry> {
    if (tree == null) return projection.sessionErrors.map { SessionErrorEntry(it.seq, it.ts?.toDouble(), it.message) }
    val raw = tree["sessionErrors"] as? JsArr ?: return emptyList()
    return raw.mapNotNull { row ->
        val o = row as? JsObj ?: return@mapNotNull null
        val message = (o["message"] as? JsStr)?.value ?: return@mapNotNull null
        SessionErrorEntry(
            seq = (o["seq"] as? JsNum)?.value?.takeIf { it.isFinite() }?.toLong(),
            ts = (o["ts"] as? JsNum)?.value?.takeIf { it.isFinite() },
            message = message,
        )
    }
}

/** The words a session-error row shows: the engine's message, cleaned like the turn rows' (an empty one stays a row). */
internal fun sessionErrorMessage(message: String): String =
    com.tether.app.client.LabelText.clean(message, NOTICE_BODY_MAX)

/**
 * The flush of chat-view.tsx:3862-3875. Errors merge into the turns by `ts` (a Double, never truncated):
 * one lands after the last turn whose startedAt <= its ts (a tie goes AFTER the turn); journal order is
 * kept; a null ts inherits the previous effective ts (a monotonic clamp, `max(errTs, ts ?? errTs)`).
 * While earlier turns are unloaded, [flush]'s `drop` discards the errors older than the first loaded turn.
 */
internal class SessionErrorFlusher(
    private val entries: List<SessionErrorEntry>,
    private val scope: String,
    private val emit: (ChatItem.SessionError) -> Unit,
) {
    private var next = 0
    private var errTs = 0.0

    fun flush(before: Double, drop: Boolean) {
        while (next < entries.size) {
            val err = entries[next]
            val effective = maxOf(errTs, err.ts ?: errTs)
            if (effective >= before) break
            errTs = effective
            next++
            if (drop) continue
            emit(
                ChatItem.SessionError(
                    scope = scope,
                    text = sessionErrorMessage(err.message),
                    ts = err.ts,
                    keySuffix = if (err.seq != null) "session-error/seq-${err.seq}" else "session-error/idx-$next",
                ),
            )
        }
    }
}

/**
 * The `lastError` row survives as the fallback for a server older than v145 (floor 129), whose snapshot carries only
 * `lastError`. It shows when the cleaned lastError is non-empty AND no session-error entry carries the same cleaned
 * message. Since R1 (ta-d3tj.10) the app's own fold appends the entry together with `lastError`, and a v145+ snapshot
 * carries both, so the row never draws twice: not even when a replayed error left `lastError` on an entry that is not
 * the last (W51 ruling 8; the web, which has no lastError row, draws the entries only).
 */
internal fun fallbackLastError(lastError: String?, entries: List<SessionErrorEntry>): String? {
    val text = sessionErrorText(lastError) ?: return null
    if (entries.any { sessionErrorMessage(it.message) == text }) return null
    return text
}

/** One gate for the empty state and the transcript floor (ChatScreen): turns, session errors, or the fallback row. */
internal fun chatHasHistory(projection: SessionProjection, tree: JsObj?): Boolean {
    if (projection.turnOrder.isNotEmpty()) return true
    val entries = sessionErrorEntries(projection, tree)
    return entries.isNotEmpty() || fallbackLastError(projection.lastError, entries) != null
}
