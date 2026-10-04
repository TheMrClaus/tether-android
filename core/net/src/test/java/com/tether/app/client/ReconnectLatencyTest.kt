package com.tether.app.client

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
}
