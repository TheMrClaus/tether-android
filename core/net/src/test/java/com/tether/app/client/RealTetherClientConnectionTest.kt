package com.tether.app.client

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1.2 connection manager against a MockWebServer: handshake order, the D5
 * native window, attach epochs, snapshot/cursor variants, ping liveness,
 * backoff, 4001, lifecycle, and the never-resend-a-turn rule. Every timer runs
 * on a [ManualScheduler] (no sleeps); ordering is proven with wire barriers.
 */
class RealTetherClientConnectionTest {

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    /** Configured client -> auth probe -> upgrade -> ready/hello -> Connected. */
    private fun connected(ready: String = readyFrame()): WebSocket {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws, ready)
        return ws
    }

    /** The server drops [ws]; fire the scheduled reconnect; handshake the new socket. */
    private fun reconnectAfterDrop(ws: WebSocket): WebSocket {
        h.enqueueConnect()
        // Server-side cancel() is not supported by MockWebServer; a going-away
        // close is the same loss of the link from the client's point of view.
        ws.close(1001, null)
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        val next = h.nextSocket()
        h.handshake(next)
        return next
    }

    private fun attachAndSnapshot(ws: WebSocket, sessionId: String, throughSeq: Long, state: String? = null) {
        h.client.attach(sessionId)
        h.expectFrame("attach")
        ws.send(if (state == null) snapshotFrame(sessionId, throughSeq) else snapshotFrame(sessionId, throughSeq, state))
        h.await(h.client.projections) { it.containsKey(sessionId) }
    }

    private fun turnState(sessionId: String, vararg turns: Pair<String, String?>): String {
        val byId = turns.joinToString(",") { (id, key) ->
            val k = if (key != null) ""","idempotencyKey":"$key"""" else ""
            """"$id":{"turnId":"$id","status":"running"$k,"blocks":[],"blocksById":{}}"""
        }
        val order = turns.joinToString(",") { "\"${it.first}\"" }
        return """{"tetherSessionId":"$sessionId","provider":"claude","cwd":"/w","status":"ready",
                   "turnOrder":[$order],"turnsById":{$byId},"activeTurnId":null,"queuedMessages":[]}"""
    }

    // ------------------------------------------------------------------
    // Handshake + native window (D5)
    // ------------------------------------------------------------------

    @Test
    fun handshakeIsReadyThenHelloThenAttachAndHelloCarriesAndroid143() {
        h.enqueueConnect()
        h.newClient()
        h.client.attach("s1") // before any socket: subscribe only
        h.client.start()
        val ws = h.nextSocket()
        h.client.attach("s2") // socket up, no ready yet: still subscribe only
        ws.send(readyFrame(workspaceRoot = "/w"))

        // Wire order proves nothing left the client before `ready`: hello is first.
        val hello = h.expectFrame("hello")
        assertEquals(143L, hello["protocolVersion"]!!.jsonPrimitive.longOrNull)
        assertEquals("android", hello["client"]!!.jsonPrimitive.content)
        val a1 = h.expectFrame("attach")
        val a2 = h.expectFrame("attach")
        assertEquals(listOf("s1", "s2"), listOf(a1, a2).map { it["sessionId"]!!.jsonPrimitive.content })
        assertNull("first attach carries no cursor", a1["afterSeq"])
        h.expectFrame("browse")
        h.expectFrame("discover")
        h.await(h.client.connection) { it == ConnectionState.Connected }
    }

    @Test
    fun anyServerInsideTheNativeWindowIsAccepted() {
        // Strict ready.protocolVersion equality is gone: a newer server whose
        // floor still admits this app's version serves it.
        val ws = connected(readyFrame(protocolVersion = 144, floor = 120))
        assertEquals(ConnectionState.Connected, h.client.connection.value)
        attachAndSnapshot(ws, "s1", 1)
    }

    @Test
    fun v128ServerWithoutNativeFloorIsServerTooOldAndTerminal() {
        h.enqueueConnect()
        h.newClient()
        h.client.attach("s1")
        h.client.start()
        val ws = h.nextSocket()
        ws.send(readyFrame(protocolVersion = 128, floor = null))
        h.expectFrame("hello")
        val state = h.await(h.client.connection) { it is ConnectionState.VersionMismatch }
        assertEquals(
            Incompatibility(IncompatibleReason.ServerTooOld, serverProtocolVersion = 128, nativeProtocolFloor = null),
            (state as ConnectionState.VersionMismatch).incompatibility,
        )
        // The client closed the socket, and nothing followed the hello.
        assertEquals(1000, h.serverCloses.poll(10, TimeUnit.SECONDS))
        assertNull("no attach after an incompatible ready", h.received.poll())
        assertTrue("terminal: no reconnect scheduled", h.scheduler.pending().none { isReconnectDelay(it.delayMs) })
        // Automatic nudges do not leave the state; only a user action does.
        val requests = h.server.requestCount
        h.client.reconnectIfIdle()
        h.client.setAppForeground(true)
        assertEquals(requests, h.server.requestCount)
    }

    @Test
    fun helloReplyClientTooOldIsTerminalUntilUserRetry() {
        val ws = connected()
        ws.send(
            """{"type":"version_mismatch","requiredVersion":131,"message":"This app is too old for this Tether server — update the app to reconnect.",
               "nativeProtocolFloor":131,"serverProtocolVersion":131,"reason":"client_too_old"}""",
        )
        val state = h.await(h.client.connection) { it is ConnectionState.VersionMismatch } as ConnectionState.VersionMismatch
        assertEquals(IncompatibleReason.ClientTooOld, state.incompatibility.reason)
        assertEquals(131, state.incompatibility.nativeProtocolFloor)
        assertEquals(131, state.incompatibility.serverProtocolVersion)
        assertTrue(state.incompatibility.message!!.contains("update the app"))
        assertEquals(1000, h.serverCloses.poll(10, TimeUnit.SECONDS))
        assertTrue(h.scheduler.pending().none { isReconnectDelay(it.delayMs) })
        val requests = h.server.requestCount
        // Automatic nudges return synchronously in a halted state (no connect launched).
        h.client.reconnectIfIdle()
        h.client.setAppForeground(true)
        assertEquals(requests, h.server.requestCount)

        // The banner's retry: connect again, now to an (updated) compatible server.
        h.enqueueConnect()
        h.client.retryConnection()
        h.handshake(h.nextSocket())
    }

    @Test
    fun helloReplyServerTooOldAndWebStyleMismatchAreServerTooOld() {
        val ws = connected()
        // ta-3uk: the exact frame a v136 server (lib/hello-compat.mjs) sends to this app's 137 hello.
        ws.send(
            """{"type":"version_mismatch","requiredVersion":136,"message":"This Tether server is older than the app — update the server to reconnect.",
               "nativeProtocolFloor":129,"serverProtocolVersion":136,"reason":"server_too_old"}""",
        )
        val state = h.await(h.client.connection) { it is ConnectionState.VersionMismatch } as ConnectionState.VersionMismatch
        assertEquals(IncompatibleReason.ServerTooOld, state.incompatibility.reason)
        assertEquals(136, state.incompatibility.serverProtocolVersion)
        assertEquals(129, state.incompatibility.nativeProtocolFloor)
        assertTrue(state.incompatibility.message!!.contains("update the server"))
        // Terminal like client_too_old: closed, no automatic reconnect (the banner's Retry is the way out).
        assertEquals(1000, h.serverCloses.poll(10, TimeUnit.SECONDS))
        assertTrue(h.scheduler.pending().none { isReconnectDelay(it.delayMs) })

        // A server that answered with the web (strict) frame only names its version.
        val web = Compatibility.fromMismatch(
            com.tether.app.protocol.ServerMessage.VersionMismatch(requiredVersion = 128, message = "reload"),
        )
        assertEquals(IncompatibleReason.ServerTooOld, web.reason)
        assertEquals(128, web.serverProtocolVersion)
    }

    // ------------------------------------------------------------------
    // Attach epochs, snapshots, cursor, dedupe
    // ------------------------------------------------------------------

    @Test
    fun attachIsIdempotentPerConnectionEpoch() {
        val ws = connected()
        h.client.attach("s1")
        h.client.attach("s1")
        val first = h.framesUntilBarrier()
        assertEquals(listOf("attach"), first.map { it.type() })
        ws.send(snapshotFrame("s1", 4))
        h.await(h.client.projections) { it.containsKey("s1") }

        // New epoch: the ready handler re-attaches from the cursor exactly once,
        // and an explicit attach() in the same epoch adds nothing.
        reconnectAfterDrop(ws)
        h.client.attach("s1")
        val second = h.framesUntilBarrier()
        assertEquals(listOf("attach"), second.map { it.type() })
        assertEquals(4L, second.single()["afterSeq"]!!.jsonPrimitive.longOrNull)
    }

    @Test
    fun snapshotVariantsMoveTheCursorLikeTheWebClient() {
        val ws = connected()
        attachAndSnapshot(ws, "s1", 5, turnState("s1"))

        // Dedupe: seq <= cursor is dropped, cursor + 1 folds.
        ws.send(turnStartedEvent("s1", "t5", 5))
        ws.send(turnStartedEvent("s1", "t6", 6))
        h.await(h.client.projections) { it["s1"]?.turnsById?.containsKey("t6") == true }
        assertFalse("seq 5 <= cursor must not fold", h.client.projections.value.getValue("s1").turnsById.containsKey("t5"))

        // Bounded (v115): the trim boundary is recorded per session.
        ws.send(snapshotFrame("s1", 6, turnState("s1", "t6" to null), trimmedBefore = 3))
        h.await(h.client.trimmedBefore) { it["s1"] == 3 }

        // Reconnect: attach afterSeq = cursor; the server is at head and replies
        // WITHOUT state. The projection (and trim boundary) stay, the cursor is 6.
        val ws2 = reconnectAfterDrop(ws)
        val reattach = h.framesUntilBarrier().single()
        assertEquals(6L, reattach["afterSeq"]!!.jsonPrimitive.longOrNull)
        ws2.send(snapshotFrame("s1", 6, state = null))
        ws2.send(turnStartedEvent("s1", "t7", 7))
        val folded = h.await(h.client.projections) { it["s1"]?.turnsById?.containsKey("t7") == true }.getValue("s1")
        assertTrue("stateless snapshot keeps the projection", folded.turnsById.containsKey("t6"))
        assertEquals(3, h.client.trimmedBefore.value["s1"])

        // reset: the cursor was AHEAD of a replaced journal. Replace wholesale and
        // move the cursor DOWN to throughSeq, or every later event looks old.
        ws2.send(snapshotFrame("s1", 2, turnState("s1"), reset = true))
        h.await(h.client.projections) { it["s1"]?.turnsById?.isEmpty() == true }
        assertNull("an untrimmed snapshot clears the boundary", h.client.trimmedBefore.value["s1"])
        ws2.send(turnStartedEvent("s1", "t3", 3))
        h.await(h.client.projections) { it["s1"]?.turnsById?.containsKey("t3") == true }
    }

    // ------------------------------------------------------------------
    // Liveness (v105 ping)
    // ------------------------------------------------------------------

    @Test
    fun unansweredPingDropsTheHalfOpenSocketAndReconnects() {
        val ws = connected()
        h.now.addAndGet(1) // the probe goes out after the last inbound frame
        h.client.reconnectIfIdle() // an OPEN socket is probed, not trusted
        val ping = h.expectFrame("ping")
        assertTrue(ping["nonce"]!!.jsonPrimitive.content.isNotEmpty())

        // Silence past the deadline: the socket is presumed dead.
        h.enqueueConnect()
        h.scheduler.await { it == ConnectionTimings.PING_TIMEOUT_MS }.fire()
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        val reconnect = h.scheduler.await(::isReconnectDelay)
        assertEquals(550L, reconnect.delayMs)
        reconnect.fire()
        h.handshake(h.nextSocket())
    }

    @Test
    fun anyInboundFrameAnswersTheProbeAndOnlyOneProbeIsOutstanding() {
        val ws = connected()
        h.now.addAndGet(1)
        h.client.reconnectIfIdle()
        h.client.reconnectIfIdle() // second nudge while a probe is outstanding: no second ping
        val out = h.framesUntilBarrier()
        assertEquals(listOf("ping"), out.map { it.type() })
        ws.send("""{"type":"pong","nonce":"${out.single()["nonce"]!!.jsonPrimitive.content}"}""")
        h.serverBarrier(ws)
        h.scheduler.await { it == ConnectionTimings.PING_TIMEOUT_MS }.fire()
        assertEquals(ConnectionState.Connected, h.client.connection.value)
        assertTrue(h.scheduler.pending().none { isReconnectDelay(it.delayMs) })
        assertTrue("socket still live", h.framesUntilBarrier().isEmpty())
    }

    // ------------------------------------------------------------------
    // Backoff
    // ------------------------------------------------------------------

    @Test
    fun reconnectBacksOffExponentiallyAndResetsOnAcceptedHandshake() {
        h.newClient()
        repeat(4) { h.enqueueAuthFailure() }
        h.client.start()
        val delays = mutableListOf<Long>()
        repeat(4) {
            val task = h.scheduler.await(::isReconnectDelay)
            delays += task.delayMs
            if (it < 3) task.fire() else {
                h.enqueueConnect()
                task.fire()
            }
        }
        assertEquals(listOf(550L, 1_100L, 2_200L, 4_400L), delays)
        val ws = h.nextSocket()
        h.handshake(ws)

        // Success reset the schedule: the next drop starts over at the base.
        ws.close(1001, null)
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        assertEquals(550L, h.scheduler.await(::isReconnectDelay).delayMs)
    }

    // ------------------------------------------------------------------
    // 4001 + lifecycle
    // ------------------------------------------------------------------

    @Test
    fun closeCode4001IsTerminalAndClearsTheCredential() {
        val ws = connected()
        val baseUrl = runBlocking { h.settings.baseUrl.first() }
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val collecting = h.scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            h.client.errors.collect { errors += it }
        }
        ws.close(4001, "device revoked")
        h.await(h.client.connection) { it == ConnectionState.AuthRequired }
        h.await(h.client.configured) { !it }
        assertNull(runBlocking { h.settings.credential.first() })
        // T1.4: the server URL survives (login-screen prefill) and the login
        // screen gets its reason; the toast says what happened.
        assertEquals(baseUrl, runBlocking { h.settings.baseUrl.first() })
        assertEquals(baseUrl, h.client.serverUrl.value)
        assertEquals(SignedOutReason.DeviceUnpaired, h.client.signedOutReason.value)
        val deadline = System.nanoTime() + 5_000_000_000
        while (errors.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(listOf("This device was unpaired from the server. Pair it again to reconnect."), errors.toList())
        collecting.cancel()
        assertTrue("terminal: no reconnect", h.scheduler.pending().none { isReconnectDelay(it.delayMs) })
        val requests = h.server.requestCount
        h.client.reconnectIfIdle()
        h.client.setAppForeground(true)
        assertEquals(requests, h.server.requestCount)
    }

    @Test
    fun backgroundGraceClosesTheSocketAndForegroundReconnectsAndRechecks() {
        val ws = connected()
        // A quick trip to the background changes nothing.
        h.client.setAppForeground(false)
        val grace = h.scheduler.await { it == ConnectionTimings.BACKGROUND_GRACE_MS }
        h.now.addAndGet(1)
        h.client.setAppForeground(true)
        assertTrue("grace cancelled on return", grace.cancelled)
        assertEquals("foreground re-checks an open socket with a ping", "ping", h.expectFrame("ping").type())
        ws.send("""{"type":"pong"}""")

        // A long one: the socket goes, and nothing reconnects in the background.
        h.client.setAppForeground(false)
        h.scheduler.await { it == ConnectionTimings.BACKGROUND_GRACE_MS }.fire()
        assertEquals(1000, h.serverCloses.poll(10, TimeUnit.SECONDS))
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        assertTrue(h.scheduler.pending().none { isReconnectDelay(it.delayMs) })
        val requests = h.server.requestCount
        h.client.reconnectIfIdle() // e.g. a network callback while backgrounded
        assertEquals(requests, h.server.requestCount)

        // Foreground: reconnect immediately (no backoff wait).
        h.enqueueConnect()
        h.client.setAppForeground(true)
        h.handshake(h.nextSocket())
    }

    // ------------------------------------------------------------------
    // PLAN §0.3: never auto-retry a turn
    // ------------------------------------------------------------------

    @Test
    fun reconnectNeverResendsATurnTheSnapshotShowsAccepted() {
        val ws = connected()
        attachAndSnapshot(ws, "s1", 1, turnState("s1"))
        h.client.send("s1", "run the tests")
        val key = h.expectFrame("send")["idempotencyKey"]!!.jsonPrimitive.content

        // The link dies before the ack. After reconnect, NOTHING is re-sent
        // before the session's snapshot: only the re-attach goes out.
        val ws2 = reconnectAfterDrop(ws)
        assertEquals(listOf("attach"), h.framesUntilBarrier().map { it.type() })

        // The journal-folded snapshot contains the key: accepted, never resent.
        ws2.send(snapshotFrame("s1", 2, turnState("s1", "t1" to key)))
        h.serverBarrier(ws2)
        assertTrue("an accepted turn is never re-sent", h.framesUntilBarrier().isEmpty())
    }

    @Test
    fun reconnectRedeliversAnUnacceptedSendOnceUnderTheSameKey() {
        val ws = connected()
        attachAndSnapshot(ws, "s1", 1, turnState("s1"))
        h.client.send("s1", "lost on the wire")
        val key = h.expectFrame("send")["idempotencyKey"]!!.jsonPrimitive.content

        val ws2 = reconnectAfterDrop(ws)
        assertEquals(listOf("attach"), h.framesUntilBarrier().map { it.type() })
        // The snapshot does NOT contain the key: the existing pending-input path
        // redelivers it, exactly once, with the SAME idempotencyKey (server dedupe).
        ws2.send(snapshotFrame("s1", 1, turnState("s1")))
        h.serverBarrier(ws2)
        val resent = h.framesUntilBarrier()
        assertEquals(listOf("send"), resent.map { it.type() })
        assertEquals(key, resent.single()["idempotencyKey"]!!.jsonPrimitive.content)
    }
}
