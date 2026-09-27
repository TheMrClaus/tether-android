package com.tether.app.client

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1.5 — the v109 node registry as the web keeps it (use-tether.ts `nodes` /
 * `nodeResult`), plus the native requestId correlation of node-add / node-remove
 * / node-probe. MockWebServer "Tether", timers on a [ManualScheduler].
 * All credentials here are obviously fake.
 */
class NodeRegistryTest {

    private val h = ConnectionHarness()
    private val errors = CopyOnWriteArrayList<String>()
    private var errorCollector: Job? = null

    @After
    fun tearDown() {
        errorCollector?.cancel()
        h.close()
    }

    private fun connected(deviceToken: String? = null): WebSocket {
        h.enqueueConnect()
        h.newClient(deviceToken = deviceToken)
        errorCollector = h.scope.launch(start = CoroutineStart.UNDISPATCHED) { h.client.errors.collect { errors += it } }
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        return ws
    }

    private fun node(id: String, status: String = "reachable") =
        """{"nodeId":"$id","label":"$id","baseUrl":"https://$id.example.test","publicKey":"cGs","createdAt":1,
           "lastSeenAt":2,"status":"$status","peerVersion":"0.9.0","peerProtocolVersion":129,"revokedAt":null}"""

    private fun nodesFrame(vararg ids: String) = """{"type":"nodes","nodes":[${ids.joinToString(",") { node(it) }}]}"""

