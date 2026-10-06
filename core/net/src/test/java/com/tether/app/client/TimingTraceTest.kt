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
    fun aLineThatIsNotFixedWordsIsDroppedNotLogged() {
        val lines = mutableListOf<String>()
        val trace = TimingTrace(sink = { lines += it }, nowMs = { 0L })
        trace.begin("resume")
        trace.mark("socket-open")
        trace.mark("socket-lost backoff")
        trace.mark("host.example.org")
        trace.mark("session s1")
        trace.mark("Probe")
        trace.mark("one two three")
        trace.mark("")
        trace.begin("user@host")
        trace.mark("ready")
        assertEquals(listOf("resume +0ms begin", "resume +0ms socket-open", "resume +0ms socket-lost backoff"), lines)
    }

    @Test
    fun aColdStartLogsEveryStepOfTheConnectInOrder() {
        connected()
        awaitMilestone("connected")
        val steps = milestones()
        // ta-coik.36: the probe goes out alongside the saved-copy bind, so their order is not fixed;
        // the socket is never opened before the bind is done, and every other step keeps its place.
        assertEquals(
            listOf("begin", "settings-read", "probe-sent", "probe-answered", "saved-copy-bound", "upgrade-sent", "socket-open", "ready", "connected").sorted(),
            steps.sorted(),
        )
        assertEquals(listOf("begin", "settings-read"), steps.take(2))
        assertTrue("the probe is sent before the bind is reported: $steps", steps.indexOf("probe-sent") < steps.indexOf("saved-copy-bound"))
        assertTrue("probe-answered precedes the upgrade: $steps", steps.indexOf("probe-answered") < steps.indexOf("upgrade-sent"))
        assertTrue("the socket waits for the bind: $steps", steps.indexOf("saved-copy-bound") < steps.indexOf("upgrade-sent"))
        assertEquals(listOf("upgrade-sent", "socket-open", "ready", "connected"), steps.takeLast(4))
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
