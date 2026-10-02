package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.TetherJson
import com.tether.app.protocol.model.OverviewFilters
import com.tether.app.protocol.overview.OverviewClient
import com.tether.app.protocol.overview.OverviewPhase
import com.tether.app.protocol.overview.OverviewSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * T15.1: the v131 Overview feed over a real socket — hooks/use-tether.ts 325-335, 802-807,
 * 1010-1017, 1212 and 1547-1564 as RealTetherClient + OverviewSync implement them — and the
 * subscription lifecycle ([OverviewFeedGate], overview.tsx 111-126): visible / hidden, ON_STOP,
 * reconnect, server switch, sign-out, and never a frame on an un-handshaken socket. The feed is
 * read-only: nothing but `overview-subscribe` / `overview-unsubscribe` ever goes out for it.
 */
class OverviewFeedTest {

    private val wire = File(System.getProperty("parity.corpus") ?: "../../parity-corpus", "wire")

    private fun example(type: String): JsonObject {
        val line = File(wire, "client-examples.jsonl").readLines().filter { it.isNotBlank() }
            .map { TetherJson.parseToJsonElement(it).jsonObject }
            .single { it["type"]!!.jsonPrimitive.content == type }
        assertEquals("true", line["verdict"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
        return line["frame"]!!.jsonObject
    }

    private val h = ConnectionHarness()

    @After fun tearDown() = h.close()

    private fun connected(): WebSocket {
        h.newClient()
        h.enqueueConnect()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        return ws
    }

    private val sub = OverviewClient.subscription(workspace = null, provider = null, statuses = null, page = 0)

    private fun card(id: String, title: String = id) =
        """{"sessionId":"$id","nodeId":"n","seq":1,"title":"$title","provider":"claude","providerLabel":"Claude Code",""" +
            """"workspace":{"key":"/w","label":"w"},"cwd":"/w","status":"running","pending":[]}"""

    private fun snapshot(feedId: String = "feed_1", cursor: Long = 1, cards: List<String> = listOf(card("a"), card("b"))) =
        """{"type":"overview-snapshot","feedId":"$feedId","cursor":$cursor,"generatedAt":100,"activitySince":50,""" +
            """"filters":{"workspaces":[],"providers":[],"statuses":["running","waiting","attention"]},""" +
            """"page":0,"pageSize":24,"pageCount":1,"totalCards":${cards.size},""" +
            """"counts":{"running":${cards.size},"waiting":0,"attention":0,"ready":0,"workspaces":1,"total":${cards.size}},""" +
            """"facets":{"workspaces":[],"providers":[]},"cards":[${cards.joinToString(",")}],""" +
            """"pending":{"items":[],"total":0,"outsideFilters":0},"activity":[]}"""

    private fun delta(cursor: Long, prevCursor: Long, feedId: String = "feed_1", upserts: List<String> = emptyList()) =
        """{"type":"overview-delta","feedId":"$feedId","cursor":$cursor,"prevCursor":$prevCursor,""" +
            """"upserts":[${upserts.joinToString(",")}],"removals":[],""" +
            """"counts":{"running":2,"waiting":0,"attention":0,"ready":0,"workspaces":1,"total":2},""" +
            """"pending":{"items":[],"total":0,"outsideFilters":0},"activity":[]}"""

    // ---- the frames -----------------------------------------------------------------------------

    @Test fun theSubscribeFramesReEncodeAsTheCorpusExamples() {
        val recorded = example("overview-subscribe")
        val filters = recorded["filters"]?.jsonObject
        fun list(key: String) = (filters?.get(key) as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }
        val frame = ClientMessage.OverviewSubscribe(
            filters = filters?.let { OverviewFilters(list("workspaces"), list("providers"), list("statuses")) },
            page = recorded["page"]?.jsonPrimitive?.content?.toInt(),
            pageSize = recorded["pageSize"]?.jsonPrimitive?.content?.toInt(),
        )
        assertEquals(recorded.toString(), frame.encode())
        assertEquals(example("overview-unsubscribe").toString(), ClientMessage.OverviewUnsubscribe.encode())
    }

    @Test fun theOverviewsSubscriptionIsTheWebsFrame() {
        // overview.tsx:64 subscriptionFor: `{filters:{}, page:0, pageSize:24}` with no filter chosen.
        assertEquals("""{"type":"overview-subscribe","filters":{},"page":0,"pageSize":24}""", OverviewSync.frameFor(sub).encode())
    }

    // ---- never on an un-handshaken socket ---------------------------------------------------------

    @Test fun aWishBeforeTheHandshakeIsSentOnlyAfterReady() {
        h.newClient()
        // No socket at all: recorded, nothing sent.
        assertFalse(h.client.subscribeOverview(sub))
        assertEquals(OverviewPhase.Idle, h.client.overview.value.phase)
        h.enqueueConnect()
        h.client.start()
        val ws = h.nextSocket()
        // The socket is open but `ready` has not come: still nothing (the web would have sent here).
        assertFalse(h.client.subscribeOverview(sub))
        ws.send(readyFrame())
        // hello is ALWAYS first; the standing wish follows the handshake.
        h.expectFrame("hello")
        assertEquals(OverviewSync.frameFor(sub).encode(), h.expectFrame("overview-subscribe").toString())
        h.await(h.client.overview) { it.phase == OverviewPhase.Loading }
        assertEquals(emptyList<JsonObject>(), h.framesUntilBarrier())
    }

    // ---- visible / hidden -------------------------------------------------------------------------

    @Test fun subscribeFoldsTheSnapshotAndDeltasAndUnsubscribeStopsTheFold() {
        val ws = connected()
        assertTrue(h.client.subscribeOverview(sub))
        h.expectFrame("overview-subscribe")
        assertEquals(OverviewPhase.Loading, h.client.overview.value.phase)
        ws.send(snapshot())
        val live = h.await(h.client.overview) { it.phase == OverviewPhase.Live }
        assertEquals(listOf("a", "b"), live.data!!.cards.map { it.sessionId })
        assertEquals(h.now.get(), live.updatedAt)
        ws.send(delta(2, 1, upserts = listOf(card("a", "A!"))))
        h.await(h.client.overview) { it.cursor == 2L }
        assertEquals("A!", h.client.overview.value.data!!.cards.first().title)

        h.client.unsubscribeOverview()
        assertEquals("""{"type":"overview-unsubscribe"}""", h.expectFrame("overview-unsubscribe").toString())
        assertEquals(OverviewPhase.Idle, h.client.overview.value.phase)
        // A frame still in flight from before the unsubscribe changes nothing (the data is kept).
        ws.send(delta(3, 2, upserts = listOf(card("a", "late"))))
        h.serverBarrier(ws)
        assertEquals("A!", h.client.overview.value.data!!.cards.first().title)
        // A second unsubscribe has no wish to drop: no frame.
        h.client.unsubscribeOverview()
        assertEquals(emptyList<JsonObject>(), h.framesUntilBarrier())
    }

    @Test fun aCursorGapResubscribesOnceAndWaitsForTheSnapshot() {
        val ws = connected()
        h.client.subscribeOverview(sub)
        h.expectFrame("overview-subscribe")
        ws.send(snapshot())
        h.await(h.client.overview) { it.phase == OverviewPhase.Live }
        ws.send(delta(5, 4))
        assertEquals(OverviewSync.frameFor(sub).encode(), h.expectFrame("overview-subscribe").toString())
        h.await(h.client.overview) { it.phase == OverviewPhase.Resyncing }
        // In-sequence-looking deltas before the snapshot: ignored, and no second resubscribe.
        ws.send(delta(6, 5))
        h.serverBarrier(ws)
        assertEquals(OverviewPhase.Resyncing, h.client.overview.value.phase)
        ws.send(snapshot(cursor = 9, cards = listOf(card("c"))))
        val fresh = h.await(h.client.overview) { it.phase == OverviewPhase.Live }
        assertEquals(9L, fresh.cursor)
        assertEquals(emptyList<JsonObject>(), h.framesUntilBarrier())
    }

    // ---- reconnect --------------------------------------------------------------------------------

    @Test fun aDroppedSocketKeepsTheDataStaleAndTheNextReadyResubscribes() {
        val ws = connected()
        h.client.subscribeOverview(sub)
        h.expectFrame("overview-subscribe")
        ws.send(snapshot())
        h.await(h.client.overview) { it.phase == OverviewPhase.Live }

        ws.close(1001, null)
        val offline = h.await(h.client.overview) { it.phase == OverviewPhase.Offline }
        assertEquals("the last data stays on screen", listOf("a", "b"), offline.data!!.cards.map { it.sessionId })
        // Deltas are never trusted across the reconnect.
        assertTrue(offline.awaitingSnapshot)

        h.enqueueConnect()
        h.scheduler.await(::isReconnectDelay).fire()
        val next = h.nextSocket()
        next.send(readyFrame())
        h.expectFrame("hello")
        assertEquals(OverviewSync.frameFor(sub).encode(), h.expectFrame("overview-subscribe").toString())
        val resyncing = h.await(h.client.overview) { it.phase == OverviewPhase.Resyncing }
        assertEquals(listOf("a", "b"), resyncing.data!!.cards.map { it.sessionId })
        // The old feed's delta (a restart would also change feedId) is ignored until the snapshot.
        next.send(delta(2, 1))
        h.serverBarrier(next)
        assertEquals(OverviewPhase.Resyncing, h.client.overview.value.phase)
        next.send(snapshot(feedId = "feed_2", cursor = 1))
        assertEquals("feed_2", h.await(h.client.overview) { it.phase == OverviewPhase.Live }.feedId)
    }

    @Test fun aDropWithNoWishResubscribesNothing() {
        val ws = connected()
        h.client.subscribeOverview(sub)
        h.expectFrame("overview-subscribe")
        h.client.unsubscribeOverview()
        h.expectFrame("overview-unsubscribe")
        ws.close(1001, null)
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        assertEquals(OverviewPhase.Idle, h.client.overview.value.phase)
        h.enqueueConnect()
        h.scheduler.await(::isReconnectDelay).fire()
        h.handshake(h.nextSocket())
        assertEquals(emptyList<JsonObject>(), h.framesUntilBarrier())
    }

    // ---- ON_STOP / background ---------------------------------------------------------------------

    @Test fun theGateSubscribesOnlyWhileVisibleAndStarted() {
        val ws = connected()
        val gate = OverviewFeedGate(h.client)
        val server = h.client.serverUrl.value
        // Composed but the app is stopped: nothing.
        gate.update(visible = true, started = false, subscription = sub, server = server)
        assertFalse(gate.subscribed)
        // ON_START: one subscribe; the same inputs again send nothing more.
        gate.update(visible = true, started = true, subscription = sub, server = server)
        gate.update(visible = true, started = true, subscription = sub, server = server)
        h.expectFrame("overview-subscribe")
        ws.send(snapshot())
        h.await(h.client.overview) { it.phase == OverviewPhase.Live }
        // A page change re-sends (it replaces the subscription).
        val page1 = OverviewClient.subscription(null, null, null, page = 1)
        gate.update(visible = true, started = true, subscription = page1, server = server)
        assertEquals(1, h.expectFrame("overview-subscribe")["page"]!!.jsonPrimitive.content.toInt())
        // ON_STOP: one unsubscribe, and the fold stops.
        gate.update(visible = true, started = false, subscription = page1, server = server)
        h.expectFrame("overview-unsubscribe")
        assertEquals(OverviewPhase.Idle, h.client.overview.value.phase)
        // ON_START again: a fresh snapshot is asked for (plan §5.7), the kept data shows meanwhile.
        gate.update(visible = true, started = true, subscription = page1, server = server)
        h.expectFrame("overview-subscribe")
        assertEquals(OverviewPhase.Resyncing, h.client.overview.value.phase)
        // Leaving the Overview: one unsubscribe; leaving twice sends nothing.
        gate.update(visible = false, started = true, subscription = page1, server = server)
        gate.leave()
        h.expectFrame("overview-unsubscribe")
        assertEquals(emptyList<JsonObject>(), h.framesUntilBarrier())
    }

    @Test fun theBackgroundGraceDropsTheSocketAndForegroundResubscribes() {
        connected()
        h.client.subscribeOverview(sub)
        h.expectFrame("overview-subscribe")
        h.client.setAppForeground(false)
        h.scheduler.await { it == ConnectionTimings.BACKGROUND_GRACE_MS }.fire()
        h.await(h.client.overview) { it.phase == OverviewPhase.Offline }
        h.enqueueConnect()
        h.client.setAppForeground(true)
        val ws = h.nextSocket()
        ws.send(readyFrame())
        h.expectFrame("hello")
        h.expectFrame("overview-subscribe")
    }

    // ---- server switch / sign-out ------------------------------------------------------------------

    @Test fun signingOutEmptiesTheOverviewAndDropsTheWish() {
        val ws = connected()
        h.client.subscribeOverview(sub)
        h.expectFrame("overview-subscribe")
        ws.send(snapshot())
        h.await(h.client.overview) { it.phase == OverviewPhase.Live }
        runBlocking { h.client.logout() }
        val cleared = h.await(h.client.overview) { it.data == null }
        assertEquals(OverviewClient.initial(), cleared)
    }

    /** A second fake Tether on another port = another origin. */
    private class OtherTether : AutoCloseable {
        val server = MockWebServer()
        val sockets = LinkedBlockingQueue<WebSocket>()
        val received = LinkedBlockingQueue<String>()
        private val listener = object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) { sockets.put(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) { if (text != READY_CATALOG_REQUEST) received.put(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/healthz" -> MockResponse().setResponseCode(200).setBody(HEALTH_137)
                    "/api/auth/login" -> MockResponse().setResponseCode(200).setBody("{}")
                        .addHeader("Set-Cookie", "tether_session=parity-fake-cookie-b; Path=/; HttpOnly")
                    "/api/auth/session" -> MockResponse().setResponseCode(200).setBody("""{"authenticated":true}""")
                    "/ws" -> MockResponse().withWebSocketUpgrade(listener)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
        }
        fun url(): String = server.url("/").toString().trimEnd('/')
        fun frame(): JsonObject = TetherJson.parseToJsonElement(received.poll(20, TimeUnit.SECONDS)!!) as JsonObject
        override fun close() = server.shutdown()
    }

    @Test fun aSwitchToAnotherServerNeverShowsTheFirstServersOverviewOrCarriesItsWish() {
        val ws = connected()
        h.client.subscribeOverview(sub)
        h.expectFrame("overview-subscribe")
        ws.send(snapshot())
        h.await(h.client.overview) { it.phase == OverviewPhase.Live }
        OtherTether().use { b ->
            assertEquals(LoginResult.Success, runBlocking { h.client.login(b.url(), "parity-fake-password") })
            assertNull("the first server's overview is gone", h.client.overview.value.data)
            assertEquals(OverviewPhase.Idle, h.client.overview.value.phase)
            val bws = b.sockets.poll(20, TimeUnit.SECONDS)!!
            bws.send(readyFrame())
            assertEquals("hello", b.frame()["type"]!!.jsonPrimitive.content)
            h.await(h.client.connection) { it == ConnectionState.Connected }
            // No wish came along: B is asked for nothing until the screen subscribes there.
            h.client.pin("barrier-b", true)
            assertEquals("pin", b.frame()["type"]!!.jsonPrimitive.content)
            // A frame B pushes unasked is not folded (Idle).
            bws.send(snapshot(feedId = "feed_b"))
            bws.send("""{"type":"pong"}""")
            Thread.sleep(50)
            assertNull(h.client.overview.value.data)
            // The gate keyed on the server subscribes afresh on B.
            val gate = OverviewFeedGate(h.client)
            gate.update(visible = true, started = true, subscription = sub, server = b.url())
            assertEquals("overview-subscribe", b.frame()["type"]!!.jsonPrimitive.content)
        }
    }

    // ---- OverviewSync alone ------------------------------------------------------------------------

    @Test fun onlyTheTwoOverviewFramesEverLeaveTheSync() {
        val sent = mutableListOf<ClientMessage>()
        val sync = OverviewSync()
        val send: (ClientMessage) -> Boolean = { sent += it; true }
        sync.subscribe(sub, send)
        sync.onFrame(ServerMessage.parse(snapshot()), 1, send)
        sync.onFrame(ServerMessage.parse(delta(7, 3)), 2, send) // gap -> resubscribe
        sync.onReady(send)
        sync.unsubscribe(send)
        sync.onReady(send) // no wish: nothing
        assertTrue(sent.all { it is ClientMessage.OverviewSubscribe || it is ClientMessage.OverviewUnsubscribe })
        assertEquals(listOf("overview-subscribe", "overview-subscribe", "overview-subscribe", "overview-unsubscribe"), sent.map { it.toJsonObject()["type"]!!.jsonPrimitive.content })
    }

    @Test fun anUnsentResubscribeLeavesTheStateOutOfStepButNotRequested() {
        val sync = OverviewSync()
        sync.subscribe(sub) { true }
        sync.onFrame(ServerMessage.parse(snapshot()), 1) { true }
        // The socket went between the frame and the resend: the fold still marks it resyncing.
        sync.onFrame(ServerMessage.parse(delta(7, 3)), 2) { false }
        assertTrue(sync.state.value.awaitingSnapshot)
        assertEquals(OverviewPhase.Resyncing, sync.state.value.phase)
        assertEquals(sub, sync.wish)
    }
}
