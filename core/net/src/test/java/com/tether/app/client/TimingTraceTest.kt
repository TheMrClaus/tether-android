package com.tether.app.client

import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-coik.32: the TetherTiming lines the owner reads with `adb logcat -s TetherTiming`: one per
 * milestone, in order, timed from the start, resume or network change, and made of fixed words only.
 */
class TimingTraceTest {

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    /** `label +Nms milestone[ word]`: nothing else may ever appear (no host, port, id or content). */
    private val shape = Regex("""^(start|resume|network) \+\d+ms [a-z-]+( [a-z-]+)?$""")

    private fun milestones(): List<String> = h.timing.map { it.substringAfter("ms ") }

    private fun connected(): WebSocket {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        return ws
    }

    private fun awaitMilestone(name: String) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (name !in milestones() && System.nanoTime() < deadline) Thread.sleep(5)
    }

    @Test
    fun aColdStartLogsEveryStepOfTheConnectInOrder() {
        connected()
        awaitMilestone("connected")
        assertEquals(
            listOf("begin", "settings-read", "saved-copy-bound", "probe-sent", "probe-answered", "upgrade-sent", "socket-open", "ready", "connected"),
            milestones(),
        )
        assertTrue(h.timing.first().startsWith("start +"))
        assertTrue("fixed words only: ${h.timing}", h.timing.all { shape.matches(it) })
    }

    @Test
    fun aResumeAfterALongAbsenceIsTimedFromTheResume() {
        connected()
        awaitMilestone("connected")
        h.timing.clear()
        h.client.setAppForeground(false)
        h.now.addAndGet(ConnectionTimings.BACKGROUND_REPLACE_AFTER_MS)
        h.enqueueConnect()
        h.client.setAppForeground(true)
        h.handshake(h.nextSocket())
        awaitMilestone("connected")
        assertEquals(
            listOf("begin", "replace-socket", "probe-sent", "probe-answered", "upgrade-sent", "socket-open", "ready", "connected"),
            milestones(),
        )
        assertTrue(h.timing.all { it.startsWith("resume +") && shape.matches(it) })
    }

    @Test
    fun aLostLinkAndANetworkChangeSayWhatFollows() {
        val ws = connected()
        awaitMilestone("connected")
        h.timing.clear()
        h.now.addAndGet(ConnectionTimings.IMMEDIATE_RECONNECT_MIN_LIFETIME_MS)
        ws.close(1001, null)
        awaitMilestone("socket-lost reconnect-now")
        assertTrue(h.timing.toList().toString(), "socket-lost reconnect-now" in milestones())
        h.client.onDefaultNetworkChanged()
        assertTrue(h.timing.any { it.startsWith("network +") && it.endsWith(" begin") })
        assertTrue("fixed words only: ${h.timing}", h.timing.all { shape.matches(it) })
    }
}
