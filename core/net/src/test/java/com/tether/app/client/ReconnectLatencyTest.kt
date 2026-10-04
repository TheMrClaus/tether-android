package com.tether.app.client

import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
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
}
