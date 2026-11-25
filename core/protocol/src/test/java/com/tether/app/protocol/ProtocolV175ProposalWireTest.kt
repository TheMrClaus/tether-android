package com.tether.app.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v175: the three proposal frames. Their exact key sets, the round trip, and that no frame carries prompt text. */
class ProtocolV175ProposalWireTest {
    private val settings: JsonObject = buildJsonObject { put("provider", "claude"); put("cwd", "/work") }

    @Test
    fun eachFrameWritesOnlyItsKeys() {
        assertEquals(setOf("type", "sessionId", "proposalId", "settings", "requestId"), ClientMessage.ProposalFrame("start", "s1", "p1", settings, "r1").toJsonObject().keys)
        assertEquals(setOf("type", "sessionId", "proposalId", "requestId"), ClientMessage.ProposalFrame("here", "s1", "p1", null, "r1").toJsonObject().keys)
        assertEquals(setOf("type", "sessionId", "proposalId"), ClientMessage.ProposalFrame("dismiss", "s1", "p1").toJsonObject().keys)
        assertEquals("proposal-dismiss", (ClientMessage.ProposalFrame("dismiss", "s1", "p1").toJsonObject()["type"] as JsonPrimitive).content)
    }

    @Test
    fun theStartFrameHasNoPromptField() {
        val frame = ClientMessage.ProposalFrame("start", "s1", "p1", settings, "r1").toJsonObject()
        assertFalse("prompt" in frame.keys)
        assertFalse("prompt" in (frame["settings"] as JsonObject).keys)
    }

    @Test
    fun framesRoundTripAndAreKnownTypes() {
        for (m in listOf(
            ClientMessage.ProposalFrame("start", "s1", "p1", settings, "r1"),
            ClientMessage.ProposalFrame("here", "s1", "p1", null, "r2"),
            ClientMessage.ProposalFrame("dismiss", "s1", "p1", null, null),
        )) assertEquals(m, ClientMessage.decode(m.toJsonObject()).getOrThrow())
        assertTrue(setOf("proposal-start", "proposal-here", "proposal-dismiss").all { it in ClientMessage.TYPES })
    }
}
