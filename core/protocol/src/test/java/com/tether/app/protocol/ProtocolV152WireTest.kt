package com.tether.app.protocol

import com.tether.app.protocol.model.AgentSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ta-hkk7.3 (W28): tether v152 `nameGenerated` on every session, additive (the hello stays 143). */
class ProtocolV152WireTest {
    private fun session(extra: String) =
        """{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"idle","startedAt":1,"updatedAt":2$extra}"""

    private fun ready(s: String) = ServerMessage.parse(
        """{"type":"ready","protocolVersion":152,"nativeProtocolFloor":129,"sessions":[$s],"providers":[],"workspaceRoot":"/w"}""",
    ) as ServerMessage.Ready

    @Test
    fun absentIsFalseAndTrueParses() {
        assertFalse(ready(session("")).sessions.single().nameGenerated)
        assertFalse(ready(session(""","nameGenerated":false""")).sessions.single().nameGenerated)
        assertTrue(ready(session(""","nameGenerated":true""")).sessions.single().nameGenerated)
    }

    @Test
    fun encodeOmitsFalseAndKeepsTrueAndRoundTrips() {
        val generated = ready(session(""","nameGenerated":true""")).sessions.single()
        val plain = ready(session("")).sessions.single()
        val withKey = TetherJson.parseToJsonElement(TetherJson.encodeToString(AgentSession.serializer(), generated)) as JsonObject
        val without = TetherJson.parseToJsonElement(TetherJson.encodeToString(AgentSession.serializer(), plain)) as JsonObject
        assertTrue(withKey["nameGenerated"]!!.jsonPrimitive.boolean)
        assertFalse("nameGenerated" in without.keys)
        assertEquals(generated, TetherJson.decodeFromString(AgentSession.serializer(), TetherJson.encodeToString(AgentSession.serializer(), generated)))
    }

    @Test
    fun helloStays143() = assertEquals(143, TARGET_PROTOCOL_VERSION)
}
