package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.ev
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.serialization.json.put

/**
 * T7.1: composer fixtures folded by the real v128 reducer, mirroring the S0.4 web scenarios the
 * composer appears in: `idle-session` (an idle Claude session, empty draft) and `streaming` (a
 * turn running for 9 minutes with 960 live tokens — the web shot reads "Working… 9m · 960
 * tokens"). The queue state adds two `queued_message_added` events, the second with
 * `flushMode: "next-call"` (issue #183), as the wire corpus's queue scenario sends them.
 */
object ComposerFixtures {
    const val SESSION_ID = "sess-0001"
    const val T_START = ChatFixtures.T_STREAM

    /** The event-anchored "now" of the busy shots: 9 minutes into the run. */
    const val BUSY_NOW = T_START + 9 * 60_000L

    val session: AgentSession = AgentSession(
        id = SESSION_ID, provider = "claude", name = "Run the tests", cwd = "/w", status = "ready",
        startedAt = T_START, updatedAt = T_START, historyId = "h-1",
    )

    val idle: ChatFixtures.Folded get() = ChatFixtures.idle

    private val busyEvents = arrayOf(
        ev("turn_started", "t1", ts = T_START) { put("idempotencyKey", "k-t1") },
        ev("user_message_accepted", "t1", ts = T_START) { put("text", "Run the test suite and tell me what fails.") },
        ev("token_progress", "t1", ts = T_START) { put("tokens", 960) },
    )

    val busy: ChatFixtures.Folded by lazy { ChatFixtures.fold(*busyEvents) }

    val queued: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *busyEvents,
            ev("queued_message_added", null, ts = T_START) {
                put("queueId", "parity-queue-1")
                put("text", "Then fix the snapshot test and rerun only that suite.")
            },
            ev("queued_message_added", null, ts = T_START) {
                put("queueId", "parity-queue-2")
                put("text", "Also bump the changelog.")
                put("flushMode", "next-call")
            },
        )
    }

    /**
     * T15.6 (v133, tests/queued-message.test.mjs): the queue folded from a legacy (origin-less)
     * draft, Tether's system notices of every kind, and the operator's own. Only the legacy and
     * the operator's rows are his; the web composer lists nothing else.
     */
    val mixedOrigins: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *busyEvents,
            ev("queued_message_added", null, ts = T_START) {
                put("queueId", "legacy")
                put("text", "Then fix the snapshot test and rerun only that suite.")
            },
            *systemNotices(),
            ev("queued_message_added", null, ts = T_START) {
                put("queueId", "user")
                put("text", "Also bump the changelog.")
                put("origin", "user")
            },
        )
    }

    /** T15.6: a queue holding nothing but Tether's notices (hostile text included). */
    val systemOnly: ChatFixtures.Folded by lazy { ChatFixtures.fold(*busyEvents, *systemNotices()) }

    private fun systemNotices() = arrayOf(
        ev("queued_message_added", null, ts = T_START) {
            put("queueId", "run")
            put("text", "SYSTEM spawn notice \u202Egnp.exe <b>x</b>")
            put("origin", "system")
            put("noticeKind", "spawn")
        },
        ev("queued_message_added", null, ts = T_START) {
            put("queueId", "cmd")
            put("text", "SYSTEM command notice")
            put("origin", "system")
            put("noticeKind", "command")
        },
        ev("queued_message_added", null, ts = T_START) {
            put("queueId", "cont")
            put("text", "SYSTEM continuation notice")
            put("origin", "system")
            put("noticeKind", "continuation")
        },
    )

    const val DRAFT ="Summarize the failures as a table:\nsuite, test, first error line.\nSkip the passing ones."
}

/** The composer as the shell stacks it under the transcript: full width, content height. */
@Composable
fun ComposerHost(skin: TetherSkin, width: Dp? = null, content: @Composable () -> Unit) {
    TetherTheme(choiceFor(skin)) {
        CompositionLocalProvider(LocalReducedMotion provides true) {
            Box((if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).testTag(ComposerTag)) { content() }
        }
    }
}

const val ComposerTag = "composer-deck"
