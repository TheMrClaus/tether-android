package com.tether.app.client

/**
 * Per-session attach-cursor state machine (specs/protocol-spec.md §5.2).
 * Not thread-safe by itself — the client serializes access.
 *
 * Rules ported exactly from hooks/use-tether.ts:
 * - no cursor (never attached)      -> ignore the event
 * - seq == null                     -> fold WITHOUT moving the cursor
 * - seq <= cursor                   -> duplicate, drop
 * - seq >  cursor + 1               -> gap: request ONE attach {afterSeq: cursor}
 *                                      per gap (resyncPending), do NOT fold
 * - seq == cursor + 1               -> advance cursor and fold
 * A snapshot replaces the projection wholesale, sets cursor = throughSeq and
 * clears the resync flag.
 */
class CursorTracker {
    private val cursors = HashMap<String, Long>()
    private val resyncPending = HashSet<String>()

    sealed interface Decision {
        /** Session never attached — ignore. */
        data object Ignore : Decision

        /** Duplicate seq — drop. */
        data object Drop : Decision

        /** Fold; cursor already advanced (or event was seqless). */
        data object Fold : Decision

        /** Gap — send `attach {sessionId, afterSeq}` (exactly once per gap). */
        data class Resync(val afterSeq: Long) : Decision

        /** Gap already being resynced (or socket closed) — drop silently. */
        data object AwaitSnapshot : Decision
    }

    /** Mark a session as subscribed before its first snapshot arrives. */
    fun expectSnapshot(sessionId: String) {
        // No cursor yet: events stay ignored until the snapshot lands, matching
        // the reference client (attach reply is synchronous server-side).
    }

    fun onSnapshot(sessionId: String, throughSeq: Long) {
        cursors[sessionId] = throughSeq
        resyncPending.remove(sessionId)
    }

    /**
     * Decide what to do with a live event. [canSend] is whether the socket is
     * open — a gap with a closed socket does NOT set the resync flag, so the
     * next event after reconnect can re-trigger the attach.
     */
    fun onEvent(sessionId: String, seq: Long?, canSend: Boolean): Decision {
        val cursor = cursors[sessionId] ?: return Decision.Ignore
        if (seq == null) return Decision.Fold // fold without cursor movement
        if (seq <= cursor) return Decision.Drop
        if (seq > cursor + 1) {
            if (sessionId !in resyncPending && canSend) {
                resyncPending.add(sessionId)
                return Decision.Resync(afterSeq = cursor)
            }
            return Decision.AwaitSnapshot
        }
        cursors[sessionId] = seq
        return Decision.Fold
    }

    fun cursorFor(sessionId: String): Long? = cursors[sessionId]

    fun attachedSessions(): Set<String> = cursors.keys.toSet()

    fun forget(sessionId: String) {
        cursors.remove(sessionId)
        resyncPending.remove(sessionId)
    }

    fun clearResyncFlags() {
        resyncPending.clear()
    }
}
