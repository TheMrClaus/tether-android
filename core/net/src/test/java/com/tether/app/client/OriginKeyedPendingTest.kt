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
import org.junit.Assert.assertFalse
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
                        "/healthz" -> MockResponse().setResponseCode(200).setBody(HEALTH_137)
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
    private fun process(settings: SettingsStore, collectErrors: Boolean = true): RealTetherClient {
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
        // A UI subscribes to errors before it starts the client; a headless start has nobody.
        if (collectErrors) scope.launch(start = CoroutineStart.UNDISPATCHED) { client.errors.collect { errors += it } }
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
     * turn with an attachment (T7.4: refused, never filed: attachments are never queued) and a
     * queued message (tries 0, never sent).
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
        // T7.4 (coordinator decision): offline, a message with attachments is refused and says so;
        // it is not held in memory to go out later (the outbox never carries attachments).
        awaitErrors { list -> list.contains(ATTACHMENTS_NOT_SENT_COPY) }
        c.queueAdd("s-a", "typed offline for A: private queued three")
        // The two records (the tried send and the queued one) reach A's slot.
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
        awaitErrors { list -> list.any { it.startsWith("2 unsent messages were not sent to this server") && it.contains(displayHost(a)) } }
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
        // Never-sent records (tries 0) go out at once; the one A already saw waits for s-a's
        // snapshot on this socket. T7.4: the refused attachment never comes back, on any server.
        val first = framesUntilBarrier(a)
        assertEquals(listOf("attach", "queue-add"), first.map { it.type() })
        assertEquals("s-a", first[0].s("sessionId"))
        assertEquals("typed offline for A: private queued three", first[1].s("text"))
        assertTrue("a refused attachment was sent", (a.allFrames + b.allFrames).none { it.contains("pic.png") || it.contains("prompt two") })

        aws.send(snapshotFrame("s-a", 1, turnState("s-a")))
        serverBarrier(aws)
        val resent = framesUntilBarrier(a)
        assertEquals(listOf("send"), resent.map { it.type() })
        assertEquals("redelivered under its SAME key", onA.triedKey, resent.single().s("idempotencyKey"))
        // And B never saw any of it.
        assertTrue(b.allFrames.none { it.contains("private") || it.contains("s-a") })
    }

    @Test
    fun setAsideRecordsPastTheTenMinuteAgeAreDroppedOnReturnNeverSent() {
        writeOnA()
        loginTo(b)
        handshake(b, b.nextSocket())
        framesUntilBarrier(b)
        now.addAndGet(10 * 60 * 1000L + 1)

        a.down = false
        loginTo(a)
        // Expired at the restore itself, before any drain: the notice, and nothing on the wire.
        awaitErrors { list -> list.any { it.startsWith("2 messages could not be delivered") } }
        val aws = a.nextSocket()
        handshake(a, aws)
        aws.send(snapshotFrame("s-a", 1, turnState("s-a")))
        serverBarrier(aws)
        assertTrue("an expired record went out", framesUntilBarrier(a).isEmpty())
        val sent = a.allFrames.map { TetherJson.parseToJsonElement(it) as JsonObject }.filter { it.type() == "send" || it.type() == "queue-add" }
        assertEquals("only the one send before the switch", 1, sent.size)
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
        awaitErrors { list -> list.any { it.startsWith("2 unsent messages were discarded when you signed out") } }
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

    /** Delegates to [inner]; clear() always fails, as a store that can write neither file does. */
    private class FailingClear(private val inner: SettingsStore) : SettingsStore by inner {
        override suspend fun clear() {
            throw java.io.IOException("disk full")
        }
    }

    /**
     * ta-8lg: a stop() whose disk wipe FAILED never reads as a landed wipe. The slot it could not
     * delete is still dead for this process: a sign-in to the same server replays nothing of it.
     */
    @Test
    fun stopDiscardsTheUnsentInputEvenWhenItsDiskWipeFails() {
        writeOnA { FailingClear(it) }
        client.stop()
        assertEquals("the wipe really failed", 2, PendingInput.fromPersisted(disk.disk).records.size)
        a.down = false
        loginTo(a)
        val aws = a.nextSocket()
        handshake(a, aws)
        aws.send(snapshotFrame("s-a", 1, turnState("s-a")))
        serverBarrier(aws)
        assertTrue("a slot whose wipe failed was read back", framesUntilBarrier(a).isEmpty())
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
        awaitErrors { list -> list.any { it.contains("saved by an earlier version") && it.contains("not sent") && it.contains("from 0.6.0") } }
        // Told once, then deleted: it can never be sent anywhere.
        awaitCondition("the unattributed slot is deleted") { runBlocking { settings.readUnattributedPendingInput() } == null }

        loginTo(b)
        val bws = b.nextSocket()
        handshake(b, bws)
        bws.send(snapshotFrame("s-a", 1, turnState("s-a")))
        serverBarrier(bws)
        assertTrue(framesUntilBarrier(b).isEmpty())
        assertTrue(b.allFrames.none { it.contains("k-orphan") || it.contains("s-a") })
        assertEquals("told once", 1, errors.count { it.contains("saved by an earlier version") })
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

    @Test
    fun aSlotOfAnotherServerWhoseRecordsAllExpiredIsPrunedWithANoticeAndALiveOneIsKept() {
        fun slot(key: String, queuedAt: Long) = PendingInput.toPersistable(
            PendingInput.addRecord(PendingInput.emptyStore(), key, PendingInput.KIND_SEND, "s-x", "old text $key", queuedAt).store,
        )
        val stale = "https://stale.example:443"
        val live = "https://live.example:443"
        disk = DiskSettings(InMemorySettings(a.url(), initialCookie = "parity-fake-cookie-a"), a.url())
        disk.slots[stale] = slot("k-stale", now.get() - 10 * 60 * 1000L - 1)
        disk.slots[live] = slot("k-live", now.get() - 60_000)
        process(disk).start()
        handshake(a, a.nextSocket())
        awaitCondition("the expired slot is pruned") { !disk.slots.containsKey(stale) }
        assertTrue("a slot with a deliverable record is kept", disk.slots.containsKey(live))
        awaitErrors { list -> list.any { it.contains("for stale.example expired") && it.contains("old text k-stale") } }
    }

    @Test
    fun theNoticeNamesTheSchemeWhenTheHostAloneWouldNameTheServerYouAreOn() {
        // http -> https on one host: "kept for tether.example" would name B.
        assertEquals("http://tether.example", displayHost("http://tether.example:80", versus = "https://tether.example:443"))
        // Hosts that differ read fine without it.
        assertEquals("a.example", displayHost("https://a.example:443", versus = "https://b.example:443"))
        assertEquals("a.example:8443", displayHost("https://a.example:8443", versus = "https://a.example:443"))
        assertEquals("[fd00::5]:3000", displayHost("http://[fd00::5]:3000"))
    }

    // ------------------------------------------------------------------
    // Round 2: race windows (held open with RealTetherClient.raceHook)
    // ------------------------------------------------------------------

    /** Holds the first [point] matching [match] until [release]; [handled] once a held frame is fully handled. */
    private inner class Hold(private val point: RacePoint, private val match: (Any?) -> Boolean) {
        private val taken = java.util.concurrent.atomic.AtomicBoolean()
        private val reached = java.util.concurrent.CountDownLatch(1)
        private val go = java.util.concurrent.CountDownLatch(1)
        private val done = java.util.concurrent.CountDownLatch(1)
        @Volatile private var held: Any? = null

        init {
            client.raceHook = { p, x ->
                if (p == point && match(x) && taken.compareAndSet(false, true)) {
                    held = x
                    reached.countDown()
                    go.await(20, TimeUnit.SECONDS)
                } else if (p == RacePoint.FrameHandled && x != null && x === held) {
                    done.countDown()
                }
            }
        }

        fun awaitReached() = assertTrue("the race point was never reached", reached.await(10, TimeUnit.SECONDS))
        fun release() = go.countDown()
        fun awaitHandled() = assertTrue("the held frame was never handled", done.await(10, TimeUnit.SECONDS))
    }

    private fun createdFrame(id: String) =
        """{"type":"created","session":{"id":"$id","provider":"claude","name":"x","cwd":"/w","status":"ready",
           "startedAt":1,"updatedAt":1,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"}}"""

    /** Connected to A with s-a attached and snapshotted. */
    private fun connectedToA(): WebSocket {
        val c = processOnA()
        c.start()
        val ws = a.nextSocket()
        handshake(a, ws)
        c.attach("s-a")
        assertEquals("attach", a.frame().type())
        ws.send(snapshotFrame("s-a", 1, turnState("s-a")))
        await(c.projections) { it.containsKey("s-a") }
        return ws
    }

    @Test
    fun aLateFrameFromTheOldServerNeverRepopulatesTheViewsAfterTheSwitch() {
        val aws = connectedToA()
        val hold = Hold(RacePoint.FrameAdmitted) { it is com.tether.app.protocol.ServerMessage.Created && it.session.id == "late-a" }
        aws.send(createdFrame("late-a"))
        hold.awaitReached() // admitted by A's listener, not yet handled...
        loginTo(b) // ...when the sign-in to B lets A's socket go and clears the views
        hold.release()
        hold.awaitHandled()
        assertTrue("A's late session shows on B", client.sessions.value.none { it.id == "late-a" })
    }

    @Test
    fun aLateSnapshotFromTheOldServerNeverSeedsACursorThatWouldBeAttachedOnTheNewOne() {
        val aws = connectedToA()
        val hold = Hold(RacePoint.FrameAdmitted) { it is com.tether.app.protocol.ServerMessage.Snapshot && it.sessionId == "s-late" }
        aws.send(snapshotFrame("s-late", 7, turnState("s-late")))
        hold.awaitReached()
        loginTo(b)
        hold.release()
        hold.awaitHandled()
        assertTrue(client.projections.value.isEmpty())
        val bws = b.nextSocket()
        handshake(b, bws)
        assertTrue("A's session was attached on B", framesUntilBarrier(b).isEmpty())
        assertTrue(b.allFrames.none { it.contains("s-late") || it.contains("s-a") })
    }

    @Test
    fun pendingFramesComputedForTheOldSocketAreNeverSentOnTheNewServersSocket() {
        connectedToA()
        val hold = Hold(RacePoint.DrainComputed) { frames ->
            (frames as? List<*>)?.any { it.toString().contains("held in the drain") } == true
        }
        val sender = Thread { client.send("s-a", "private: held in the drain") }
        sender.start()
        hold.awaitReached() // computed for A's socket, not yet sent...
        loginTo(b) // ...when the switch lets A's socket go and B's comes up
        val bws = b.nextSocket()
        handshake(b, bws)
        hold.release()
        sender.join(10_000)
        serverBarrier(bws)
        assertTrue(framesUntilBarrier(b).isEmpty())
        assertTrue("a frame computed for A went to B", b.allFrames.none { it.contains("held in the drain") || it.contains("s-a") })
    }

    @Test
    fun aRefusedVerdictPastItsStalenessCheckStillNeverFreesTheNewAttemptsSlot() =
        aVerdictInTheNarrowWindowLeavesTheNewAttemptAlone(MockResponse().setResponseCode(401).setBody("{}"))

    @Test
    fun aRejectedVerdictPastItsStalenessCheckStillNeverFreesTheNewAttemptsSlot() =
        aVerdictInTheNarrowWindowLeavesTheNewAttemptAlone(MockResponse().setResponseCode(200).setBody("""{"authenticated":false}"""))

    /**
     * A's verdict passes the staleness check before the verdict switch and is
     * held there; THEN the sign-in to B starts B's attempt (probe out for 3 s).
     * Released, A's verdict must still not free B's slot: otherwise the
     * reconnectIfIdle() below starts a second, parallel attempt at B.
     */
    private fun aVerdictInTheNarrowWindowLeavesTheNewAttemptAlone(verdict: MockResponse) {
        a.probeAnswers += verdict
        processOnA()
        val hold = Hold(RacePoint.VerdictChecked) { true }
        client.start()
        hold.awaitReached()
        b.probeAnswers += MockResponse().setResponseCode(200).setBody("""{"authenticated":true}""")
            .setHeadersDelay(3_000, TimeUnit.MILLISECONDS)
        loginTo(b)
        hold.release()
        Thread.sleep(500) // A's released verdict runs to its end (no network involved)
        client.reconnectIfIdle()
        val bws = b.nextSocket()
        handshake(b, bws)
        assertEquals("a second attempt ran in parallel", 1, b.probes.get())
        assertNull(client.signedOutReason.value)
    }

    /**
     * ta-cdh: the sign-in's own start() can bring the new server's socket up
     * before the sign-in kicks the connection. That kick must not treat the
     * fresh socket as one to probe: the hello is the first frame of the epoch.
     */
    @Test
    fun aSocketTheSignInOpenedBeforeItsKickGetsTheHelloFirst() {
        connectedToA()
        val hold = Hold(RacePoint.SignInStarted) { true }
        val result = java.util.concurrent.atomic.AtomicReference<LoginResult>()
        val login = Thread { result.set(runBlocking { client.login(b.url(), "parity-fake-password") }) }
        login.start()
        hold.awaitReached() // start() ran; the kick has not...
        val bws = b.nextSocket()
        // ...and the client holds B's socket: onOpen runs before any frame is handled.
        bws.send(createdFrame("opened-before-the-kick"))
        await(client.sessions) { list -> list.any { it.id == "opened-before-the-kick" } }
        hold.release()
        login.join(10_000)
        assertEquals(LoginResult.Success, result.get())
        handshake(b, bws)
        assertTrue(framesUntilBarrier(b).isEmpty())
        assertEquals(listOf("hello", "pin"), b.allFrames.map { (TetherJson.parseToJsonElement(it) as JsonObject).type() })
    }

    @Test
    fun aSaveQueuedBeforeTheSwitchNeverWritesTheOldStoreOrAnEmptyOneIntoTheNewServersSlot() {
        // B's slot already holds a record (a set-aside from an earlier visit).
        val bRecord = PendingInput.resetInFlight(
            PendingInput.markSent(
                PendingInput.addRecord(PendingInput.emptyStore(), "k-b", PendingInput.KIND_SEND, "s-b", "for B", now.get()).store,
                listOf("k-b"),
                now.get(),
            ),
        )
        connectedToA()
        disk.slots[b.origin()] = PendingInput.toPersistable(bRecord)

        // A save of A's store is stuck in its write; more saves queue behind it.
        val writeA = kotlinx.coroutines.CompletableDeferred<Unit>()
        disk.beforeWrite = { origin -> if (origin == a.origin()) writeA.await() }
        client.send("s-a", "private: queued saves")
        client.queueAdd("s-a", "private: queued saves two")
        // The switch to B, held while it reads B's slot.
        val readB = kotlinx.coroutines.CompletableDeferred<Unit>()
        val readingB = java.util.concurrent.CountDownLatch(1)
        disk.beforeRead = { origin -> if (origin == b.origin()) { readingB.countDown(); readB.await() } }
        val login = Thread { loginTo(b) }
        login.start()
        assertTrue(readingB.await(10, TimeUnit.SECONDS))
        // The queued saves now run, with the store switched to B but not yet loaded.
        writeA.complete(Unit)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(800)
        while (System.nanoTime() < deadline && disk.allWrites.none { it.first == b.origin() }) Thread.sleep(10)
        readB.complete(Unit)
        login.join(10_000)

        val bws = b.nextSocket()
        handshake(b, bws)
        assertEquals(listOf("attach"), framesUntilBarrier(b).map { it.type() })
        bws.send(snapshotFrame("s-b", 1, turnState("s-b")))
        serverBarrier(bws)
        assertEquals("B's own record survived", listOf("k-b"), framesUntilBarrier(b).map { it.s("idempotencyKey") })
        assertTrue(b.allFrames.none { it.contains("private") || it.contains("s-a") })
    }

    // ------------------------------------------------------------------
    // Round 2: rapid switches, process death mid-switch
    // ------------------------------------------------------------------

    /** Signed in to [server] (up), whose link then drops: the user types [text] for [sessionId]. */
    private fun typeWhileDown(server: FakeTether, ws: WebSocket, sessionId: String, text: String) {
        server.down = true
        ws.close(1001, null)
        await(client.connection) { it == ConnectionState.Disconnected }
        client.send(sessionId, text)
    }

    @Test
    fun rapidSwitchesAToBToAToBDeliverEachServersInputOnlyThere() {
        writeOnA()
        loginTo(b)
        val b1 = b.nextSocket()
        handshake(b, b1)
        typeWhileDown(b, b1, "s-b", "private-b: typed for B")

        a.down = false
        loginTo(a)
        val a2 = a.nextSocket()
        handshake(a, a2)
        assertEquals(listOf("attach", "queue-add"), framesUntilBarrier(a).map { it.type() })

        b.down = false
        loginTo(b)
        val b2 = b.nextSocket()
        handshake(b, b2)
        val onB = framesUntilBarrier(b)
        assertEquals(listOf("attach", "send"), onB.map { it.type() })
        assertEquals("private-b: typed for B", onB[1].s("text"))

        assertTrue("B's input reached A", a.allFrames.none { it.contains("private-b") || it.contains("s-b") })
        assertTrue("A's input reached B", b.allFrames.none { it.contains("typed offline for A") || it.contains("tried on A") || it.contains("s-a") })
        awaitErrors { list -> list.count { it.contains("kept for") } >= 2 }
    }

    @Test
    fun switchesAToBToCNeverCarryEitherServersInputToTheNext() {
        FakeTether().use { c ->
            writeOnA()
            loginTo(b)
            val b1 = b.nextSocket()
            handshake(b, b1)
            typeWhileDown(b, b1, "s-b", "private-b: typed for B")
            loginTo(c)
            val cws = c.nextSocket()
            handshake(c, cws)
            cws.send(snapshotFrame("s-a", 1, turnState("s-a")))
            cws.send(snapshotFrame("s-b", 1, turnState("s-b")))
            serverBarrier(cws)
            assertTrue(framesUntilBarrier(c).isEmpty())
            assertTrue(c.allFrames.none { it.contains("private") || it.contains("s-a") || it.contains("s-b") })
            awaitErrors { list ->
                list.any { it.contains("kept for ${displayHost(a)}") } && list.any { it.contains("kept for ${displayHost(b)}") }
            }
        }
    }

    @Test
    fun processDeathBetweenTheURLMoveAndTheSetAsideWriteNeverLeaksToTheNewServer() {
        val onA = writeOnA()
        // From here no write to A's slot lands: the process dies mid-switch,
        // after setServer moved the URL and before the set-aside write reached
        // A's slot. Writes to B's slot still land, so a store that followed the
        // URL instead of its origin would be caught writing A's input there.
        disk.beforeWrite = { origin -> if (origin == a.origin()) throw java.io.IOException("killed mid-write") }
        client.send("s-a", "private: lost with the process")
        loginTo(b)
        b.nextSocket() // the dying process reached B's upgrade
        Thread.sleep(300) // its queued writes run
        scopes.forEach { it.cancel() }
        scopes.clear()
        b.serverSockets.forEach { runCatching { it.close(1001, null) } }
        disk.beforeWrite = null
        assertTrue("A's input in B's slot", PendingInput.fromPersisted(disk.slots[b.origin()]).records.none { it.sessionId == "s-a" })

        process(disk).start() // configured for B now
        handshake(b, b.nextSocket())
        assertTrue(framesUntilBarrier(b).isEmpty())

        a.down = false
        loginTo(a)
        val aws = a.nextSocket()
        handshake(a, aws)
        assertEquals(listOf("attach"), framesUntilBarrier(a).map { it.type() })
        aws.send(snapshotFrame("s-a", 1, turnState("s-a")))
        serverBarrier(aws)
        val resent = framesUntilBarrier(a)
        assertEquals(listOf("send", "queue-add"), resent.map { it.type() })
        assertEquals(onA.triedKey, resent[0].s("idempotencyKey"))
        assertTrue(b.allFrames.none { it.contains("private") || it.contains("s-a") })
    }

    // ------------------------------------------------------------------
    // ta-cpn: slots that cannot be read are never pruned; one-time notices
    // are delivered before their data is deleted; a late ready never shows
    // ------------------------------------------------------------------

    private fun seededDisk(): DiskSettings {
        disk = DiskSettings(InMemorySettings(a.url(), initialCookie = "parity-fake-cookie-a"), a.url())
        return disk
    }

    private fun expiredSlot(key: String) = PendingInput.toPersistable(
        PendingInput.addRecord(PendingInput.emptyStore(), key, PendingInput.KIND_SEND, "s-x", "old text $key", now.get() - 10 * 60 * 1000L - 1).store,
    )

    @Test
    fun onlyASlotThatWasReadAndUnderstoodIsEverPruned() {
        val unreadable = "https://unreadable.example:443"
        val futureVersion = "https://future.example:443"
        val garbage = "https://garbage.example:443"
        val malformed = "https://malformed.example:443"
        val empty = "https://empty.example:443"
        seededDisk()
        disk.slots[unreadable] = expiredSlot("k-unreadable")
        disk.slots[futureVersion] = """{"v":3,"records":[],"cleared":[]}"""
        disk.slots[garbage] = "not json at all"
        disk.slots[malformed] = """{"v":2,"records":[{"key":7}],"cleared":[]}"""
        disk.slots[empty] = """{"v":2,"records":[],"cleared":["k-old"]}"""
        disk.beforeRead = { origin -> if (origin == unreadable) throw java.io.IOException("disk read failed") }
        process(disk).start()
        handshake(a, a.nextSocket())
        // The genuinely empty slot goes (the control: pruning ran)...
        awaitCondition("the empty slot is pruned") { !disk.slots.containsKey(empty) }
        // ...and nothing it could not read or understand does.
        for (kept in listOf(unreadable, futureVersion, garbage, malformed)) {
            assertTrue("pruned unseen: $kept", disk.slots.containsKey(kept))
        }
        assertTrue("a notice for a slot that was not read: $errors", errors.none { it.contains("expired before") })
    }

    @Test
    fun aHeadlessStartNeverDeletesAOneTimeNoticesDataUntilSomeoneIsTold() {
        // No UI subscribed (a push/WorkManager start): the expired slot and the
        // unattributed 0.6.0 leftover both stay.
        val stale = "https://stale.example:443"
        seededDisk()
        disk.slots[stale] = expiredSlot("k-stale")
        val orphan = InMemorySettings(initialLegacyPendingInput = PendingInput.toPersistable(
            PendingInput.addRecord(PendingInput.emptyStore(), "k-orphan", PendingInput.KIND_SEND, "s-a", "from 0.6.0", now.get()).store,
        ))
        process(disk, collectErrors = false).start()
        handshake(a, a.nextSocket())
        process(orphan, collectErrors = false).start()
        await(client.connection) { it == ConnectionState.AuthRequired }
        Thread.sleep(700) // both bindings ran their prune / notice step
        assertTrue("an expired slot was deleted with nobody told", disk.slots.containsKey(stale))
        assertTrue("the leftover was deleted with nobody told", runBlocking { orphan.readUnattributedPendingInput() } != null)

        // The next start with the UI up tells, and only then deletes.
        scopes.forEach { it.cancel() }
        scopes.clear()
        a.serverSockets.forEach { runCatching { it.close(1001, null) } }
        process(disk).start()
        handshake(a, a.nextSocket())
        awaitErrors { list -> list.any { it.contains("for stale.example expired") && it.contains("old text k-stale") } }
        awaitCondition("pruned once told") { !disk.slots.containsKey(stale) }
        process(orphan).start()
        awaitErrors { list -> list.any { it.contains("saved by an earlier version") && it.contains("from 0.6.0") } }
        awaitCondition("deleted once told") { runBlocking { orphan.readUnattributedPendingInput() } == null }
    }

    /**
     * T6.7 r2: an error frame A's socket admitted but had not handled when the sign-in to B let that
     * socket go is never shown: the "still current" check and the emit are one step under the lock.
     * B's own error comes through, tagged with B's origin (the view drops any other).
     */
    @Test
    fun aLateErrorFromTheOldServerIsNeverShownAfterTheSwitch() {
        val aws = connectedToA()
        val seen = CopyOnWriteArrayList<ServerErrorText>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { client.serverErrors.collect { seen += it } }
        val hold = Hold(RacePoint.FrameAdmitted) { it is com.tether.app.protocol.ServerMessage.ErrorFrame && it.message == "late words from A" }
        aws.send("""{"type":"error","message":"late words from A"}""")
        hold.awaitReached() // admitted by A's listener, not yet handled...
        loginTo(b) // ...when the sign-in to B lets A's socket go
        hold.release()
        hold.awaitHandled()
        val bws = b.nextSocket()
        handshake(b, bws)
        bws.send("""{"type":"error","message":"words from B"}""")
        awaitCondition("B's error is shown") { seen.any { it.text == "words from B" } }
        assertTrue("A's late words reached the toast after the switch: $seen", seen.none { it.text == "late words from A" })
        assertEquals(listOf(ServerErrorText("words from B", b.origin())), seen.toList())
    }

    @Test
    fun aLateReadyFromTheOldServerNeverShowsItsSessionsOnTheNewOne() {
        processOnA()
        val hold = Hold(RacePoint.FrameAdmitted) { it is com.tether.app.protocol.ServerMessage.Ready }
        client.start()
        val aws = a.nextSocket()
        aws.send(
            """{"type":"ready","protocolVersion":137,"nativeProtocolFloor":129,"sessions":[${
                createdFrame("a-only-session").substringAfter("\"session\":").removeSuffix("}")
            }],"providers":[{"id":"a-provider","label":"A"}],"workspaceRoot":"/a-root"}""",
        )
        hold.awaitReached() // A's ready admitted, not yet handled...
        loginTo(b) // ...when the sign-in to B lets A's socket go
        hold.release()
        hold.awaitHandled()
        assertTrue("A's sessions show on B", client.sessions.value.none { it.id == "a-only-session" })
        assertEquals(null, client.workspaceRoot.value)
        assertTrue(client.providers.value.isEmpty())
    }

    /**
     * T15.6 (ta-ylh leftover): A's `ready.hiddenAgentSessionCount` is A's. A sign-in to B clears it
     * at once, and a pre-v135 B (whose ready carries no count) never shows A's number.
     */
    @Test
    fun theHiddenAgentSessionCountIsClearedOnAServerSwitch() {
        processOnA()
        client.start()
        val aws = a.nextSocket()
        aws.send(readyFrame().replaceFirst("\"sessions\":", "\"hiddenAgentSessionCount\":3,\"sessions\":"))
        assertEquals("hello", a.frame().type())
        assertEquals(3, await(client.hiddenAgentSessionCount) { it != null })
        loginTo(b)
        assertEquals("A's hidden count shows on B", null, client.hiddenAgentSessionCount.value)
        handshake(b, b.nextSocket())
        assertEquals("a count-less ready from B shows A's hidden count", null, client.hiddenAgentSessionCount.value)
    }

    // ------------------------------------------------------------------
    // 4. T7.3 r2: a delegate mention is durable per origin too
    // ------------------------------------------------------------------

    private fun readyWith(sessionId: String) =
        """{"type":"ready","protocolVersion":137,"nativeProtocolFloor":129,"sessions":[{"id":"$sessionId","provider":"claude","name":"n","cwd":"/w",""" +
            """"status":"ready","startedAt":1,"updatedAt":1,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"}],""" +
            """"providers":[],"workspaceRoot":null}"""

    private val catalog =
        """{"type":"providers-snapshot","entries":[{"key":"claude","provider":"claude","status":"ready","models":[{"value":"m-build","displayName":"Build model"}]}]}"""

    @Test
    fun aDelegatedSendFiledOfflineForAIsNeverSeenByBAndReachesAWithItsMention() {
        val c = processOnA()
        c.start()
        val ws = a.nextSocket()
        ws.send(readyWith("s-a"))
        assertEquals("hello", a.frame().type())
        await(c.connection) { it == ConnectionState.Connected }
        ws.send(catalog)
        await(c.providerCatalog) { it.isNotEmpty() }
        c.attach("s-a")
        assertEquals("attach", a.frame().type())
        ws.send(snapshotFrame("s-a", 1, turnState("s-a")))
        await(c.projections) { it.containsKey("s-a") }

        // A goes away; the operator delegates while it is unreachable (drawn for A: the outbox's origin).
        a.down = true
        ws.close(1001, null)
        await(c.connection) { it == ConnectionState.Disconnected }
        val mention = com.tether.app.protocol.DelegateMention(provider = "claude", mode = "build", model = "m-build")
        assertEquals(MentionResult.NotLive, c.sendDelegated("s-a", "x", emptyList(), mention, b.origin()))
        assertEquals(MentionResult.Sent, c.sendDelegated("s-a", "delegated offline for A: private brief", emptyList(), mention, a.origin()))
        awaitCondition("A's slot holds the delegation with its mention") {
            PendingInput.fromPersisted(disk.disk).records.any { it.mention == mention }
        }

        // A hostile B: its own session (the same id), its own catalog, a snapshot, an event.
        loginTo(b)
        val bws = b.nextSocket()
        bws.send(readyWith("s-a"))
        assertEquals("hello", b.frame().type())
        await(client.connection) { it == ConnectionState.Connected }
        bws.send(catalog)
        bws.send(snapshotFrame("s-a", 1, turnState("s-a")))
        bws.send(turnStartedEvent("s-a", "t9", 2))
        serverBarrier(bws)
        assertTrue("B received frames before the barrier", framesUntilBarrier(b).isEmpty())
        for (text in b.allFrames) {
            assertTrue("A's delegation reached B: $text", !text.contains("private") && !text.contains("mention") && !text.contains("\"send\""))
        }
        // A's slot on disk keeps it, mention and mode intact; B's slot has nothing.
        val kept = PendingInput.fromPersisted(disk.disk).records.single { it.text.contains("delegated offline") }
        assertEquals(mention, kept.mention)
        assertNull(runBlocking { disk.readPendingInput(b.origin()) }?.takeIf { PendingInput.fromPersisted(it).records.isNotEmpty() })

        // Back to A: it goes out there, never sent before (tries 0), with the mention and its mode.
        a.down = false
        loginTo(a)
        val aws = a.nextSocket()
        aws.send(readyWith("s-a"))
        assertEquals("hello", a.frame().type())
        await(client.connection) { it == ConnectionState.Connected }
        val first = framesUntilBarrier(a)
        val send = first.single { it.type() == "send" }
        assertEquals("delegated offline for A: private brief", send.s("text"))
        val m = send["mention"] as JsonObject
        assertEquals("build", m["mode"]!!.jsonPrimitive.content)
        assertEquals("claude", m["provider"]!!.jsonPrimitive.content)
        assertEquals("m-build", m["model"]!!.jsonPrimitive.content)
        assertTrue(b.allFrames.none { it.contains("private") })
    }

    // ------------------------------------------------------------------
    // 5. ta-895: a New session profile never crosses a server switch
    // ------------------------------------------------------------------

    private val readyWithClaude =
        """{"type":"ready","protocolVersion":137,"nativeProtocolFloor":129,"sessions":[],""" +
            """"providers":[{"id":"claude","label":"Claude","glyph":"C","available":true}],"workspaceRoot":null}"""

    private val accountsCatalog =
        """{"type":"providers-snapshot","entries":[""" +
            """{"key":"work","provider":"claude","status":"ready","profileId":"work","extends":"claude","label":"Claude (work)","models":[]},""" +
            """{"key":"claude","provider":"claude","status":"ready","label":"Claude","models":[]}]}"""

    @Test
    fun aProfileRowDrawnForAIsNeverCreatedOnBNotEvenAsTheDefault() {
        val c = processOnA()
        c.start()
        val ws = a.nextSocket()
        ws.send(readyWithClaude)
        assertEquals("hello", a.frame().type())
        await(c.connection) { it == ConnectionState.Connected }
        ws.send(accountsCatalog)
        await(c.providerCatalogLive) { it }
        val drawnOnA = NewSessionChoice("work", "claude", "work")
        val originA = c.consentOrigin.value
        assertEquals(a.origin(), originA)

        loginTo(b)
        assertTrue("A's catalog shows on B", client.providerCatalog.value.isEmpty())
        assertEquals(false, client.providerCatalogLive.value)
        val bws = b.nextSocket()
        bws.send(readyWithClaude)
        assertEquals("hello", b.frame().type())
        await(client.connection) { it == ConnectionState.Connected }

        // Drawn for A: refused outright on B's socket.
        assertEquals(NewSessionResult.NotLive, client.createNewSession(drawnOnA, "/w", originA))
        // Re-drawn for B before B's catalog is in: B's base providers stand in, never A's profile.
        assertEquals(NewSessionResult.NotOffered, client.createNewSession(drawnOnA, "/w", b.origin()))
        // B's own catalog, without that account: still refused, and never the default instead.
        bws.send("""{"type":"providers-snapshot","entries":[{"key":"claude","provider":"claude","status":"ready","label":"Claude","models":[]}]}""")
        await(client.providerCatalogLive) { it }
        assertEquals(NewSessionResult.NotOffered, client.createNewSession(drawnOnA, "/w", b.origin()))
        assertTrue(framesUntilBarrier(b).none { it.type() == "create" })
        assertTrue("A saw a create", a.allFrames.none { it.contains("\"create\"") })
    }

    /**
     * ta-8cv r2 (security F1): the draft composer's first message for a session A just created is
     * recorded only while, in the same step under the client's lock, the outbox and the live socket
     * are still A's and the create's. A sign-in switch to B (login runs on IO) landing after the
     * composer decided to send and before the record leaves nothing in B's outbox, and nothing of
     * the prompt reaches B.
     */
    @Test
    fun aFirstMessageForASessionCreatedOnAIsNeverFiledForB() {
        val c = processOnA()
        c.start()
        val ws = a.nextSocket()
        ws.send(readyWithClaude)
        assertEquals("hello", a.frame().type())
        await(c.connection) { it == ConnectionState.Connected }
        val originA = c.consentOrigin.value!!
        val epochA = c.linkEpoch.value
        ws.send(
            """{"type":"created","session":{"id":"s-new","provider":"claude","name":"n","cwd":"/w","status":"ready",
               "startedAt":1,"updatedAt":1,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"},"requestId":"r-1"}""",
        )
        await(c.sessions) { list -> list.any { it.id == "s-new" } }
        // Drawn for another server than the one this socket and outbox belong to: refused even on
        // the create's own socket (the origin binding alone).
        assertFalse(c.sendFirst("s-new", "private prompt for A only", b.origin(), epochA))
        // Positive control, still on A: recorded, and on A's wire.
        assertTrue(c.sendFirst("s-new", "first words for A", originA, epochA))
        assertTrue(framesUntilBarrier(a).any { it.type() == "send" && it.s("text") == "first words for A" })

        loginTo(b)
        assertFalse("the switch landed: refused, nothing recorded", c.sendFirst("s-new", "private prompt for A only", originA, epochA))
        val bws = b.nextSocket()
        bws.send(readyWithClaude)
        assertEquals("hello", b.frame().type())
        await(c.connection) { it == ConnectionState.Connected }
        assertFalse("B's socket is not the create's", c.sendFirst("s-new", "private prompt for A only", originA, c.linkEpoch.value))
        assertFalse("nor is B the create's server", c.sendFirst("s-new", "private prompt for A only", b.origin(), c.linkEpoch.value))
        assertTrue(framesUntilBarrier(b).none { it.type() == "send" })
        assertTrue("nothing of the prompt reached B", b.allFrames.none { it.contains("private prompt for A only") })
        assertTrue("nor A", a.allFrames.none { it.contains("private prompt for A only") })
    }
}
