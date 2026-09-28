package com.tether.app.client

import com.tether.app.protocol.GrantedPermissions
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state these tests snapshot: turn t1 running with
 * - `r-choice`: provider choices `accept` / `decline` (no grant),
 * - `r-plain`: no choices (the Approve / Deny fallback),
 * - `r-grant`: an `exact` and a `subset` choice over requested read `/a`, `/b`, write `/c`, network,
 * - `q1`: one question, "Which DB?".
 * Folded by the real v128 reducer, so every shape is the one the server projects.
 */
internal fun consentStateJson(): String {
    val tree = foldTree(
        freshTree(),
        ev("turn_started", "t1", seq = 1, ts = 1) { put("idempotencyKey", "k1") },
        ev("approval_request", "t1", seq = 2, ts = 2) {
            put("requestId", "r-choice"); put("toolId", "tool-1"); put("name", "command_execution")
            putJsonObject("input") { put("command", "rm -rf build") }
            putJsonArray("choices") {
                addJsonObject { put("choiceId", "accept"); put("label", "Yes") }
                addJsonObject { put("choiceId", "decline"); put("label", "No") }
            }
        },
        ev("approval_request", "t1", seq = 3, ts = 3) {
            put("requestId", "r-plain"); put("toolId", "tool-2"); put("name", "Write")
            putJsonObject("input") { put("file_path", "/w/x") }
        },
        ev("approval_request", "t1", seq = 4, ts = 4) {
            put("requestId", "r-grant"); put("toolId", "tool-3"); put("name", "permissions")
            putJsonArray("choices") {
                addJsonObject { put("choiceId", "all"); put("label", "Allow all"); put("permissionGrant", "exact") }
                addJsonObject { put("choiceId", "some"); put("label", "Allow selected"); put("permissionGrant", "subset") }
                addJsonObject { put("choiceId", "none"); put("label", "Deny") }
            }
            putJsonObject("metadata") {
                put("provider", "codex"); put("kind", "permissions")
                putJsonObject("requestedPermissions") {
                    putJsonObject("fileSystem") {
                        putJsonArray("read") { add("/a"); add("/b") }
                        putJsonArray("write") { add("/c") }
                    }
                    putJsonObject("network") { put("enabled", true) }
                }
            }
        },
        ev("question_request", "t1", seq = 5, ts = 5) {
            put("requestId", "q1"); put("toolId", "ask-1")
            putJsonArray("questions") {
                addJsonObject {
                    put("question", "Which DB?"); put("header", "Database"); put("multiSelect", false)
                    putJsonArray("options") { addJsonObject { put("label", "Postgres"); put("description", "") } }
                }
            }
        },
    )
    return JsCodec.toJson(tree).toString()
}

internal fun eventFrame(sessionId: String, seq: Long, type: String, turnId: String?, fields: String = ""): String =
    """{"type":"event","sessionId":"$sessionId","event":{"type":"$type","turnId":${if (turnId == null) "null" else "\"$turnId\""},"seq":$seq,"ts":$seq$fields}}"""

/**
 * T6.3 security semantics at the one place a decision reaches the wire (RealTetherClient over a
 * MockWebServer socket): I2 nothing received ever produces an `approval` / `question` frame;
 * I3 a decision goes out at most once, only on a live connection, only for a request pending in the
 * current state, only with a choice the request offered; nothing is held for later.
 */
