package com.tether.app.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v165: Steer on one waiting message. `queue-steer`, the row-specific `interrupt`, and the `queue_steer_result` answer. */
class ProtocolV165SteerWireTest {
    @Test
    fun theSteerFrameCarriesTheSessionAndTheRow() {
        val frame = ClientMessage.QueueSteer("s1", "q2").toJsonObject()
        assertEquals(JsonObject(mapOf("type" to JsonPrimitive("queue-steer"), "sessionId" to JsonPrimitive("s1"), "queueId" to JsonPrimitive("q2"))), frame)
        assertEquals(ClientMessage.QueueSteer("s1", "q2"), ClientMessage.decode(frame).getOrThrow())
    }

    @Test
    fun theRowIsNamedOnAnInterruptOnlyWhenOneIsGiven() {
        assertEquals(setOf("type", "sessionId"), ClientMessage.Interrupt("s1").toJsonObject().keys)
        val row = ClientMessage.Interrupt("s1", queueId = "q2")
        assertEquals(setOf("type", "sessionId", "queueId"), row.toJsonObject().keys)
        assertEquals(row, ClientMessage.decode(row.toJsonObject()).getOrThrow())
    }

    @Test
    fun theAnswerDecodesEveryStatusAndKeepsAnUnknownOneAsText() {
        for (status in listOf("steered", "not_steerable", "uncertain", "unavailable", "something-new")) {
            val m = ServerMessage.parse("""{"type":"queue_steer_result","sessionId":"s1","queueId":"q1","status":"$status"}""")
            assertEquals(ServerMessage.QueueSteerResult("s1", "q1", status, null), m)
        }
        val withReason = ServerMessage.parse(ServerFixtures.QUEUE_STEER_RESULT)
        assertTrue(withReason is ServerMessage.QueueSteerResult)
        assertEquals("attachments", (withReason as ServerMessage.QueueSteerResult).reason)
    }
}
