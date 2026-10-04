package com.tether.app.ui.chat

import com.tether.app.client.ControlResult
import com.tether.app.client.NoticeResult
import com.tether.app.client.SessionControl
import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import kotlinx.serialization.json.put

/**
 * T6.6: the notice states, folded by the real v128 reducer from the event shapes the fold reads
 * (events.mjs model_fallback, context_compacted, provider_notice, external_advancement,
 * background_*, turn_interrupted, limit_hit, rate_limit_resume_scheduled, api_retry). The web
 * seeder's fake engine emits none of these, so none has a frozen web frame except the ones the
 * montage README names.
 */
object NoticeFixtures {
    private const val TS = ChatFixtures.T_IDLE // 01:01 UTC

    /** The limit prompt's reset: 02:01 UTC (resume at 02:03 UTC). */
    const val RESETS_AT = TS + 3_600_000L

    private fun open(turnId: String, prompt: String, ts: Long = TS): List<AgentEvent> = listOf(
        ev("turn_started", turnId, ts = ts) { put("idempotencyKey", "k-$turnId") },
        ev("user_message_accepted", turnId, ts = ts) { put("text", prompt) },
    )

    private fun reply(turnId: String, text: String, ts: Long = TS): List<AgentEvent> = listOf(
        ev("message_started", turnId, ts = ts) { put("blockId", "$turnId:m0") },
        ev("message_completed", turnId, ts = ts) { put("blockId", "$turnId:m0"); put("text", text) },
    )

    private fun end(turnId: String, outcome: String = "ok", ts: Long = TS) = ev("turn_end", turnId, ts = ts) { put("outcome", outcome) }

    /** issue #179: a Claude CLI model fallback, a turn-scoped provider notice. */
    val claudeFallback: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *open("t1", "Refactor the parser.").toTypedArray(),
            ev("model_fallback", "t1", ts = TS) {
                put("message", "Opus is temporarily overloaded, so this turn continues on Sonnet.")
                put("fromModel", "claude-opus-5"); put("toModel", "claude-sonnet-5"); put("trigger", "overloaded"); put("fallbackId", "fb-1")
            },
            *reply("t1", "Done — the parser now reads one token at a time.").toTypedArray(),
            end("t1"),
        )
    }

    /** A rich Codex turn: a compaction, an info notice and an error notice. */
    val codexNotices: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *open("t1", "Keep going with the migration.").toTypedArray(),
            ev("context_compacted", "t1", ts = TS) { put("itemId", "cmp-1") },
            ev("provider_notice", "t1", ts = TS) { put("noticeId", "n-info"); put("level", "info"); put("code", "config"); put("message", "A newer model is available for this thread.") },
            ev("provider_notice", "t1", ts = TS) { put("noticeId", "n-err"); put("level", "error"); put("message", "The sandbox refused a network call.") },
            *reply("t1", "Migration step 3 of 5 is done.").toTypedArray(),
            end("t1"),
        )
    }

    /** External advancement and the two background-loss notices at the transcript foot. */
    val sessionNotices: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *ChatFixtures.turn("t1", "Summarize the README in one line.", "(fake engine) you said: Summarize the README in one line.", TS),
            evNullTurn("external_advancement", seq = 10, ts = TS) { put("noticeId", "ext-1"); put("count", 2) },
            evNullTurn("background_abandoned", seq = 11, ts = TS) { put("outstanding", 2); put("reason", "engine_died") },
            evNullTurn("background_interrupted", seq = 12, ts = TS) { put("outstanding", 1) },
        )
    }

    /** issue #184: a turn Tether interrupted (by the operator's next message), said at its outcome row. */
    val interrupted: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *open("t1", "Run the full test suite.").toTypedArray(),
            evNullTurn("turn_interrupted", seq = 5, ts = TS + 42_000) {
                put("by", "message-send"); put("interruptedTurnId", "t1"); put("stoppedTools", 1); put("stoppedBackground", 2)
            },
            end("t1", outcome = "cancelled", ts = TS + 42_000),
        )
    }

    /** v88: a finished turn, then Claude's session limit with a reset instant (the card). */
    val limit: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *ChatFixtures.turn("t1", "Continue the refactor.", "Paused: the session limit was reached.", TS),
            evNullTurn("limit_hit", seq = 8, ts = TS) { put("resetAt", RESETS_AT); put("limitType", "five_hour") },
        )
    }

    /** The limit prompt with an automatic resume armed (the scheduled row). */
    val scheduled: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *ChatFixtures.turn("t1", "Continue the refactor.", "Paused: the session limit was reached.", TS),
            evNullTurn("limit_hit", seq = 8, ts = TS) { put("resetAt", RESETS_AT); put("limitType", "five_hour") },
            evNullTurn("rate_limit_resume_scheduled", seq = 9, ts = TS) { put("resetsAt", RESETS_AT); put("resumeAt", RESETS_AT + 120_000) },
        )
    }

    /** The web `notices` scenario: a turn recovered as outcome_unknown after a restart. */
    val outcomeUnknown: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *open("t1", "Start the long migration.").toTypedArray(),
            *reply("t1", "Starting a long task.").toTypedArray(),
            end("t1", outcome = "outcome_unknown"),
        )
    }

    /** v17: an open turn between HTTP attempts. */
    val apiRetry: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *open("t1", "Write the changelog.").toTypedArray(),
            ev("api_retry", "t1", ts = TS) { put("attempt", 2); put("maxRetries", 5); put("delayMs", 4_000); put("error", "overloaded") },
        )
    }

    /** Recorded taps. */
    class Recorder {
        val dismissed = mutableListOf<String>()
        val controls = mutableListOf<SessionControl>()
        var dismissResult: NoticeResult = NoticeResult.Sent
        var controlResult: ControlResult = ControlResult.Sent
        val refusals = mutableListOf<String>()

        fun actions(link: Any? = "link-1", sessionId: String = "s1") = NoticeActions(
            sessionId = sessionId,
            link = link,
            onDismiss = { key -> dismissed.add(key); dismissResult },
            onRateLimit = { c -> controls.add(c); controlResult },
            onRefused = { refusals.add(it) },
        )
    }

    /** Actions that accept every tap (the goldens draw the live state). */
    val live: NoticeActions = Recorder().actions()
}
