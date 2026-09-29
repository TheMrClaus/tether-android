package com.tether.app.client

import com.tether.app.protocol.TetherJson
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T13.2 r2 (SYNC_DESIGN §4.2): INTERRUPT and End session (`kill`) from a copy that is not live
 * (RealTetherClient over a MockWebServer socket). While offline or catching up, a saved copy's
 * stale "busy" or "running" never stops a real turn or a real session: `interrupt` goes out only on
 * a live link, bound to the server that drew the key, for a session live on it that may be driven;
 * `kill` only on a live link of the server that drew the control (r3) for a listed session, and,
 * from a session's own copy (the default), only once that copy is live. Nothing refused is held for
 * the reconnect.
 */
class InterruptKillTransmissionTest {

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    private fun connected(ready: String = readyWithSessions("s1", "s2")): Pair<RealTetherClient, WebSocket> {
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, ready)
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 5))
        h.await(client.liveSessions) { "s1" in it }
        return client to ws
    }

    private fun framesOf(type: String): List<JsonObject> = h.framesUntilBarrier().filter { it.type() == type }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    // ---- interrupt --------------------------------------------------------------------------------

    @Test
    fun aLiveSessionsInterruptSendsExactlyTheFrame() {
        val (client, _) = connected()
        assertEquals(InterruptResult.Sent, client.interrupt("s1", client.consentOrigin.value))
        val frames = framesOf("interrupt")
        assertEquals(1, frames.size)
        assertEquals(setOf("type", "sessionId"), frames[0].keys)
        assertEquals("s1", frames[0].str("sessionId"))
    }

    @Test
    fun offlineOrCatchingUpTheInterruptIsRefusedAndNothingIsHeldForTheReconnect() {
        val (client, ws) = connected()
        val origin = client.consentOrigin.value
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        // A saved copy: the turn it shows as running is not known to run now.
        assertEquals(InterruptResult.NotConnected, client.interrupt("s1", origin))

        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2, readyWithSessions("s1", "s2"))
        h.expectFrame("attach")
        // Catching up: connected and attached, the snapshot not in yet.
        assertEquals(InterruptResult.NotLive, client.interrupt("s1", client.consentOrigin.value))
        assertTrue("the refused taps were not held for the new link", framesOf("interrupt").isEmpty())

        ws2.send(snapshotFrame("s1", 5))
        h.await(client.liveSessions) { "s1" in it }
        assertTrue("going live sends nothing by itself", framesOf("interrupt").isEmpty())
        assertEquals(InterruptResult.Sent, client.interrupt("s1", client.consentOrigin.value))
        assertEquals(1, framesOf("interrupt").size)
    }

    @Test
    fun aListedSessionThatIsNotLiveOnThisConnectionIsNotInterrupted() {
        val (client, _) = connected()
        assertEquals(InterruptResult.NotLive, client.interrupt("s2", client.consentOrigin.value))
        assertTrue(framesOf("interrupt").isEmpty())
    }

    @Test
    fun anInterruptDrawnForAnotherServerIsRefused() {
        val (client, _) = connected()
        assertEquals(InterruptResult.NotLive, client.interrupt("s1", "https://other.example"))
        assertEquals(InterruptResult.NotLive, client.interrupt("s1", null))
        assertTrue(framesOf("interrupt").isEmpty())
    }

    @Test
    fun aReadOnlyHandedOffUnlistedOrEmptySessionIsLocked() {
        val (client, ws) = connected(readyWithSessions("s1", extra = ""","readOnly":true"""))
        assertEquals(InterruptResult.Locked, client.interrupt("s1", client.consentOrigin.value))
        ws.send("""{"type":"session","session":{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"active","startedAt":1,"updatedAt":1,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless","handedOffTo":"s9"}}""")
        h.await(client.sessions) { list -> list.any { it.id == "s1" && it.handedOffTo == "s9" } }
        assertEquals(InterruptResult.Locked, client.interrupt("s1", client.consentOrigin.value))
        assertEquals(InterruptResult.Locked, client.interrupt("", client.consentOrigin.value))
        assertTrue(framesOf("interrupt").isEmpty())
    }

    @Test
    fun anUnlistedSessionFailsClosed() {
        val (client, _) = connected(readyFrame())
        assertEquals(InterruptResult.Locked, client.interrupt("s1", client.consentOrigin.value))
        assertTrue(framesOf("interrupt").isEmpty())
    }

    @Test
    fun aHaltedClientSendsNoInterrupt() {
        val (client, _) = connected()
        val origin = client.consentOrigin.value
        client.stop()
        assertEquals(InterruptResult.NotConnected, client.interrupt("s1", origin))
    }

    @Test
    fun nothingReceivedEverProducesAnInterrupt() {
        val (client, ws) = connected()
        ws.send("""{"type":"interrupt","sessionId":"s1"}""")
        ws.send(turnStartedEvent("s1", "t1", 6))
        // Every frame above was handled before the barrier session showed up.
        h.serverBarrier(ws)
        assertTrue("no frame the operator did not tap for", framesOf("interrupt").isEmpty())
    }

    // ---- kill -------------------------------------------------------------------------------------

    @Test
    fun endSessionFromALiveCopySendsExactlyTheFrame() {
        val (client, _) = connected()
        client.kill("s1", client.consentOrigin.value)
        val frames = framesOf("kill")
        assertEquals(1, frames.size)
        assertEquals(setOf("type", "sessionId"), frames[0].keys)
        assertEquals("s1", frames[0].str("sessionId"))
    }

    @Test
    fun offlineEndSessionIsRefusedEitherWayAndNothingIsHeldForTheReconnect() {
        val (client, ws) = connected()
        val origin = client.consentOrigin.value
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        // Drawn while live (the same server): the link, not the origin, refuses them.
        client.kill("s1", origin)
        client.kill("s1", origin, requireLive = false)

        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2, readyWithSessions("s1", "s2"))
        h.expectFrame("attach")
        // Catching up: the header's End session (its own copy) is still refused.
        client.kill("s1", client.consentOrigin.value)
        assertTrue("nothing refused was held for the new link", framesOf("kill").isEmpty())

        ws2.send(snapshotFrame("s1", 5))
        h.await(client.liveSessions) { "s1" in it }
        assertTrue(framesOf("kill").isEmpty())
        client.kill("s1", client.consentOrigin.value)
        assertEquals(1, framesOf("kill").size)
    }

    @Test
    fun aSessionNotLiveOnThisConnectionIsEndedOnlyFromTheLiveList() {
        val (client, _) = connected()
        // s2 is listed on this link but not attached: its own copy is not live …
        client.kill("s2", client.consentOrigin.value)
        assertTrue(framesOf("kill").isEmpty())
        // … the sidebar row, drawn from the live list, may end it.
        client.kill("s2", client.consentOrigin.value, requireLive = false)
        assertEquals(listOf("s2"), framesOf("kill").map { it.str("sessionId") })
    }

    @Test
    fun anUnlistedSessionIsNeverEnded() {
        val (client, _) = connected()
        client.kill("s-unknown", client.consentOrigin.value)
        client.kill("s-unknown", client.consentOrigin.value, requireLive = false)
        client.kill("", client.consentOrigin.value, requireLive = false)
        assertTrue(framesOf("kill").isEmpty())
    }

    @Test
    fun anEndDrawnForAnotherServerIsRefusedEitherWay() {
        val (client, _) = connected()
        client.kill("s1", "https://other.example")
        client.kill("s1", "https://other.example", requireLive = false)
        client.kill("s1", null)
        client.kill("s1", null, requireLive = false)
        assertTrue(framesOf("kill").isEmpty())
    }

    /**
     * r3: an End session (header confirmation or armed sidebar row) drawn while on server A, and
     * tapped after the client signed in to server B, never ends B's session, even when B lists (and
     * has live) a session with the same id: nothing reaches B, or A.
     */
    @Test
    fun anEndDrawnForServerAIsRefusedOnServerBEvenForTheSameSessionId() {
        val (client, _) = connected()
        val originA = client.consentOrigin.value
        assertNotNull(originA)
        SecondServer().use { b ->
            b.enqueueLoginAndConnect("parity-fake-cookie-b")
            assertEquals(LoginResult.Success, runBlocking { client.login(b.url(), "parity-fake-password") })
            val bws = b.nextSocket()
            bws.send(readyWithSessions("s1", "s2"))
            assertEquals("hello", b.frame().type())
            h.await(client.connection) { it == ConnectionState.Connected }
            val originB = h.await(client.consentOrigin) { it != null && it != originA }
            assertNotEquals(originA, originB)
            // B's own s1, live on B: every other precondition for an End holds.
            client.attach("s1")
            b.frameOf("attach")
            bws.send(snapshotFrame("s1", 5))
            h.await(client.liveSessions) { "s1" in it }
            assertTrue(b.framesUntilBarrier(client).isEmpty())

            client.kill("s1", originA)
            client.kill("s1", originA, requireLive = false)
            client.kill("s2", originA, requireLive = false)
            assertTrue("an End drawn for A reached B", b.framesUntilBarrier(client).none { it.type() == "kill" })
            assertTrue("an End reached A", h.received.none { it.contains("\"type\":\"kill\"") })

            // The control: drawn for B, the same End goes out on B.
            client.kill("s1", originB)
            assertEquals(listOf("s1"), b.framesUntilBarrier(client).filter { it.type() == "kill" }.map { it.str("sessionId") })
        }
    }

    // ---- reportsFreshness -------------------------------------------------------------------------

    @Test
    fun theRealClientDeclaresItReportsFreshnessBeforeAnyConnection() {
        // r3: declared, not inferred from the identity of its syncStates flow.
        assertTrue(h.newClient().reportsFreshness)
    }

    @Test
    fun theRealClientReportsFreshnessSoAMissingEntryIsNeverLive() {
        val (client, _) = connected()
        assertTrue(client.reportsFreshness)
        assertFalse("s1 is live, but with no entry it is not", LiveCopy.isLive("s1", client.liveSessions.value, null, client.reportsFreshness))
        // syncStates is derived from the live set a dispatch later: wait for its Live entry.
        val sync = h.await(client.syncStates) { it["s1"]?.freshness == Freshness.Live }["s1"]
        assertTrue(LiveCopy.isLive("s1", client.liveSessions.value, sync, client.reportsFreshness))
    }

    @Test
    fun aClientThatReportsNoFreshnessKeepsTheLiveSetRule() {
        val stub = com.tether.app.ui.StubClient()
        assertFalse(stub.reportsFreshness)
        assertTrue(LiveCopy.isLive("s1", setOf("s1"), null, stub.reportsFreshness))
        assertFalse(LiveCopy.isLive("s1", emptySet(), null, stub.reportsFreshness))
    }

    /** A second "Tether" to sign in to: healthz, password login, auth probe, upgrade (as NodeRegistryTest's). */
    private class SecondServer : AutoCloseable {
        val server = MockWebServer().apply { start() }
        private val sockets = LinkedBlockingQueue<WebSocket>()
        private val received = LinkedBlockingQueue<String>()
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

        fun enqueueLoginAndConnect(cookie: String) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(HEALTH_132))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("{}")
                    .addHeader("Set-Cookie", "tether_session=$cookie; Path=/; HttpOnly"),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"authenticated":true}"""))
            server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        }

        fun nextSocket(): WebSocket {
            val ws = sockets.poll(20, TimeUnit.SECONDS)
            assertNotNull("the client never connected to the second server", ws)
            return ws!!
        }

        fun frame(): JsonObject {
            val text = received.poll(20, TimeUnit.SECONDS)
            assertNotNull("expected a frame at the second server", text)
            return TetherJson.parseToJsonElement(text!!) as JsonObject
        }

        fun frameOf(type: String): JsonObject {
            while (true) {
                val f = frame()
                if (f.type() == type) return f
            }
        }

        /** Every frame the client sent here before a `pin` barrier it sends now (wire order). */
        fun framesUntilBarrier(client: RealTetherClient): List<JsonObject> {
            val id = "barrier-b-${System.nanoTime()}"
            client.pin(id, true)
            val out = mutableListOf<JsonObject>()
            while (true) {
                val f = frame()
                if (f.type() == "pin" && f["sessionId"]?.jsonPrimitive?.content == id) return out
                out += f
            }
        }

        override fun close() {
            while (true) {
                val ws = sockets.poll() ?: break
                runCatching { ws.close(1000, null) }
            }
            server.shutdown()
        }
    }
}
