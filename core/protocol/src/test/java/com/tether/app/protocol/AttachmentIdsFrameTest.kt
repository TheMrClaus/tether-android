package com.tether.app.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ta-delivery A2 (wire v155, tether dlv-L1 dc1bc720): `send` and `queue-add` carry `attachmentIds`; never with inline `attachments`. */
class AttachmentIdsFrameTest {
    private fun obj(m: ClientMessage): JsonObject = TetherJson.parseToJsonElement(m.encode()).jsonObject

    @Test
    fun sendCarriesAttachmentIdsAndNoInlineBytes() {
        val ids = listOf("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")
        val o = obj(ClientMessage.Send("s1", "hi", "k1", attachments = null, attachmentIds = ids))
        assertEquals(ids, o["attachmentIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(o.containsKey("attachments"))
        val back = ClientMessage.decode(o).getOrThrow() as ClientMessage.Send
        assertEquals(ids, back.attachmentIds)
    }

    @Test
    fun queueAddCarriesAttachmentIdsAndIsUnchangedWithout() {
        val o = obj(ClientMessage.QueueAdd("s1", "q1", "hi", listOf("11111111-1111-4111-8111-111111111111")))
        assertTrue(o["attachmentIds"] is JsonArray)
        assertFalse(obj(ClientMessage.QueueAdd("s1", "q1", "hi")).containsKey("attachmentIds"))
        assertFalse(obj(ClientMessage.Send("s1", "hi", "k1")).containsKey("attachmentIds"))
    }
}
