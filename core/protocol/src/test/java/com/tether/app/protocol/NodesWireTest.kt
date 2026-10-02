package com.tether.app.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T1.5: the v109 node registry frames (lib/protocol.ts NodeSummary, `nodes`,
 * `node-result`, node-add/remove/probe) against the vendored wire corpus and
 * hand fixtures for what the corpus cannot show (every recorded `nodes` list is
 * empty, since the capture server had no peers).
 */
class NodesWireTest {

    private val corpusDir = File(System.getProperty("tether.parityCorpusWire") ?: "../../parity-corpus/wire")

    private fun jsonl(file: File): List<JsonObject> =
        file.readLines().filter { it.isNotBlank() }.map { TetherJson.parseToJsonElement(it).jsonObject }

    private fun scenarioFrames(dir: String): List<Pair<String, JsonObject>> =
        corpusDir.listFiles { f -> f.name.endsWith(".jsonl") && f.name != "client-examples.jsonl" }!!
            .sortedBy { it.name }
            .flatMap { file -> jsonl(file).filter { it.str("dir") == dir }.map { file.name to it["frame"]!!.jsonObject } }

    // ---- server -> client ----------------------------------------------------

    @Test
    fun everyRecordedNodeResultDecodesFieldForField() {
        val frames = scenarioFrames("s2c").filter { it.second.str("type") == "node-result" }
        assertTrue("corpus has node-result frames", frames.size >= 3)
        for ((file, raw) in frames) {
            val m = ServerMessage.parse(raw)
            assertTrue("$file: $raw -> $m", m is ServerMessage.NodeResult)
            m as ServerMessage.NodeResult
            assertEquals(raw["ok"]!!.jsonPrimitive.booleanOrNull, m.ok)
            assertEquals(raw.str("nodeId"), m.nodeId)
            assertEquals(raw.str("message"), m.message)
            assertEquals(raw.str("requestId"), m.requestId)
        }
    }

    @Test
    fun everyRecordedNodesFrameDecodesWithEveryEntry() {
        val frames = scenarioFrames("s2c").filter { it.second.str("type") == "nodes" }
        // nodes.jsonl (after each action), handshake-echo.jsonl (after hello), discovery.jsonl (observer).
        assertTrue("corpus has nodes frames", frames.size >= 4)
        assertTrue(frames.any { it.first == "handshake-echo.jsonl" })
        for ((file, raw) in frames) {
            val m = ServerMessage.parse(raw)
            assertTrue("$file: $raw -> $m", m is ServerMessage.Nodes)
            assertEquals(raw["nodes"]!!.jsonArray.size, (m as ServerMessage.Nodes).nodes.size)
        }
    }

    @Test
    fun aPopulatedNodesFrameDecodesEveryNodeSummaryFieldAndStatus() {
        val statuses = listOf("unknown", "reachable", "unreachable", "unauthorized", "identity_mismatch", "skew", "error")
        val entries = statuses.mapIndexed { i, status ->
            """{"nodeId":"node-000$i","label":"Peer $i","baseUrl":"https://peer$i.example.test","publicKey":"cGFyaXR5LWtleQ",
               "createdAt":1700000000000,"lastSeenAt":${if (i == 0) 0 else 1700000000500},"status":"$status",
               "peerVersion":${if (i == 0) "null" else "\"0.9.$i\""},"peerProtocolVersion":${if (i == 0) "null" else 120 + i},
               "revokedAt":${if (i == 6) 1700000009999 else "null"}}"""
        }
        val m = ServerMessage.parse("""{"type":"nodes","nodes":[${entries.joinToString(",")}]}""")
        assertTrue("$m", m is ServerMessage.Nodes)
        val nodes = (m as ServerMessage.Nodes).nodes
        assertEquals(statuses, nodes.map { it.status })
        assertEquals(
            NodeSummary(
                nodeId = "node-0000", label = "Peer 0", baseUrl = "https://peer0.example.test", publicKey = "cGFyaXR5LWtleQ",
                createdAt = 1_700_000_000_000, lastSeenAt = 0, status = "unknown",
                peerVersion = null, peerProtocolVersion = null, revokedAt = null,
            ),
            nodes[0],
        )
        assertEquals("0.9.5", nodes[5].peerVersion)
        assertEquals(125, nodes[5].peerProtocolVersion)
        assertEquals(1_700_000_000_500, nodes[5].lastSeenAt)
        assertEquals(1_700_000_009_999, nodes[6].revokedAt)
    }

    @Test
    fun aMalformedNodeEntryIsDroppedNotTheFrame() {
        // D4 tolerance: one bad element must not blank the whole registry.
        val m = ServerMessage.parse(
            """{"type":"nodes","nodes":[{"label":"no id"},{"nodeId":"ok-1","status":"reachable","futureField":1}]}""",
        )
        assertTrue("$m", m is ServerMessage.Nodes)
        assertEquals(listOf("ok-1"), (m as ServerMessage.Nodes).nodes.map { it.nodeId })
        // A future status value decodes as-is (the web renders it "Not probed yet").
        val future = ServerMessage.parse("""{"type":"nodes","nodes":[{"nodeId":"n","status":"quarantined"}]}""")
        assertEquals("quarantined", (future as ServerMessage.Nodes).nodes.single().status)
    }