    private fun nodeResult(ok: Boolean, requestId: String?, nodeId: String? = null, message: String? = null) = buildString {
        append("""{"type":"node-result","ok":$ok""")
        if (nodeId != null) append(""","nodeId":"$nodeId"""")
        if (message != null) append(""","message":"$message"""")
        if (requestId != null) append(""","requestId":"$requestId"""")
        append("}")
    }

    private fun <T> request(block: suspend () -> T): Deferred<T> = h.scope.async { block() }

    private fun <T> Deferred<T>.get(): T = runBlocking { withTimeout(10_000) { await() } }

    private fun JsonObject.s(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun awaitErrors(n: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (errors.size < n && System.nanoTime() < deadline) Thread.sleep(5)
    }

    private fun awaitNoPendingRequests() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (h.client.pendingNodeRequestCount() != 0 && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals("a node request leaked", 0, h.client.pendingNodeRequestCount())
    }

    // ------------------------------------------------------------------
    // The registry state
    // ------------------------------------------------------------------

    @Test
    fun everyNodesFrameReplacesTheRegistryWholesale() {
        val ws = connected()
        assertEquals(emptyList<Any>(), h.client.nodes.value)
        ws.send(nodesFrame("a", "b"))
        h.await(h.client.nodes) { it.map { n -> n.nodeId } == listOf("a", "b") }
        ws.send(nodesFrame("b"))
        h.await(h.client.nodes) { it.map { n -> n.nodeId } == listOf("b") }
        assertEquals("https://b.example.test", h.client.nodes.value.single().baseUrl)
        assertEquals(129, h.client.nodes.value.single().peerProtocolVersion)
        ws.send(nodesFrame())
        h.await(h.client.nodes) { it.isEmpty() }
    }

    @Test
    fun aReconnectKeepsTheListUntilTheNewConnectionsListArrives() {
        // The web never clears `nodes` on a drop; the server re-sends it after hello.
        val ws = connected()
        ws.send(nodesFrame("a"))
        h.await(h.client.nodes) { it.size == 1 }
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        assertEquals(listOf("a"), h.client.nodes.value.map { it.nodeId })
        h.scheduler.await(::isReconnectDelay).fire()
        val next = h.nextSocket()
        h.handshake(next)
        assertEquals(listOf("a"), h.client.nodes.value.map { it.nodeId })
        next.send(nodesFrame("c"))
        h.await(h.client.nodes) { it.map { n -> n.nodeId } == listOf("c") }
    }

    @Test
    fun logoutClearsTheRegistryAndTheLastResult() {
        val ws = connected()
        ws.send(nodesFrame("a"))
        ws.send(nodeResult(true, null, "a", "Reachable."))
        h.await(h.client.nodeResult) { it != null }
        h.await(h.client.nodes) { it.size == 1 }
        h.server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("{}")) // POST /api/auth/logout
        runBlocking { h.client.logout() }
        assertEquals(emptyList<Any>(), h.client.nodes.value)
        assertNull(h.client.nodeResult.value)
    }

    @Test
    fun aServerSideSignOutClearsTheRegistry() {
        val ws = connected()
        ws.send(nodesFrame("a"))
        h.await(h.client.nodes) { it.size == 1 }
        ws.close(4001, "device revoked")
        h.await(h.client.connection) { it == ConnectionState.AuthRequired }
        h.await(h.client.nodes) { it.isEmpty() }
    }

    // ------------------------------------------------------------------
    // Requests: requestId correlation
    // ------------------------------------------------------------------

    @Test
    fun addNodeSendsOneFrameWithARequestIdAndResolvesOnItsResult() {
        val ws = connected()
        val call = request { h.client.addNode(NodeCredential("  parity-fake-bundle \n"), label = "  Peer ", baseUrl = "   ") }
        val frame = h.expectFrame("node-add")
        // The web form trims, and the hook omits a blank label/baseUrl.
        assertEquals("parity-fake-bundle", frame.s("credential"))
        assertEquals("Peer", frame.s("label"))
        assertFalse(frame.containsKey("baseUrl"))
        val requestId = frame.s("requestId")!!
        assertTrue(requestId.isNotEmpty() && requestId.length <= 64)
        // Server order: broadcastNodes() first, then the node-result.
        ws.send(nodesFrame("peer-1"))
        ws.send(nodeResult(true, requestId, "peer-1", "Reachable."))
        val outcome = call.get()
        assertTrue("$outcome", outcome is NodeRequestOutcome.Answered)
        val result = (outcome as NodeRequestOutcome.Answered).result
        assertEquals(NodeActionResult(true, "peer-1", "Reachable.", h.now.get()), result)
        assertEquals(result, h.client.nodeResult.value)
        assertEquals(listOf("peer-1"), h.client.nodes.value.map { it.nodeId })
        awaitNoPendingRequests()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun outOfOrderResultsResolveTheirOwnRequests() {
        val ws = connected()
        val probe = request { h.client.probeNode("node-a") }
        val probeId = h.expectFrame("node-probe").also { assertEquals("node-a", it.s("nodeId")) }.s("requestId")!!
        val remove = request { h.client.removeNode("node-b") }
        val removeId = h.expectFrame("node-remove").also { assertEquals("node-b", it.s("nodeId")) }.s("requestId")!!
        assertTrue(probeId != removeId)

        ws.send(nodeResult(true, removeId, "node-b", "Node removed."))
        val removed = remove.get() as NodeRequestOutcome.Answered
        assertEquals("Node removed.", removed.result.message)
        assertFalse(probe.isCompleted)

        ws.send(nodeResult(false, probeId, "node-a", "The node did not respond in time (timed out)."))
        val probed = probe.get() as NodeRequestOutcome.Answered
        assertEquals(false, probed.result.ok)
        assertEquals("node-a", probed.result.nodeId)
        // nodeResult is the LAST node-result, like the web's.
        assertEquals("node-a", h.client.nodeResult.value?.nodeId)
        awaitNoPendingRequests()
    }

    @Test
    fun anUnknownOrMissingRequestIdUpdatesNodeResultButEndsNoRequest() {
        val ws = connected()
        val probe = request { h.client.probeNode("node-a") }
        val probeId = h.expectFrame("node-probe").s("requestId")!!

        ws.send(nodeResult(false, "node-someone-else", "x", "No such node."))
        h.await(h.client.nodeResult) { it?.nodeId == "x" }
        ws.send(nodeResult(true, null, "y", "Done by another request."))
        h.await(h.client.nodeResult) { it?.nodeId == "y" }
        h.serverBarrier(ws)
        assertFalse("a stranger's result must not end this request", probe.isCompleted)
        assertEquals(1, h.client.pendingNodeRequestCount())

        ws.send(nodeResult(true, probeId, "node-a", "Reachable."))
        assertEquals("node-a", (probe.get() as NodeRequestOutcome.Answered).result.nodeId)
        awaitNoPendingRequests()
    }

    @Test
    fun aCorrelatedErrorFrameEndsTheRequestAndIsStillShown() {
        val ws = connected()
        val add = request { h.client.addNode(NodeCredential("parity-fake-bundle")) }
        val id = h.expectFrame("node-add").s("requestId")!!
        ws.send("""{"type":"error","message":"Could not add that node.","requestId":"$id"}""")
        assertEquals(NodeRequestOutcome.ServerError("Could not add that node."), add.get())
        awaitErrors(1)
        assertEquals(listOf("Could not add that node."), errors.toList())
        awaitNoPendingRequests()
    }

    // ------------------------------------------------------------------
    // Timeout, link loss, not connected, cancellation, no retry
    // ------------------------------------------------------------------

    @Test
    fun anUnansweredRequestTimesOutAndALateResultOnlyUpdatesNodeResult() {
        val ws = connected()
        val probe = request { h.client.probeNode("node-a") }
        val id = h.expectFrame("node-probe").s("requestId")!!
        h.scheduler.await { it == NodeRegistryRules.REQUEST_TIMEOUT_MS }.fire()
        assertEquals(NodeRequestOutcome.TimedOut, probe.get())
        awaitNoPendingRequests()
        assertEquals(ConnectionState.Connected, h.client.connection.value)

        ws.send(nodeResult(true, id, "node-a", "Reachable."))
        h.await(h.client.nodeResult) { it?.nodeId == "node-a" }
        // Nothing was retried.
        assertTrue(h.framesUntilBarrier().none { it.type()!!.startsWith("node-") })
    }

    @Test
    fun aDroppedLinkEndsEveryPendingRequestAtOnceAndNothingIsResent() {
        val ws = connected()
        val probe = request { h.client.probeNode("node-a") }
        h.expectFrame("node-probe")
        val add = request { h.client.addNode(NodeCredential("parity-fake-bundle")) }
        h.expectFrame("node-add")
        assertEquals(2, h.client.pendingNodeRequestCount())

        h.enqueueConnect()
        ws.close(1001, null)
        // Well before any timeout fires: the link loss settles both.
        assertEquals(NodeRequestOutcome.LinkLost, probe.get())
        assertEquals(NodeRequestOutcome.LinkLost, add.get())
        awaitNoPendingRequests()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (h.scheduler.pending().any { it.delayMs == NodeRegistryRules.REQUEST_TIMEOUT_MS } && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        assertTrue("timeouts cancelled", h.scheduler.pending().none { it.delayMs == NodeRegistryRules.REQUEST_TIMEOUT_MS })

        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        val next = h.nextSocket()
        h.handshake(next)
        assertTrue("no node frame is ever resent", h.framesUntilBarrier().none { it.type()!!.startsWith("node-") })
    }

    @Test
    fun withoutALiveLinkNothingIsSentAndTheWebsRefusalIsShown() {
        h.newClient(configured = false)
        errorCollector = h.scope.launch(start = CoroutineStart.UNDISPATCHED) { h.client.errors.collect { errors += it } }
        assertEquals(NodeRequestOutcome.NotSent, runBlocking { h.client.probeNode("node-a") })
        assertEquals(NodeRequestOutcome.NotSent, runBlocking { h.client.addNode(NodeCredential("parity-fake-bundle")) })
        awaitErrors(2)
        assertEquals(List(2) { NodeRegistryRules.NOT_SENT_MESSAGE }, errors.toList())
        assertEquals(0, h.client.pendingNodeRequestCount())
        assertTrue(h.scheduler.pending().isEmpty())
    }

    @Test
    fun aCancelledCallerLeavesNoPendingRequest() {
        connected()
        val probe = request { h.client.probeNode("node-a") }
        h.expectFrame("node-probe")
        assertEquals(1, h.client.pendingNodeRequestCount())
        runBlocking { probe.cancel(); probe.join() }
        awaitNoPendingRequests()
        assertTrue(h.scheduler.pending().none { it.delayMs == NodeRegistryRules.REQUEST_TIMEOUT_MS })
    }

    @Test
    fun framesOutsideTheServersBoundsAreRefusedBeforeSending() {
        connected()
        // Empty after trimming: the web keeps its button disabled; nothing shown.
        assertEquals(
            NodeRequestOutcome.Invalid(NodeRegistryRules.EMPTY_CREDENTIAL_MESSAGE),
            runBlocking { h.client.addNode(NodeCredential("   ")) },
        )
        // Server bounds (protocol-validate.mjs): shown, as the server's error would be.
        val longLabel = runBlocking { h.client.addNode(NodeCredential("parity-fake-bundle"), label = "é".repeat(33)) }
        assertEquals(NodeRequestOutcome.Invalid("node-add.label must be a bounded string (<=64 chars)"), longLabel)
        val longCredential = runBlocking { h.client.addNode(NodeCredential("x".repeat(4097))) }
        assertEquals(NodeRequestOutcome.Invalid("node-add.credential must be a bounded non-empty string"), longCredential)
        val badId = runBlocking { h.client.probeNode("n".repeat(129)) }
        assertEquals(NodeRequestOutcome.Invalid("node-probe.nodeId must be a bounded non-empty string"), badId)
        assertEquals(NodeRequestOutcome.Invalid("node-remove.nodeId must be a bounded non-empty string"), runBlocking { h.client.removeNode("") })
        awaitErrors(4)
        assertEquals(4, errors.size)
        assertTrue("nothing went out", h.framesUntilBarrier().isEmpty())
    }

    // ------------------------------------------------------------------
    // Device token (paired device) + credential secrecy
    // ------------------------------------------------------------------

    @Test
    fun aPairedDeviceGetsWhateverTheServerAnswersWithoutCrashing() {
        // server.mjs does not gate the node frames by principal kind today; if it
        // ever refuses a device token it answers with a node-result or an error
        // frame, and either one reaches the caller as a plain outcome.
        val ws = connected(deviceToken = "parity-fake-device-token")
        val add = request { h.client.addNode(NodeCredential("parity-fake-bundle")) }
        val addId = h.expectFrame("node-add").s("requestId")!!
        ws.send(nodeResult(false, addId, message = "Manage nodes from a browser session, not from a paired device."))
        val refused = add.get() as NodeRequestOutcome.Answered
        assertFalse(refused.result.ok)
        assertEquals("Manage nodes from a browser session, not from a paired device.", refused.result.message)

        val probe = request { h.client.probeNode("node-a") }
        val probeId = h.expectFrame("node-probe").s("requestId")!!
        ws.send("""{"type":"error","message":"Forbidden.","requestId":"$probeId"}""")
        assertEquals(NodeRequestOutcome.ServerError("Forbidden."), probe.get())
        assertEquals(ConnectionState.Connected, h.client.connection.value)
        awaitNoPendingRequests()
    }

    @Test
    fun theCredentialAppearsOnTheWireOnceAndNowhereElse() {
        val secret = "parity-FAKE-node-bearer-7f3a"
        val out = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        val capture = PrintStream(out, true)
        val outcomes = mutableListOf<NodeRequestOutcome>()
        val credential = NodeCredential(secret)
        try {
            System.setOut(capture)
            System.setErr(capture)
            val ws = connected()
            // Answered (ok and not ok), a correlated error, a timeout, a link loss:
            // every path the credential could leak through.
            val a1 = request { h.client.addNode(credential, label = "Peer") }
            val f1 = h.expectFrame("node-add")
            ws.send(nodeResult(false, f1.s("requestId"), message = "That credential could not be read. Re-copy it from the peer."))
            outcomes += a1.get()
            val a2 = request { h.client.addNode(credential) }
            val f2 = h.expectFrame("node-add")
            ws.send("""{"type":"error","message":"Could not add that node.","requestId":"${f2.s("requestId")}"}""")
            outcomes += a2.get()
            val a3 = request { h.client.addNode(credential) }
            val f3 = h.expectFrame("node-add")
            h.scheduler.await { it == NodeRegistryRules.REQUEST_TIMEOUT_MS }.fire()
            outcomes += a3.get()
            val a4 = request { h.client.addNode(credential) }
            val f4 = h.expectFrame("node-add")
            ws.close(1001, null)
            outcomes += a4.get()
            // Printing the objects a developer might print.
            println(credential)
            println(outcomes)
            println(h.client.nodeResult.value)
            println(h.client.nodes.value)
            // The durable send queue never saw it.
            assertFalse((runBlocking { h.settings.readPendingInput() } ?: "").contains(secret))
            // Exactly what went out: the four node-add frames carried it, verbatim.
            assertEquals(List(4) { secret }, listOf(f1, f2, f3, f4).map { it.s("credential") })
            assertEquals(
                listOf(
                    NodeRequestOutcome.Answered::class,
                    NodeRequestOutcome.ServerError::class,
                    NodeRequestOutcome.TimedOut::class,
                    NodeRequestOutcome.LinkLost::class,
                ),
                outcomes.map { it::class },
            )
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        assertEquals("NodeCredential(***)", credential.toString())
        assertFalse("stdout/stderr", out.toString().contains(secret))
        assertFalse("outcomes", outcomes.toString().contains(secret))
        assertFalse("errors flow", errors.any { it.contains(secret) })
        assertFalse("nodeResult", h.client.nodeResult.value.toString().contains(secret))
        assertTrue(out.toString().contains("NodeCredential(***)"))
    }
}
