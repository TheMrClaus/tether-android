package com.tether.app.ui.chat

import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import kotlinx.serialization.json.put

/**
 * T6.7: interrupt and error states, folded by the real v128 reducer from the event shapes the fold
 * reads (events.mjs cancel_requested, cancelled, process_exit, error, turn_end). The web seeder's
 * fake engine reaches none of them in a frozen frame (docs/parity/screens/interrupt-errors).
 */
object InterruptErrorFixtures {
    private const val TS = ChatFixtures.T_IDLE // 01:01 UTC

    private fun open(turnId: String, prompt: String, ts: Long = TS): List<AgentEvent> = listOf(
        ev("turn_started", turnId, ts = ts) { put("idempotencyKey", "k-$turnId") },
        ev("user_message_accepted", turnId, ts = ts) { put("text", prompt) },
    )

    private fun reply(turnId: String, text: String, ts: Long = TS): List<AgentEvent> = listOf(
        ev("message_started", turnId, ts = ts) { put("blockId", "$turnId:m0") },
        ev("message_completed", turnId, ts = ts) { put("blockId", "$turnId:m0"); put("text", text) },
    )

    /** Turn A running (the composer's busy deck). */
    val turnA: ChatFixtures.Folded by lazy { ChatFixtures.fold(*open("t1", "Run the test suite.").toTypedArray()) }

    /** Turn A ended and turn B began (a queued message flushed at the boundary). */
    val turnB: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *open("t1", "Run the test suite.").toTypedArray(),
            *reply("t1", "All 212 tests pass.").toTypedArray(),
            ev("turn_end", "t1", ts = TS) { put("outcome", "ok") },
            *open("t2", "Then bump the changelog.").toTypedArray(),
        )
    }

    /** Turn A with an interrupt requested (cancel_requested: "Interrupting"). */
    val cancelling: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(*open("t1", "Run the test suite.").toTypedArray(), ev("cancel_requested", "t1", ts = TS))
    }

    /**
     * The error surfaces of a transcript: a turn that failed (`error` then `turn_end` outcome error,
     * its process exit folded but not drawn, as on the web) and a session-level `error` (no turn:
     * `lastError`). The engine's words carry a line break and a right-to-left override to show the
     * row cleans them.
     */
    val errors: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *open("t1", "Deploy the preview build.").toTypedArray(),
            ev("error", "t1", ts = TS) { put("message", "Engine exited before the turn finished:\nrate limited by the provider.") },
            ev("process_exit", "t1", ts = TS) { put("code", 1); put("signal", null as String?) },
            ev("turn_end", "t1", ts = TS) { put("outcome", "error") },
            evNullTurn("error", seq = 10, ts = TS) { put("message", "The Claude CLI could not start: \u202Ecommand not found\n(claude)") },
        )
    }
}
