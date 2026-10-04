package com.tether.app.client

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-coik.32: the link comes back as fast as it can, never slower than the web (which waits a fixed
 * 1.8 s after a close and pings an open socket for up to 8 s on return). Every timer is on a
 * [ManualScheduler]; the clock is the harness's manual one.
 */
class ReconnectLatencyTest {

    private val h = ConnectionHarness()

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

    private fun lose(ws: WebSocket) {
        ws.close(1001, null)
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
    }

    // ------------------------------------------------------------------
    // R2: the first reconnect after a link that worked is immediate
    // ------------------------------------------------------------------

    @Test
    fun aLinkThatWorkedIsReplacedAtOnceInFrontThenAttemptsBackOff() {
        val ws = connected()
        h.now.addAndGet(ConnectionTimings.IMMEDIATE_RECONNECT_MIN_LIFETIME_MS)
        lose(ws)
        val first = h.scheduler.await(::isReconnectDelay)
        assertEquals("no wait before the first attempt", 0L, first.delayMs)

        // That attempt fails: from here on the backoff applies, from its base.
        h.enqueueAuthFailure()
        first.fire()
        val second = h.scheduler.await { isReconnectDelay(it) && it != 0L }
        assertEquals(550L, second.delayMs)
        h.enqueueConnect()
        second.fire()
        h.handshake(h.nextSocket())
    }

    @Test
    fun aLinkLostRightAfterItsHandshakeBacksOffSoADroppingServerCannotLoop() {
        val ws = connected()
        h.now.addAndGet(ConnectionTimings.IMMEDIATE_RECONNECT_MIN_LIFETIME_MS - 1)
        lose(ws)
        assertEquals(550L, h.scheduler.await(::isReconnectDelay).delayMs)
    }

    @Test
    fun inTheBackgroundALostLinkBacksOff() {
        val ws = connected()
        h.now.addAndGet(ConnectionTimings.IMMEDIATE_RECONNECT_MIN_LIFETIME_MS)
        h.client.setAppForeground(false)
        lose(ws)
        assertEquals(550L, h.scheduler.await(::isReconnectDelay).delayMs)
    }

    // ------------------------------------------------------------------
    // R1: a socket presumed dead is replaced, not pinged
    // ------------------------------------------------------------------

    @Test
    fun backAfterALongAbsenceTheOpenSocketIsReplacedAtOnceWithoutAPing() {
        connected()
        h.client.setAppForeground(false)
        h.now.addAndGet(ConnectionTimings.BACKGROUND_REPLACE_AFTER_MS)
        h.enqueueConnect()
        h.client.setAppForeground(true)
        // A new socket at once: no timer had to fire, and nothing went out on the old one.
        val next = h.nextSocket()
        h.handshake(next)
        assertTrue("no ping wait", h.scheduler.pending().none { it.delayMs == ConnectionTimings.PING_TIMEOUT_MS })
        assertTrue("no reconnect timer", h.scheduler.history().none { isReconnectDelay(it.delayMs) })
    }

    @Test
    fun backAfterAShortTripTheOpenSocketIsStillPingedAsOnTheWeb() {
        connected()
        h.client.setAppForeground(false)
        h.now.addAndGet(ConnectionTimings.BACKGROUND_REPLACE_AFTER_MS - 1)
        h.client.setAppForeground(true)
        assertEquals("ping", h.expectFrame("ping").type())
        assertEquals("only the first probe and upgrade: no new connect", 2, h.server.requestCount)
    }

    @Test
    fun aDefaultNetworkChangeReplacesAnOpenSocketAtOnce() {
        connected()
        h.enqueueConnect()
        h.client.onDefaultNetworkChanged()
        h.handshake(h.nextSocket())
        assertTrue("no ping wait", h.scheduler.pending().none { it.delayMs == ConnectionTimings.PING_TIMEOUT_MS })
        assertTrue("no reconnect timer", h.scheduler.history().none { isReconnectDelay(it.delayMs) })
    }

    @Test
    fun aDefaultNetworkChangeCutsABackoffWaitShort() {
        val ws = connected()
        lose(ws) // lost right after its handshake: a backoff wait
        val wait = h.scheduler.await(::isReconnectDelay)
        h.enqueueConnect()
        h.client.onDefaultNetworkChanged()
        h.handshake(h.nextSocket())
        assertTrue("the wait was cancelled", wait.cancelled)
    }

    @Test
    fun aDefaultNetworkChangeDoesNothingWhileSuspendedInTheBackground() {
        connected()
        h.client.setAppForeground(false)
        h.scheduler.await { it == ConnectionTimings.BACKGROUND_GRACE_MS }.fire()
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        val requests = h.server.requestCount
        h.client.onDefaultNetworkChanged()
        assertEquals(requests, h.server.requestCount)
        assertTrue(h.scheduler.pending().none { isReconnectDelay(it.delayMs) })
    }

    @Test
    fun theWatchReportsAChangeOnlyForAnotherOrAReturningDefaultNetwork() {
        val watch = DefaultNetworkWatch<String>()
        assertFalse("the first network is the current one, not a change", watch.available("wifi"))
        assertFalse("the same one again is not a change", watch.available("wifi"))
        assertTrue("another default network", watch.available("cell"))
        watch.lost("wifi") // not the current one: nothing
        assertFalse(watch.available("cell"))
        watch.lost("cell")
        assertTrue("the same network back after it was lost", watch.available("cell"))
    }

