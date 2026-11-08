package com.tether.app.client

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W7 H1 (tether-android #4): the foreground heartbeat. Deterministic: ManualScheduler + injected clock; the
 * heartbeat is the scheduler's own task kind ([ManualScheduler.pendingHeartbeats]).
 */
class HeartbeatTest {
    private val h = ConnectionHarness()
    private val interval = ConnectionTimings.HEARTBEAT_INTERVAL_MS

    @After
    fun tearDown() = h.close()

    private fun connected(): WebSocket {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        return ws
    }

    private fun heartbeat(): ManualScheduler.Task {
        val pending = h.scheduler.pendingHeartbeats()
        assertEquals("exactly one heartbeat task must be pending (none = no ping would ever be sent)", 1, pending.size)
        return pending.single()
    }

    /** Fire the pending heartbeat and read what reached the wire (the barrier proves nothing else came). */
    private fun fireHeartbeat(): List<String> {
        heartbeat().fire()
        return h.framesUntilBarrier().map { it.type().orEmpty() }
    }

    @Test
    fun t1_idleDeadDownlinkIsPingedWithoutATapAndDroppedAndReconnected() {
        connected()
        h.now.addAndGet(interval)
        assertEquals(listOf("ping"), fireHeartbeat())
        // The server stays silent: the probe's deadline drops the socket and the existing reconnect path takes over.
        h.enqueueConnect()
        h.scheduler.await { it == ConnectionTimings.PING_TIMEOUT_MS }.fire()
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        h.handshake(h.nextSocket())
        assertEquals(ConnectionState.Connected, h.client.connection.value)
    }

    @Test
    fun t2_midTurnWhoseEventsStopIsPingedAfterTheQuietInterval() {
        val ws = connected()
        h.client.attach("s1")
        h.expectFrame("attach")
        ws.send(
            snapshotFrame(
                "s1", 0,
                """{"tetherSessionId":"s1","provider":"claude","cwd":"/w","status":"ready","turnOrder":[],"turnsById":{},"activeTurnId":null,"queuedMessages":[]}""",
            ),
        )
        h.await(h.client.projections) { it.containsKey("s1") }
        ws.send(turnStartedEvent("s1", "t1", 1))
        h.await(h.client.projections) { it["s1"]?.turnsById?.containsKey("t1") == true }
        // The events stop. Only the heartbeat can notice.
        h.now.addAndGet(interval)
        assertEquals(listOf("ping"), fireHeartbeat())
        h.enqueueConnect()
        h.scheduler.await { it == ConnectionTimings.PING_TIMEOUT_MS }.fire()
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
    }

    @Test
    fun t3_healthyLinkSendsNothingAndAQuietOneIsPingedOncePerIntervalPlusRtt() {
        val ws = connected()
        // Traffic 5 s in: the run at +20 s finds the link heard from 15 s ago and sends nothing.
        h.now.addAndGet(5_000)
        h.serverBarrier(ws)
        h.now.addAndGet(15_000)
        assertEquals(emptyList<String>(), fireHeartbeat())
        assertEquals("next look when the link has been quiet an interval", 5_000L, heartbeat().delayMs)

        // Quiet an interval: exactly one ping. A pong comes back after a non-zero round trip.
        h.now.addAndGet(5_000)
        heartbeat().fire()
        val ping = h.expectFrame("ping")
        h.now.addAndGet(300)
        ws.send("""{"type":"pong","nonce":"${ping["nonce"]!!.jsonPrimitive.content}"}""")
        h.serverBarrier(ws)
        // The probe's deadline passes with the link alive: no drop.
        h.scheduler.await { it == ConnectionTimings.PING_TIMEOUT_MS }.fire()
        assertEquals(ConnectionState.Connected, h.client.connection.value)

        // The next scheduled run (an interval after the ping) is 300 ms short of an interval of quiet: no ping, a 1 s floor wait...
        h.now.addAndGet(interval - 300)
        assertEquals(emptyList<String>(), fireHeartbeat())
        assertEquals(ConnectionTimings.HEARTBEAT_MIN_RESCHEDULE_MS, heartbeat().delayMs)
        // ...and the next ping follows within ~interval + RTT + floor of the last, not two intervals.
        h.now.addAndGet(ConnectionTimings.HEARTBEAT_MIN_RESCHEDULE_MS)
        assertEquals(listOf("ping"), fireHeartbeat())
    }

    @Test
    fun t4_noHeartbeatBeforeReadyNorWhileBackgroundedNorAfterAnyDetach() {
        // Before READY (socket open, no handshake): none.
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        assertTrue("no heartbeat before ready", h.scheduler.pendingHeartbeats().isEmpty())
        h.handshake(ws)
        heartbeat()

        // Background: none; foreground: one again.
        h.client.setAppForeground(false)
        assertTrue("none while backgrounded", h.scheduler.pendingHeartbeats().isEmpty())
        h.client.setAppForeground(true)
        heartbeat()
        h.framesUntilBarrier() // the return's own probe, if any

        // A socket loss cancels it until the next ready.
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        assertTrue("none while down", h.scheduler.pendingHeartbeats().isEmpty())
        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        assertTrue("none before the new ready", h.scheduler.pendingHeartbeats().isEmpty())
        h.handshake(ws2)
        heartbeat()

        // replaceSocketNow (a network change on a live socket): exactly one, for the new socket.
        h.enqueueConnect()
        h.client.onDefaultNetworkChanged()
        val ws3 = h.nextSocket()
        h.handshake(ws3)
        heartbeat()

        // A sign-in on the live socket (the credential/server switch path): the old socket's heartbeat is gone, then one at ready.
        val base = h.server.url("/").toString().trimEnd('/')
        h.server.enqueue(MockResponse().setBody("""{"ok":true,"protocolVersion":143,"nativeProtocolFloor":129,"pairing":true}"""))
        h.server.enqueue(MockResponse().setBody("""{"ok":true}""").addHeader("set-cookie", "tether_session=s; Path=/; HttpOnly"))
        h.enqueueConnect()
        assertEquals(LoginResult.Success, runBlocking { h.client.login(base, "pw", "Operator") })
        val ws4 = h.nextSocket()
        assertTrue("none before the new ready", h.scheduler.pendingHeartbeats().isEmpty())
        h.handshake(ws4)
        heartbeat()

        // Sign-out, then stop(): none.
        h.server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        runBlocking { h.client.logout() }
        assertTrue("none after sign-out", h.scheduler.pendingHeartbeats().isEmpty())
    }

    @Test
    fun t4b_stopCancelsTheHeartbeat() {
        connected()
        heartbeat()
        h.client.stop()
        assertTrue("none after stop", h.scheduler.pendingHeartbeats().isEmpty())
    }

    @Test
    fun t5_anOutstandingProbeIsNeverDoubledOrSupersededByTheHeartbeat() {
        connected()
        h.now.addAndGet(interval)
        h.client.reconnectIfIdle() // a tap's probe is outstanding
        val probe = h.scheduler.await { it == ConnectionTimings.PING_TIMEOUT_MS }
        // The heartbeat run finds the link quiet but a probe pending: no second ping, and that probe is untouched.
        assertEquals("only the tap's ping is on the wire", listOf("ping"), fireHeartbeat().let { it })
        assertFalse("the outstanding probe's deadline was not superseded", probe.cancelled)
        assertEquals(1, h.scheduler.pending().count { it.delayMs == ConnectionTimings.PING_TIMEOUT_MS })
        assertEquals("it still looks again in an interval", interval, heartbeat().delayMs)
    }

    @Test
    fun t6_aRunJustAfterTrafficPingsAtLastInboundPlusIntervalAndDropsEightSecondsLater() {
        val ws = connected()
        h.now.addAndGet(interval - 100)
        h.serverBarrier(ws) // last inbound at +19.9 s
        h.now.addAndGet(100)
        assertEquals(emptyList<String>(), fireHeartbeat()) // the run at +20 s: heard 100 ms ago
        assertEquals(interval - 100, heartbeat().delayMs) // looks again at lastInbound + interval, not one interval later
        h.now.addAndGet(interval - 100)
        assertEquals(listOf("ping"), fireHeartbeat())
        h.enqueueConnect()
        h.scheduler.await { it == ConnectionTimings.PING_TIMEOUT_MS }.fire() // +8 s of silence
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
    }
}
