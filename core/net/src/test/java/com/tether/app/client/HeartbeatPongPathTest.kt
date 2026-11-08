package com.tether.app.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W7 H0 (tether-android #4, R-B1): does a WS PONG leave on the PRODUCTION path? RealTetherClient on its real
 * socket (TetherWebSocket + the real onPing hook, the app's plain OkHttpClient()) behind a raw-frame server.
 * A pong that only left when a scheduler task fired, or when another frame was written, would be RED here.
 */
class HeartbeatPongPathTest {
    private val raw = RawTetherServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val now = AtomicLong(1_000_000)
    private val scheduler = ManualScheduler()
    private lateinit var client: RealTetherClient
    private val linkLog = java.util.concurrent.ConcurrentLinkedQueue<String>()

    @After
    fun tearDown() {
        if (::client.isInitialized) client.stop()
        raw.close()
        scope.cancel()
    }

    private fun connected(): RawWs {
        val settings = InMemorySettings(initialBaseUrl = raw.baseUrl, initialCookie = "cookie")
        client = RealTetherClient(
            settings = settings,
            httpClient = OkHttpClient(),
            linkLog = { linkLog.add(it) },
            scope = scope,
            clock = { now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = scheduler,
            retentionBudgetBytes = Runtime.getRuntime().maxMemory() / 4,
            ioDispatcher = Dispatchers.IO,
        )
        client.start()
        val ws = raw.nextSocket()
        ws.send(readyFrame())
        runBlocking { withTimeout(20_000) { client.connection.first { it == ConnectionState.Connected } } }
        return ws
    }

    private fun assertPong(ws: RawWs, payload: String, seconds: Long = 5): RawWs.Control {
        val c = ws.nextControl(seconds) ?: error("no control frame came back for ping '$payload'")
        assertEquals("a PONG (0xA)", 0xA, c.opcode)
        assertEquals(payload, String(c.payload))
        return c
    }

    @Test
    fun caseI_anIdleSocketAnswersAServerPingWithItsPayload() {
        val ws = connected()
        ws.ping("idle-1")
        assertPong(ws, "idle-1")
    }

    @Test
    fun caseII_aPingBehindAHeldFoldIsAnsweredWithin500msOfTheFoldEnding() {
        val ws = connected()
        val hold = AtomicBoolean(false)
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        client.raceHook = { point, _ ->
            if (point == RacePoint.FrameAdmitted && hold.compareAndSet(false, true)) {
                admitted.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        // >= 1 MiB inbound frame; its fold is held at FrameAdmitted, on the single reader thread.
        ws.send("""{"type":"pong","nonce":"${"x".repeat(1_100_000)}"}""")
        assertTrue("the fold started", admitted.await(20, TimeUnit.SECONDS))
        ws.ping("behind-fold")
        val before = ws.controls.poll(1, TimeUnit.SECONDS)
        // Known single-reader fact (TetherWebSocket.kt:232): a pong cannot leave while the reader is held. Recorded, not asserted.
        println("H0 case (ii): pong while the fold was held = ${before != null}")
        val releasedAt = System.nanoTime()
        release.countDown()
        val pong = before ?: assertPong(ws, "behind-fold", 5)
        assertEquals(0xA, pong.opcode)
        val lateMs = TimeUnit.NANOSECONDS.toMillis(pong.readAtNanos - releasedAt)
        println("H0 case (ii): pong read ${lateMs} ms after the release")
        assertTrue("pong within 500 ms of the release (was $lateMs)", before != null || lateMs <= 500)
    }

    @Test
    fun caseIII_twoPingsATickApartAreBothAnswered() {
        val ws = connected()
        ws.ping("tick-1")
        assertPong(ws, "tick-1")
        Thread.sleep(300)
        ws.ping("tick-2")
        assertPong(ws, "tick-2")
    }
}
