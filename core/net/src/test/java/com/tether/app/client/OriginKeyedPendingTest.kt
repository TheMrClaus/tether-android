package com.tether.app.client

import com.tether.app.protocol.Attachment
import com.tether.app.protocol.TetherJson
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-s8q: unsent input belongs to the server ORIGIN it was written for. A
 * sign-in to another origin never replays it there (no send, no queue-add, no
 * attach of its sessions, whatever that server answers); it is set aside for
 * its own origin and comes back, under the T1.3 rules, when the user signs in
 * there again. A re-login to the SAME origin keeps the T1.3 guarantees exactly.
 * Two MockWebServer "Tethers" on different ports = two origins. All
 * credentials here are obviously fake.
 */
class OriginKeyedPendingTest {

    /** One fake Tether: every frame any of its sockets received, every request path. */
    private class FakeTether : AutoCloseable {
        val server = MockWebServer()
        val sockets = LinkedBlockingQueue<WebSocket>()
        val serverSockets = CopyOnWriteArrayList<WebSocket>()
        val received = LinkedBlockingQueue<String>()
        val allFrames = CopyOnWriteArrayList<String>()
        val paths = CopyOnWriteArrayList<String>()
        val probes = AtomicInteger()

        @Volatile var down = false

        /** Replaces the next auth probe answers, in order (e.g. a delay or a verdict). */
        val probeAnswers = ConcurrentLinkedQueue<MockResponse>()