    // ------------------------------------------------------------------
    // R3: the open chat first, the rest after its snapshot, the open chat watched
    // ------------------------------------------------------------------

    private fun sessionJson(id: String) =
        """{"id":"$id","provider":"claude","name":"$id","cwd":"/w","status":"ready","startedAt":1,"updatedAt":1,""" +
            """"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"}"""

    private fun readyListing(vararg ids: String) =
        """{"type":"ready","protocolVersion":137,"nativeProtocolFloor":129,"sessions":[${ids.joinToString(",") { sessionJson(it) }}],""" +
            """"providers":[],"workspaceRoot":null}"""

    private fun attaches(frames: List<kotlinx.serialization.json.JsonObject>): List<Pair<String, Long?>> =
        frames.filter { it.type() == "attach" }.map {
            it["sessionId"]!!.jsonPrimitive.content to it["afterSeq"]?.jsonPrimitive?.longOrNull
        }

    /** Opens [ids] in order (one clock tick apart), each with a snapshot at seq 1, on [ws]. */
    private fun open(ws: WebSocket, vararg ids: String) {
        for (id in ids) {
            h.now.addAndGet(1)
            h.client.attach(id)
            h.expectFrame("attach")
            ws.send(snapshotFrame(id, 1))
            h.await(h.client.projections) { it.containsKey(id) }
        }
    }

    private fun connectedListing(vararg ids: String): WebSocket {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws, readyListing(*ids))
        return ws
    }

    private fun reconnect(ws: WebSocket, ready: String): WebSocket {
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        val next = h.nextSocket()
        h.handshake(next, ready)
        return next
    }

    @Test
    fun onANewSocketTheOpenChatIsAttachedFirstTheRestAfterItsSnapshotAndItLast() {
        val ws = connectedListing("s1", "s2", "s3")
        open(ws, "s1", "s3", "s2") // s2 is the chat on screen; s3 was opened after s1
        val ws2 = reconnect(ws, readyListing("s1", "s2", "s3"))
        assertEquals("only the open chat before its snapshot", listOf("s2" to 1L), attaches(h.framesUntilBarrier()))

        ws2.send(snapshotFrame("s2", 1, state = null))
        h.serverBarrier(ws2)
        // The rest, most recently opened first, then the open chat again (the server watches the last).
        assertEquals(listOf("s3" to 1L, "s1" to 1L, "s2" to 1L), attaches(h.framesUntilBarrier()))
    }

    @Test
    fun aSessionHoldingInputIsAttachedAtOnceWithTheOpenChat() {
        val ws = connectedListing("s1", "s2")
        open(ws, "s1", "s2")
        h.client.send("s1", "keep me")
        h.expectFrame("send")
        val ws2 = reconnect(ws, readyListing("s1", "s2"))
        // s1's record was on the wire: a FULL attach (no cursor), with the open chat's.
        assertEquals(listOf("s2" to 1L, "s1" to null), attaches(h.framesUntilBarrier()))
        ws2.send(snapshotFrame("s2", 1, state = null))
        h.serverBarrier(ws2)
        assertEquals("the open chat again, last", listOf("s2" to 1L), attaches(h.framesUntilBarrier()))
    }

    @Test
    fun withoutTheOpenChatsSnapshotTheRestGoesAfterTheWaitWithoutASecondFetch() {
        val ws = connectedListing("s1", "s2")
        open(ws, "s1", "s2")
        reconnect(ws, readyListing("s1", "s2"))
        assertEquals(listOf("s2" to 1L), attaches(h.framesUntilBarrier()))
        h.scheduler.await { it == ConnectionTimings.DEFERRED_ATTACH_MAX_WAIT_MS }.fire()
        assertEquals(listOf("s1" to 1L), attaches(h.framesUntilBarrier()))
    }

    @Test
    fun openingAWaitingSessionAttachesItAtOnceAndItBecomesTheWatchedOne() {
        val ws = connectedListing("s1", "s2", "s3")
        open(ws, "s1", "s2", "s3")
        val ws2 = reconnect(ws, readyListing("s1", "s2", "s3"))
        assertEquals(listOf("s3" to 1L), attaches(h.framesUntilBarrier()))
        h.now.addAndGet(1)
        h.client.attach("s1") // the reader opens s1 while it waits
        assertEquals(listOf("s1" to 1L), attaches(h.framesUntilBarrier()))
        ws2.send(snapshotFrame("s1", 1, state = null))
        h.serverBarrier(ws2)
        assertEquals(listOf("s2" to 1L, "s1" to 1L), attaches(h.framesUntilBarrier()))
    }

    @Test
    fun anOpenChatTheServerNoLongerListsChangesNothing() {
        val ws = connectedListing("s1", "s2")
        open(ws, "s1", "s2")
        reconnect(ws, readyListing("s1"))
        assertEquals("every subscription at once, in order", listOf("s1" to 1L, "s2" to 1L), attaches(h.framesUntilBarrier()))
        assertTrue(h.scheduler.pending().none { it.delayMs == ConnectionTimings.DEFERRED_ATTACH_MAX_WAIT_MS })
    }
}
