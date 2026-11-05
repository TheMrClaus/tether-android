package com.tether.app.protocol.fold

import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.model.QueuedMessage
import kotlinx.serialization.json.Json
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.arr
import com.tether.app.protocol.tree.obj
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * delivery A4a (lead #143): the v155 fold paths of tether dc1bc720 (delivery L1) — `queued_message_delivered`
 * (D-L141-6 (2)), `heldReason` (D-L141-7 B2) and the queued attachments. The reducer corpus
 * (`queued-message-delivered-steer.json`) pins the delivered case; these pin the Kotlin-visible shapes.
 */
class ProtocolV155FoldTest {
    private fun added(queueId: String, attachments: JsonArray? = null, ids: JsonArray? = null) =
        evNullTurn("queued_message_added") {
            put("queueId", queueId)
            put("text", "q-$queueId")
            if (attachments != null) put("attachments", attachments)
            if (ids != null) put("attachmentIds", ids)
        }

    private fun delivered(turnId: String, queueId: String, text: String = "steered") =
        ev("queued_message_delivered", turnId) {
            put("queueId", queueId)
            put("text", text)
            put("via", "steer")
        }

    private fun typed(state: JsObj) = checkNotNull(LegacyProjectionAdapter.adaptOnce(state))

    private fun blocks(state: JsObj, turnId: String): JsObj = ((state["turnsById"] as JsObj)[turnId] as JsObj)["blocksById"] as JsObj

    @Test fun deliveredFoldsIntoAUserBlockKeyedByTurnAndQueueId() {
        val state = foldTree(
            freshTree(),
            ev("turn_started", "t1", seq = 1, ts = 1),
            added("q1"),
            delivered("t1", "q1"),
        )
        val block = blocks(state, "t1")["user:t1:q1"] as JsObj
        assertEquals("user:t1:q1", (block["blockId"] as JsStr).value)
        assertEquals("user_message", (block["kind"] as JsStr).value)
        assertEquals("steered", (block["text"] as JsStr).value)
        assertEquals(JsBool.TRUE, block["steered"])
        assertEquals("q1", (block["queueId"] as JsStr).value)
        assertTrue(state["queuedMessages"].arr!!.isEmpty())
        assertEquals(listOf("q1"), state["removedQueueIds"].arr!!.map { (it as JsStr).value })
        assertNull(state["withdrawnQueueIds"])
    }

    @Test fun aDuplicateOrLateDeliveryChangesNothing() {
        val once = foldTree(freshTree(), ev("turn_started", "t1", seq = 1, ts = 1), added("q1"), delivered("t1", "q1"))
        assertEquals(once, foldTree(once, delivered("t1", "q1", "dup")))
        // a delivery for a turn that is not the open current one takes nothing
        assertEquals(once, foldTree(once, delivered("t9", "q2")))
    }

    @Test fun theTurnsOwnPromptBlockAndASteeredBlockCoexist() {
        val state = foldTree(
            freshTree(),
            ev("turn_started", "t1", seq = 1, ts = 1),
            ev("user_message_accepted", "t1", seq = 2, ts = 2) { put("text", "first") },
            delivered("t1", "q1"),
        )
        val b = blocks(state, "t1")
        assertEquals(listOf("user:t1", "user:t1:q1"), ((state["turnsById"] as JsObj)["t1"] as JsObj)["blocks"].arr!!.map { (it as JsStr).value })
        assertEquals("first", ((b["user:t1"] as JsObj)["text"] as JsStr).value)
        assertEquals("steered", ((b["user:t1:q1"] as JsObj)["text"] as JsStr).value)
        // typed view: the steered block decodes with its two new fields, the turn's own prompt without them
        val turn = typed(state).turnsById.getValue("t1")
        assertEquals(listOf(null, true), turn.blocks.map { turn.blocksById.getValue(it).steered })
    }

    @Test fun heldReasonRoundTripsAndAbsentReadsOpenFailed() {
        val state = foldTree(
            freshTree(),
            added("a"), added("b"), added("c"),
            evNullTurn("queued_message_updated") { put("queueId", "a"); put("text", "a2"); put("held", true) },
            evNullTurn("queued_message_updated") { put("queueId", "b"); put("text", "b2"); put("held", true); put("heldReason", "steer_uncertain") },
        )
        val rows = typed(state).queuedMessages.associateBy { it.queueId }
        assertEquals("open_failed", rows.getValue("a").heldReasonValue)
        assertEquals("steer_uncertain", rows.getValue("b").heldReasonValue)
        assertEquals(false, rows.getValue("c").held)
        // an explicit held:false clears the flag and the reason
        val cleared = foldTree(state, evNullTurn("queued_message_updated") { put("queueId", "b"); put("text", "b3"); put("held", false) })
        val b = cleared["queuedMessages"].arr!!.map { it as JsObj }.first { (it["queueId"] as JsStr).value == "b" }
        assertNull(b["held"])
        assertNull(b["heldReason"])
    }

    @Test fun queuedAttachmentsAndStagedIdsAreKeptOnTheRow() {
        val meta = buildJsonArray { add(buildJsonObject { put("name", "p.png"); put("mediaType", "image/png") }) }
        val ids = buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("att-1")) }
        val state = foldTree(freshTree(), added("q1", meta, ids), added("q2", JsonArray(emptyList()), JsonArray(emptyList())))
        val rows = typed(state).queuedMessages
        assertEquals("p.png", rows[0].attachments!!.single().name)
        assertEquals(listOf("att-1"), rows[0].attachmentIds)
        assertNull(rows[1].attachments)
        assertNull(rows[1].attachmentIds)
    }

    /**
     * D-L143-3 item 4: L5b adds a third reason, "attachment_missing", inside the unshipped 155 — and a later server may add more.
     * heldReason is a plain String, never a closed enum: a known-new and an unknown value fold, decode and re-encode without throwing.
     */
    @Test fun anUnknownOrNewHeldReasonDecodesAndRoundTripsWithoutThrowing() {
        val state = foldTree(
            freshTree(),
            added("m"), added("z"),
            evNullTurn("queued_message_updated") { put("queueId", "m"); put("text", "m"); put("held", true); put("heldReason", "attachment_missing") },
            evNullTurn("queued_message_updated") { put("queueId", "z"); put("text", "z"); put("held", true); put("heldReason", "zzz") },
        )
        val rows = typed(state).queuedMessages.associateBy { it.queueId }
        assertEquals("attachment_missing", rows.getValue("m").heldReasonValue)
        assertEquals("zzz", rows.getValue("z").heldReasonValue)
        val json = Json { ignoreUnknownKeys = true }
        for (row in rows.values) {
            assertEquals(row, json.decodeFromString(QueuedMessage.serializer(), json.encodeToString(QueuedMessage.serializer(), row)))
        }
        assertEquals("zzz", state["queuedMessages"].arr!!.map { it as JsObj }.first { (it["queueId"] as JsStr).value == "z" }["heldReason"].let { (it as JsStr).value })
    }
}
