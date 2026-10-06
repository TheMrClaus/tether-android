package com.tether.app.protocol

import com.tether.app.protocol.model.AgentSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ta-coik.62: protocol v52 `AgentSession.resumeCommand` (lib/protocol.ts at tether 29537e0, ~:2135): the
 * server-built terminal resume command. Absent (the fake engine; a fresh chat with no native id) = null.
 */
class ResumeCommandWireTest {
    private fun session(extra: String, id: String = "s1") =
        """{"id":"$id","provider":"claude","name":"n","cwd":"/w","status":"idle","startedAt":1,"updatedAt":2$extra}"""

    private fun created(extra: String) =
        (ServerMessage.parse("""{"type":"created","session":${session(extra)}}""") as ServerMessage.Created).session

    @Test
    fun theCommandIsDecodedVerbatim() {
        val cmd = "cd -- /w && CLAUDE_CONFIG_DIR=/h/.claude claude --resume 3f2a9c1e"
        assertEquals(cmd, created(""","resumeCommand":${kotlinx.serialization.json.JsonPrimitive(cmd)}""").resumeCommand)
    }

    @Test
    fun absentIsNull() {
        assertNull(created("").resumeCommand)
        assertNull(created(""","resumeCommand":null""").resumeCommand)
    }

    @Test
    fun itRidesTheReadyFrameRowsAndAbsentRowsStayNull() {
        val ready = ServerMessage.parse(
            """{"type":"ready","protocolVersion":143,"nativeProtocolFloor":129,"sessions":[${session("")},${session(""","resumeCommand":"codex resume x"""", id = "s2")}],"providers":[],"workspaceRoot":"/w"}""",
        ) as ServerMessage.Ready
        assertEquals(listOf("s1", "s2"), ready.sessions.map { it.id })
        assertEquals(listOf(null, "codex resume x"), ready.sessions.map { it.resumeCommand })
    }

    @Test
    fun theRowRoundTrips() {
        val row: AgentSession = created(""","resumeCommand":"opencode -s x"""")
        val json = TetherJson.encodeToString(AgentSession.serializer(), row)
        assertEquals("opencode -s x", TetherJson.decodeFromString(AgentSession.serializer(), json).resumeCommand)
    }
}
