package com.tether.app.client.sync

import com.tether.app.mirror.HydratedSession
import com.tether.app.mirror.JournalMirror
import com.tether.app.mirror.SessionRowInput
import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.TARGET_PROTOCOL_VERSION
import com.tether.app.protocol.TetherJson
import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.str
import kotlinx.coroutines.CompletableDeferred

/**
 * The reducer the mirror's LOCAL bases depend on (SYNC_DESIGN §2.4): the vendored reducer
 * corpus (its manifest `tetherSha`) plus the protocol the port models (TARGET_PROTOCOL_VERSION).
 * A new APK with another reducer port changes it, which clears the cursors of local checkpoints.
 * MirrorVersionTest pins the SHA to `parity-corpus/corpus-manifest.json`, so a corpus re-sync
 * must bump it.
 *
 * ta-ylh: keyed on TARGET, not the advertised PROTOCOL_VERSION (held at 132 for the owner gate):
 * the v133 fold keeps queued-message `origin` / `noticeKind`, so a checkpoint folded by the v132
 * port must not be reused, although the corpus did not move. T15.8: the corpus moved to 887c222.
 */
const val REDUCER_CORPUS_SHA = "887c22214126fa662192e2a3adf6f2dd44e69cd6"
val REDUCER_VERSION: String = "$REDUCER_CORPUS_SHA/v$TARGET_PROTOCOL_VERSION"

/**
 * T13.1: what the client's frame handlers hand the mirror (SYNC_DESIGN §2.3), as mirror
 * writes. Stateless: every call names the origin of the socket the frame came on.
 */
class MirrorLink(val mirror: JournalMirror) {

    /** `ready` (full list: missing rows become gone), `created` / `session-update` (upsert). */
    fun sessions(origin: String, sessions: List<AgentSession>, full: Boolean) {
        mirror.recordSessions(origin, sessions.map(::row), full)
    }

    /** A snapshot: with state it re-bases; without, the server confirmed the cursor (§3.1). */
    fun snapshot(origin: String, message: ServerMessage.Snapshot) {
        val state = message.state
        if (state == null) {
            mirror.recordVerified(origin, message.sessionId, message.throughSeq)
        } else {
            mirror.recordState(
                origin, message.sessionId, message.throughSeq, message.trimmedBefore, state,
                keptDetailIds(state, message.trimmedBefore),
            )
        }
    }

    /**
     * An event the cursor folded. A seqless one is never persisted and clears the cursor (§2.3);
     * the returned deferred completes once that clear is committed (the caller waits for it
     * before folding). Null for an ordinary event.
     */
    fun event(origin: String, sessionId: String, event: AgentEvent): CompletableDeferred<Unit>? {
        val seq = event.seq ?: return mirror.recordSeqless(origin, sessionId)
        mirror.recordEvent(origin, sessionId, seq, event.type, event.ts, event.raw.toString())
        return null
    }

    /** `turns-detail`: [tree] (the session's current projection, if any) gives each turn's index. */
    fun turnsDetail(origin: String, sessionId: String, turns: JsObj, tree: JsObj?) {
        val order = (tree?.get("turnOrder") as? JsArr)?.mapNotNull { it.str }.orEmpty()
        val entries = LinkedHashMap<String, Pair<Int?, JsObj>>()
        for ((turnId, turn) in turns) {
            val obj = turn as? JsObj ?: continue
            entries[turnId] = order.indexOf(turnId).takeIf { it >= 0 } to obj
        }
        if (entries.isNotEmpty()) mirror.recordTurnDetails(origin, sessionId, entries)
    }

    fun opened(origin: String, sessionId: String) = mirror.recordOpened(origin, sessionId)

    fun drop(origin: String, sessionId: String) = mirror.dropSession(origin, sessionId)

    companion object {
        fun row(session: AgentSession) = SessionRowInput(
            sessionId = session.id,
            json = TetherJson.encodeToString(AgentSession.serializer(), session),
            updatedAt = session.updatedAt,
            lastMessageAt = session.lastMessageAt,
            pinned = session.pinned,
            runtimeArchived = session.runtimeArchived,
        )

        fun decodeSession(json: String): AgentSession? = try {
            TetherJson.decodeFromString(AgentSession.serializer(), json)
        } catch (_: IllegalArgumentException) {
            null // kotlinx SerializationException is an IllegalArgumentException
        }

        /**
         * The fetched turn details a new server [state] leaves useful: those of turns it still
         * holds trimmed (index < [trimmedBefore]). Every other detail is superseded (§2.3): the
         * state holds that turn in full, or no longer holds it. No trimmedBefore = none.
         */
        fun keptDetailIds(state: JsObj, trimmedBefore: Int?): Set<String> {
            if (trimmedBefore == null || trimmedBefore <= 0) return emptySet()
            val order = (state["turnOrder"] as? JsArr) ?: return emptySet()
            return order.take(trimmedBefore).mapNotNull { it.str }.toSet()
        }

        /** `{...base, turnsById: {...base.turnsById, ...details}}`: RealTetherClient's turns-detail merge. */
        fun splice(base: JsObj, details: Map<String, JsObj>): JsObj {
            if (details.isEmpty()) return base
            val turnsById = base["turnsById"] as? JsObj ?: JsObj.EMPTY
            return base.put("turnsById", turnsById.spread(JsObj.from(details)))
        }

        /**
         * The projection a mirrored session rebuilds to (§10 C2): `fold(splice(base, details), tail)`
         * by the same reducer that folds live events. Throws what the reducer throws.
         */
        fun rebuild(session: HydratedSession): JsObj {
            var tree = splice(session.base, session.details)
            for (event in session.tail) tree = reduce(tree, event)
            return tree
        }

        /** Canonical JSON, for equality checks and logs-free comparisons. */
        fun canonical(tree: JsObj): String = JsCodec.canonical(tree)
    }
}
