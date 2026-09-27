package com.tether.app.client

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4.5 end to end over a MockWebServer "Tether": `log` frames fold into [TetherClient.eventLog]
 * (replayed tail deduped across a reconnect, emptied on a restart and on sign-out), and
 * [TetherClient.fetchStats] reads GET /api/stats with the connection's credential.
 */
class EventLogClientTest {

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    private fun connected(deviceToken: String? = null): WebSocket {
        h.enqueueConnect()
        h.newClient(deviceToken = deviceToken)
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        return ws
    }

    private fun logFrame(boot: Long, vararg seqs: Long, level: String = "info") =
        """{"type":"log","bootId":$boot,"entries":[${seqs.joinToString(",") { """{"seq":$it,"ts":$it,"level":"$level","event":"turn.start","sid":"s1"}""" }}]}"""

    @Test
    fun logFramesFoldIntoTheEventLog() {
        val ws = connected()
        ws.send(logFrame(1, 1, 2))
        ws.send(logFrame(1, 3, level = "warn"))
        h.await(h.client.eventLog) { it.entries.size == 3 }
        assertEquals(listOf("info", "info", "warn"), h.client.eventLog.value.entries.map { it.level })
        assertEquals("1", h.client.eventLog.value.bootId)
    }

    @Test
    fun theTailReplayedOnReconnectDedupesAndARestartStartsOver() {
        val ws = connected()
        ws.send(logFrame(1, 1, 2))
        h.await(h.client.eventLog) { it.entries.size == 2 }
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        assertEquals("kept across the drop", 2, h.client.eventLog.value.entries.size)
        h.scheduler.await(::isReconnectDelay).fire()
        val next = h.nextSocket()
        h.handshake(next)
        next.send(logFrame(1, 1, 2, 3)) // server.mjs:8010 replays its tail on connect
        h.await(h.client.eventLog) { it.entries.size == 3 }
        assertEquals(listOf(1L, 2, 3), h.client.eventLog.value.entries.map { it.seq })
        val generation = h.client.eventLog.value.generation
        next.send(logFrame(2, 1))
        h.await(h.client.eventLog) { it.bootId == "2" }
        assertEquals("a restart is the same sign-in", generation, h.client.eventLog.value.generation)
        assertEquals(listOf(1L), h.client.eventLog.value.entries.map { it.seq })
    }

    @Test
    fun signingOutEmptiesTheLog() {
        val ws = connected()
        ws.send(logFrame(1, 1))
        h.await(h.client.eventLog) { it.entries.size == 1 }
        h.server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) // POST /api/auth/logout
        val before = h.client.eventLog.value.generation
        runBlocking { h.client.logout() }
        assertEquals("emptied, and a new sign-in generation", EventLog(generation = before + 1), h.client.eventLog.value)
    }

    private fun drainTo(path: String): okhttp3.mockwebserver.RecordedRequest {
        while (true) {
            val request = h.server.takeRequest(10, TimeUnit.SECONDS)
            assertNotNull("no request to $path", request)
            if (request!!.path == path) return request
        }
    }

    @Test
    fun fetchStatsReadsTheSnapshotWithTheCookie() {
        connected()
        h.server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ok":true,"uptimeMs":61000,"pid":9,"protocolVersion":128,"headless":{"mode":"claude","persistent":false},
                   "sessions":{"total":2,"byMode":{"headless":2}},"headlessRuntime":{"warm":1,"activeTurns":0,"maxConcurrentTurns":0},
                   "memory":{"rss":1048576,"heapUsed":524288},"clients":1}""",
            ),
        )
        val result = runBlocking { h.client.fetchStats() }
        assertTrue("$result", result is StatsResult.Loaded)
        assertEquals(61_000L, (result as StatsResult.Loaded).stats.uptimeMs)
        val request = drainTo("/api/stats")
        assertEquals("GET", request.method)
        assertTrue(request.getHeader("Cookie").orEmpty().contains("cookie"))
    }

    @Test
    fun fetchStatsWorksWithAPairedDeviceToken() {
        connected(deviceToken = "tthr_fake_for_tests")
        h.server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"pid":1}"""))
        val result = runBlocking { h.client.fetchStats() }
        assertTrue("$result", result is StatsResult.Loaded)
        assertEquals("Bearer tthr_fake_for_tests", drainTo("/api/stats").getHeader("Authorization"))
    }

    @Test
    fun aFailedStatsRequestReadsLikeTheWebsMessage() {
        connected()
        h.server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        assertEquals(StatsResult.Failed("stats request failed (503)"), runBlocking { h.client.fetchStats() })
        h.server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        assertEquals(StatsResult.Failed("Could not load stats."), runBlocking { h.client.fetchStats() })
    }
}
