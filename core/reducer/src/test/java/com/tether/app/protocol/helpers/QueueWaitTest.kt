package com.tether.app.protocol.helpers

import com.tether.app.protocol.helpers.QueueWait.LiveWork
import com.tether.app.protocol.helpers.QueueWait.heldLabel
import com.tether.app.protocol.helpers.QueueWait.interruptAndSendRowId
import com.tether.app.protocol.helpers.QueueWait.interruptCostCopy
import com.tether.app.protocol.helpers.QueueWait.queuedLabel
import com.tether.app.protocol.helpers.QueueWait.steeredStopCopy
import com.tether.app.protocol.helpers.QueueWait.backgroundSpared
import com.tether.app.protocol.helpers.QueueWait.stopConfirms
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.tether.app.protocol.model.QueuedMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Delivery UI words, delivery-composer-spec §1 V4/V5, §4.1, §4.1a A/C and §4.2: every string is the spec's, verbatim.
 * (ta-ceo's deferred-wait / formatWaited / 60 s choice copy is retired with "Interrupt now" and its tests with it.)
 */
class QueueWaitTest {

    private fun row(
        id: String = "q",
        held: Boolean = false,
        reason: String? = null,
        origin: String? = null,
        kind: String? = null,
        attachmentIds: List<String>? = null,
        flushMode: String? = null,
    ) = QueuedMessage(
        queueId = id, text = "t-$id", held = held, heldReason = reason,
        origin = origin?.let(::JsonPrimitive), noticeKind = kind?.let(::JsonPrimitive), attachmentIds = attachmentIds, flushMode = flushMode,
    )

    // ---- the composer's own Stop (unchanged, ta-ceo) -------------------------------------------------

    @Test
    fun stopConfirmsOnlyForWorkStopEnds() {
        assertFalse(stopConfirms(LiveWork.None))
        assertTrue(stopConfirms(LiveWork(outstandingBackground = 7)))
        assertTrue(stopConfirms(LiveWork(openTools = 1)))
        assertTrue(stopConfirms(LiveWork(1, 1)))
        // §4.1c A.3: spared background tasks alone never open the confirmation; an open tool still does.
        assertFalse(stopConfirms(LiveWork(outstandingBackground = 3), backgroundSpared = true))
        assertTrue(stopConfirms(LiveWork(2, 3), backgroundSpared = true))
    }

    // ---- §4.1c A + D-L148-1 (1): the gate -------------------------------------------------------------

    @Test
    fun backgroundSparedNeedsProtocol155AndPersistentClaude() {
        assertFalse("a 154 server keeps the old words", backgroundSpared("claude", 154, true))
        assertFalse("per-turn Claude (persistentSessions false)", backgroundSpared("claude", 155, false))
        assertTrue(backgroundSpared("claude", 155, true))
        assertTrue(backgroundSpared("claude", 160, true))
        assertFalse("unknown version", backgroundSpared("claude", null, true))
        assertFalse("another engine", backgroundSpared("codex", 155, true))
    }

    @Test
    fun interruptCostCopyWhenBackgroundIsSpared() {
        fun c(w: LiveWork, spared: Boolean = true) = interruptCostCopy(w, true, true, backgroundSpared = spared)
        assertEquals("Stops the current response.", c(LiveWork.None))
        assertEquals("Stops the current response and 1 running tool call.", c(LiveWork(1, 0)))
        assertEquals("Stops the current response. 1 background task keeps running.", c(LiveWork(0, 1)))
        assertEquals("Stops the current response. 3 background tasks keep running.", c(LiveWork(0, 3)))
        assertEquals(
            "Stops the current response and 2 running tool calls. 3 background tasks keep running.",
            c(LiveWork(2, 3)),
        )
        // Not spared (a 154 server / per-turn Claude): today's §4.1 words.
        assertEquals("Stops the current response, and may stop 3 background tasks.", c(LiveWork(0, 3), spared = false))
        assertEquals(
            "Stops the current response and 1 running tool call, and may stop 3 background tasks.",
            c(LiveWork(1, 3), spared = false),
        )
        // Spared on an engine that cannot see background work says nothing about it.
        assertEquals(
            "Stops the current response. It may also stop background work the agent started.",
            interruptCostCopy(LiveWork(0, 3), foregroundCertain = false, backgroundVisible = false, backgroundSpared = true),
        )
    }

    // ---- spec §4.1 the cost sentence ------------------------------------------------------------------

    @Test
    fun interruptCostCopyIsTheSpecTable() {
        assertEquals("Stops the current response.", interruptCostCopy(LiveWork.None, true, true))
        assertEquals(
            "Stops the current response and 1 running tool call, and may stop 3 background tasks.",
            interruptCostCopy(LiveWork(1, 3), foregroundCertain = true, backgroundVisible = true),
        )
        assertEquals(
            "Stops the current response and 1 running tool call. It may also stop background work the agent started.",
            interruptCostCopy(LiveWork(1, 0), foregroundCertain = true, backgroundVisible = false),
        )
        assertEquals(
            "Stops the current response, and may stop 1 running tool call. It may also stop background work the agent started.",
            interruptCostCopy(LiveWork(1, 0), foregroundCertain = false, backgroundVisible = false),
        )
        assertEquals(
            "Stops the current response. It may also stop background work the agent started.",
            interruptCostCopy(LiveWork.None, foregroundCertain = false, backgroundVisible = false),
        )
        assertEquals(
            "Stops the current response, and may stop 2 running tool calls and 3 background tasks.",
            interruptCostCopy(LiveWork(2, 3), foregroundCertain = false, backgroundVisible = true),
        )
        // The engine facts: foreground certain on Claude and Pi only; background visible on Claude only; an unknown engine neither.
        assertEquals(listOf(true, true, false, false, false), listOf("claude", "pi", "codex", "opencode", null).map(QueueWait::foregroundCertain))
        assertEquals(listOf(true, false, false, false), listOf("claude", "pi", "codex", null).map(QueueWait::backgroundVisible))
    }

    // ---- spec §4.1a A ---------------------------------------------------------------------------------

    @Test
    fun steeredStopCopyIsPerEngineAndSaysMay() {
        assertEquals("The message you sent into this turn may still run after Stop.", steeredStopCopy("claude", 1))
        assertEquals("The 2 messages you sent into this turn may still run after Stop.", steeredStopCopy("claude", 2))
        assertEquals("The message you sent into this turn may wait and go with your next message.", steeredStopCopy("opencode", 1))
        assertEquals("The 3 messages you sent into this turn may wait and go with your next message.", steeredStopCopy("opencode", 3))
        assertEquals("The message you sent into this turn may go back to the queue, after any newer messages.", steeredStopCopy("codex", 1))
        assertEquals("The 2 messages you sent into this turn may go back to the queue, after any newer messages.", steeredStopCopy("pi", 2))
        // n = 0 and engines with no row below say nothing.
        assertEquals("", steeredStopCopy("claude", 0))
        for (p in listOf("reasonix", "acp", "dsh", "claude-per-turn", null)) assertEquals("", steeredStopCopy(p, 2))
    }

    // ---- spec §1 V4 / V5 ------------------------------------------------------------------------------

    @Test
    fun queuedLabelIsV5OnlyWhenTheEngineCanSteer() {
        assertEquals("Queued on the server · sends when this turn ends", queuedLabel(false, row()))
        assertEquals("Queued on the server · goes into this turn at the agent’s next step", queuedLabel(true, row()))
        // A row carrying attachments is never steered in the first ship (D-L143-3 item 5): it reads V4.
        assertEquals(QueueWait.QUEUED_TURN_END_COPY, queuedLabel(true, row(attachmentIds = listOf("a1"))))
    }

    @Test
    fun aLegacyNextCallRowReadsV4EvenOnASteerEngine() {
        assertEquals(QueueWait.QUEUED_TURN_END_COPY, queuedLabel(true, row(flushMode = "next-call")))
        assertEquals(false, QueueWait.isSteered(true, row(flushMode = "next-call")))
        assertEquals(true, QueueWait.isSteered(true, row()))
    }

    // ---- spec §4.1a C: held rows ----------------------------------------------------------------------

    @Test
    fun heldLabelIsTheReasonLeadPlusTheKindHintVerbatim() {
        // open_failed (and absent) = today's web strings byte for byte.
        val failed = "Not sent: the session failed to open."
        assertEquals("$failed Edit to send it again, or remove it.", heldLabel(row(held = true, reason = "open_failed")))
        assertEquals("$failed Edit to send it again, or remove it.", heldLabel(row(held = true, reason = null)))
        assertEquals(
            "$failed This message from another session cannot be edited; remove it.",
            heldLabel(row(held = true, origin = "system", kind = "peer")),
        )
        assertEquals(
            "$failed Stop handover in the Session links menu (⋯) cancels it.",
            heldLabel(row(held = true, origin = "system", kind = "handover-request")),
        )
        assertEquals(
            "May not have reached the agent; it was not sent again. Edit to resend it, or remove it.",
            heldLabel(row(held = true, reason = "steer_uncertain")),
        )
        assertEquals(
            "Not sent: an attachment was missing when it was time to send. Edit to send the text without it, or remove this message.",
            heldLabel(row(held = true, reason = "attachment_missing")),
        )
        // A newer server's reason (decoded as a string): "Not sent." + the kind hint, never "failed to open".
        assertEquals("Not sent. Edit to send it again, or remove it.", heldLabel(row(held = true, reason = "zzz")))
        assertEquals("Not sent. This message from another session cannot be edited; remove it.", heldLabel(row(held = true, reason = "zzz", origin = "system", kind = "peer")))
    }

    // ---- spec §4.2: the one row that may Interrupt and send --------------------------------------------

    @Test
    fun interruptAndSendIsOnTheFirstNonHeldOperatorRowOnly() {
        assertEquals("a", interruptAndSendRowId(listOf(row("a"), row("b"), row("c"))))
        // Held rows are skipped (they never send by themselves); the next live operator row is the one.
        assertEquals("b", interruptAndSendRowId(listOf(row("a", held = true), row("b"), row("c"))))
        // A peer / handover row first = none, even with an operator row behind it.
        assertNull(interruptAndSendRowId(listOf(row("p", origin = "system", kind = "peer"), row("b"))))
        assertNull(interruptAndSendRowId(listOf(row("h", origin = "system", kind = "handover-request"), row("b"))))
        // Nothing live to deliver.
        assertNull(interruptAndSendRowId(listOf(row("a", held = true))))
        assertNull(interruptAndSendRowId(emptyList()))
    }
}
