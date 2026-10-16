package com.tether.app.protocol

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ta-9wdf / ta-74gu: tether v151 (#273 part 2): Stop handover, handover with different settings, the stage fields. */
class ProtocolV151WireTest {
    private fun session(extra: String, id: String = "s1") =
        """{"id":"$id","provider":"claude","name":"n","cwd":"/w","status":"idle","startedAt":1,"updatedAt":2$extra}"""

    private fun ready(vararg sessions: String) = ServerMessage.parse(
        """{"type":"ready","protocolVersion":151,"nativeProtocolFloor":129,"sessions":[${sessions.joinToString(",")}],"providers":[],"workspaceRoot":"/w"}""",
    ) as ServerMessage.Ready

    @Test
    fun handoverCancelFrameIsTypeSessionIdAndRequestId() {
        val frame = ClientMessage.HandoverCancel("s1", "r1").toJsonObject()
        assertEquals(setOf("type", "sessionId", "requestId"), frame.keys)
        assertEquals("handover-cancel", (frame["type"] as JsonPrimitive).content)
        assertEquals(ClientMessage.HandoverCancel("s1", "r1"), ClientMessage.decode(frame).getOrThrow())
        assertEquals(setOf("type", "sessionId"), ClientMessage.HandoverCancel("s1").toJsonObject().keys)
        assertTrue("handover-cancel" in ClientMessage.TYPES)
    }

    @Test
    fun handoverRequestWithSettingsCarriesSuccessorAndRequestIdKeepingAbsentVersusNull() {
        val successor = buildJsonObject {
            put("provider", "codex")
            put("cwd", "/w")
            put("approvalPolicy", JsonNull)
            put("name", "Billing 2")
        }
        val frame = ClientMessage.HandoverRequest("s1", successor, "r1").toJsonObject()
        assertEquals(setOf("type", "sessionId", "requestId", "successor"), frame.keys)
        val sent = frame["successor"] as JsonObject
        assertTrue("an explicit null stays a key", sent.containsKey("approvalPolicy") && sent["approvalPolicy"] is JsonNull)
        assertTrue("an absent key stays absent", !sent.containsKey("model"))
        assertEquals(ClientMessage.HandoverRequest("s1", successor, "r1"), ClientMessage.decode(frame).getOrThrow())
    }

    @Test
    fun theAsIsRequestIsStillTypeAndSessionIdOnly() {
        assertEquals(setOf("type", "sessionId"), ClientMessage.HandoverRequest("s1").toJsonObject().keys)
    }

    @Test
    fun sessionStageFieldsPresentAbsentAndMalformed() {
        val r = ready(
            session(""","handoverStage":"waiting_background","handoverBackgroundTasks":3,"handoverSuccessorName":"parity15""""),
            session("", id = "s2"),
            session(""","handoverStage":7,"handoverBackgroundTasks":"x","handoverSuccessorName":{"a":1}""", id = "s3"),
            session(""","handoverStage":"a_future_stage"""", id = "s4"),
        )
        val (a, b, c, d) = r.sessions
        assertEquals("waiting_background", a.handoverStageCode)
        assertEquals(3, a.handoverBackgroundTaskCount)
        assertEquals("parity15", a.handoverSuccessorHint)
        assertNull(b.handoverStageCode); assertNull(b.handoverBackgroundTaskCount); assertNull(b.handoverSuccessorHint)
        assertEquals(listOf("s1", "s2", "s3", "s4"), r.sessions.map { it.id })
        assertNull(c.handoverStageCode); assertNull(c.handoverBackgroundTaskCount); assertNull(c.handoverSuccessorHint)
        assertEquals("a_future_stage", d.handoverStageCode)
    }
}
