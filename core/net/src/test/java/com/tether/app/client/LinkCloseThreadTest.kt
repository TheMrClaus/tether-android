package com.tether.app.client

import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tth-1om0 (#3): letting go of pooled connections and of an abandoned socket writes on the socket
 * (TLS close_notify), which Android 17's StrictMode refuses on the main thread. The close must
 * happen off the thread that called [RealTetherClient.setAppForeground] /
 * [RealTetherClient.onDefaultNetworkChanged].
 */
class LinkCloseThreadTest {
    private class Close(val socketId: Int, val thread: String)

    private val closes = ConcurrentLinkedQueue<Close>()
    private val created = ConcurrentLinkedQueue<Int>()

    private val recording = object : SocketFactory() {
        private val plain = getDefault()
        private fun wrap(): Socket {
            val id = created.size
            created.add(id)
            return object : Socket() {
                override fun close() {
                    closes.add(Close(id, Thread.currentThread().name))
                    super.close()
                }
            }
        }
        override fun createSocket(): Socket = wrap()
        override fun createSocket(host: String?, port: Int): Socket = plain.createSocket(host, port)
        override fun createSocket(host: String?, port: Int, a: java.net.InetAddress?, p: Int): Socket = plain.createSocket(host, port, a, p)
        override fun createSocket(a: java.net.InetAddress?, port: Int): Socket = plain.createSocket(a, port)
        override fun createSocket(a: java.net.InetAddress?, port: Int, l: java.net.InetAddress?, p: Int): Socket = plain.createSocket(a, port, l, p)
    }

    private val h = ConnectionHarness(OkHttpClient.Builder().socketFactory(recording).build())

    @After
    fun tearDown() = h.close()

    /** A connected client with ONE idle pooled connection: the first socket the client's HTTP client made. */
    private fun connectedWithIdleConnection() {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        h.handshake(h.nextSocket())
        h.server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        kotlinx.coroutines.runBlocking { h.client.fetchStats() }
        assertTrue("a connection is idle in the pool", h.http.connectionPool.idleConnectionCount() > 0)
        closes.clear()
        seeded = created.last()
    }

    private var seeded = -1

    private fun awaitCloses() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && closes.none { it.socketId == seeded }) Thread.sleep(5)
    }

    private fun onCallerThread(name: String, block: () -> Unit) {
        val t = Thread(block, name)
        t.start()
        t.join(20_000)
    }

    @Test
    fun theStaleResumeClosesItsSocketsOffTheCallerThread() {
        connectedWithIdleConnection()
        h.client.setAppForeground(false)
        h.now.addAndGet(ConnectionTimings.BACKGROUND_WIRE_FRESH_MS)
        h.enqueueConnect()
        onCallerThread("main-sim") { h.client.setAppForeground(true) }
        h.handshake(h.nextSocket())
        awaitCloses()
        val mine = closes.filter { it.socketId == seeded }
        assertTrue("the seeded idle pooled socket was closed (not vacuous)", mine.isNotEmpty())
        assertEquals("no close of it on the caller thread", emptyList<String>(), mine.filter { it.thread == "main-sim" }.map { it.thread })
    }

    @Test
    fun aNetworkChangeClosesItsSocketsOffTheCallerThread() {
        connectedWithIdleConnection()
        h.enqueueConnect()
        onCallerThread("net-sim") { h.client.onDefaultNetworkChanged() }
        h.handshake(h.nextSocket())
        awaitCloses()
        val mine = closes.filter { it.socketId == seeded }
        assertTrue("the seeded idle pooled socket was closed (not vacuous)", mine.isNotEmpty())
        assertEquals("no close of it on the caller thread", emptyList<String>(), mine.filter { it.thread == "net-sim" }.map { it.thread })
    }
}

/**
 * tth-1om0 ordering: with the eviction held back on its dispatcher the way a slow IO thread could, the
 * link opened on a return still cannot ride a connection made before the absence.
 */
