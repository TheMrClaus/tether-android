package com.tether.app.client

import com.tether.app.protocol.TetherJson
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * ta-2ew: two MockWebServer "Tethers" on different ports (= two origins) and one RealTetherClient
 * signed in to the first. Every credential here is obviously fake. [wrapSettings] may wrap its store
 * (e.g. one whose clear fails).
 */
internal class TwoOriginFixture(
    wrapSettings: (SettingsStore) -> SettingsStore = { it },
) : AutoCloseable {

    /** One fake Tether: every frame any of its sockets received (the ready's catalog read aside). */
    class FakeTether : AutoCloseable {
        val server = MockWebServer()
        val sockets = LinkedBlockingQueue<WebSocket>()
        private val serverSockets = CopyOnWriteArrayList<WebSocket>()
        val received = LinkedBlockingQueue<String>()
        val allFrames = CopyOnWriteArrayList<String>()

        private val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                serverSockets += webSocket
                sockets.put(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                allFrames += text
                if (!isReadyRead(text)) received.put(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/healthz" -> MockResponse().setResponseCode(200).setBody(HEALTH_143)
                    "/api/auth/login" -> MockResponse().setResponseCode(200).setBody("{}")
                        .addHeader("Set-Cookie", "tether_session=parity-fake-cookie-${server.port}; Path=/; HttpOnly")
                    "/api/auth/session" -> MockResponse().setResponseCode(200).setBody("""{"authenticated":true}""")
                    "/ws" -> MockResponse().withWebSocketUpgrade(listener)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
        }

        fun url(): String = server.url("/").toString().trimEnd('/')

        fun origin(): String = serverOrigin(url())!!

        fun nextSocket(): WebSocket {
            val ws = sockets.poll(15, TimeUnit.SECONDS)
            assertNotNull("the client never reached this server's ws upgrade", ws)
            return ws!!
        }

        fun frame(): JsonObject {
            val text = received.poll(10, TimeUnit.SECONDS)
            assertNotNull("expected a client frame at this server", text)
            return TetherJson.parseToJsonElement(text!!) as JsonObject
        }

        override fun close() {
            serverSockets.forEach { runCatching { it.close(1000, null) } }
            server.shutdown()
        }
    }

    val a = FakeTether()
    val b = FakeTether()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val now = AtomicLong(1_000_000)
    val client: RealTetherClient = RealTetherClient(
        settings = wrapSettings(InMemorySettings(initialBaseUrl = a.url(), initialCookie = "parity-fake-cookie-a")),
        httpClient = OkHttpClient(),
        scope = scope,
        clock = { now.get() },
        backoff = testBackoff(),
        sweepIntervalMs = 3_600_000,
        scheduler = ManualScheduler(),
    )

    fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(10_000) { flow.first(predicate) } }

    fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(message, condition())
    }

    /** ready (with a base Claude provider) -> hello, Connected, then this socket's catalog (one Claude row). */
    fun handshake(server: FakeTether, ws: WebSocket) {
        ws.send(READY)
        assertEquals("hello", server.frame().type())
        await(client.connection) { it == ConnectionState.Connected }
        ws.send("""{"type":"providers-snapshot","entries":[{"key":"claude","provider":"claude","status":"ready","label":"Claude","models":[]}]}""")
        await(client.providerCatalogLive) { it }
    }

    /** Started and connected to A. */
    fun connectedToA(): WebSocket {
        client.start()
        val ws = a.nextSocket()
        handshake(a, ws)
        return ws
    }

    fun loginTo(server: FakeTether) {
        assertEquals(LoginResult.Success, runBlocking { client.login(server.url(), "parity-fake-password") })
    }

    /** Everything the client sent [server] before a barrier frame (client-side wire order). */
    fun framesUntilBarrier(server: FakeTether): List<JsonObject> {
        client.pin("barrier-${System.nanoTime()}", true)
        val out = mutableListOf<JsonObject>()
        while (true) {
            val f = server.frame()
            if (f.type() == "pin" && f["sessionId"]!!.jsonPrimitive.content.startsWith("barrier-")) return out
            out += f
        }
    }

    /** Holds the first [point] until [release] (on whichever thread reaches it). */
    inner class Hold(private val point: RacePoint) {
        private val taken = java.util.concurrent.atomic.AtomicBoolean()
        private val reached = java.util.concurrent.CountDownLatch(1)
        private val go = java.util.concurrent.CountDownLatch(1)

        init {
            client.raceHook = { p, _ ->
                if (p == point && taken.compareAndSet(false, true)) {
                    reached.countDown()
                    go.await(20, TimeUnit.SECONDS)
                }
            }
        }

        fun awaitReached() = assertTrue("the race point was never reached", reached.await(10, TimeUnit.SECONDS))
        fun release() = go.countDown()
    }

    override fun close() {
        runCatching { client.stop() }
        scope.cancel()
        a.close()
        b.close()
    }

    companion object {
        val READY =
            """{"type":"ready","protocolVersion":${com.tether.app.protocol.PROTOCOL_VERSION},"nativeProtocolFloor":129,"sessions":[],""" +
                """"providers":[{"id":"claude","label":"Claude","glyph":"C","available":true}],"workspaceRoot":null}"""

        fun createdFrame(id: String, requestId: String?): String =
            """{"type":"created","session":{"id":"$id","provider":"claude","name":"n","cwd":"/w","status":"ready",""" +
                """"startedAt":1,"updatedAt":1,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"}""" +
                (if (requestId != null) ""","requestId":"$requestId"""" else "") + "}"
    }
}
