package com.tether.app.protocol.helpers

import com.tether.app.protocol.helpers.QueueWait.RowAction
import com.tether.app.protocol.model.QueuedMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v165: which action a waiting row gets, what its wait label says in each mode, and the protocol gate. */
class QueueSteerRulesTest {
    private fun row(id: String = "q", attachmentIds: List<String>? = null, held: Boolean = false, kind: String? = null, origin: String? = null) =
        QueuedMessage(queueId = id, text = "t-$id", held = held, attachmentIds = attachmentIds, noticeKind = kind?.let(::JsonPrimitive), origin = origin?.let(::JsonPrimitive))

    @Test fun theGateIsTheServersProtocol165() {
        assertFalse(QueueWait.steerSupported(164))
        assertFalse(QueueWait.steerSupported(null))
        assertTrue(QueueWait.steerSupported(165))
        assertTrue(QueueWait.steerSupported(170))
        assertFalse("a server below 165 never holds", QueueWait.steerHeld("hold", 164))
        assertTrue(QueueWait.steerHeld("hold", 165))
        assertFalse(QueueWait.steerHeld("auto", 165))
        assertFalse("absent is automatic", QueueWait.steerHeld(null, 165))
        assertFalse("an unknown value is automatic", QueueWait.steerHeld("later", 165))
    }

    @Test fun theLabelFollowsTheRowsOutcome() {
        assertEquals(RowAction.Steer, QueueWait.rowAction(steerCapable = true, message = row()))
        assertEquals("attachments take Interrupt and send", RowAction.InterruptAndSend, QueueWait.rowAction(true, row(attachmentIds = listOf("a"))))
        assertEquals("an engine that cannot steer", RowAction.InterruptAndSend, QueueWait.rowAction(false, row()))
        assertEquals("a refused steer offers Interrupt and send", RowAction.InterruptAndSend, QueueWait.rowAction(true, row(), refused = true))
    }

    @Test fun everyWaitingOperatorRowGetsTheActionAt165ButOnlyTheNextOneBefore() {
        val a = row("a"); val b = row("b")
        assertTrue(QueueWait.rowOffersAction(a, steerSupported = true, nextRowId = "a"))
        assertTrue(QueueWait.rowOffersAction(b, steerSupported = true, nextRowId = "a"))
        assertTrue(QueueWait.rowOffersAction(a, steerSupported = false, nextRowId = "a"))
        assertFalse(QueueWait.rowOffersAction(b, steerSupported = false, nextRowId = "a"))
        assertFalse("a held row", QueueWait.rowOffersAction(row("h", held = true), true, "a"))
        assertFalse("a peer row", QueueWait.rowOffersAction(row("p", kind = "peer", origin = "system"), true, "a"))
    }

    @Test fun theWaitLabelFollowsTheModeAndTheRefusal() {
        val plain = row()
        assertEquals(QueueWait.QUEUED_STEER_COPY, QueueWait.queuedLabel(true, plain))
        assertEquals("Queued on the server · waits for Steer or the end of this turn", QueueWait.queuedLabel(true, plain, hold = true))
        assertEquals("attachments keep the turn-end label in hold mode", QueueWait.QUEUED_TURN_END_COPY, QueueWait.queuedLabel(true, row(attachmentIds = listOf("a")), hold = true))
        assertEquals("an engine that cannot steer", QueueWait.QUEUED_TURN_END_COPY, QueueWait.queuedLabel(false, plain, hold = true))
        assertEquals(
            "Queued on the server · the agent could not take it into this turn; it sends when this turn ends",
            QueueWait.queuedLabel(true, plain, refused = true),
        )
    }

    @Test fun theRowSummaryReadsEachState() {
        val plain = row()
        val steer = QueueWait.queueRowSummary(plain, steer = true)
        assertEquals(QueueWait.QueueRowGlyph.CornerDownRight, steer.glyph)
        assertEquals("Into this turn", steer.lead)
        val hold = QueueWait.queueRowSummary(plain, holdByMode = true)
        assertEquals(QueueWait.QueueRowGlyph.Clock, hold.glyph)
        assertEquals("", hold.lead)
        assertEquals("Waits for Steer or the turn end", hold.meta)
        val refused = QueueWait.queueRowSummary(plain, steer = true, steerRefused = true)
        assertEquals(QueueWait.QueueRowGlyph.Clock, refused.glyph)
        assertEquals("Not steered", refused.lead)
        assertEquals(QueueWait.QueueLeadTone.Muted, refused.leadTone)
        assertEquals("Not steered · sends when this turn ends", refused.meta)
        assertEquals("Sends when this turn ends", QueueWait.queueRowSummary(plain).meta)
    }
}