class LinkReuseOrderTest {
    /** Holds every task until [release]; nothing runs on the caller. */
    private class Gate : kotlinx.coroutines.CoroutineDispatcher() {
        private val held = ConcurrentLinkedQueue<Runnable>()
        @Volatile private var open = false
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            if (open) Thread(block).start() else held.add(block)
        }
        fun release() {
            open = true
            while (true) Thread(held.poll() ?: break).start()
        }
    }

    private fun runOnce(): List<Int> {
        val h = ConnectionHarness()
        try {
            return runOnce(h)
        } finally {
            h.close()
        }
    }

    private fun runOnce(h: ConnectionHarness): List<Int> {
        val gate = Gate()
        h.ioDispatcher = gate
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        h.handshake(h.nextSocket())
        // An idle HTTP/1.1 connection of the link's own address (what the client's probe and upgrade
        // would pick up): made by a client with the same shape as the probe's, in the shared pool.
        h.server.enqueue(MockResponse().setResponseCode(200).setBody("""{"authenticated":true}"""))
        h.http.newBuilder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).followRedirects(false).followSslRedirects(false).build()
            .newCall(okhttp3.Request.Builder().url(h.server.url("/api/auth/session")).build()).execute().close()
        check(h.http.connectionPool.idleConnectionCount() > 0)
        h.client.setAppForeground(false)
        h.now.addAndGet(ConnectionTimings.BACKGROUND_WIRE_FRESH_MS)
        h.enqueueConnect()
        h.client.setAppForeground(true)
        // The eviction has NOT run (gated) and the reconnect completes anyway.
        h.handshake(h.nextSocket())
        assertTrue("the old connections were still pooled while the link opened", h.http.connectionPool.idleConnectionCount() > 0)
        gate.release()
        val seq = ArrayList<Int>()
        while (true) seq.add((h.server.takeRequest(100, TimeUnit.MILLISECONDS) ?: break).sequenceNumber)
        // [initial probe, initial upgrade, fetchStats, resume probe, resume upgrade]
        return seq
    }

    @Test
    fun theResumeProbeIsOnANewConnectionNotAPooledOne() {
        repeat(20) {
            val seq = runOnce()
            assertEquals("requests: $seq", 5, seq.size)
            assertEquals("the resume probe opened a new connection (sequence number 0): $seq", 0, seq[3])
            assertEquals("the upgrade followed on the probe's connection: $seq", 1, seq[4])
        }
    }
}

class LinkLogTest {
    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    @Test
    fun aFailureAndACloseEachEmitOneLineWithTheFieldsAndNoAddress() {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        h.now.addAndGet(5_000)
        h.client.serverPingForTest()
        h.now.addAndGet(250)
        ws.close(1001, "going away\nFAKE line é " + "x".repeat(90))
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        val closed = h.linkLog.toList()
        assertEquals("one line for the close: $closed", 1, closed.size)
        val line = closed.single()
        assertTrue(line, line.startsWith("socket-lost kind=closed ex=- code=1001 "))
        assertTrue(line, "age=5250 inbound=250 ping=250 fg=true probe=false" in line)
        assertTrue("no newline injected: $line", '\n' !in line)
        assertTrue("reason bounded: $line", Regex("reason=\"([^\"]*)\"").find(line)!!.groupValues[1].length <= 64)

        // A failure: the server drops the TCP connection under the next socket.
        h.enqueueConnect()
        h.scheduler.await { true }.fire()
        val next = h.nextSocket()
        h.handshake(next)
        h.server.shutdown()
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        val all = h.linkLog.toList()
        assertEquals("one more line for the failure: $all", 2, all.size)
        assertTrue(all[1], all[1].startsWith("socket-lost kind=failure ex="))
        for (l in all) {
            for (secret in listOf("127.0.0.1", "localhost", "cookie", "http")) {
                assertTrue("$secret in $l", secret !in l)
            }
            assertTrue(l, !Regex(":\\d{4,5}").containsMatchIn(l))
        }
    }

    /** tth-dywx fix round 1: a late callback of a replaced socket is marked stale and carries no current-socket age. */
    @Test
    fun aLateCallbackFromAReplacedSocketIsMarkedStaleAndCarriesNoCurrentAge() {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        h.enqueueConnect()
        h.client.onDefaultNetworkChanged()
        h.handshake(h.nextSocket())
        val deadline = System.currentTimeMillis() + 5_000
        while (h.linkLog.none { "stale=true" in it } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val lines = h.linkLog.toList()
        val stale = lines.firstOrNull { "stale=true" in it }
        assertTrue("a stale line is emitted: $lines", stale != null)
        assertTrue("no current-socket age on a stale line: $stale", "age=" !in stale!! && "inbound=" !in stale && "ping=" !in stale)
    }
}
