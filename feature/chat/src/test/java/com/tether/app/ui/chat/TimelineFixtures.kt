package com.tether.app.ui.chat

import com.tether.app.protocol.reduce.ev
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * T6.5 fixtures, folded by the real reducer. [seeded] is the web's `conversation-timeline` scenario
 * (tether scripts/parity-seed.mjs:412-420: "Plan the refactor", five prompts the fake engine echoes,
 * all at 01:02).
 */
object TimelineFixtures {
    const val T_SEEDED = 3_720_000L // 01:02 UTC

    val PROMPTS = listOf(
        "Let's plan the config refactor.",
        "Which files does it touch?",
        "Add retry backoff to the plan.",
        "Write the plan as a checklist.",
        "Looks good — anything I missed?",
    )

    val seeded: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *PROMPTS.withIndex().flatMap { (i, p) ->
                ChatFixtures.turn("timeline-${i + 1}", p, "(fake engine) you said: $p", T_SEEDED).toList()
            }.toTypedArray(),
        )
    }

    /** [n] prompts, one minute apart from 01:00, each with a two-paragraph reply (a long transcript). */
    fun many(n: Int): ChatFixtures.Folded = ChatFixtures.fold(
        *(1..n).flatMap { k ->
            ChatFixtures.turn("t$k", "Prompt $k", "Reply $k\n\nwith a second paragraph so the transcript is long", 3_600_000L + (k - 1) * 60_000L).toList()
        }.toTypedArray(),
    )

    /**
     * Edge prompts: an attachment-only message, a prompt carrying bidi controls and a zero-width
     * space, and a last turn still waiting for its reply.
     */
    val edges: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            ev("turn_started", "e1", ts = T_SEEDED) { put("idempotencyKey", "k-e1") },
            ev("user_message_accepted", "e1", ts = T_SEEDED) {
                put("text", "")
                putJsonArray("attachments") {
                    addJsonObject { put("name", "screenshot.png"); put("mediaType", "image/png") }
                    addJsonObject { put("name", "report.pdf"); put("mediaType", "application/pdf") }
                }
            },
            ev("message_started", "e1", ts = T_SEEDED) { put("blockId", "e1:m0") },
            ev("message_completed", "e1", ts = T_SEEDED) { put("blockId", "e1:m0"); put("text", "Both files read.") },
            ev("turn_end", "e1", ts = T_SEEDED) { put("outcome", "ok") },
            *ChatFixtures.turn("e2", "Fix the \u202Eparser\u202C   bug\u200B now", "Done.", T_SEEDED + 60_000L),
            ev("turn_started", "e3", ts = T_SEEDED + 120_000L) { put("idempotencyKey", "k-e3") },
            ev("user_message_accepted", "e3", ts = T_SEEDED + 120_000L) { put("text", "Still thinking?") },
        )
    }
}
