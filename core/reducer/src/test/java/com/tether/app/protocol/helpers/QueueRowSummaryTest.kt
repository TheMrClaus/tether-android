package com.tether.app.protocol.helpers

import com.tether.app.protocol.model.AttachmentMeta
import com.tether.app.protocol.model.QueuedMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * tth-5u90 / D-GH-8 (spec A9b): the Kotlin twin of lib/queue-wait.ts `queueRowSummary`. The cases are the web unit test's table
 * (same inputs, same lead / preview / kindName / meta words), including R2-N1: a held `steer_uncertain` row reads "Unconfirmed"
 * even on a handed-off strip, and R2-N2: its tone is the danger tone with the warning glyph, like "Not sent".
 */
class QueueRowSummaryTest {
    private fun peer(text: String, id: String? = "s-1", name: String? = "disaster52", held: Boolean = false) = QueuedMessage(
        queueId = "p", text = text, origin = JsonPrimitive("system"), noticeKind = JsonPrimitive("peer"),
        fromSessionId = id, fromSessionName = name, held = held,
    )

    @Test fun peerLeadNamesTheSenderAndTheBodyIsThePreview() {
        val s = QueueWait.queueRowSummary(peer("Message from session disaster52 (s-1): Please run lint."))
        assertEquals("From disaster52", s.lead)
        assertEquals(QueueWait.QueueLeadTone.Ink, s.leadTone)
        assertEquals("Please run lint.", s.preview)
        assertEquals("Message from session disaster52, another agent, not you", s.kindName)
        assertEquals("From disaster52", s.sheetTitle)
        assertEquals("Another session, not you", s.meta)
        assertEquals(QueueWait.QueueRowGlyph.Clock, s.glyph)
    }

    @Test fun aPeerTextWithoutTheDisplayPrefixKeepsAttributionAndFullText() {
        val s = QueueWait.queueRowSummary(peer("Just text", name = null))
        assertEquals("From another session", s.lead)
        assertEquals("Just text", s.preview)
    }

    @Test fun aHeldPeerPreviewsTheUnstrippedText() {
        val s = QueueWait.queueRowSummary(peer("Message from session disaster52 (s-1): Body", held = true))
        assertEquals("Not sent", s.lead)
        assertEquals(QueueWait.QueueLeadTone.Danger, s.leadTone)
        assertEquals("Message from session disaster52 (s-1): Body", s.preview)
        assertEquals(QueueWait.QueueRowGlyph.AlertTriangle, s.glyph)
    }

    @Test fun operatorRowsByState() {
        fun op(held: Boolean = false, reason: String? = null) = QueuedMessage(queueId = "o", text = "Do it\n  now", held = held, heldReason = reason)
        assertEquals("", QueueWait.queueRowSummary(op()).lead)
        assertEquals("Sends when this turn ends", QueueWait.queueRowSummary(op()).meta)
        assertEquals("Do it now", QueueWait.queueRowSummary(op()).preview)
        QueueWait.queueRowSummary(op(), steer = true).also {
            assertEquals("Into this turn", it.lead); assertEquals(QueueWait.QueueRowGlyph.CornerDownRight, it.glyph); assertEquals("Goes into this turn", it.meta)
        }
        QueueWait.queueRowSummary(op(), movesToNewSession = true).also { assertEquals("To new session", it.lead); assertEquals("Goes to the new session", it.meta) }
        QueueWait.queueRowSummary(op(held = true, reason = "open_failed")).also {
            assertEquals("Not sent", it.lead); assertEquals("Your message, not sent", it.kindName); assertEquals("Not sent", it.meta); assertEquals(QueueWait.QueueLeadTone.Danger, it.leadTone)
        }
        QueueWait.queueRowSummary(op(held = true, reason = "steer_uncertain")).also {
            assertEquals("Unconfirmed", it.lead)
            assertEquals(QueueWait.QueueLeadTone.Danger, it.leadTone)
            assertEquals(QueueWait.QueueRowGlyph.AlertTriangle, it.glyph)
            assertEquals("Your queued message, unconfirmed", it.kindName)
            assertEquals("Maybe not delivered", it.meta)
        }
    }

    @Test fun r2n1UnconfirmedAlsoReadsUnconfirmedOnAHandedOffStrip() {
        val uncertain = QueuedMessage(queueId = "u", text = "x", held = true, heldReason = "steer_uncertain")
        assertEquals("Unconfirmed", QueueWait.queueRowSummary(uncertain, handedOff = true).lead)
        assertEquals("Not sent", QueueWait.queueRowSummary(QueuedMessage(queueId = "o", text = "x"), handedOff = true).lead)
        assertEquals("Not sent", QueueWait.queueRowSummary(QueuedMessage(queueId = "h", text = "x", held = true), handedOff = true).lead)
    }

    @Test fun handoverInstructionAndAttachments() {
        val instruction = QueuedMessage(queueId = "i", text = "Handover instruction", origin = JsonPrimitive("system"), noticeKind = JsonPrimitive("handover-request"))
        QueueWait.queueRowSummary(instruction).also { assertEquals("Handover", it.lead); assertEquals(QueueWait.QueueRowGlyph.ArrowRightLeft, it.glyph); assertEquals("Runs when this turn ends", it.meta) }
        QueueWait.queueRowSummary(instruction, handedOff = true).also { assertEquals("Not sent", it.lead); assertEquals("Not sent", it.meta) }
        val files = QueuedMessage(
            queueId = "a", text = "", attachmentIds = listOf("1", "2"),
            attachments = listOf(AttachmentMeta("a.png", "image/png"), AttachmentMeta("b.txt", "text/plain")),
        )
        QueueWait.queueRowSummary(files).also { assertEquals("2 files", it.preview); assertEquals("Your queued message, 2 files", it.kindName) }
    }
}
