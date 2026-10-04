package com.tether.app.client

import com.tether.app.protocol.GrantedPermissions
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsCodec
import kotlinx.coroutines.launch
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
    return """{"type":"ready","protocolVersion":${com.tether.app.protocol.PROTOCOL_VERSION},"nativeProtocolFloor":129,"sessions":[$rows],"providers":[],"workspaceRoot":null}"""
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
        assertEquals(ConsentResult.InvalidChoice, client.answerQuestion("s1", "q1", consentFp(client, "s1", "q1", question = true), listOf(ConsentGuard.QuestionPick(5, listOf(0), ""))))
        assertEquals(ConsentResult.Sent, client.answerQuestion("s1", "q1", consentFp(client, "s1", "q1", question = true), listOf(ConsentGuard.QuestionPick(0, emptyList(), "Postgres, please"))))
        assertEquals(ConsentResult.AlreadyDecided, client.answerQuestion("s1", "q1", consentFp(client, "s1", "q1", question = true), listOf(ConsentGuard.QuestionPick(0, listOf(0), ""))))
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
        assertEquals(ConsentResult.NotPending, client.answerQuestion("s1", "q1", consentFp(client, "s1", "q1", question = true), listOf(ConsentGuard.QuestionPick(0, listOf(0), ""))))
        ws.send(eventFrame("s1", 7, "question_resolved", "t1", ""","requestId":"q1""""))
        h.serverBarrier(ws)
        assertEquals(ConsentResult.NotPending, client.answerQuestion("s1", "q1", consentFp(client, "s1", "q1", question = true), listOf(ConsentGuard.QuestionPick(0, listOf(0), ""))))
        assertTrue(consentFrames().isEmpty())
    }

    @Test
    fun offlineADecisionIsRefusedAndNotHeldButCatchingUpItIsSentAsOnTheWeb() {
        val (client, ws) = connected()
        val errors = java.util.concurrent.LinkedBlockingQueue<String>()
        h.scope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { client.errors.collect { errors.put(it) } }
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        assertTrue("nothing is live without a socket", client.liveSessions.value.isEmpty())
        // Disconnected: refused in the web's words (use-tether.ts 90fbb9f :337-344), nothing kept.
        assertEquals(ConsentResult.NotConnected, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
        assertEquals("The secure link is reconnecting. Your input was not sent.", errors.poll(20, java.util.concurrent.TimeUnit.SECONDS))

        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2, readyWithSessions("s1"))
        assertEquals("s1", h.expectFrame("attach").str("sessionId"))
        // ta-coik.24: connected again, catching up (the saved tree still shows r-choice pending): sent
        // on the open socket, as the web's `send` does; the refused tap above was not held for it.
        assertTrue(client.projectionTrees.value.containsKey("s1"))
        assertFalse("s1" in client.liveSessions.value)
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
        assertEquals(1, consentFrames().size)

        // Still one decision per request: the snapshot landing does not open a second one.
        ws2.send(snapshotFrame("s1", 5, consentStateJson()))
        h.await(client.liveSessions) { "s1" in it }
        assertEquals(ConsentResult.AlreadyDecided, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
        assertTrue(consentFrames().isEmpty())
    }

    @Test
    fun catchingUpAQuestionIsAnsweredAsOnTheWeb() {
        val (client, ws) = connected()
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2, readyWithSessions("s1"))
        h.expectFrame("attach")
        assertFalse("s1" in client.liveSessions.value)
        assertEquals(ConsentResult.Sent, client.answerQuestion("s1", "q1", consentFp(client, "s1", "q1", question = true), listOf(ConsentGuard.QuestionPick(0, listOf(0), ""))))
        assertEquals(listOf("question"), consentFrames().map { it.type() })
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
    fun aGapDoesNotHoldADecisionBackAsOnTheWeb() {
        val (client, ws) = connected()
        // seq 9 after 5: a gap, the cursor asks for a resync.
        ws.send(eventFrame("s1", 9, "tool_start", "t1", ""","toolId":"x","name":"Bash","input":{}"""))
        assertEquals("attach", h.frame().type())
        h.await(client.liveSessions) { "s1" !in it }
        // ta-coik.24: the socket is open, so the decision goes out (use-tether.ts 90fbb9f :337-344).
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
        assertEquals(1, consentFrames().size)
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
        assertEquals(ConsentResult.Locked, client.answerQuestion("s1", "q1", consentFp(client, "s1", "q1", question = true), listOf(ConsentGuard.QuestionPick(0, listOf(0), ""))))
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
        assertEquals(ConsentResult.Sent, client.answerQuestion("s1", "q1", consentFp(client, "s1", "q1", question = true), listOf(ConsentGuard.QuestionPick(0, listOf(0), ""))))
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
        assertEquals(ConsentResult.InvalidChoice, client.answerQuestion("s1", "q1", fp, listOf(ConsentGuard.QuestionPick(0, listOf(7), ""))))
        assertEquals(ConsentResult.InvalidChoice, client.answerQuestion("s1", "q1", fp, listOf(ConsentGuard.QuestionPick(0, listOf(0, 0), ""))))
        assertTrue(consentFrames().isEmpty())
        assertEquals(ConsentResult.Sent, client.answerQuestion("s1", "q1", fp, listOf(ConsentGuard.QuestionPick(0, listOf(0), ""))))
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
    fun theCardIdentitySurvivesADropAndASnapshotThatReplacesTheTree() {
        val (client, ws) = connected()
        fun identity(): Pair<String, Any> {
            val tree = client.projectionTrees.value.getValue("s1")
            val request = ConsentGuard.pendingApproval(tree, "r-grant")!!
            return ConsentGuard.cardIdentity("s1", ConsentGuard.activeTurnId(tree)!!, request) to request
        }
        val (before, requestBefore) = identity()
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2, readyWithSessions("s1"))
        h.expectFrame("attach")
        // A state that differs elsewhere (the server's newer view): the tree is replaced wholesale.
        val newer = consentStateJson().replace("\"lastError\":null", "\"lastError\":\"elsewhere\"")
        assertTrue(newer != consentStateJson())
        ws2.send(snapshotFrame("s1", 5, newer))
        h.await(client.projectionTrees) { (it["s1"]?.get("lastError") as? com.tether.app.protocol.tree.JsStr)?.value == "elsewhere" }
        h.await(client.liveSessions) { "s1" in it }
        val (after, requestAfter) = identity()
        assertTrue("the snapshot replaced the request object", requestBefore !== requestAfter)
        assertEquals(before, after)
        // The WIRE fingerprint also matches (same server): the card's decision still goes out.
        assertEquals(ConsentResult.InvalidChoice, client.approval("s1", "r-grant", consentFp(client, "s1", "r-grant"), choiceId = "all"))
    }

    /** [consentStateJson] with every pending request's `createdAt` stamp removed (a v129/v130 server's snapshot). */
    private fun unstampedStateJson(): String {
        var tree = JsCodec.parse(consentStateJson()) as com.tether.app.protocol.tree.JsObj
        val turns = tree["turnsById"] as com.tether.app.protocol.tree.JsObj
        var t1 = turns["t1"] as com.tether.app.protocol.tree.JsObj
        for (key in listOf("pendingApprovals", "pendingQuestions")) {
            val map = t1[key] as com.tether.app.protocol.tree.JsObj
            var next = map
            for ((id, v) in map) next = next.put(id, (v as com.tether.app.protocol.tree.JsObj).remove("createdAt"))
            t1 = t1.put(key, next)
        }
        tree = tree.put("turnsById", turns.put("t1", t1)).put("lastError", com.tether.app.protocol.tree.JsStr("unstamped"))
        return JsCodec.toJson(tree).toString()
    }

    @Test
    fun aStampedAndAnUnstampedCopyOfOneRequestAreOneDecision() {
        // B2, the verifier's repro: the v132 fold stamps createdAt; a snapshot from an older server in
        // the window (or across an upgrade) carries the same request without it.
        val (client, ws) = connected()
        val stamped = ConsentGuard.pendingApproval(client.projectionTrees.value["s1"], "r-choice")!!
        assertTrue("the v132 fold stamps pending requests", stamped.has("createdAt"))
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
        ws.send(snapshotFrame("s1", 5, unstampedStateJson()))
        h.await(client.projectionTrees) { (it["s1"]?.get("lastError") as? com.tether.app.protocol.tree.JsStr)?.value == "unstamped" }
        assertTrue(!ConsentGuard.pendingApproval(client.projectionTrees.value["s1"], "r-choice")!!.has("createdAt"))
        assertEquals(ConsentResult.AlreadyDecided, client.approval("s1", "r-choice", consentFp(client, "s1", "r-choice"), choiceId = "accept"))
        assertEquals("exactly one decision on the wire", 1, consentFrames().size)
    }

    @Test
    fun aForgedOrStaleFingerprintIsRefused() {
        val (client, _) = connected()
        assertEquals(ConsentResult.NotPending, client.approval("s1", "r-choice", "0".repeat(64), choiceId = "accept"))
        assertEquals(ConsentResult.NotPending, client.answerQuestion("s1", "q1", "", listOf(ConsentGuard.QuestionPick(0, listOf(0), ""))))
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
        // No copy of s9 was taken from it, so there is no request to decide.
        assertEquals(ConsentResult.NotPending, client.approval("s9", "r-choice", "x", choiceId = "accept"))
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
            // The same text again, options reordered plus one: one slot, labels unioned (L1).
            q("DB?", false, "SQLite", "Postgres", "DynamoDB"),
        ),
    )

    private fun pick(slot: Int, vararg idx: Int, other: String = "") = ConsentGuard.QuestionPick(slot, idx.toList(), other)

    @Test fun answersAreBuiltFromIndicesAsTheWebBuildsThem() {
        val slots = ConsentGuard.questionSlots(questionRequest)
        assertEquals(listOf(0, 1, 0), slots.slotOf)
        assertEquals(listOf("Postgres", "SQLite", "DynamoDB"), slots.labels[0])
        // A label is the same label whichever page picked it: index 2 of slot 0 is DynamoDB.
        assertEquals(ConsentGuard.QuestionReply(mapOf("DB?" to "DynamoDB"), null), ConsentGuard.buildAnswers(questionRequest, listOf(pick(0, 2)), emptySet()))
        assertEquals(
            ConsentGuard.QuestionReply(mapOf("DB?" to "SQLite", "Env?" to "production, eu, us, canary"), "canary"),
            ConsentGuard.buildAnswers(questionRequest, listOf(pick(0, 1), pick(1, 1, 2, other = "  canary ")), emptySet()),
        )
        // The web iterates every prompt, a repeated text included: its Other text lands in response twice.
        assertEquals(
            ConsentGuard.QuestionReply(mapOf("DB?" to "Mongo"), "Mongo\nMongo"),
            ConsentGuard.buildAnswers(questionRequest, listOf(pick(0, other = "Mongo")), emptySet()),
        )
        assertEquals(ConsentGuard.QuestionReply(emptyMap(), null), ConsentGuard.buildAnswers(questionRequest, emptyList(), setOf(0, 1)))
        assertEquals(ConsentGuard.QuestionReply(mapOf("Env?" to "staging"), null), ConsentGuard.buildAnswers(questionRequest, listOf(pick(0, 0), pick(1, 0)), setOf(0)))
    }

    @Test fun indicesTheRequestDidNotOfferAreRefused() {
        fun bad(picks: List<ConsentGuard.QuestionPick>, skipped: Set<Int> = emptySet()) =
            assertEquals("$picks / $skipped", null, ConsentGuard.buildAnswers(questionRequest, picks, skipped))
        bad(listOf(pick(0, 3))) // out of the slot's labels
        bad(listOf(pick(0, 0, 1))) // two picks on a single-select slot
        bad(listOf(pick(1, 0, 0))) // a label twice
        bad(listOf(pick(2, 0))) // prompt 2 is not a slot (it shares slot 0)
        bad(listOf(pick(9, 0))) // no such slot
        bad(listOf(pick(0, 0), pick(0, 1))) // a slot twice
        bad(listOf(pick(0, -1)))
        bad(listOf(pick(0, other = "x".repeat(ConsentGuard.MAX_OTHER_CHARS + 1))))
        bad(emptyList(), setOf(2)) // skipping a non-slot
        bad(listOf(pick(0, other = "two\nlines"))) // I-3: an Other text is one line
        bad(listOf(pick(0, other = "cr\r")))
    }

    @Test fun theHashesCoverTheConsentFieldsOnly() {
        val base = request
            .put("choices", com.tether.app.protocol.tree.JsArr.of(com.tether.app.protocol.tree.JsObj.of("choiceId" to com.tether.app.protocol.tree.JsStr("a"), "label" to com.tether.app.protocol.tree.JsStr("A"))))
            .put("metadata", com.tether.app.protocol.tree.JsObj.of("paths" to com.tether.app.protocol.tree.JsArr.of(com.tether.app.protocol.tree.JsStr("/a"))))
        fun both(r: com.tether.app.protocol.tree.JsObj) = ConsentGuard.fingerprint("o", "t1", r) to ConsentGuard.cardIdentity("s1", "t1", r)
        val ref = both(base)
        // Bookkeeping stamps and future additive fields: the same request.
        assertEquals(ref, both(base.put("createdAt", com.tether.app.protocol.tree.JsNum(6.0))))
        assertEquals(ref, both(base.put("createdAt", com.tether.app.protocol.tree.JsNum(7.0)).put("somethingNew", com.tether.app.protocol.tree.JsStr("x"))))
        // What the operator consents to: a different request.
        assertTrue(ref != both(base.put("input", com.tether.app.protocol.tree.JsObj.of("command" to com.tether.app.protocol.tree.JsStr("rm -rf /")))))
        assertTrue(ref != both(base.put("choices", com.tether.app.protocol.tree.JsArr.EMPTY)))
        assertTrue(ref != both(base.put("metadata", com.tether.app.protocol.tree.JsObj.of("paths" to com.tether.app.protocol.tree.JsArr.of(com.tether.app.protocol.tree.JsStr("/b"))))))
        assertTrue(ref != both(base.put("name", com.tether.app.protocol.tree.JsStr("Write"))))
        assertTrue(ref != both(base.put("toolId", com.tether.app.protocol.tree.JsStr("other"))))
        val q = com.tether.app.protocol.tree.JsObj.of("requestId" to com.tether.app.protocol.tree.JsStr("q"), "questions" to com.tether.app.protocol.tree.JsArr.EMPTY)
        assertEquals(both(q), both(q.put("createdAt", com.tether.app.protocol.tree.JsNum(1.0))))
        assertTrue(both(q) != both(q.put("questions", com.tether.app.protocol.tree.JsArr.of(com.tether.app.protocol.tree.JsObj.EMPTY))))
    }

    @Test fun theOtherCutNeverSplitsASurrogatePair() {
        val s = "a".repeat(ConsentGuard.MAX_OTHER_CHARS - 1) + "😀"
        val cut = ConsentGuard.cutCodePoints(s, ConsentGuard.MAX_OTHER_CHARS)
        assertEquals(ConsentGuard.MAX_OTHER_CHARS - 1, cut.length)
        assertEquals("ab", ConsentGuard.cutCodePoints("ab", 4))
        assertEquals("😀", ConsentGuard.cutCodePoints("😀x", 2))
    }

    @Test fun theCardIdentityCarriesTheSessionButNotTheServer() {
        val a = ConsentGuard.cardIdentity("s1", "t1", request)
        assertEquals(a, ConsentGuard.cardIdentity("s1", "t1", request))
        assertTrue(a != ConsentGuard.cardIdentity("s2", "t1", request))
        assertTrue(a != ConsentGuard.cardIdentity("s1", "t2", request))
        assertTrue(a != ConsentGuard.fingerprint("", "t1", request))
    }
}
