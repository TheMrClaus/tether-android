package com.tether.app.protocol

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-ozn2.3: tether v150 (#273 operator handover). The hello stays at 143 (wave-check 1: the server routes
 * `handover-request` without a version check, server.mjs:12572, and accepts any native hello in 129..150).
 */
class ProtocolV150WireTest {
    private fun session(extra: String, id: String = "s1") =
        """{"id":"$id","provider":"claude","name":"n","cwd":"/w","status":"idle","startedAt":1,"updatedAt":2$extra}"""

    private fun ready(vararg sessions: String) = ServerMessage.parse(
        """{"type":"ready","protocolVersion":150,"nativeProtocolFloor":129,"sessions":[${sessions.joinToString(",")}],"providers":[],"workspaceRoot":"/w"}""",
    ) as ServerMessage.Ready

    @Test
    fun handoverRequestFrameIsTypeAndSessionIdOnly() {
        val frame = ClientMessage.HandoverRequest("s1").toJsonObject()
        assertEquals(setOf("type", "sessionId"), frame.keys)
        assertEquals("handover-request", (frame["type"] as JsonPrimitive).content)
        assertEquals(ClientMessage.HandoverRequest("s1"), ClientMessage.decode(frame).getOrThrow())
        assertTrue("handover-request" in ClientMessage.TYPES)
    }

    @Test
    fun sessionFieldsPresentAbsentAndNull() {
        val r = ready(
            session(""","handedOffFrom":"src","handoverPending":true,"handoverBlockedReason":"plan_mode""""),
            session(""","handoverBlockedReason":null""", id = "s2"),
            session("", id = "s3"),
        )
        val (a, b, c) = r.sessions
        assertEquals("src", a.handedOffFrom)
        assertTrue(a.handoverPending)
        assertEquals("plan_mode", a.handoverBlockedCode)
        assertNull(b.handoverBlockedCode); assertFalse(b.handoverPending); assertNull(b.handedOffFrom)
        assertNull(c.handoverBlockedCode); assertNull(c.handoverBlockedReason)
    }

    @Test
    fun aMalformedBlockedReasonNeverDropsTheSession() {
        val r = ready(
            session(""","handoverBlockedReason":42"""),
            session(""","handoverBlockedReason":{"a":1}""", id = "s2"),
            session(""","handoverBlockedReason":["locked"]""", id = "s3"),
            session(""","handoverBlockedReason":"a_future_code"""", id = "s4"),
            session(""","handoverBlockedReason":"" """, id = "s5"),
        )
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), r.sessions.map { it.id })
        assertEquals(listOf(null, null, null, "a_future_code", null), r.sessions.map { it.handoverBlockedCode })
    }

    private fun snapshotWithQueue(queue: String) = ServerMessage.parse(
        """{"type":"snapshot","sessionId":"sess-1","throughSeq":9,"state":{
             "tetherSessionId":"sess-1","provider":"claude","cwd":"/w","status":"running","activeTurnId":null,
             "turnOrder":[],"turnsById":{},"queuedMessages":$queue,"removedQueueIds":[]}}""",
    ) as ServerMessage.Snapshot

    @Test
    fun theHandoverRequestRowStaysInTheComposerListInOrderAndTheHandoverRowIsHidden() {
        val frame = snapshotWithQueue(
            """[{"queueId":"a","text":"one","origin":"user"},
                {"queueId":"r","text":"Tether instruction","origin":"system","noticeKind":"handover-request"},
                {"queueId":"h","text":"first prompt","origin":"system","noticeKind":"handover"},
                {"queueId":"s","text":"auto","origin":"system","noticeKind":"spawn"},
                {"queueId":"b","text":"two"}]""",
        )
        val typed = frame.projection!!.queuedMessages
        assertEquals(listOf("a", "r", "b"), com.tether.app.protocol.model.operatorQueuedMessages(typed).map { it.queueId })
        assertEquals(listOf(false, true, false, false, false), typed.map { it.isHandoverRequest })
        val views = com.tether.app.protocol.model.SessionView(frame.state!!).queuedMessages
        assertEquals(listOf("a", "r", "b"), views.filter { it.isListedInComposer }.map { it.queueId })
        assertEquals(listOf(false, true, false, false, false), views.map { it.isHandoverRequest })
        // the user-only predicate is unchanged
        assertEquals(listOf("a", "b"), typed.filter { it.isOperatorMessage }.map { it.queueId })
    }
}
