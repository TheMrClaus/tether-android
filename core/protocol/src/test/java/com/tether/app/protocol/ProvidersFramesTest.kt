package com.tether.app.protocol

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-q6p: `providers` (v84) and the retired `acp-agents` (v74) decode tolerantly: a missing or
 * wrongly typed part never drops the frame into [ServerMessage.Unknown] (whose `raw` would hold each
 * profile's plaintext env values), a list that is not the whole registry is marked not intact, and
 * no providers or acp-agents frame, inbound or outbound, prints an env value.
 */
class ProvidersFramesTest {
    private val sentinel = "SENTINEL-env-4a17-do-not-leak"

    private fun parse(text: String) = ServerMessage.parse(text)

    private fun profile(id: String) = """{"id":"$id","extends":"codex","label":"L","enabled":true,"env":{"API_KEY":"$sentinel"}}"""

    @Test fun theRecordedFramesDecode() {
        val wire = File(System.getProperty("parity.corpus") ?: "../../parity-corpus", "wire/settings.jsonl")
        val frames = wire.readLines().filter { it.isNotBlank() }.map { TetherJson.parseToJsonElement(it).jsonObject }
            .filter { it["dir"] == JsonPrimitive("s2c") }.map { it["frame"]!!.jsonObject }
            .filter { it["type"] == JsonPrimitive("providers") }
        assertEquals(2, frames.size)
        val empty = ServerMessage.parse(frames[0]) as ServerMessage.Providers
        assertEquals(emptyList<JsonObject>(), empty.profiles)
        assertTrue(empty.intact)
        val one = ServerMessage.parse(frames[1]) as ServerMessage.Providers
        assertEquals(listOf("parity-claude"), one.profiles.map { (it["id"] as JsonPrimitive).content })
        assertTrue(one.intact)
    }

    @Test fun aWhollyReadListIsIntactAndKeepsEveryKey() {
        val m = parse("""{"type":"providers","profiles":[${profile("a")},{"id":"b","future":{"x":1}}],"extra":1}""") as ServerMessage.Providers
        assertEquals(2, m.profiles.size)
        assertTrue(m.intact)
        assertEquals("""{"x":1}""", m.profiles[1]["future"].toString())
    }

    @Test fun aMissingOrWronglyTypedListIsEmptyAndNotIntact() {
        for (bad in listOf("""{"type":"providers"}""", """{"type":"providers","profiles":"$sentinel"}""", """{"type":"providers","profiles":{"a":1}}""",
            """{"type":"providers","profiles":null}""")) {
            val m = parse(bad)
            assertTrue("$bad -> $m", m is ServerMessage.Providers)
            m as ServerMessage.Providers
            assertEquals(emptyList<JsonObject>(), m.profiles)
            assertFalse(bad, m.intact)
            assertFalse(m.toString().contains(sentinel))
        }
    }

    @Test fun aNonObjectEntryIsDroppedAndTheListIsNotIntact() {
        val m = parse("""{"type":"providers","profiles":[${profile("a")},"$sentinel",7,null,[1]]}""") as ServerMessage.Providers
        assertEquals(1, m.profiles.size)
        assertFalse(m.intact)
    }

    @Test fun noProvidersFramePrintsAnEnvValue() {
        val inbound = parse("""{"type":"providers","profiles":[${profile("a")}]}""") as ServerMessage.Providers
        val outbound = ClientMessage.SetProviders(inbound.profiles)
        val acp = parse("""{"type":"acp-agents","agents":[{"id":"g","command":"gemini","env":{"K":"$sentinel"}}]}""") as ServerMessage.AcpAgents
        val setAcp = ClientMessage.SetAcpAgents(acp.agents)
        for (text in listOf(inbound.toString(), outbound.toString(), acp.toString(), setAcp.toString())) {
            assertFalse(text, text.contains(sentinel))
            assertFalse(text, text.contains("API_KEY"))
        }
        assertEquals("Providers(profiles=1, intact=true)", inbound.toString())
        assertEquals("SetProviders(profiles=1)", outbound.toString())
        assertEquals("AcpAgents(agents=1)", acp.toString())
        assertEquals("SetAcpAgents(agents=1)", setAcp.toString())
        // The wire form still carries the value: only what is printed is redacted.
        assertTrue(outbound.encode().contains(sentinel))
    }

    /** acp-agents is retired at 887c222 (server.mjs never sends it): still decoded, never an Unknown holding its raw entries. */
    @Test fun theRetiredAcpAgentsFrameDecodesTolerantly() {
        for (bad in listOf("""{"type":"acp-agents"}""", """{"type":"acp-agents","agents":"$sentinel"}""", """{"type":"acp-agents","agents":[1,"x",{"id":"g"}]}""")) {
            val m = parse(bad)
            assertTrue("$bad -> $m", m is ServerMessage.AcpAgents)
            assertFalse(m.toString().contains(sentinel))
        }
        assertEquals(1, (parse("""{"type":"acp-agents","agents":[1,"x",{"id":"g"}]}""") as ServerMessage.AcpAgents).agents.size)
        assertEquals("""{"type":"acp-agents"}""", ClientMessage.AcpAgentsRequest.encode())
    }
}