    @Test
    fun nodeFramesWithoutTheirRequiredFieldAreUnknownNotACrash() {
        assertTrue(ServerMessage.parse("""{"type":"nodes"}""") is ServerMessage.Unknown)
        assertTrue(ServerMessage.parse("""{"type":"nodes","nodes":{}}""") is ServerMessage.Unknown)
        assertTrue(ServerMessage.parse("""{"type":"node-result","message":"x"}""") is ServerMessage.Unknown)
        val bare = ServerMessage.parse("""{"type":"node-result","ok":false}""") as ServerMessage.NodeResult
        assertNull(bare.requestId)
        assertNull(bare.message)
    }

    // ---- client -> server ----------------------------------------------------

    @Test
    fun recordedNodeClientFramesReEncodeToTheirCanonicalShape() {
        val examples = jsonl(File(corpusDir, "client-examples.jsonl")).map { it["frame"]!!.jsonObject }
        val scenario = scenarioFrames("c2s").map { it.second }
        val frames = (examples + scenario).filter { it.str("type") in setOf("node-add", "node-remove", "node-probe") }
        // 3 client examples + the 3 frames the nodes scenario sent.
        assertEquals(6, frames.size)
        for (frame in frames) {
            val decoded = ClientMessage.decode(frame).getOrThrow()
            assertEquals(WireConformanceTest.canonical(frame), WireConformanceTest.canonical(decoded.toJsonObject()))
        }
    }

    @Test
    fun nodeAddOmitsAbsentOptionalFieldsOnTheWire() {
        assertEquals(
            """{"credential":"parity-credential-bundle","type":"node-add"}""",
            WireConformanceTest.canonical(ClientMessage.NodeAdd("parity-credential-bundle").toJsonObject()),
        )
        assertEquals(
            """{"credential":"parity-credential-bundle","label":"Peer","requestId":"node-add-1","type":"node-add"}""",
            WireConformanceTest.canonical(ClientMessage.NodeAdd("parity-credential-bundle", label = "Peer", requestId = "node-add-1").toJsonObject()),
        )
        assertEquals(
            """{"nodeId":"node-0001","type":"node-probe"}""",
            WireConformanceTest.canonical(ClientMessage.NodeProbe("node-0001").toJsonObject()),
        )
    }

    @Test
    fun aNodeAddNeverPrintsItsCredentialInAnyStringForm() {
        val secret = "parity-fake-n0de-bearer"
        val frame = ClientMessage.NodeAdd(credential = secret, label = "Peer", requestId = "r1")
        assertFalse(frame.toString().contains(secret))
        assertFalse(listOf(frame).toString().contains(secret))
        assertFalse(mapOf("k" to frame).toString().contains(secret))
        // A decode failure names the field, never the value of another one.
        val bad = ClientMessage.decode(
            TetherJson.parseToJsonElement("""{"type":"node-add","credential":"$secret","label":7}""").jsonObject,
        )
        if (bad.isFailure) assertFalse(bad.exceptionOrNull().toString().contains(secret))
        else assertFalse(bad.getOrThrow().toString().contains(secret))
    }

    /** T10.3 r2 (security F5): no generated `copy()` / `componentN()` path out; equality stays exact. */
    @Test
    fun aNodeAddHasNoDataClassPathToItsCredential() {
        val methods = ClientMessage.NodeAdd::class.java.methods.map { it.name }
        assertFalse("copy() exposes the credential", methods.any { it == "copy" || it.startsWith("copy$") })
        assertFalse("componentN() exposes the credential", methods.any { it.startsWith("component") })
        assertFalse("a public getter exposes the credential", methods.any { it == "getCredential" })
        val a = ClientMessage.NodeAdd("parity-fake-a", "Peer", null, "r1")
        assertEquals(a, ClientMessage.NodeAdd("parity-fake-a", "Peer", null, "r1"))
        assertEquals(a.hashCode(), ClientMessage.NodeAdd("parity-fake-a", "Peer", null, "r1").hashCode())
        assertFalse(a == ClientMessage.NodeAdd("parity-fake-b", "Peer", null, "r1"))
        assertFalse(a == ClientMessage.NodeAdd("parity-fake-a", "Peer", null, "r2"))
        // The decoder still reads it field for field.
        val decoded = ClientMessage.decode(
            TetherJson.parseToJsonElement("""{"type":"node-add","credential":"parity-fake-a","label":"Peer","requestId":"r1"}""").jsonObject,
        ).getOrThrow()
        assertEquals(a, decoded)
    }
}