        private val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                serverSockets += webSocket
                sockets.put(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                allFrames += text
                received.put(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    paths += request.path.orEmpty()
                    return when (request.path) {
                        "/healthz" -> MockResponse().setResponseCode(200).setBody(HEALTH_129)
                        "/api/auth/login" -> MockResponse().setResponseCode(200).setBody("{}")
                            .addHeader("Set-Cookie", "tether_session=parity-fake-cookie-${server.port}; Path=/; HttpOnly")
                        "/api/auth/session" -> {
                            probes.incrementAndGet()
                            probeAnswers.poll() ?: if (down) {
                                MockResponse().setResponseCode(503).setBody("{}")
                            } else {
                                MockResponse().setResponseCode(200).setBody("""{"authenticated":true}""")
                            }
                        }
                        "/ws" -> if (down) MockResponse().setResponseCode(503) else MockResponse().withWebSocketUpgrade(listener)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
        }

        fun url(): String = server.url("/").toString().trimEnd('/')

        fun origin(): String = serverOrigin(url())!!

        fun nextSocket(): WebSocket {
            val ws = sockets.poll(15, TimeUnit.SECONDS)
            assertNotNull("the client never reached this server's ws upgrade", ws)
            return ws!!
        }

        fun frame(): JsonObject {
            val text = received.poll(10, TimeUnit.SECONDS)
            assertNotNull("expected a client frame at this server", text)
            return TetherJson.parseToJsonElement(text!!) as JsonObject
        }

        override fun close() {
            serverSockets.forEach { runCatching { it.close(1000, null) } }
            server.shutdown()
        }
    }

    private val a = FakeTether()
    private val b = FakeTether()
    private val now = AtomicLong(1_000_000)
    private val errors = CopyOnWriteArrayList<String>()
    private val scopes = mutableListOf<CoroutineScope>()
    private lateinit var disk: DiskSettings
    private lateinit var client: RealTetherClient

    @After
    fun tearDown() {
        if (::client.isInitialized) runCatching { client.stop() }
        scopes.forEach { it.cancel() }
        a.close()
        b.close()
    }

    /** A process over [settings]; the errors (notices) it emits are collected from the start. */
    private fun process(settings: SettingsStore): RealTetherClient {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        client = RealTetherClient(
            settings = settings,
            httpClient = OkHttpClient(),
            scope = scope,
            clock = { now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = ManualScheduler(),
        )
        scope.launch(start = CoroutineStart.UNDISPATCHED) { client.errors.collect { errors += it } }
        return client
    }

    /** Configured for A (cookie), on a per-origin disk whose home slot is A's. */
    private fun processOnA(wrap: (DiskSettings) -> SettingsStore = { it }): RealTetherClient {
        disk = DiskSettings(InMemorySettings(a.url(), initialCookie = "parity-fake-cookie-a"), a.url())
        return process(wrap(disk))
    }

    /** Delegates to [inner]; clear() waits for [release] (the async clear stop() launches). */
    private class GatedClear(private val inner: SettingsStore) : SettingsStore by inner {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        override suspend fun clear() {
            release.await()
            inner.clear()
        }
    }

    private fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(10_000) { flow.first(predicate) } }

    private fun handshake(server: FakeTether, ws: WebSocket) {
        ws.send(readyFrame())
        assertEquals("hello", server.frame().type())
        await(client.connection) { it == ConnectionState.Connected }
    }

    /** Everything the client sent [server] before a barrier frame (client-side wire order). */
    private fun framesUntilBarrier(server: FakeTether): List<JsonObject> {
        client.pin("barrier-${System.nanoTime()}", true)
        val out = mutableListOf<JsonObject>()
        while (true) {
            val f = server.frame()
            if (f.type() == "pin" && f["sessionId"]!!.jsonPrimitive.content.startsWith("barrier-")) return out
            out += f
        }
    }

    /** Every frame [ws] sent before this one has been handled by the client. */
    private fun serverBarrier(ws: WebSocket) {
        val id = "barrier-${System.nanoTime()}"
        ws.send(
            """{"type":"created","session":{"id":"$id","provider":"claude","name":"b","cwd":"/w","status":"ready",
               "startedAt":1,"updatedAt":1,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"}}""",
        )
        await(client.sessions) { list -> list.any { it.id == id } }
    }

    private fun turnState(sessionId: String, vararg keys: String): String {
        val byId = keys.withIndex().joinToString(",") { (i, key) ->
            """"t$i":{"turnId":"t$i","status":"completed","idempotencyKey":"$key","blocks":[],"blocksById":{}}"""
        }
        val order = keys.indices.joinToString(",") { "\"t$it\"" }
        return """{"tetherSessionId":"$sessionId","provider":"claude","cwd":"/w","status":"ready",
                   "turnOrder":[$order],"turnsById":{$byId},"activeTurnId":null,"queuedMessages":[]}"""
    }

    private fun JsonObject.s(field: String): String? = this[field]?.jsonPrimitive?.content

    private fun awaitErrors(predicate: (List<String>) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate(errors.toList()) && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("notices: $errors", predicate(errors.toList()))
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(message, condition())
    }

    private fun displayHost(server: FakeTether) = "${server.server.hostName}:${server.server.port}"

    private val picture = Attachment("pic.png", "image/png", "iVBORw0KGgo=")

    /** What [writeOnA] left: the key A saw go out once (tries 1), and A's socket. */
    private class OnA(val triedKey: String, val ws: WebSocket)

    /**
     * Connected to A, s-a attached and snapshotted; one turn sent (tries 1,
     * never acked); then A goes away. While A is unreachable the user types a
     * turn with an attachment and a queued message (tries 0, never sent).
     */
    private fun writeOnA(wrap: (DiskSettings) -> SettingsStore = { it }): OnA {
        val c = processOnA(wrap)
        c.start()
        val ws = a.nextSocket()
        handshake(a, ws)
        c.attach("s-a")
        assertEquals("attach", a.frame().type())
        ws.send(snapshotFrame("s-a", 1, turnState("s-a")))
        await(c.projections) { it.containsKey("s-a") }
        c.send("s-a", "tried on A: private prompt one")
        val tried = a.frame().also { assertEquals("send", it.type()) }.s("idempotencyKey")!!

        a.down = true
        ws.close(1001, null)
        await(c.connection) { it == ConnectionState.Disconnected }
        c.send("s-a", "typed offline for A: private prompt two", listOf(picture))
        c.queueAdd("s-a", "typed offline for A: private queued three")
        // The attachment record is memory-only (T1.3); the other two reach A's slot.
        awaitCondition("A's slot holds its records") {
            PendingInput.fromPersisted(disk.disk).records.size == 2
        }
        return OnA(tried, ws)
    }

    private fun loginTo(server: FakeTether, url: String = server.url()) {
        assertEquals(LoginResult.Success, runBlocking { client.login(url, "parity-fake-password") })
    }

    // ------------------------------------------------------------------
    // 1. Filed for A, then a sign-in to B: B gets nothing of A's
    // ------------------------------------------------------------------

    @Test
    fun aSignInToAnotherServerNeverReplaysTheFirstServersInputThere() {
        val onA = writeOnA()
        loginTo(b)
        // Right after the switch nothing of A's shows: no session, no projection.
        assertTrue(client.projections.value.isEmpty())
        assertTrue(client.projectionTrees.value.isEmpty())
        assertTrue(client.sessions.value.isEmpty())
        val bws = b.nextSocket()
        handshake(b, bws)
        // A hostile B answers as if the client had attached A's session: a
        // state-bearing snapshot lacking every key, then an event.
        bws.send(snapshotFrame("s-a", 1, turnState("s-a")))
        bws.send(turnStartedEvent("s-a", "t9", 2))
        serverBarrier(bws)
        assertTrue("B received frames before the barrier", framesUntilBarrier(b).isEmpty())

        // Every frame B ever received: the hello and the barrier, nothing of A's.
        val types = b.allFrames.map { (TetherJson.parseToJsonElement(it) as JsonObject).type() }
        assertEquals(listOf("hello", "pin"), types)
        for (text in b.allFrames) {
            assertTrue("A's session reached B: $text", !text.contains("s-a"))
            assertTrue("A's content reached B: $text", !text.contains("private"))
            assertTrue("A's key reached B: $text", !text.contains(onA.triedKey))
        }

        // The user is told, naming the server the messages were kept for.
        awaitErrors { list -> list.any { it.startsWith("3 unsent messages were not sent to this server") && it.contains(displayHost(a)) } }
        // ...and they stay in A's slot, untouched by B.
        assertEquals(2, PendingInput.fromPersisted(disk.disk).records.size)
        assertNull("B's slot got something", runBlocking { disk.readPendingInput(b.origin()) }?.takeIf {
            PendingInput.fromPersisted(it).records.isNotEmpty()
        })
    }

    /** Delegates to [inner]; once [armed], session() waits for [release] (start()'s settings read). */
    private class GatedSession(private val inner: SettingsStore) : SettingsStore by inner {
        @Volatile var armed = false
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        override suspend fun session(): Session {
            if (armed) release.await()
            return inner.session()
        }
    }

    @Test
    fun theSwitchHappensInTheSignInItselfBeforeAnyConnectionToTheNewServer() {
        // start()'s settings read is held back, so the connection to B comes up
        // through reconnectIfIdle() alone: only the sign-in's own switch stands
        // between A's subscriptions / records and B.
        var gated: GatedSession? = null
        writeOnA { GatedSession(it).also { g -> gated = g } }
        gated!!.armed = true
        try {
            loginTo(b)
            val bws = b.nextSocket()
            handshake(b, bws)
            serverBarrier(bws)
            assertTrue(framesUntilBarrier(b).isEmpty())
            assertTrue(b.allFrames.none { it.contains("s-a") || it.contains("private") })
        } finally {
            gated!!.release.complete(Unit)
        }
    }

    // ------------------------------------------------------------------
    // 2. Back to A: the set-aside records return under the T1.3 rules
    // ------------------------------------------------------------------

    @Test
    fun signingInToTheFirstServerAgainBringsItsRecordsBackUnderTheT13Rules() {
        val onA = writeOnA()
        loginTo(b)
        handshake(b, b.nextSocket())
        framesUntilBarrier(b)

        a.down = false
        loginTo(a)
        val aws = a.nextSocket()
        handshake(a, aws)
        // Never-sent records (tries 0) go out at once, the attachment with them;
        // the one A already saw waits for s-a's snapshot on this socket.
        val first = framesUntilBarrier(a)
        assertEquals(listOf("attach", "send", "queue-add"), first.map { it.type() })
        assertEquals("s-a", first[0].s("sessionId"))
        assertEquals("typed offline for A: private prompt two", first[1].s("text"))
        assertTrue("the attachment came back", first[1].toString().contains("pic.png"))
        assertEquals("typed offline for A: private queued three", first[2].s("text"))

        aws.send(snapshotFrame("s-a", 1, turnState("s-a")))
        serverBarrier(aws)
        val resent = framesUntilBarrier(a)
        assertEquals(listOf("send"), resent.map { it.type() })
        assertEquals("redelivered under its SAME key", onA.triedKey, resent.single().s("idempotencyKey"))
        // And B never saw any of it.
        assertTrue(b.allFrames.none { it.contains("private") || it.contains("s-a") })
    }

    @Test
    fun theSetAsideRecordsSurviveProcessDeathInTheirOwnOriginsSlot() {
        val onA = writeOnA()
        loginTo(b)
        handshake(b, b.nextSocket())
        framesUntilBarrier(b)
        // Process death while signed in to B: nothing of it runs any more, its socket is gone.
        awaitCondition("A's set-aside slot") { PendingInput.fromPersisted(disk.disk).records.size == 2 }
        scopes.forEach { it.cancel() }
        scopes.clear()
        b.serverSockets.forEach { runCatching { it.close(1001, null) } }

        // The next process starts on B (the configured server): nothing of A's.
        process(disk).start()
        handshake(b, b.nextSocket())
        assertTrue(framesUntilBarrier(b).isEmpty())

        a.down = false
        loginTo(a)
        val aws = a.nextSocket()
        handshake(a, aws)
        // From disk every record counts as possibly sent: nothing before the snapshot.
        assertEquals(listOf("attach"), framesUntilBarrier(a).map { it.type() })
        aws.send(snapshotFrame("s-a", 1, turnState("s-a")))
        serverBarrier(aws)
        val resent = framesUntilBarrier(a)
        assertEquals(listOf("send", "queue-add"), resent.map { it.type() })
        assertEquals(onA.triedKey, resent[0].s("idempotencyKey"))
        assertTrue(b.allFrames.none { it.contains("private") || it.contains("s-a") })
    }

    // ------------------------------------------------------------------
    // 3. Same origin (re-login after the session expired): T1.3 unchanged
    // ------------------------------------------------------------------

    @Test
    fun aReLoginToTheSameOriginKeepsAndDeliversPendingTurnsExactlyOnce() {
        val c = processOnA()
        c.start()
        val ws = a.nextSocket()
        handshake(a, ws)
        c.attach("s-a")
        assertEquals("attach", a.frame().type())
        ws.send(snapshotFrame("s-a", 1, turnState("s-a")))
        await(c.projections) { it.containsKey("s-a") }
        c.send("s-a", "sent before expiry")
        val tried = a.frame().s("idempotencyKey")!!

        // The server signs this cookie out; the user types while signed out.
        ws.close(4002, "session revoked")
        await(c.connection) { it == ConnectionState.AuthRequired }
        c.send("s-a", "typed while signed out")

        // Re-login, the URL spelled differently: same origin.
        val host = a.server.hostName.uppercase()
        loginTo(a, "http://$host:${a.server.port}/")
        val ws2 = a.nextSocket()
        handshake(a, ws2)
        val first = framesUntilBarrier(a)
        assertEquals(listOf("attach", "send"), first.map { it.type() })
        assertEquals("typed while signed out", first[1].s("text"))
        val fresh = first[1].s("idempotencyKey")!!

        ws2.send(snapshotFrame("s-a", 1, turnState("s-a")))
        serverBarrier(ws2)
        assertEquals(listOf(tried), framesUntilBarrier(a).map { it.s("idempotencyKey") })
        // A second snapshot on the same socket: in flight, not re-sent.
        ws2.send(snapshotFrame("s-a", 1, turnState("s-a")))
        serverBarrier(ws2)
        assertTrue(framesUntilBarrier(a).isEmpty())
        // Both acked: gone for good.
        ws2.send(turnStartedEvent("s-a", "t1", 2, tried))
        ws2.send(turnStartedEvent("s-a", "t2", 3, fresh))
        awaitCondition("both acked on disk") { PendingInput.fromPersisted(disk.disk).records.isEmpty() && disk.disk != null }
        assertTrue("no set-aside notice for the same origin: $errors", errors.none { it.contains("kept for") })

        // Each turn went out once, the tried one once more after the snapshot (T1.3).
        val sends = a.allFrames.map { TetherJson.parseToJsonElement(it) as JsonObject }.filter { it.type() == "send" }
        assertEquals(listOf(tried, fresh, tried), sends.map { it.s("idempotencyKey") })
    }

    @Test
    fun stopDiscardsTheUnsentInputInMemoryAsWellAsOnDisk() {
        writeOnA()
        client.stop()
        awaitErrors { list -> list.any { it.startsWith("3 unsent messages were discarded when you signed out") } }
        a.down = false
        loginTo(a)
        val aws = a.nextSocket()
        handshake(a, aws)
        aws.send(snapshotFrame("s-a", 1, turnState("s-a")))
        serverBarrier(aws)
        assertTrue("stop() left input to replay", framesUntilBarrier(a).isEmpty())
    }

    @Test
    fun stopDiscardsTheUnsentInputEvenBeforeItsDiskWipeLands() {
        var gated: GatedClear? = null
        writeOnA { GatedClear(it).also { g -> gated = g } }
        try {
            client.stop()
            a.down = false
            // Signed in again while the wipe of A's slot is still held back.
            loginTo(a)
            val aws = a.nextSocket()
            handshake(a, aws)
            aws.send(snapshotFrame("s-a", 1, turnState("s-a")))
            serverBarrier(aws)
            assertTrue("a slot being wiped was read back", framesUntilBarrier(a).isEmpty())
        } finally {
            gated!!.release.complete(Unit)
        }
    }

    // ------------------------------------------------------------------
    // 4. The 0.6.0 single slot
    // ------------------------------------------------------------------

    private fun legacyPayload(key: String): String {
        val store = PendingInput.addRecord(PendingInput.emptyStore(), key, PendingInput.KIND_SEND, "s-a", "from 0.6.0", now.get()).store
        return PendingInput.toPersistable(store)
    }

    @Test
    fun aLegacySlotIsTheConfiguredServersAndWaitsForItsSnapshot() {
        process(InMemorySettings(a.url(), initialCookie = "parity-fake-cookie-a", initialLegacyPendingInput = legacyPayload("k-legacy")))
        client.start()
        val ws = a.nextSocket()
        handshake(a, ws)
        assertEquals(listOf("attach"), framesUntilBarrier(a).map { it.type() })
        ws.send(snapshotFrame("s-a", 1, turnState("s-a")))
        serverBarrier(ws)
        assertEquals(listOf("k-legacy"), framesUntilBarrier(a).map { it.s("idempotencyKey") })
    }

    @Test
    fun aLegacySlotWithNoServerIsNeverSentAnywhere() {
        val settings = InMemorySettings(initialLegacyPendingInput = legacyPayload("k-orphan"))
        process(settings)
        client.start()
        await(client.connection) { it == ConnectionState.AuthRequired }
        awaitErrors { list -> list.any { it.contains("saved by an earlier version") && it.contains("will not be sent") } }

        loginTo(b)
        val bws = b.nextSocket()
        handshake(b, bws)
        bws.send(snapshotFrame("s-a", 1, turnState("s-a")))
        serverBarrier(bws)
        assertTrue(framesUntilBarrier(b).isEmpty())
        assertTrue(b.allFrames.none { it.contains("k-orphan") || it.contains("s-a") })
        // Still kept, still unattributed.
        assertEquals(listOf("k-orphan"), PendingInput.fromPersisted(runBlocking { settings.readUnattributedPendingInput() }).records.map { it.key })
        assertTrue(PendingInput.fromPersisted(runBlocking { settings.readPendingInput(b.origin()) }).records.isEmpty())
    }

    // ------------------------------------------------------------------
    // 6. Folded T1.5 INFO (a): a stale verdict never frees a newer attempt's slot
    // ------------------------------------------------------------------

    @Test
    fun aRefusedVerdictOfTheOldSignInNeverFreesTheNewAttemptsSlot() =
        aStaleVerdictLeavesTheNewAttemptAlone(MockResponse().setResponseCode(401).setBody("{}"))

    @Test
    fun aRejectedVerdictOfTheOldSignInNeverFreesTheNewAttemptsSlot() =
        aStaleVerdictLeavesTheNewAttemptAlone(MockResponse().setResponseCode(200).setBody("""{"authenticated":false}"""))

    /**
     * A's probe answers [verdict] after 1.5 s, while B's probe (3 s) is the
     * attempt in flight. If A's stale verdict released the connect slot, the
     * reconnectIfIdle() below would start a second, parallel attempt at B.
     */
    private fun aStaleVerdictLeavesTheNewAttemptAlone(verdict: MockResponse) {
        a.probeAnswers += verdict.setHeadersDelay(1_500, TimeUnit.MILLISECONDS)
        processOnA().start()
        awaitCondition("A's probe is out") { a.probes.get() == 1 }
        b.probeAnswers += MockResponse().setResponseCode(200).setBody("""{"authenticated":true}""")
            .setHeadersDelay(3_000, TimeUnit.MILLISECONDS)
        loginTo(b)
        Thread.sleep(2_000) // A's stale verdict (1.5 s) has come back; B's probe (3 s) is still out
        client.reconnectIfIdle()
        val bws = b.nextSocket()
        handshake(b, bws)
        assertEquals("a second attempt ran in parallel", 1, b.probes.get())
        assertNull(client.signedOutReason.value)
        assertEquals(ConnectionState.Connected, client.connection.value)
    }
}
