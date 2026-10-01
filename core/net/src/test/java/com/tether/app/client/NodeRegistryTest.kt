package com.tether.app.client

import com.tether.app.protocol.TetherJson
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
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
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    /** T6.7: every toast, the client's words and the server's (the server's arrive on serverErrors). */
    private suspend fun collectToasts(): Nothing = kotlinx.coroutines.coroutineScope {
        launch(start = CoroutineStart.UNDISPATCHED) { h.client.serverErrors.collect { errors += it.text } }
        h.client.errors.collect { errors += it }
    }

    private fun connected(deviceToken: String? = null): WebSocket {
        h.enqueueConnect()
        h.newClient(deviceToken = deviceToken)
        errorCollector = h.scope.launch(start = CoroutineStart.UNDISPATCHED) { collectToasts() }
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

    /** T10.3: the harness server's origin, the one every node request here is drawn for. */
    private fun origin(): String = serverOrigin(h.server.url("/").toString())!!

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
    fun stopClearsTheRegistryAndTheLastResult() {
        val ws = connected()
        ws.send(nodesFrame("a"))
        ws.send(nodeResult(true, null, "a", "Reachable."))
        h.await(h.client.nodeResult) { it != null }
        h.await(h.client.nodes) { it.size == 1 }
        h.client.stop()
        assertEquals(emptyList<Any>(), h.client.nodes.value)
        assertNull(h.client.nodeResult.value)
    }

    /** A second "Tether" to sign in to: healthz, password login, auth probe, upgrade. */
    private class OtherServer : AutoCloseable {
        val server = MockWebServer().apply { start() }
        val sockets = LinkedBlockingQueue<WebSocket>()
        val received = LinkedBlockingQueue<String>()
        private val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                sockets.put(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                received.put(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }

        fun url(): String = server.url("/").toString().trimEnd('/')

        fun enqueueLoginAndConnect(cookie: String, probeDelayMs: Long = 0) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(HEALTH_137))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("{}")
                    .addHeader("Set-Cookie", "tether_session=$cookie; Path=/; HttpOnly"),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"authenticated":true}""")
                    .setHeadersDelay(probeDelayMs, TimeUnit.MILLISECONDS),
            )
            server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        }

        fun frame(): JsonObject {
            val text = received.poll(10, TimeUnit.SECONDS)
            assertNotNull("expected a frame at the other server", text)
            return TetherJson.parseToJsonElement(text!!) as JsonObject
        }

        override fun close() {
            while (true) {
                val ws = sockets.poll() ?: break
                runCatching { ws.close(1000, null) }
            }
            server.shutdown()
        }
    }

    private fun createdFrame(sessionId: String) =
        """{"type":"created","session":{"id":"$sessionId","provider":"claude","name":"x","cwd":"/w","status":"ready",
           "startedAt":1,"updatedAt":1,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"}}"""

    @Test
    fun signingInToAnotherServerDropsTheOldSocketAndItsRegistry() {
        // Server A answers the client's close by FIRST sending more frames and
        // then holding its close reply: those frames reach the client while its
        // close handshake is still open, i.e. before the socket is gone on its own.
        val lateSent = java.util.concurrent.CountDownLatch(1)
        val holdingA = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                h.sockets.put(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                h.received.put(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                h.serverCloses.put(code)
                webSocket.send(nodesFrame("late-from-a"))
                webSocket.send(createdFrame("late-session-from-a"))
                lateSent.countDown()
            }
        }
        h.server.enqueue(MockResponse().setResponseCode(200).setBody("""{"authenticated":true}"""))
        h.server.enqueue(MockResponse().withWebSocketUpgrade(holdingA))
        h.newClient()
        h.client.start()
        val a = h.nextSocket()
        try {
            h.handshake(a)
            a.send(nodesFrame("from-a"))
            h.await(h.client.nodes) { it.map { n -> n.nodeId } == listOf("from-a") }
            val aRequests = h.server.requestCount
            OtherServer().use { b ->
                b.enqueueLoginAndConnect("parity-fake-cookie-b")
                assertEquals(LoginResult.Success, runBlocking { h.client.login(b.url(), "parity-fake-password") })
                // Right after the new sign-in: nothing of A's registry is shown.
                assertEquals(emptyList<Any>(), h.client.nodes.value)
                assertNull(h.client.nodeResult.value)
                // A's socket was closed by the client (a clean close, as on logout)...
                assertEquals(1000, h.serverCloses.poll(10, TimeUnit.SECONDS))
                // ...and what A sent after that, before its close reply, is dropped.
                assertTrue(lateSent.await(10, TimeUnit.SECONDS))
                val settle = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
                while (System.nanoTime() < settle) {
                    assertEquals("a frame from A landed", emptyList<Any>(), h.client.nodes.value)
                    assertTrue("a frame from A landed", h.client.sessions.value.none { it.id == "late-session-from-a" })
                    Thread.sleep(10)
                }
                assertSwitchedToB(b)
                assertTrue("A received a node frame", h.received.none { it.contains("\"node-") })
                assertTrue("a frame from A landed", h.client.sessions.value.none { it.id == "late-session-from-a" })
                // A saw nothing more after the switch: no probe, no upgrade, with any credential.
                assertEquals(aRequests, h.server.requestCount)
            }
        } finally {
            runCatching { a.close(1000, null) }
        }
    }

    /** B is the live server: its registry shows and the next node request (with its credential) goes to B. */
    private fun assertSwitchedToB(b: OtherServer) {
        run {
            val bws = b.sockets.poll(10, TimeUnit.SECONDS)
            assertNotNull("the client never connected to the new server", bws)
            bws!!.send(readyFrame())
            assertEquals("hello", b.frame().type())
            h.await(h.client.connection) { it == ConnectionState.Connected }
            bws.send(nodesFrame("from-b"))
            h.await(h.client.nodes) { it.map { n -> n.nodeId } == listOf("from-b") }
            h.serverBarrier(bws)
            assertEquals(listOf("from-b"), h.client.nodes.value.map { it.nodeId })

            // The next node request (and its credential) goes to B, never to A.
            val add = request { h.client.addNode(serverOrigin(b.url())!!, NodeCredential("parity-fake-bundle")) }
            val frame = b.frame()
            assertEquals("node-add", frame.type())
            assertEquals("parity-fake-bundle", frame.s("credential"))
            bws.send(nodeResult(true, frame.s("requestId"), "peer-b", "Reachable."))
            assertTrue(add.get() is NodeRequestOutcome.Answered)
        }
    }

    @Test
    fun aFailedProbeOfTheOldSignInDoesNothingAfterTheSwitch() {
        // A's probe FAILS (HTTP 500) after 1.5 s, while B's probe (3 s) is in
        // flight: that failure is not this connection's, so no Disconnected
        // handling, no reconnect timer, no release of B's connect slot.
        h.server.enqueue(MockResponse().setResponseCode(500).setBody("{}").setHeadersDelay(1_500, TimeUnit.MILLISECONDS))
        h.newClient()
        h.client.start()
        assertNotNull("A's auth probe", h.server.takeRequest(10, TimeUnit.SECONDS))
        OtherServer().use { b ->
            b.enqueueLoginAndConnect("parity-fake-cookie-b", probeDelayMs = 3_000)
            assertEquals(LoginResult.Success, runBlocking { h.client.login(b.url(), "parity-fake-password") })
            val bws = b.sockets.poll(15, TimeUnit.SECONDS)
            assertNotNull("the client never connected to the new server", bws)
            bws!!.send(readyFrame())
            assertEquals("hello", b.frame().type())
            h.await(h.client.connection) { it == ConnectionState.Connected }
            assertTrue(
                "A's stale failure scheduled a reconnect: ${h.scheduler.history().map { it.delayMs }}",
                h.scheduler.history().none { isReconnectDelay(it.delayMs) },
            )
            assertEquals(1, h.server.requestCount)
        }
    }

    /** Delegates to [inner]; clear() waits for [release] (the async clear stop() launches). */
    private class GatedClearSettings(private val inner: InMemorySettings) : SettingsStore by inner {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        override suspend fun clear() {
            release.await()
            inner.clear()
        }
    }

    @Test
    fun aProbeThatReturnsAfterStopNeverConnectsTheSignedOutClient() =
        aProbeThatReturnsAfterSignOutNeverConnects(deviceToken = null) {
            h.client.stop()
            h.await(h.client.configured) { !it }
        }

    @Test
    fun aProbeThatReturnsAfterLogoutNeverConnectsTheSignedOutClient() =
        // A device token: logout() makes no server call, so the queue stays probe + upgrade.
        aProbeThatReturnsAfterSignOutNeverConnects(deviceToken = "parity-fake-device-token") {
            assertEquals(LogoutResult.LocalOnly, runBlocking { h.client.logout() })
            h.await(h.client.configured) { !it }
        }

    /**
     * stop()/logout() -> start() finds no credential -> AuthRequired. start()
     * resets `stopped`, so only the attempt generation keeps the ended attempt's
     * late "authenticated" from opening a socket with the credential that was
     * just signed out.
     */
    private fun aProbeThatReturnsAfterSignOutNeverConnects(deviceToken: String?, signOut: () -> Unit) {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                h.sockets.put(webSocket)
            }
        }
        h.server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"authenticated":true}""")
                .setHeadersDelay(1_500, TimeUnit.MILLISECONDS),
        )
        h.server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        h.newClient(deviceToken = deviceToken)
        h.client.start()
        assertNotNull("the probe is out", h.server.takeRequest(10, TimeUnit.SECONDS))
        signOut()
        h.client.start()
        h.await(h.client.connection) { it == ConnectionState.AuthRequired }
        Thread.sleep(2_000) // past the stopped attempt's probe (1.5 s)
        assertEquals("no upgrade after sign-out", 1, h.server.requestCount)
        assertTrue(h.sockets.isEmpty())
        assertEquals(ConnectionState.AuthRequired, h.client.connection.value)
    }

    @Test
    fun stopThenASignInWhileAProbeIsInFlightStillConnects() {
        // The stopped attempt's probe is still out when the next attempt starts:
        // it must not keep the connect slot (no self-lockout). The next attempt is
        // a sign-in: since ta-jt9 L-B1 a start() right after stop() never re-adopts
        // what the store still holds before stop()'s async clear lands (that would
        // undo the sign-out), so only a new login connects again. The sign-in waits
        // for that clear (L-1), so the clear never deletes the new credential.
        // (The slot through stop() alone: aProbeThatReturnsAfterStopNeverConnects...)
        val probes = java.util.concurrent.atomic.AtomicInteger()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                h.sockets.put(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                h.received.put(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }
        h.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                "/api/auth/session" -> MockResponse().setResponseCode(200).setBody("""{"authenticated":true}""")
                    .setHeadersDelay(if (probes.getAndIncrement() == 0) 1_500L else 0L, TimeUnit.MILLISECONDS)
                "/ws" -> MockResponse().withWebSocketUpgrade(listener)
                "/healthz" -> MockResponse().setResponseCode(200).setBody(HEALTH_137)
                "/api/auth/login" -> MockResponse().setResponseCode(200)
                    .addHeader("Set-Cookie", "tether_session=parity-fake-cookie-2; Path=/").setBody("{}")
                else -> MockResponse().setResponseCode(404)
            }
        }
        h.server.start()
        val inner = InMemorySettings(initialBaseUrl = h.server.url("/").toString().trimEnd('/'), initialCookie = "parity-fake-cookie")
        val gated = GatedClearSettings(inner)
        h.settings = inner
        h.client = RealTetherClient(
            settings = gated,
            httpClient = okhttp3.OkHttpClient(),
            scope = h.scope,
            clock = { h.now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = h.scheduler,
        )
        try {
            h.client.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (probes.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals("the first probe is out", 1, probes.get())
            h.client.stop()
            // stop()'s clear lands while the sign-in is already waiting for it.
            Thread { Thread.sleep(200); gated.release.complete(Unit) }.start()
            assertEquals(LoginResult.Success, runBlocking { h.client.login(h.server.url("/").toString(), "pw") })
            assertEquals(
                "stop()'s late clear kept the new sign-in's credential",
                Credential.Cookie("parity-fake-cookie-2"),
                runBlocking { inner.session().credential },
            )
            // Connected through a fresh attempt, well before the stopped one's
            // probe (1.5 s) returns; and that stale probe changes nothing after.
            val ws = h.sockets.poll(10, TimeUnit.SECONDS)
            assertNotNull("self-lockout: the client never connected after stop() + start()", ws)
            h.handshake(ws!!)
            Thread.sleep(1_700)
            assertEquals(ConnectionState.Connected, h.client.connection.value)
            assertEquals(2, probes.get())
            assertTrue("one socket only", h.sockets.isEmpty())
            assertEquals(Credential.Cookie("parity-fake-cookie-2"), runBlocking { inner.session().credential })
            h.await(h.client.configured) { it }
        } finally {
            gated.release.complete(Unit)
        }
    }

    @Test
    fun anAuthProbeOfTheOldSignInThatReturnsLateNeverOpensItsSocket() {
        // A's probe answers after 1.5 s; the sign-in to B lands meanwhile, and
        // B's own probe (3 s) is still in flight when A's verdict comes back.
        h.enqueueConnect(probeDelayMs = 1_500)
        h.newClient()
        h.client.start()
        assertNotNull("A's auth probe", h.server.takeRequest(10, TimeUnit.SECONDS))
        OtherServer().use { b ->
            b.enqueueLoginAndConnect("parity-fake-cookie-b", probeDelayMs = 3_000)
            assertEquals(LoginResult.Success, runBlocking { h.client.login(b.url(), "parity-fake-password") })
            // B connects (A's stale attempt does not hold the connect slot)...
            val bws = b.sockets.poll(15, TimeUnit.SECONDS)
            assertNotNull("the client never connected to the new server", bws)
            bws!!.send(readyFrame())
            assertEquals("hello", b.frame().type())
            h.await(h.client.connection) { it == ConnectionState.Connected }
            // ...and A's late "authenticated" verdict never opened a socket to A
            // with A's credential: A saw its probe and nothing else.
            assertEquals(1, h.server.requestCount)
            assertTrue(h.sockets.isEmpty())
        }
    }

    /**
     * OkHttp refuses a send once it has started closing, while the client still
     * holds the socket as live (the close handshake is not finished). One frame
     * over OkHttp's 16 MiB queue cap starts that close (RealWebSocket.send); the
     * server here never answers the close, so the window stays open.
     */
    @Test
    fun aSendOkHttpRefusesIsNotSentNotLinkLost() {
        val silent = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                h.sockets.put(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                h.received.put(text)
            }
            // onClosing deliberately unanswered.
        }
        h.server.enqueue(MockResponse().setResponseCode(200).setBody("""{"authenticated":true}"""))
        h.server.enqueue(MockResponse().withWebSocketUpgrade(silent))
        h.newClient()
        errorCollector = h.scope.launch(start = CoroutineStart.UNDISPATCHED) { collectToasts() }
        h.client.start()
        val serverSide = h.nextSocket()
        try {
            h.handshake(serverSide)

            assertFalse("OkHttp refuses the oversized frame", h.client.markSeen("x".repeat(17 * 1024 * 1024), 1L))
            assertEquals(ConnectionState.Connected, h.client.connection.value)

            assertEquals(NodeRequestOutcome.NotSent, runBlocking { h.client.probeNode(origin(), "node-a") })
            awaitErrors(1)
            assertEquals(listOf(NodeRegistryRules.NOT_SENT_MESSAGE), errors.toList())
            awaitNoPendingRequests()
            assertTrue(h.scheduler.pending().none { it.delayMs == NodeRegistryRules.REQUEST_TIMEOUT_MS })
        } finally {
            // Answer the close now, so the server can shut down.
            runCatching { serverSide.close(1000, null) }
        }
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
        val call = request { h.client.addNode(origin(), NodeCredential("  parity-fake-bundle \n"), label = "  Peer ", baseUrl = "   ") }
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
        val probe = request { h.client.probeNode(origin(), "node-a") }
        val probeId = h.expectFrame("node-probe").also { assertEquals("node-a", it.s("nodeId")) }.s("requestId")!!
        val remove = request { h.client.removeNode(origin(), "node-b") }
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
        val probe = request { h.client.probeNode(origin(), "node-a") }
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
        val add = request { h.client.addNode(origin(), NodeCredential("parity-fake-bundle")) }
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
        val probe = request { h.client.probeNode(origin(), "node-a") }
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
        val probe = request { h.client.probeNode(origin(), "node-a") }
        h.expectFrame("node-probe")
        val add = request { h.client.addNode(origin(), NodeCredential("parity-fake-bundle")) }
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
        errorCollector = h.scope.launch(start = CoroutineStart.UNDISPATCHED) { collectToasts() }
        assertEquals(NodeRequestOutcome.NotSent, runBlocking { h.client.probeNode(origin(), "node-a") })
        assertEquals(NodeRequestOutcome.NotSent, runBlocking { h.client.addNode(origin(), NodeCredential("parity-fake-bundle")) })
        awaitErrors(2)
        assertEquals(List(2) { NodeRegistryRules.NOT_SENT_MESSAGE }, errors.toList())
        assertEquals(0, h.client.pendingNodeRequestCount())
        assertTrue(h.scheduler.pending().isEmpty())
    }

    @Test
    fun aCancelledCallerLeavesNoPendingRequest() {
        connected()
        val probe = request { h.client.probeNode(origin(), "node-a") }
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
            runBlocking { h.client.addNode(origin(), NodeCredential("   ")) },
        )
        // Server bounds (protocol-validate.mjs): shown, as the server's error would be.
        val longLabel = runBlocking { h.client.addNode(origin(), NodeCredential("parity-fake-bundle"), label = "é".repeat(33)) }
        assertEquals(NodeRequestOutcome.Invalid("node-add.label must be a bounded string (<=64 chars)"), longLabel)
        val longCredential = runBlocking { h.client.addNode(origin(), NodeCredential("x".repeat(4097))) }
        assertEquals(NodeRequestOutcome.Invalid("node-add.credential must be a bounded non-empty string"), longCredential)
        val badId = runBlocking { h.client.probeNode(origin(), "n".repeat(129)) }
        assertEquals(NodeRequestOutcome.Invalid("node-probe.nodeId must be a bounded non-empty string"), badId)
        assertEquals(NodeRequestOutcome.Invalid("node-remove.nodeId must be a bounded non-empty string"), runBlocking { h.client.removeNode(origin(), "") })
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
        val add = request { h.client.addNode(origin(), NodeCredential("parity-fake-bundle")) }
        val addId = h.expectFrame("node-add").s("requestId")!!
        ws.send(nodeResult(false, addId, message = "Manage nodes from a browser session, not from a paired device."))
        val refused = add.get() as NodeRequestOutcome.Answered
        assertFalse(refused.result.ok)
        assertEquals("Manage nodes from a browser session, not from a paired device.", refused.result.message)

        val probe = request { h.client.probeNode(origin(), "node-a") }
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
            val a1 = request { h.client.addNode(origin(), credential, label = "Peer") }
            val f1 = h.expectFrame("node-add")
            ws.send(nodeResult(false, f1.s("requestId"), message = "That credential could not be read. Re-copy it from the peer."))
            outcomes += a1.get()
            val a2 = request { h.client.addNode(origin(), credential) }
            val f2 = h.expectFrame("node-add")
            ws.send("""{"type":"error","message":"Could not add that node.","requestId":"${f2.s("requestId")}"}""")
            outcomes += a2.get()
            val a3 = request { h.client.addNode(origin(), credential) }
            val f3 = h.expectFrame("node-add")
            h.scheduler.await { it == NodeRegistryRules.REQUEST_TIMEOUT_MS }.fire()
            outcomes += a3.get()
            val a4 = request { h.client.addNode(origin(), credential) }
            val f4 = h.expectFrame("node-add")
            ws.close(1001, null)
            outcomes += a4.get()
            // Printing the objects a developer might print.
            println(credential)
            println(outcomes)
            println(h.client.nodeResult.value)
            println(h.client.nodes.value)
            // The durable send queue never saw it.
            val origin = serverOrigin(h.server.url("/").toString())!!
            assertFalse((runBlocking { h.settings.readPendingInput(origin) } ?: "").contains(secret))
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

    // ------------------------------------------------------------------
    // T10.3: bound to the drawing origin; exact frames; a later socket never answers
    // ------------------------------------------------------------------

    @Test
    fun aRequestDrawnForAnotherServerIsNeverSent() {
        val ws = connected()
        val other = "https://other-console.example.test"
        // Bounded (get(): 10 s): a frame that did go out would wait for an answer, and fail here rather than hang.
        assertEquals(NodeRequestOutcome.NotSent, request { h.client.addNode(other, NodeCredential("parity-FAKE-bundle-for-b")) }.get())
        assertEquals(NodeRequestOutcome.NotSent, request { h.client.probeNode(other, "node-a") }.get())
        assertEquals(NodeRequestOutcome.NotSent, request { h.client.removeNode(other, "node-a") }.get())
        h.serverBarrier(ws)
        assertTrue("a frame drawn for another server went out", h.framesUntilBarrier().none { it.type()!!.startsWith("node-") })
        assertEquals(0, h.client.pendingNodeRequestCount())
        // Control: the same request for THIS server goes out.
        val probe = request { h.client.probeNode(origin(), "node-a") }
        val id = h.expectFrame("node-probe").s("requestId")!!
        ws.send(nodeResult(true, id, "node-a", "Reachable."))
        assertTrue(probe.get() is NodeRequestOutcome.Answered)
    }

    @Test
    fun theNodeFramesAreExactlyTheWebsJson() {
        val ws = connected()
        val add = request { h.client.addNode(origin(), NodeCredential(" parity-FAKE-bundle "), label = " Workstation ", baseUrl = " http://10.0.0.2:4173 ") }
        val addFrame = h.expectFrame("node-add")
        val addId = addFrame.s("requestId")!!
        assertEquals(
            TetherJson.parseToJsonElement(
                """{"type":"node-add","credential":"parity-FAKE-bundle","label":"Workstation","baseUrl":"http://10.0.0.2:4173","requestId":"$addId"}""",
            ),
            addFrame,
        )
        ws.send(nodeResult(true, addId, "node-w", "Reachable."))
        add.get()
        // Blank label and base URL are omitted, as use-tether.ts addNode does.
        val bare = request { h.client.addNode(origin(), NodeCredential("parity-FAKE-bundle"), label = "  ", baseUrl = "") }
        val bareFrame = h.expectFrame("node-add")
        val bareId = bareFrame.s("requestId")!!
        assertEquals(TetherJson.parseToJsonElement("""{"type":"node-add","credential":"parity-FAKE-bundle","requestId":"$bareId"}"""), bareFrame)
        ws.send(nodeResult(false, bareId, message = "That credential could not be read. Re-copy it from the peer."))
        bare.get()
        val probe = request { h.client.probeNode(origin(), "node-w") }
        val probeFrame = h.expectFrame("node-probe")
        val probeId = probeFrame.s("requestId")!!
        assertEquals(TetherJson.parseToJsonElement("""{"type":"node-probe","nodeId":"node-w","requestId":"$probeId"}"""), probeFrame)
        ws.send(nodeResult(true, probeId, "node-w", "Reachable."))
        probe.get()
        val remove = request { h.client.removeNode(origin(), "node-w") }
        val removeFrame = h.expectFrame("node-remove")
        val removeId = removeFrame.s("requestId")!!
        assertEquals(TetherJson.parseToJsonElement("""{"type":"node-remove","nodeId":"node-w","requestId":"$removeId"}"""), removeFrame)
        ws.send(nodeResult(true, removeId, "node-w", "Node removed."))
        remove.get()
        awaitNoPendingRequests()
    }

    @Test
    fun aResultArrivingOnALaterSocketNeverAnswersAnEarlierRequest() {
        val ws = connected()
        val first = request { h.client.probeNode(origin(), "node-a") }
        val oldId = h.expectFrame("node-probe").s("requestId")!!
        h.enqueueConnect()
        ws.close(1001, null)
        assertEquals(NodeRequestOutcome.LinkLost, first.get())
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        val next = h.nextSocket()
        h.handshake(next)
        // A new request on the new socket; the old request's id answered there ends nothing.
        val second = request { h.client.probeNode(origin(), "node-a") }
        val newId = h.expectFrame("node-probe").s("requestId")!!
        assertTrue(newId != oldId)
        next.send(nodeResult(true, oldId, "node-a", "Reachable."))
        h.serverBarrier(next)
        assertFalse("the earlier request's id answered the new one", second.isCompleted)
        assertEquals(NodeRequestOutcome.LinkLost, first.get())
        next.send(nodeResult(false, newId, "node-a", "The node did not respond in time (timed out)."))
        val answered = second.get() as NodeRequestOutcome.Answered
        assertEquals("The node did not respond in time (timed out).", answered.result.message)
        awaitNoPendingRequests()
    }

    @Test
    fun theConsolesProtocolVersionIsPublishedAndDroppedWithTheRegistry() {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        assertNull(h.client.serverProtocolVersion.value)
        h.handshake(ws, readyFrame(protocolVersion = 140))
        h.await(h.client.serverProtocolVersion) { it == 140 }
        h.server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) // POST /api/auth/logout
        runBlocking { h.client.logout() }
        h.await(h.client.serverProtocolVersion) { it == null }
    }
}