class ConsentTransmissionTest {

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    private fun connected(ready: String = readyFrame()): Pair<RealTetherClient, WebSocket> {
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, ready)
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 5, consentStateJson()))
        h.await(client.liveSessions) { "s1" in it }
        return client to ws
    }

    private fun consentFrames(): List<JsonObject> = h.framesUntilBarrier().filter { it.type() == "approval" || it.type() == "question" }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun aTapSendsOneApprovalBoundToItsRequestAndNeverASecond() {
        val (client, _) = connected()
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-choice", choiceId = "accept"))
        // A double tap, the same card on a second tab, a recomposition: refused, nothing on the wire.
        assertEquals(ConsentResult.AlreadyDecided, client.approval("s1", "r-choice", choiceId = "accept"))
        assertEquals(ConsentResult.AlreadyDecided, client.approval("s1", "r-choice", choiceId = "decline"))
        val frames = consentFrames()
        assertEquals(1, frames.size)
        assertEquals("s1", frames[0].str("sessionId"))
        assertEquals("r-choice", frames[0].str("requestId"))
        assertEquals("accept", frames[0].str("choiceId"))
        assertTrue(consentKey("s1", "r-choice") in client.decidedRequests.value)
    }

    @Test
    fun onlyChoicesTheRequestOfferedGoOut() {
        val (client, _) = connected()
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-choice", choiceId = "yolo"))
        // The Approve / Deny fallback exists only for a request without provider choices.
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-choice", decision = "allow"))
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-plain", decision = "always"))
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-plain", choiceId = "accept"))
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-plain"))
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-plain", choiceId = "a", decision = "allow"))
        assertTrue("a refused decision claims nothing", consentFrames().isEmpty())
        // ...so the operator's real decision still goes out.
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-plain", decision = "deny"))
        assertEquals(listOf("deny"), consentFrames().map { it.str("decision") })
    }

    @Test
    fun aPermissionGrantIsExactlyOrASubsetOfWhatWasRequested() {
        val (client, _) = connected()
        val exact = GrantedPermissions(fileSystemRead = listOf("/a", "/b"), fileSystemWrite = listOf("/c"), networkEnabled = true)
        val wider = GrantedPermissions(fileSystemRead = listOf("/a", "/etc"), networkEnabled = null)
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-grant", choiceId = "all", grantedPermissions = null))
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-grant", choiceId = "all", grantedPermissions = exact.copy(networkEnabled = null)))
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-grant", choiceId = "some", grantedPermissions = wider))
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-grant", choiceId = "some", grantedPermissions = GrantedPermissions(networkEnabled = false)))
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-grant", choiceId = "some", grantedPermissions = null))
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-grant", choiceId = "none", grantedPermissions = exact))
        assertTrue(consentFrames().isEmpty())
        val subset = GrantedPermissions(fileSystemRead = listOf("/b"), networkEnabled = true)
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-grant", choiceId = "some", grantedPermissions = subset))
        val frame = consentFrames().single()
        assertEquals("some", frame.str("choiceId"))
        val granted = frame["grantedPermissions"]!!.jsonObject
        assertEquals("""{"fileSystem":{"read":["/b"]},"network":{"enabled":true}}""", granted.toString())
    }

    @Test
    fun theExactGrantIsTheRequestedObjectAsTheWebSendsIt() {
        val (client, _) = connected()
        val exact = GrantedPermissions(fileSystemRead = listOf("/a", "/b"), fileSystemWrite = listOf("/c"), networkEnabled = true)
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-grant", choiceId = "all", grantedPermissions = exact))
        assertEquals(
            """{"fileSystem":{"read":["/a","/b"],"write":["/c"]},"network":{"enabled":true}}""",
            consentFrames().single()["grantedPermissions"].toString(),
        )
    }

    @Test
    fun aResolvedExpiredOrUnknownRequestIsNotActionable() {
        val (client, ws) = connected()
        assertEquals(ConsentResult.NotPending, client.approval("s1", "r-unknown", choiceId = "accept"))
        ws.send(eventFrame("s1", 6, "approval_expired", "t1", ""","requestId":"r-plain""""))
        ws.send(eventFrame("s1", 7, "approval_resolved", "t1", ""","requestId":"r-choice","choiceId":"decline""""))
        h.serverBarrier(ws)
        assertEquals(ConsentResult.NotPending, client.approval("s1", "r-plain", decision = "allow"))
        assertEquals(ConsentResult.NotPending, client.approval("s1", "r-choice", choiceId = "accept"))
        assertTrue(consentFrames().isEmpty())
    }

    @Test
    fun aQuestionIsAnsweredOnceOnlyWithItsOwnQuestionsAndNeverAfterAnAnswerIsOnRecord() {
        val (client, ws) = connected()
        assertEquals(ConsentResult.InvalidChoice, client.answerQuestion("s1", "q1", mapOf("Something else?" to "x")))
        assertEquals(ConsentResult.Sent, client.answerQuestion("s1", "q1", mapOf("Which DB?" to "Postgres"), "Postgres, please"))
        assertEquals(ConsentResult.AlreadyDecided, client.answerQuestion("s1", "q1", mapOf("Which DB?" to "SQLite")))
        val frame = consentFrames().single()
        assertEquals("q1", frame.str("requestId"))
        assertEquals("""{"answers":{"Which DB?":"Postgres"},"response":"Postgres, please"}""", frame["answers"].toString())

    }

    @Test
    fun aQuestionAnsweredElsewhereCancelledOrResolvedIsClosed() {
        val (client, ws) = connected()
        // Another device answered first: question_answered lands before question_resolved.
        ws.send(eventFrame("s1", 6, "question_answered", "t1", ""","requestId":"q1","toolId":"ask-1","items":[]"""))
        h.serverBarrier(ws)
        assertEquals(ConsentResult.NotPending, client.answerQuestion("s1", "q1", mapOf("Which DB?" to "SQLite")))
        ws.send(eventFrame("s1", 7, "question_resolved", "t1", ""","requestId":"q1""""))
        h.serverBarrier(ws)
        assertEquals(ConsentResult.NotPending, client.answerQuestion("s1", "q1", mapOf("Which DB?" to "SQLite")))
        assertTrue(consentFrames().isEmpty())
    }

    @Test
    fun theCopyHeldFromALostConnectionIsNotActionableUntilTheNewSnapshot() {
        val (client, ws) = connected()
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        assertTrue("nothing is live without a socket", client.liveSessions.value.isEmpty())
        // Disconnected: refused, and nothing is kept to be sent later.
        assertEquals(ConsentResult.NotConnected, client.approval("s1", "r-choice", choiceId = "accept"))

        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2)
        assertEquals("s1", h.expectFrame("attach").str("sessionId"))
        // Connected again, the saved tree still shows r-choice pending: not live yet, refused.
        assertTrue(client.projectionTrees.value.containsKey("s1"))
        assertEquals(ConsentResult.NotLive, client.approval("s1", "r-choice", choiceId = "accept"))
        assertTrue("the refused tap was not held for the new link", consentFrames().isEmpty())

        ws2.send(snapshotFrame("s1", 5, consentStateJson()))
        h.await(client.liveSessions) { "s1" in it }
        assertTrue("the snapshot sends nothing by itself (no replay)", consentFrames().isEmpty())
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-choice", choiceId = "accept"))
        assertEquals(1, consentFrames().size)
    }

    @Test
    fun aDecisionSentBeforeADropIsNeverSentAgainAfterTheReconnect() {
        val (client, ws) = connected()
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-choice", choiceId = "accept"))
        assertEquals(1, consentFrames().size)
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2)
        h.expectFrame("attach")
        // The request is still pending in the new snapshot (the first frame may have been lost).
        ws2.send(snapshotFrame("s1", 5, consentStateJson()))
        h.await(client.liveSessions) { "s1" in it }
        assertTrue(consentKey("s1", "r-choice") in client.decidedRequests.value)
        assertEquals(ConsentResult.AlreadyDecided, client.approval("s1", "r-choice", choiceId = "accept"))
        assertTrue(consentFrames().isEmpty())
    }

    @Test
    fun aGapMakesTheSessionNotLiveUntilItsResyncSnapshot() {
        val (client, ws) = connected()
        // seq 9 after 5: a gap, the cursor asks for a resync.
        ws.send(eventFrame("s1", 9, "tool_start", "t1", ""","toolId":"x","name":"Bash","input":{}"""))
        assertEquals("attach", h.frame().type())
        h.await(client.liveSessions) { "s1" !in it }
        assertEquals(ConsentResult.NotLive, client.approval("s1", "r-choice", choiceId = "accept"))
        ws.send(snapshotFrame("s1", 9, consentStateJson()))
        h.await(client.liveSessions) { "s1" in it }
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-choice", choiceId = "accept"))
    }

    @Test
    fun nothingReceivedEverProducesADecision() {
        val (client, ws) = connected()
        // Fresh requests, the declared-but-unsent server `approval` / `approval_resolved` frames,
        // resolutions: folded or ignored, never answered.
        ws.send(eventFrame("s1", 6, "approval_request", "t1", ""","requestId":"r-new","toolId":"t9","name":"Bash","input":{"command":"ls"}"""))
        ws.send(eventFrame("s1", 7, "question_request", "t1", ""","requestId":"q-new","toolId":"ask-9","questions":[]"""))
        ws.send("""{"type":"approval","sessionId":"s1","requestId":"r-new","toolId":"t9","name":"Bash","input":{}}""")
        ws.send("""{"type":"approval_resolved","sessionId":"s1","requestId":"r-new","choiceId":"accept"}""")
        ws.send(eventFrame("s1", 8, "approval_resolved", "t1", ""","requestId":"r-choice","choiceId":"accept""""))
        h.serverBarrier(ws)
        assertTrue("no frame the client did not tap for", consentFrames().isEmpty())
        assertTrue(client.decidedRequests.value.isEmpty())
    }

    @Test
    fun aReadOnlyOrHandedOffSessionIsLocked() {
        val session = { id: String, extra: String ->
            """{"id":"$id","provider":"claude","name":"n","cwd":"/w","status":"active","startedAt":1,"updatedAt":1,"endedAt":null,
               "exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"$extra}"""
        }
        val ready = """{"type":"ready","protocolVersion":129,"nativeProtocolFloor":129,"sessions":[${session("s1", ""","readOnly":true""")}],
                        "providers":[],"workspaceRoot":null}"""
        val (client, ws) = connected(ready)
        assertEquals(ConsentResult.Locked, client.approval("s1", "r-choice", choiceId = "accept"))
        ws.send("""{"type":"session","session":${session("s1", ""","handedOffTo":"s2"""")}}""")
        h.await(client.sessions) { list -> list.any { it.id == "s1" && it.handedOffTo == "s2" } }
        assertEquals(ConsentResult.Locked, client.answerQuestion("s1", "q1", mapOf("Which DB?" to "Postgres")))
        assertTrue(consentFrames().isEmpty())
        assertFalse(client.decidedRequests.value.contains(consentKey("s1", "q1")))
    }

    @Test
    fun decisionsAreNeverPersisted() {
        val (client, _) = connected()
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-choice", choiceId = "accept"))
        assertEquals(ConsentResult.Sent, client.answerQuestion("s1", "q1", mapOf("Which DB?" to "Postgres")))
        // A prompt IS persisted (T1.3): wait for that write, then read every slot on the "disk".
        client.send("s1", "persist me")
        val slots = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(20_000) {
                var read: List<String>
                do {
                    read = h.settings.pendingInputOrigins().mapNotNull { h.settings.readPendingInput(it) }
                    if (read.none { it.contains("persist me") }) kotlinx.coroutines.delay(20)
                } while (read.none { it.contains("persist me") })
                read
            }
        }
        assertFalse("no approval or answer is written to disk", slots.any { it.contains("r-choice") || it.contains("q1") || it.contains("Postgres") })
    }
}
