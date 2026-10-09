package com.tether.app.client

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.WebSocket
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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
        h.now.addAndGet(ConnectionTimings.BACKGROUND_WIRE_FRESH_MS)
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
    fun eachTripIsTimedOnItsOwnSoAShortOneAfterALongOneIsStillPinged() {
        connected()
        h.client.setAppForeground(false)
        h.now.addAndGet(ConnectionTimings.BACKGROUND_WIRE_FRESH_MS)
        h.enqueueConnect()
        h.client.setAppForeground(true)
        h.handshake(h.nextSocket())
        // Back in front for a while, then a short trip: only that trip counts.
        h.now.addAndGet(ConnectionTimings.BACKGROUND_REPLACE_AFTER_MS)
        h.client.setAppForeground(false)
        h.now.addAndGet(1_000)
        h.client.setAppForeground(true)
        assertEquals("ping", h.expectFrame("ping").type())
        assertEquals("two connects only: the short trip opened none", 4, h.server.requestCount)
    }

    // ------------------------------------------------------------------
    // ta-nl5m (C1): a live socket is kept on a return, a frozen one is replaced
    // ------------------------------------------------------------------

    @Test
    fun theResumeConstantsAreThePlannedOnes() {
        assertEquals(35_000L, ConnectionTimings.BACKGROUND_WIRE_FRESH_MS)
        assertEquals(3_000L, ConnectionTimings.RESUME_PING_TIMEOUT_MS)
        assertEquals(180_000L, ConnectionTimings.BACKGROUND_GRACE_MS)
    }

    @Test
    fun aRecentServerPingKeepsTheSocketAndVerifiesItWithAShortPing() {
        val ws = connected()
        val requests = h.server.requestCount
        h.client.setAppForeground(false)
        h.now.addAndGet(20_000)
        // The server's heartbeat reached the thawed process: no frame, only a WebSocket ping.
        h.client.serverPingForTest()
        h.now.addAndGet(20_000)
        h.client.setAppForeground(true)
        assertEquals("ping", h.expectFrame("ping").type())
        h.scheduler.await { it == ConnectionTimings.RESUME_PING_TIMEOUT_MS }
        assertTrue("the web's 8 s is not the deadline here", h.scheduler.pending().none { it.delayMs == ConnectionTimings.PING_TIMEOUT_MS })
        ws.send("""{"type":"pong"}""")
        h.await(h.client.connection) { it == ConnectionState.Connected }
        assertEquals("the socket was kept: no new connect", requests, h.server.requestCount)
        assertTrue("nothing missed on a live socket, nothing re-attached", h.received.none { it.contains("\"attach\"") })
        assertTrue("no reconnect timer", h.scheduler.history().none { isReconnectDelay(it.delayMs) })
    }

    @Test
    fun aSocketThatHeardFromTheServerJustInsideTheFreshWindowIsKept() {
        connected()
        h.client.setAppForeground(false)
        h.now.addAndGet(ConnectionTimings.BACKGROUND_WIRE_FRESH_MS - 1)
        h.client.setAppForeground(true)
        assertEquals("ping", h.expectFrame("ping").type())
        assertEquals("kept: only the first probe and upgrade", 2, h.server.requestCount)
    }

    @Test
    fun aSocketThatHeardNothingForTheFreshWindowIsReplacedEvenIfAPingIsAskedFor() {
        connected()
        h.client.setAppForeground(false)
        h.now.addAndGet(ConnectionTimings.BACKGROUND_WIRE_FRESH_MS)
        h.enqueueConnect()
        h.client.setAppForeground(true)
        h.handshake(h.nextSocket())
        assertEquals("replaced: a second probe and upgrade", 4, h.server.requestCount)
        assertTrue("no resume ping wait", h.scheduler.history().none { it.delayMs == ConnectionTimings.RESUME_PING_TIMEOUT_MS })
    }

    @Test
    fun aServerFrameCountsAsFreshAsAPingDoes() {
        val ws = connected()
        h.client.setAppForeground(false)
        h.now.addAndGet(20_000)
        ws.send("""{"type":"pong"}""")
        h.await(h.client.connection) { it == ConnectionState.Connected }
        awaitFrameStamp()
        h.now.addAndGet(20_000)
        h.client.setAppForeground(true)
        assertEquals("ping", h.expectFrame("ping").type())
        assertEquals("kept", 2, h.server.requestCount)
    }

    /** Lets the client thread take the frame just sent (the clock is manual, so poll the effect). */
    private fun awaitFrameStamp() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && h.client.lastServerFrameAtForTest() < h.now.get()) Thread.sleep(5)
    }

    @Test
    fun aKeptSocketThatDoesNotAnswerTheShortPingIsReplacedAtOnce() {
        connected()
        h.client.setAppForeground(false)
        h.now.addAndGet(ConnectionTimings.BACKGROUND_REPLACE_AFTER_MS)
        h.client.setAppForeground(true)
        h.expectFrame("ping")
        val deadline = h.scheduler.await { it == ConnectionTimings.RESUME_PING_TIMEOUT_MS }
        h.now.addAndGet(ConnectionTimings.RESUME_PING_TIMEOUT_MS)
        h.enqueueConnect()
        deadline.fire()
        // A link that worked is replaced with no backoff wait.
        val retry = h.scheduler.await(::isReconnectDelay)
        assertEquals("no wait before the new attempt", 0L, retry.delayMs)
        retry.fire()
        h.handshake(h.nextSocket())
        assertTrue("no backoff", h.scheduler.history().none { isReconnectDelay(it.delayMs) && it.delayMs != 0L })
    }

    @Test
    fun aLongProbePendingFromBeforeTheTripDoesNotSwallowTheResumeProbe() {
        connected()
        h.client.reconnectIfIdle()
        h.expectFrame("ping")
        val long = h.scheduler.await { it == ConnectionTimings.PING_TIMEOUT_MS }
        h.client.setAppForeground(false)
        h.now.addAndGet(ConnectionTimings.BACKGROUND_REPLACE_AFTER_MS)
        h.client.setAppForeground(true)
        assertEquals("a second ping went out", "ping", h.expectFrame("ping").type())
        assertTrue("the 8 s probe was superseded", long.cancelled)
        h.scheduler.await { it == ConnectionTimings.RESUME_PING_TIMEOUT_MS }
    }

    // ------------------------------------------------------------------
    // ta-nl5m (C3): the 3-minute grace
    // ------------------------------------------------------------------

    @Test
    fun aSocketLostInsideTheGraceRetriesWithBackoffAndStopsWhenTheGraceEnds() {
        val ws = connected()
        h.client.setAppForeground(false)
        val grace = h.scheduler.await { it == ConnectionTimings.BACKGROUND_GRACE_MS }
        assertEquals(180_000L, grace.delayMs)
        lose(ws)
        // In the background the loss backs off (no immediate retry), and the retry still happens.
        val first = h.scheduler.await(::isReconnectDelay)
        assertEquals(550L, first.delayMs)
        h.enqueueAuthFailure()
        first.fire()
        val second = h.scheduler.await { isReconnectDelay(it) && it != 550L }
        assertEquals("a longer wait after a failed retry", 1100L, second.delayMs)

        // The grace ends: no retry is pending any more, and nothing goes out.
        grace.fire()
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        assertTrue("the pending retry was cancelled", second.cancelled)
        assertTrue(h.scheduler.pending().none { isReconnectDelay(it.delayMs) })
        val requests = h.server.requestCount
        Thread.sleep(100)
        assertEquals("no background loop after the suspend", requests, h.server.requestCount)
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
        """{"type":"ready","protocolVersion":143,"nativeProtocolFloor":129,"sessions":[${ids.joinToString(",") { sessionJson(it) }}],""" +
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
        assertTrue("TetherTiming says when the open chat was in", h.timing.any { it.endsWith(" open-chat-snapshot") })
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

    /** Verifier's reproduction (r1 REFUTED): a chat never opened before, opened during the wait. */
    @Test
    fun aChatFirstOpenedDuringTheWaitIsTheOneTheServerEndsUpWatching() {
        val ws = connectedListing("s1", "s2", "s3")
        open(ws, "s1", "s2")
        val ws2 = reconnect(ws, readyListing("s1", "s2", "s3"))
        assertEquals(listOf("s2" to 1L), attaches(h.framesUntilBarrier()))
        h.now.addAndGet(1)
        h.client.attach("s3") // never opened in this process: attached at once, in full
        assertEquals(listOf("s3" to null), attaches(h.framesUntilBarrier()))
        ws2.send(snapshotFrame("s2", 1, state = null))
        h.serverBarrier(ws2)
        // The chat left behind is not attached again: the last attach is still the open chat's.
        assertEquals(emptyList<Pair<String, Long?>>(), attaches(h.framesUntilBarrier()))
        ws2.send(snapshotFrame("s3", 1))
        h.serverBarrier(ws2)
        val rest = attaches(h.framesUntilBarrier())
        assertEquals(listOf("s1" to 1L, "s3" to 1L), rest)
        assertEquals("the server watches the chat on screen", "s3", rest.last().first)
    }

    @Test
    fun afterTheWaitTimesOutTheOpenChatIsAttachedAgainOnceItsSnapshotIsIn() {
        val ws = connectedListing("s1", "s2", "s3")
        open(ws, "s1", "s2")
        val ws2 = reconnect(ws, readyListing("s1", "s2", "s3"))
        assertEquals(listOf("s2" to 1L), attaches(h.framesUntilBarrier()))
        h.client.attach("s3")
        assertEquals(listOf("s3" to null), attaches(h.framesUntilBarrier()))
        h.scheduler.await { it == ConnectionTimings.DEFERRED_ATTACH_MAX_WAIT_MS }.fire()
        assertEquals("the rest, without a second fetch of s3", listOf("s1" to 1L), attaches(h.framesUntilBarrier()))
        ws2.send(snapshotFrame("s3", 1))
        h.serverBarrier(ws2)
        assertEquals("then s3 again, from its new cursor", listOf("s3" to 1L), attaches(h.framesUntilBarrier()))
    }

    @Test
    fun afterTheWaitTimesOutOpeningAnotherChatDropsTheRewatchOfTheOneLeft() {
        val ws = connectedListing("s1", "s2", "s3")
        open(ws, "s1", "s2")
        val ws2 = reconnect(ws, readyListing("s1", "s2", "s3"))
        assertEquals(listOf("s2" to 1L), attaches(h.framesUntilBarrier()))
        h.scheduler.await { it == ConnectionTimings.DEFERRED_ATTACH_MAX_WAIT_MS }.fire()
        assertEquals(listOf("s1" to 1L), attaches(h.framesUntilBarrier()))
        h.client.attach("s3")
        assertEquals(listOf("s3" to null), attaches(h.framesUntilBarrier()))
        ws2.send(snapshotFrame("s2", 1, state = null))
        h.serverBarrier(ws2)
        assertEquals("s2 is not on screen any more", emptyList<Pair<String, Long?>>(), attaches(h.framesUntilBarrier()))
    }

    @Test
    fun openingTheChatAlreadyInFocusChangesNothing() {
        val ws = connectedListing("s1", "s2")
        open(ws, "s1", "s2")
        val ws2 = reconnect(ws, readyListing("s1", "s2"))
        assertEquals(listOf("s2" to 1L), attaches(h.framesUntilBarrier()))
        h.client.attach("s2")
        assertEquals("attached already", emptyList<Pair<String, Long?>>(), attaches(h.framesUntilBarrier()))
        ws2.send(snapshotFrame("s2", 1, state = null))
        h.serverBarrier(ws2)
        assertEquals(listOf("s1" to 1L, "s2" to 1L), attaches(h.framesUntilBarrier()))
    }

    @Test
    fun openingAnAttachedChatWhoseSnapshotIsInReleasesTheRestAtOnce() {
        val ws = connectedListing("s1", "s2", "s3")
        open(ws, "s1", "s3", "s2")
        h.client.send("s1", "keep me")
        h.expectFrame("send")
        val ws2 = reconnect(ws, readyListing("s1", "s2", "s3"))
        assertEquals(listOf("s2" to 1L, "s1" to null), attaches(h.framesUntilBarrier()))
        ws2.send(snapshotFrame("s1", 1))
        h.serverBarrier(ws2)
        h.framesUntilBarrier() // s1's redelivery
        h.client.attach("s1") // attached with s2, its snapshot in: nothing left to wait for
        assertEquals(listOf("s3" to 1L, "s1" to 1L), attaches(h.framesUntilBarrier()))
    }

    @Test
    fun anOpenChatTheServerNoLongerListsChangesNothing() {
        val ws = connectedListing("s1", "s2")
        open(ws, "s1", "s2")
        reconnect(ws, readyListing("s1"))
        assertEquals("every subscription at once, in order", listOf("s1" to 1L, "s2" to 1L), attaches(h.framesUntilBarrier()))
        assertTrue(h.scheduler.pending().none { it.delayMs == ConnectionTimings.DEFERRED_ATTACH_MAX_WAIT_MS })
    }

    // ------------------------------------------------------------------
    // R4: nothing is reused from the previous network
    // ------------------------------------------------------------------

    @Test
    fun aDefaultNetworkChangeEvictsThePooledConnections() {
        connected()
        // An ordinary HTTP read leaves its connection idle in the pool.
        h.server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("{}"))
        kotlinx.coroutines.runBlocking { h.client.fetchStats() }
        assertTrue("a connection is idle in the pool", h.http.connectionPool.idleConnectionCount() > 0)
        // Suspended in the background, so nothing reconnects and refills the pool meanwhile.
        h.client.setAppForeground(false)
        h.scheduler.await { it == ConnectionTimings.BACKGROUND_GRACE_MS }.fire()
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        h.client.onDefaultNetworkChanged()
        assertEquals("nothing made on the previous network is reused", 0, h.http.connectionPool.idleConnectionCount())
    }

    @Test
    fun theUpgradeReusesTheConnectionItsProbeJustMade() {
        connected()
        val probe = h.server.takeRequest()
        val upgrade = h.server.takeRequest()
        assertEquals("/api/auth/session", probe.path)
        assertEquals("/ws", upgrade.path)
        assertEquals("the probe opened the connection", 0, probe.sequenceNumber)
        assertEquals("the upgrade is its second request: no second TCP + TLS handshake", 1, upgrade.sequenceNumber)
    }

    // ------------------------------------------------------------------
    // A network change ends the connect in flight on the previous network
    // ------------------------------------------------------------------

    /** Serves by path: the first probe with [firstProbe], later ones ok; the first upgrade with [firstUpgrade]. */
    private fun serveByPath(firstProbe: MockResponse, firstUpgrade: MockResponse = h.upgradeResponse()) {
        val probes = java.util.concurrent.atomic.AtomicInteger()
        val upgrades = java.util.concurrent.atomic.AtomicInteger()
        h.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/auth/session" ->
                    if (probes.getAndIncrement() == 0) firstProbe else MockResponse().setBody("""{"authenticated":true}""")
                "/ws" -> if (upgrades.getAndIncrement() == 0) firstUpgrade else h.upgradeResponse()
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun probesRunning() = h.http.dispatcher.runningCalls().count { it.request().url.encodedPath == "/api/auth/session" }

    @Test
    fun aNetworkChangeDuringTheProbeCancelsItAndItsLateVerdictDoesNothing() {
        val lateMs = 2_000L
        // The first probe answers late, and says the credential is no good.
        serveByPath(MockResponse().setBody("""{"authenticated":false}""").setHeadersDelay(lateMs, TimeUnit.MILLISECONDS))
        h.newClient()
        h.client.start()
        assertEquals("/api/auth/session", h.server.takeRequest(10, TimeUnit.SECONDS)?.path)
        val changedAt = System.nanoTime()
        h.client.onDefaultNetworkChanged()
        h.handshake(h.nextSocket())
        // The abandoned probe is cancelled, not left to run out on the previous network.
        while (probesRunning() > 0 && System.nanoTime() - changedAt < TimeUnit.MILLISECONDS.toNanos(lateMs / 2)) Thread.sleep(5)
        assertEquals("the abandoned probe was cancelled", 0, probesRunning())
        // Past the moment its verdict would have come: nothing acted on it.
        Thread.sleep(lateMs + 500)
        assertEquals(ConnectionState.Connected, h.client.connection.value)
        assertTrue("the credential is kept", h.client.configured.value)
        assertEquals(null, h.client.signedOutReason.value)
        assertEquals("one socket only", 0, h.sockets.size)
        assertTrue("an abandoned probe is not a failure: ${h.timing}", h.timing.none { it.endsWith(" probe-failed") })
    }

    @Test
    fun aNetworkChangeDuringTheUpgradeAbandonsItAndConnectsAgainAtOnce() {
        // The first upgrade never answers (written into a network that is gone).
        serveByPath(MockResponse().setBody("""{"authenticated":true}"""), MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        h.newClient()
        h.client.start()
        assertEquals("/api/auth/session", h.server.takeRequest(10, TimeUnit.SECONDS)?.path)
        assertEquals("/ws", h.server.takeRequest(10, TimeUnit.SECONDS)?.path)
        h.client.onDefaultNetworkChanged()
        h.handshake(h.nextSocket())
        assertTrue("no reconnect timer", h.scheduler.history().none { isReconnectDelay(it.delayMs) })
    }

    @Test
    fun lateFramesOfAReplacedSocketAreIgnored() {
        val old = connectedListing("s1")
        h.enqueueConnect()
        h.client.onDefaultNetworkChanged()
        val next = h.nextSocket()
        h.handshake(next, readyListing("s2"))
        old.send(readyListing("ghost"))
        old.send(snapshotFrame("ghost", 1))
        h.serverBarrier(next)
        assertEquals(listOf("s2"), h.client.sessions.value.map { it.id }.filter { !it.startsWith("barrier-") })
        assertFalse(h.client.projections.value.containsKey("ghost"))
        assertEquals(ConnectionState.Connected, h.client.connection.value)
    }
}
