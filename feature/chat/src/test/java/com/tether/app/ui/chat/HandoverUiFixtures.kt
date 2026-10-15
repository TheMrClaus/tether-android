package com.tether.app.ui.chat

import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.ev
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** #273: the transcripts and sessions of the handover states (the successor, the source, the busy lead). */
object HandoverUiFixtures {
    const val TS = ChatFixtures.T_IDLE

    val source: AgentSession = ComposerFixtures.session.copy(id = "src-1", name = "Alpha Lead")
    val successor: AgentSession = ComposerFixtures.session.copy(id = "succ-1", name = "Parser rewrite", handedOffFrom = "src-1")

    private fun fromBlock(sessionId: String, name: String) = buildJsonObject { put("sessionId", sessionId); put("name", name) }

    /** The successor's transcript: the handover prompt (from the source), then a reply. */
    val successorTranscript: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            ev("turn_started", "t1", ts = TS) { put("idempotencyKey", "k1") },
            ev("user_message_accepted", "t1", ts = TS) {
                put("text", "Carry on with the parser rewrite: tokens first, then the grammar. Tests are in /w/parser.")
                put("from", fromBlock("src-1", "Alpha Lead"))
            },
            ev("message_started", "t1", ts = TS) { put("blockId", "t1:m0") },
            ev("message_delta", "t1", ts = TS) { put("blockId", "t1:m0"); put("text", "Picking up from the handover prompt.") },
            ev("message_completed", "t1", ts = TS) { put("blockId", "t1:m0"); put("text", "Picking up from the handover prompt.") },
            ev("turn_end", "t1", ts = TS) { put("outcome", "ok") },
        )
    }

    /** The busy lead's transcript after the operator's request: Tether's instruction as the user row, in this session. */
    fun instructionTranscript(sessionId: String): ChatFixtures.Folded = ChatFixtures.fold(
        ev("turn_started", "t1", ts = TS) { put("idempotencyKey", "k1") },
        ev("user_message_accepted", "t1", ts = TS) {
            put("text", "Write a handover prompt for the next lead, then stop.")
            put("from", fromBlock(sessionId, "Tether"))
        },
    )

    /** A turn, then a handover cancelled notice (provider_notice, code handover_cancelled). */
    val cancelledNotice: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            ev("turn_started", "t1", ts = TS) { put("idempotencyKey", "k-t1") },
            ev("user_message_accepted", "t1", ts = TS) { put("text", "Hand this over.") },
            ev("provider_notice", "t1", ts = TS) {
                put("noticeId", "n-handover"); put("level", "warning"); put("code", "handover_cancelled"); put("message", "raw server words that are never shown")
            },
            ev("message_started", "t1", ts = TS) { put("blockId", "t1:m0") },
            ev("message_delta", "t1", ts = TS) { put("blockId", "t1:m0"); put("text", "Writing the handover prompt now.") },
            ev("message_completed", "t1", ts = TS) { put("blockId", "t1:m0"); put("text", "Writing the handover prompt now.") },
            ev("turn_end", "t1", ts = TS) { put("outcome", "ok") },
        )
    }

    /** A busy turn with Tether's handover instruction waiting in the composer's queue (and one of the operator's own). */
    val busyWithRequest: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            ev("turn_started", "t1", ts = ComposerFixtures.T_START) { put("idempotencyKey", "k-t1") },
            ev("user_message_accepted", "t1", ts = ComposerFixtures.T_START) { put("text", "Run the test suite and tell me what fails.") },
            ev("token_progress", "t1", ts = ComposerFixtures.T_START) { put("tokens", 960) },
            ev("queued_message_added", null, ts = ComposerFixtures.T_START) {
                put("queueId", "handover-q")
                put("text", "Write a handover prompt for the next lead, then stop. A new session will carry on from it.")
                put("origin", "system")
                put("noticeKind", "handover-request")
            },
            ev("queued_message_added", null, ts = ComposerFixtures.T_START) {
                put("queueId", "mine")
                put("text", "Also bump the changelog.")
                put("origin", "user")
            },
        )
    }
}
