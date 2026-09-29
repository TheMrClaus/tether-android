package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.TetherJson
import com.tether.app.protocol.model.HistorySession
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T5.2: `resume` against the vendored wire corpus (parity-corpus/wire), and RealTetherClient's
 * half of the web's reopen: the frame use-tether.ts resumeHistory sends, the unicast `created`
 * reply the dashboard follows, and the refusal that surfaces as an error toast.
 */
class ResumeTest {

    private val wire = File(System.getProperty("parity.corpus") ?: "../../parity-corpus", "wire")

    private fun jsonl(name: String): List<JsonObject> =
        File(wire, name).readLines().filter { it.isNotBlank() }.map { TetherJson.parseToJsonElement(it).jsonObject }

    private fun example(type: String): JsonObject {
        val line = jsonl("client-examples.jsonl").single { it["type"]!!.jsonPrimitive.content == type }
        assertEquals("true", line["verdict"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
        return line["frame"]!!.jsonObject
    }

    private fun recorded(file: String, dir: String): List<JsonObject> =
        jsonl(file).filter { it["dir"]?.jsonPrimitive?.content == dir }.map { it["frame"]!!.jsonObject }

    private fun history(id: String, cwd: String, profileId: String? = null) =
        HistorySession(historyId = id, provider = "claude", name = "n", cwd = cwd, updatedAt = 1, profileId = profileId)

    // ---- client -> server: re-encoded against the recorded valid examples --------------------

    @Test fun resumeReEncodesAsTheCorpusExample() {
        val recorded = example("resume")
        val encoded = ClientMessage.Resume(recorded["historyId"]!!.jsonPrimitive.content, recorded["cwd"]!!.jsonPrimitive.content)
        assertEquals(recorded, encoded.toJsonObject())
        // Byte for byte, key order included (lib/protocol.ts:3393).
        assertEquals(recorded.toString(), encoded.encode())
    }

    @Test fun theRecordedScenarioResumeReEncodes() {
        val sent = recorded("resume.jsonl", "c2s").single()
        assertEquals(sent.toString(), ClientMessage.Resume(sent["historyId"]!!.jsonPrimitive.content, sent["cwd"]!!.jsonPrimitive.content).encode())
    }

    @Test fun aProfileRidesAfterTheCwd() {
        // use-tether.ts:1494 `{type, historyId, cwd, ...(profileId ? {profileId} : {})}`.
        assertEquals(
            """{"type":"resume","historyId":"h","cwd":"/w","profileId":"work"}""",
            ClientMessage.Resume("h", "/w", "work").encode(),
        )
    }

    @Test fun historiesCarryTheV89ProfileId() {
        val frame = """{"type":"histories","cwd":"/w","sessions":[""" +
            """{"historyId":"a","provider":"claude","name":"n","cwd":"/w","updatedAt":2,"profileId":"work"},""" +
            """{"historyId":"b","provider":"codex","name":"m","cwd":"/w","updatedAt":1}]}"""
        val parsed = ServerMessage.parse(TetherJson.parseToJsonElement(frame).jsonObject) as ServerMessage.Histories
        assertEquals(listOf("work", null), parsed.sessions.map { it.profileId })
    }

    // ---- RealTetherClient over a socket ------------------------------------------------------

    private val h = ConnectionHarness()

    @After fun tearDown() = h.close()

    private fun connected(): okhttp3.WebSocket {
        h.newClient()
        h.enqueueConnect()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        return ws
    }

    @Test fun resumeSendsTheProfileOnlyWhenTheHistoryNamesOne() {
        connected()
        assertTrue(h.client.resume(history("h1", "/w/app", profileId = "work")))
        assertEquals(TetherJson.parseToJsonElement("""{"type":"resume","historyId":"h1","cwd":"/w/app","profileId":"work"}"""), h.expectFrame("resume"))
        assertTrue(h.client.resume(history("h2", "/w", profileId = "")))
        assertEquals(TetherJson.parseToJsonElement("""{"type":"resume","historyId":"h2","cwd":"/w"}"""), h.expectFrame("resume"))
        assertTrue(h.client.resume(history("h3", "/w")))
        assertEquals(TetherJson.parseToJsonElement("""{"type":"resume","historyId":"h3","cwd":"/w"}"""), h.expectFrame("resume"))
    }

    @Test fun theCreatedReplyIsPublishedWithAMonotonicSeq() {
        val ws = connected()
        assertNull(h.client.createdSessions.value)
        val created = jsonl("commands.jsonl").map { it["frame"]!!.jsonObject }.first { it["type"]!!.jsonPrimitive.content == "created" }
        ws.send(created.toString())
        val first = h.await(h.client.createdSessions) { it != null }!!
        assertEquals("sess-0008", first.session.id)
        assertEquals("create-commands", first.requestId)
        assertTrue("the session joins the list first", h.client.sessions.value.any { it.id == "sess-0008" })
        // A resume dedup-hit answers with the SAME session again: still a fresh reply.
        ws.send(created.toString().replace(""","requestId":"create-commands"""", ""))
        val second = h.await(h.client.createdSessions) { it != null && it.seq > first.seq }!!
        assertEquals("sess-0008", second.session.id)
        assertNull(second.requestId)
    }

    @Test fun aResumeRefusalIsAnErrorToast() {
        val ws = connected()
        val refusal = recorded("resume.jsonl", "s2c").single()
        assertEquals("error", refusal["type"]!!.jsonPrimitive.content)
        runBlocking {
            // T6.7: the server's words are a SERVER toast (serverErrors), cleaned and attributed.
            val toast = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { withTimeout(20_000) { h.client.serverErrors.first() } }
            ws.send(refusal.toString())
            assertEquals("That saved session is no longer available.", toast.await())
        }
        assertNull("a refusal is not a created reply", h.client.createdSessions.value)
    }

    @Test fun nothingIsSentWithoutAHandshakenSocket() {
        h.newClient(configured = false)
        assertFalse(h.client.resume(history("h", "/w")))
    }
}
