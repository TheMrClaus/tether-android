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

/** A `ready` that lists [ids] (a decision is refused for an unlisted session, L2). */
internal fun readyWithSessions(vararg ids: String, extra: String = ""): String {
    val rows = ids.joinToString(",") { id ->
        """{"id":"$id","provider":"claude","name":"n","cwd":"/w","status":"active","startedAt":1,"updatedAt":1,"endedAt":null,""" +
            """"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"$extra}"""
    }
    return """{"type":"ready","protocolVersion":129,"nativeProtocolFloor":129,"sessions":[$rows],"providers":[],"workspaceRoot":null}"""
}

/** The fingerprint a card would render for [requestId] now ("" when it is not pending). */
internal fun consentFp(client: TetherClient, sessionId: String, requestId: String, question: Boolean = false): String {
    val tree = client.projectionTrees.value[sessionId]
    val origin = client.consentOrigin.value ?: return ""
    val turnId = ConsentGuard.activeTurnId(tree) ?: return ""
    val request = (if (question) ConsentGuard.pendingQuestion(tree, requestId) else ConsentGuard.pendingApproval(tree, requestId)) ?: return ""
    return ConsentGuard.fingerprint(origin, turnId, request)
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

    private fun connected(ready: String = readyWithSessions("s1")): Pair<RealTetherClient, WebSocket> {
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
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
        // A double tap, the same card on a second tab, a recomposition: refused, nothing on the wire.
        assertEquals(ConsentResult.AlreadyDecided, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
        assertEquals(ConsentResult.AlreadyDecided, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "decline"))
        val frames = consentFrames()
        assertEquals(1, frames.size)
        assertEquals("s1", frames[0].str("sessionId"))
        assertEquals("r-choice", frames[0].str("requestId"))
        assertEquals("accept", frames[0].str("choiceId"))
        assertTrue(consentKey("s1", "r-choice", consentFp(client, "s1", "r-choice")) in client.decidedRequests.value)
    }

    @Test
    fun onlyChoicesTheRequestOfferedGoOut() {
        val (client, _) = connected()
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "yolo"))
        // The Approve / Deny fallback exists only for a request without provider choices.
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), decision = "allow"))
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-plain", consentFp(client, "s1", "r-plain"), decision = "always"))
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-plain", consentFp(client, "s1", "r-plain"), choiceId = "accept"))
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-plain", consentFp(client, "s1", "r-plain")))
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-plain", consentFp(client, "s1", "r-plain"), choiceId = "a", decision = "allow"))
        assertTrue("a refused decision claims nothing", consentFrames().isEmpty())
        // ...so the operator's real decision still goes out.
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-plain", consentFp(client, "s1", "r-plain"), decision = "deny"))
        assertEquals(listOf("deny"), consentFrames().map { it.str("decision") })
    }

    @Test
    fun aPermissionGrantIsExactlyOrASubsetOfWhatWasRequested() {
        val (client, _) = connected()
        val exact = GrantedPermissions(fileSystemRead = listOf("/a", "/b"), fileSystemWrite = listOf("/c"), networkEnabled = true)
        val wider = GrantedPermissions(fileSystemRead = listOf("/a", "/etc"), networkEnabled = null)
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-grant", consentFp(client, "s1", "r-grant"), choiceId = "all", grantedPermissions = null))
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-grant", consentFp(client, "s1", "r-grant"), choiceId = "all", grantedPermissions = exact.copy(networkEnabled = null)))
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-grant", consentFp(client, "s1", "r-grant"), choiceId = "some", grantedPermissions = wider))
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-grant", consentFp(client, "s1", "r-grant"), choiceId = "some", grantedPermissions = GrantedPermissions(networkEnabled = false)))
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-grant", consentFp(client, "s1", "r-grant"), choiceId = "some", grantedPermissions = null))
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-grant", consentFp(client, "s1", "r-grant"), choiceId = "none", grantedPermissions = exact))
        assertTrue(consentFrames().isEmpty())
        val subset = GrantedPermissions(fileSystemRead = listOf("/b"), networkEnabled = true)
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-grant", consentFp(client, "s1", "r-grant"), choiceId = "some", grantedPermissions = subset))
        val frame = consentFrames().single()
        assertEquals("some", frame.str("choiceId"))
        val granted = frame["grantedPermissions"]!!.jsonObject
        assertEquals("""{"fileSystem":{"read":["/b"]},"network":{"enabled":true}}""", granted.toString())
    }

    @Test
    fun theExactGrantIsTheRequestedObjectAsTheWebSendsIt() {
        val (client, _) = connected()
        val exact = GrantedPermissions(fileSystemRead = listOf("/a", "/b"), fileSystemWrite = listOf("/c"), networkEnabled = true)
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-grant", consentFp(client, "s1", "r-grant"), choiceId = "all", grantedPermissions = exact))
        assertEquals(
            """{"fileSystem":{"read":["/a","/b"],"write":["/c"]},"network":{"enabled":true}}""",
            consentFrames().single()["grantedPermissions"].toString(),
        )
    }

    @Test
    fun aResolvedExpiredOrUnknownRequestIsNotActionable() {
        val (client, ws) = connected()
        assertEquals(ConsentResult.NotPending, client.approval("s1", "r-unknown", consentFp(client, "s1", "r-unknown"), choiceId = "accept"))
        ws.send(eventFrame("s1", 6, "approval_expired", "t1", ""","requestId":"r-plain""""))
        ws.send(eventFrame("s1", 7, "approval_resolved", "t1", ""","requestId":"r-choice","choiceId":"decline""""))
        h.serverBarrier(ws)
        assertEquals(ConsentResult.NotPending, client.approval("s1", "r-plain", consentFp(client, "s1", "r-plain"), decision = "allow"))
        assertEquals(ConsentResult.NotPending, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
        assertTrue(consentFrames().isEmpty())
    }

    @Test
    fun aQuestionIsAnsweredOnceOnlyWithItsOwnQuestionsAndNeverAfterAnAnswerIsOnRecord() {
        val (client, ws) = connected()
        assertEquals(ConsentResult.InvalidChoice, client.answerQuestion("s1", "q1", consentFp(client, "s1", "q1", question = true), mapOf("Something else?" to "x")))
        assertEquals(ConsentResult.Sent, client.answerQuestion("s1", "q1", consentFp(client, "s1", "q1", question = true), mapOf("Which DB?" to "Postgres, please"), "Postgres, please"))
        assertEquals(ConsentResult.AlreadyDecided, client.answerQuestion("s1", "q1", consentFp(client, "s1", "q1", question = true), mapOf("Which DB?" to "SQLite")))
        val frame = consentFrames().single()
        assertEquals("q1", frame.str("requestId"))
        assertEquals("""{"answers":{"Which DB?":"Postgres, please"},"response":"Postgres, please"}""", frame["answers"].toString())

    }

    @Test
    fun aQuestionAnsweredElsewhereCancelledOrResolvedIsClosed() {
        val (client, ws) = connected()
        // Another device answered first: question_answered lands before question_resolved.
        ws.send(eventFrame("s1", 6, "question_answered", "t1", ""","requestId":"q1","toolId":"ask-1","items":[]"""))
        h.serverBarrier(ws)
        assertEquals(ConsentResult.NotPending, client.answerQuestion("s1", "q1", consentFp(client, "s1", "q1", question = true), mapOf("Which DB?" to "SQLite")))
        ws.send(eventFrame("s1", 7, "question_resolved", "t1", ""","requestId":"q1""""))
        h.serverBarrier(ws)
        assertEquals(ConsentResult.NotPending, client.answerQuestion("s1", "q1", consentFp(client, "s1", "q1", question = true), mapOf("Which DB?" to "SQLite")))
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
        assertEquals(ConsentResult.NotConnected, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))

        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2, readyWithSessions("s1"))
        assertEquals("s1", h.expectFrame("attach").str("sessionId"))
        // Connected again, the saved tree still shows r-choice pending: not live yet, refused.
        assertTrue(client.projectionTrees.value.containsKey("s1"))
        assertEquals(ConsentResult.NotLive, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
        assertTrue("the refused tap was not held for the new link", consentFrames().isEmpty())

        ws2.send(snapshotFrame("s1", 5, consentStateJson()))
        h.await(client.liveSessions) { "s1" in it }
        assertTrue("the snapshot sends nothing by itself (no replay)", consentFrames().isEmpty())
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
        assertEquals(1, consentFrames().size)
    }

    @Test
    fun aDecisionSentBeforeADropIsNeverSentAgainAfterTheReconnect() {
        val (client, ws) = connected()
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
        assertEquals(1, consentFrames().size)
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2, readyWithSessions("s1"))
        h.expectFrame("attach")
        // The request is still pending in the new snapshot (the first frame may have been lost).
        ws2.send(snapshotFrame("s1", 5, consentStateJson()))
        h.await(client.liveSessions) { "s1" in it }
        assertTrue(consentKey("s1", "r-choice", consentFp(client, "s1", "r-choice")) in client.decidedRequests.value)
        assertEquals(ConsentResult.AlreadyDecided, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
        assertTrue(consentFrames().isEmpty())
    }

    @Test
    fun aGapMakesTheSessionNotLiveUntilItsResyncSnapshot() {
        val (client, ws) = connected()
        // seq 9 after 5: a gap, the cursor asks for a resync.
        ws.send(eventFrame("s1", 9, "tool_start", "t1", ""","toolId":"x","name":"Bash","input":{}"""))
        assertEquals("attach", h.frame().type())
        h.await(client.liveSessions) { "s1" !in it }
        assertEquals(ConsentResult.NotLive, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
        ws.send(snapshotFrame("s1", 9, consentStateJson()))
        h.await(client.liveSessions) { "s1" in it }
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
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
    fun aReadOnlyHandedOffOrUnlistedSessionIsLocked() {
        val (client, ws) = connected(readyWithSessions("s1", extra = ""","readOnly":true"""))
        assertEquals(ConsentResult.Locked, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
        ws.send("""{"type":"session","session":{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"active","startedAt":1,"updatedAt":1,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless","handedOffTo":"s2"}}""")
        h.await(client.sessions) { list -> list.any { it.id == "s1" && it.handedOffTo == "s2" } }
        assertEquals(ConsentResult.Locked, client.answerQuestion("s1", "q1", consentFp(client, "s1", "q1", question = true), mapOf("Which DB?" to "Postgres")))
        assertTrue(consentFrames().isEmpty())
        assertTrue(client.decidedRequests.value.isEmpty())
    }

    @Test
    fun anUnlistedSessionFailsClosed() {
        // L2: a live, pending request whose session the server never listed is refused.
        val (client, _) = connected(readyFrame())
        assertEquals(ConsentResult.Locked, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
        assertTrue(consentFrames().isEmpty())
    }

    @Test
    fun decisionsAreNeverPersisted() {
        val (client, _) = connected()
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
        assertEquals(ConsentResult.Sent, client.answerQuestion("s1", "q1", consentFp(client, "s1", "q1", question = true), mapOf("Which DB?" to "Postgres")))
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

    // ---- round 2: fingerprint binding, wire form, liveness, ledger ------------------------------

    @Test
    fun aReRaisedRequestIsANewDecisionAndTheStaleRenderIsRefused() {
        val (client, ws) = connected()
        val first = consentFp(client, "s1", "r-choice")
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-choice", first, choiceId = "accept"))
        assertEquals(1, consentFrames().size)
        // The engine re-raises the SAME id with a wider request (the reducer overwrites the entry).
        ws.send(eventFrame("s1", 6, "approval_request", "t1", ""","requestId":"r-choice","toolId":"tool-1","name":"command_execution","input":{"command":"rm -rf /"},"choices":[{"choiceId":"accept","label":"Yes"}]"""))
        h.serverBarrier(ws)
        val second = consentFp(client, "s1", "r-choice")
        assertTrue(first != second)
        // A tap on what was rendered before the re-raise is not a decision on what is pending now.
        assertEquals(ConsentResult.NotPending, client.approval("s1", "r-choice", first, choiceId = "accept"))
        assertTrue(consentFrames().isEmpty())
        // The new request is decidable, once.
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-choice", second, choiceId = "accept"))
        assertEquals(ConsentResult.AlreadyDecided, client.approval("s1", "r-choice", second, choiceId = "accept"))
        assertEquals(1, consentFrames().size)
        assertTrue(consentKey("s1", "r-choice", first) in client.decidedRequests.value)
        assertTrue(consentKey("s1", "r-choice", second) in client.decidedRequests.value)
    }

    @Test
    fun anAnswerValueTheRequestDidNotOfferIsRefused() {
        val (client, _) = connected()
        val fp = consentFp(client, "s1", "q1", question = true)
        assertEquals(ConsentResult.InvalidChoice, client.answerQuestion("s1", "q1", fp, mapOf("Which DB?" to "DROP TABLE users")))
        assertEquals(ConsentResult.InvalidChoice, client.answerQuestion("s1", "q1", fp, mapOf("Which DB?" to "Postgres"), "extra instructions"))
        assertTrue(consentFrames().isEmpty())
        assertEquals(ConsentResult.Sent, client.answerQuestion("s1", "q1", fp, mapOf("Which DB?" to "Postgres")))
    }

    @Test
    fun logoutClearsTheLiveAndDecidedStateAndNothingCanBeSent() {
        val (client, _) = connected()
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
        assertTrue(client.decidedRequests.value.isNotEmpty())
        h.server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("{}"))
        kotlinx.coroutines.runBlocking { client.logout() }
        assertTrue(client.liveSessions.value.isEmpty())
        assertEquals(null, client.consentOrigin.value)
        assertTrue(client.decidedRequests.value.isEmpty())
        assertTrue(client.unconfirmedRequests.value.isEmpty())
        assertEquals(ConsentResult.NotConnected, client.approval("s1", "r-plain", "x", decision = "allow"))
    }

    @Test
    fun aForgedOrStaleFingerprintIsRefused() {
        val (client, _) = connected()
        assertEquals(ConsentResult.NotPending, client.approval("s1", "r-choice", "0".repeat(64), choiceId = "accept"))
        assertEquals(ConsentResult.NotPending, client.answerQuestion("s1", "q1", "", mapOf("Which DB?" to "Postgres")))
        assertTrue(consentFrames().isEmpty())
    }

    @Test
    fun aGrantThatDoesNotSurviveTheWireIsRefused() {
        val (client, _) = connected()
        // L1: paths with hasFileSystem = false would encode as {} (a different grant from the one checked).
        val lying = GrantedPermissions(fileSystemRead = listOf("/b"), hasFileSystem = false)
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-grant", consentFp(client, "s1", "r-grant"), choiceId = "some", grantedPermissions = lying))
        val lyingExact = GrantedPermissions(fileSystemRead = listOf("/a", "/b"), fileSystemWrite = listOf("/c"), hasFileSystem = false, networkEnabled = true)
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-grant", consentFp(client, "s1", "r-grant"), choiceId = "all", grantedPermissions = lyingExact))
        assertTrue(consentFrames().isEmpty())
    }

    @Test
    fun aSnapshotNobodyAttachedDoesNotMakeASessionLive() {
        val (client, ws) = connected(readyWithSessions("s1", "s9"))
        // SYNC_DESIGN §4.1: s9 was never attached on this socket.
        ws.send(snapshotFrame("s9", 5, consentStateJson().replace("\"s1\"", "\"s9\"")))
        ws.send(snapshotFrame("s9", 5, state = null))
        h.serverBarrier(ws)
        assertFalse("s9" in client.liveSessions.value)
        assertEquals(ConsentResult.NotLive, client.approval("s9", "r-choice", "x", choiceId = "accept"))
        assertTrue(consentFrames().isEmpty())
    }

    @Test
    fun aDecisionFromAnEarlierSocketIsReportedUnconfirmedAndNeverResent() {
        val (client, ws) = connected()
        val fp = consentFp(client, "s1", "r-choice")
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-choice", fp, choiceId = "accept"))
        assertTrue(client.unconfirmedRequests.value.isEmpty())
        consentFrames()
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2, readyWithSessions("s1"))
        h.expectFrame("attach")
        ws2.send(snapshotFrame("s1", 5, consentStateJson()))
        h.await(client.liveSessions) { "s1" in it }
        assertTrue(consentKey("s1", "r-choice", fp) in client.unconfirmedRequests.value)
        assertEquals(ConsentResult.AlreadyDecided, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
        assertTrue(consentFrames().isEmpty())
    }
}

/** T6.3 round 2: the fingerprint's canonical form and the ledger's eviction rule, directly. */
class ConsentGuardUnitTest {
    private val request = com.tether.app.protocol.tree.JsObj.of(
        "requestId" to com.tether.app.protocol.tree.JsStr("r1"),
        "name" to com.tether.app.protocol.tree.JsStr("Bash"),
        "input" to com.tether.app.protocol.tree.JsObj.of("command" to com.tether.app.protocol.tree.JsStr("ls"), "cwd" to com.tether.app.protocol.tree.JsNum(1.0)),
    )

    @Test fun theFingerprintIsSha256OfTheCanonicalJson() {
        val canonical = """{"activeTurnId":"t1","origin":"http://h:1","request":{"input":{"command":"ls","cwd":1},"name":"Bash","requestId":"r1"}}"""
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, ConsentGuard.fingerprint("http://h:1", "t1", request))
    }

    @Test fun keyOrderDoesNotMatterButContentTurnAndServerDo() {
        val reordered = com.tether.app.protocol.tree.JsObj.of(
            "input" to com.tether.app.protocol.tree.JsObj.of("cwd" to com.tether.app.protocol.tree.JsNum(1.0), "command" to com.tether.app.protocol.tree.JsStr("ls")),
            "name" to com.tether.app.protocol.tree.JsStr("Bash"),
            "requestId" to com.tether.app.protocol.tree.JsStr("r1"),
        )
        val base = ConsentGuard.fingerprint("o", "t1", request)
        assertEquals(base, ConsentGuard.fingerprint("o", "t1", reordered))
        assertTrue(base != ConsentGuard.fingerprint("o2", "t1", request))
        assertTrue(base != ConsentGuard.fingerprint("o", "t2", request))
        assertTrue(base != ConsentGuard.fingerprint("o", "t1", request.put("name", com.tether.app.protocol.tree.JsStr("Write"))))
    }

    @Test fun evictionOnlyTakesRequestsNoLongerPending() {
        val ledger = ConsentLedger(capacity = 2)
        fun e(id: String) = ConsentLedger.Entry("o", "s", "t", id, "fp-$id", 1)
        val pending = mutableSetOf("a", "b", "c")
        val still: (ConsentLedger.Entry) -> Boolean = { it.requestId in pending }
        assertTrue(ledger.claim(e("a"), still))
        assertTrue(ledger.claim(e("b"), still))
        assertTrue(ledger.claim(e("c"), still))
        assertEquals("nothing evicted while every claim may still be pending", 3, ledger.size)
        pending.remove("b")
        assertTrue(ledger.claim(e("d"), still))
        assertTrue("a still pending: kept", ledger.contains(e("a")))
        assertFalse("b resolved: evicted", ledger.contains(e("b")))
        assertFalse("a second claim of a kept request is refused", ledger.claim(e("a"), still))
    }

    private fun q(text: String, multi: Boolean, vararg labels: String) = com.tether.app.protocol.tree.JsObj.of(
        "question" to com.tether.app.protocol.tree.JsStr(text),
        "header" to com.tether.app.protocol.tree.JsStr("H"),
        "multiSelect" to com.tether.app.protocol.tree.JsBool.of(multi),
        "options" to com.tether.app.protocol.tree.JsArr.of(labels.map { com.tether.app.protocol.tree.JsObj.of("label" to com.tether.app.protocol.tree.JsStr(it)) }),
    )

    private val questionRequest = com.tether.app.protocol.tree.JsObj.of(
        "requestId" to com.tether.app.protocol.tree.JsStr("q"),
        "questions" to com.tether.app.protocol.tree.JsArr.of(
            q("DB?", false, "Postgres", "SQLite"),
            q("Env?", true, "staging", "production", "eu, us"),
        ),
    )

    private fun ok(answers: Map<String, String>, response: String? = null) =
        assertEquals("$answers / $response", null, ConsentGuard.checkQuestion(questionRequest, answers, response))

    private fun bad(answers: Map<String, String>, response: String? = null) =
        assertEquals("$answers / $response", ConsentResult.InvalidChoice, ConsentGuard.checkQuestion(questionRequest, answers, response))

    @Test fun answerValuesAreOfferedLabelsOrTheOperatorsOwnOtherText() {
        ok(emptyMap())
        ok(mapOf("DB?" to "Postgres"))
        ok(mapOf("Env?" to "production, staging"))
        ok(mapOf("Env?" to "eu, us, staging")) // a label that itself contains ", "
        ok(mapOf("DB?" to "Mongo"), "Mongo") // "Other" text, echoed in response
        ok(mapOf("DB?" to "SQLite, with WAL", "Env?" to "staging, canary"), "with WAL\ncanary")
        bad(mapOf("DB?" to "Mongo")) // free text that is not in response
        bad(mapOf("DB?" to "Postgres, SQLite")) // two picks on a single-select question
        bad(mapOf("Env?" to "staging, staging")) // a label twice
        bad(mapOf("Env?" to "prod")) // not offered, not Other
        bad(mapOf("DB?" to "")) // an empty answer (the web leaves the key out)
        bad(mapOf("DB?" to "Postgres"), "smuggled") // response lines nobody typed into an answer
        bad(mapOf("DB?" to "Mongo", "Env?" to "x"), "x\nMongo") // Other texts out of question order
        bad(mapOf("Nope?" to "Postgres"))
    }
}
