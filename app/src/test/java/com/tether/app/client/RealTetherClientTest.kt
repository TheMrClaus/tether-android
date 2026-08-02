package com.tether.app.client

import com.tether.app.protocol.TetherJson
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end-ish: login -> ws upgrade (Cookie + Origin) -> ready -> attach ->
 * snapshot -> live event fold, against a MockWebServer.
 */
class RealTetherClientTest {

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val serverSockets = LinkedBlockingQueue<WebSocket>()
    private val serverReceived = LinkedBlockingQueue<String>()
    private lateinit var client: RealTetherClient

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            serverSockets.put(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            serverReceived.put(text)
        }
    }

    @After
    fun tearDown() {
        if (::client.isInitialized) client.stop()
        scope.cancel()
        server.shutdown()
    }

    private fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(10_000) { flow.first(predicate) } }

    private fun nextFrame(): JsonObject {
        val text = serverReceived.poll(10, TimeUnit.SECONDS)
        assertNotNull("expected a client frame", text)
        return TetherJson.parseToJsonElement(text!!) as JsonObject
    }

    @Test
    fun loginConnectAttachSnapshotAndEventFold() {
        // login(): healthz, then the password POST.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"ok":true,"uptimeMs":1,"sessions":0,"outstandingBackground":0,"protocolVersion":40}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Set-Cookie", "tether_session=cookie-value-1; Path=/; HttpOnly; SameSite=Strict")
                .setBody("""{"ok":true}"""),
        )
        // connect(): auth probe, then the WS upgrade.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"authenticated":true}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(wsListener))
        server.start()

        client = RealTetherClient(
            settings = InMemorySettings(),
            httpClient = OkHttpClient(),
            scope = scope,
        )

        val result = runBlocking { client.login(server.url("/").toString(), "tether") }
        assertEquals(LoginResult.Success, result)
        await(client.configured) { it }

        val serverSocket = serverSockets.poll(10, TimeUnit.SECONDS)
        assertNotNull("client never reached the ws upgrade", serverSocket)

        // The upgrade request MUST carry the cookie and a matching Origin.
        server.takeRequest() // healthz
        server.takeRequest() // login
        server.takeRequest() // auth probe
        val upgrade = server.takeRequest()
        assertEquals("/ws", upgrade.path)
        assertTrue(upgrade.getHeader("Cookie")!!.contains("tether_session=cookie-value-1"))
        val origin = upgrade.getHeader("Origin")
        assertEquals("http://${upgrade.getHeader("Host")}", origin)

        // First frame: ready (workspaceRoot null so no browse/discover follow).
        serverSocket!!.send(
            """
            {"type":"ready","protocolVersion":40,
             "sessions":[{"id":"s1","provider":"claude","name":"session one","cwd":"/w","status":"ready","startedAt":1,"updatedAt":10,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"}],
             "providers":[{"id":"claude","label":"Claude","glyph":"C","available":true}],
             "workspaceRoot":null}
            """.trimIndent(),
        )
        await(client.connection) { it == ConnectionState.Connected }
        assertEquals("s1", await(client.sessions) { it.isNotEmpty() }.single().id)

        // Attach -> the server sees the attach frame and replies with a snapshot.
        client.attach("s1")
        val attach = nextFrame()
        assertEquals("attach", attach["type"]!!.jsonPrimitive.content)
        assertEquals("s1", attach["sessionId"]!!.jsonPrimitive.content)
        serverSocket.send(
            """
            {"type":"snapshot","sessionId":"s1","throughSeq":1,
             "state":{"tetherSessionId":"s1","provider":"claude","cwd":"/w","status":"ready",
                      "turnOrder":[],"turnsById":{},"activeTurnId":null,"queuedMessages":[]}}
            """.trimIndent(),
        )
        await(client.projections) { it.containsKey("s1") }

        // Durable send: the frame carries a client-minted idempotencyKey.
        client.send("s1", "hello over the wire")
        val send = nextFrame()
        assertEquals("send", send["type"]!!.jsonPrimitive.content)
        assertEquals("hello over the wire", send["text"]!!.jsonPrimitive.content)
        val key = send["idempotencyKey"]!!.jsonPrimitive.content
        assertTrue(key.isNotEmpty())

        // Live ack + fold: turn_started (seq 2) then a message delta (seq 3).
        serverSocket.send(
            """{"type":"event","sessionId":"s1","event":{"type":"turn_started","turnId":"t1","idempotencyKey":"$key","seq":2,"ts":1000}}""",
        )
        serverSocket.send(
            """{"type":"event","sessionId":"s1","event":{"type":"message_delta","turnId":"t1","blockId":"m1:t0","text":"Hi.","seq":3,"ts":1100}}""",
        )
        val projection = await(client.projections) {
            it["s1"]?.turnsById?.get("t1")?.blocksById?.get("m1:t0")?.text == "Hi."
        }.getValue("s1")
        assertEquals("t1", projection.activeTurnId)
        assertEquals(key, projection.turnsById.getValue("t1").idempotencyKey)

        // A duplicate seq is dropped, not double-folded.
        serverSocket.send(
            """{"type":"event","sessionId":"s1","event":{"type":"message_delta","turnId":"t1","blockId":"m1:t0","text":"DUPLICATE","seq":3,"ts":1100}}""",
        )
        serverSocket.send(
            """{"type":"event","sessionId":"s1","event":{"type":"message_delta","turnId":"t1","blockId":"m1:t0","text":" Bye.","seq":4,"ts":1200}}""",
        )
        val text = await(client.projections) {
            it["s1"]?.turnsById?.get("t1")?.blocksById?.get("m1:t0")?.text?.endsWith("Bye.") == true
        }.getValue("s1").turnsById.getValue("t1").blocksById.getValue("m1:t0").text
        assertEquals("Hi. Bye.", text)

        // Events for sessions with no cursor are ignored entirely.
        serverSocket.send(
            """{"type":"event","sessionId":"other","event":{"type":"turn_started","turnId":"tx","seq":1,"ts":1}}""",
        )
        // Server error frames surface as toasts.
        val errors = LinkedBlockingQueue<String>()
        val errorJob = scope.launchCollect(client) { errors.put(it) }
        serverSocket.send("""{"type":"error","message":"That session no longer exists."}""")
        assertEquals("That session no longer exists.", errors.poll(10, TimeUnit.SECONDS))
        errorJob.cancel()
        assertTrue(client.projections.value.keys == setOf("s1"))
    }

    @Test
    fun gapTriggersExactlyOneReAttachAndSnapshotHeals() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"ok":true,"protocolVersion":40}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Set-Cookie", "tether_session=c2; Path=/")
                .setBody("""{"ok":true}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"authenticated":true}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(wsListener))
        server.start()

        client = RealTetherClient(settings = InMemorySettings(), httpClient = OkHttpClient(), scope = scope)
        assertEquals(LoginResult.Success, runBlocking { client.login(server.url("/").toString(), "pw") })
        val serverSocket = serverSockets.poll(10, TimeUnit.SECONDS)!!
        serverSocket.send("""{"type":"ready","protocolVersion":40,"sessions":[],"providers":[],"workspaceRoot":null}""")
        await(client.connection) { it == ConnectionState.Connected }

        client.attach("s1")
        nextFrame() // the attach
        serverSocket.send(
            """{"type":"snapshot","sessionId":"s1","throughSeq":5,
                "state":{"tetherSessionId":"s1","provider":"claude","cwd":"/w"}}""",
        )
        await(client.projections) { it.containsKey("s1") }

        // seq 8 gaps past cursor 5: exactly ONE re-attach with afterSeq=5.
        serverSocket.send(
            """{"type":"event","sessionId":"s1","event":{"type":"turn_started","turnId":"t9","seq":8,"ts":1}}""",
        )
        serverSocket.send(
            """{"type":"event","sessionId":"s1","event":{"type":"turn_started","turnId":"t9","seq":9,"ts":2}}""",
        )
        val resync = nextFrame()
        assertEquals("attach", resync["type"]!!.jsonPrimitive.content)
        assertEquals(5L, resync["afterSeq"]!!.jsonPrimitive.content.toLong())
        assertTrue("no second re-attach for the same gap", serverReceived.poll(500, TimeUnit.MILLISECONDS) == null)
        // The gapped events were NOT folded.
        assertTrue(client.projections.value.getValue("s1").turnsById.isEmpty())

        // Snapshot heals: cursor jumps to throughSeq and the state is replaced wholesale.
        serverSocket.send(
            """{"type":"snapshot","sessionId":"s1","throughSeq":9,
                "state":{"tetherSessionId":"s1","provider":"claude","cwd":"/w","status":"active",
                         "turnOrder":["t9"],"turnsById":{"t9":{"turnId":"t9","status":"running","blocks":[],"blocksById":{}}},
                         "activeTurnId":"t9"}}""",
        )
        await(client.projections) { it["s1"]?.turnsById?.containsKey("t9") == true }
        serverSocket.send(
            """{"type":"event","sessionId":"s1","event":{"type":"turn_end","turnId":"t9","outcome":"ok","seq":10,"ts":3}}""",
        )
        await(client.projections) { it["s1"]?.turnsById?.get("t9")?.outcome == "ok" }
    }

    @Test
    fun versionMismatchStopsPermanently() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"protocolVersion":40}"""))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Set-Cookie", "tether_session=c3; Path=/")
                .setBody("""{"ok":true}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"authenticated":true}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(wsListener))
        server.start()

        client = RealTetherClient(settings = InMemorySettings(), httpClient = OkHttpClient(), scope = scope)
        assertEquals(LoginResult.Success, runBlocking { client.login(server.url("/").toString(), "pw") })
        val serverSocket = serverSockets.poll(10, TimeUnit.SECONDS)!!
        serverSocket.send("""{"type":"ready","protocolVersion":41,"sessions":[],"providers":[],"workspaceRoot":null}""")
        val state = await(client.connection) { it is ConnectionState.VersionMismatch }
        assertEquals(41, (state as ConnectionState.VersionMismatch).requiredVersion)
    }

    @Test
    fun loginFailuresMapToResults() {
        // Bad password.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"protocolVersion":40}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"That password is not correct."}"""))
        // Rate limited.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"protocolVersion":40}"""))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"Too many attempts. Try again in a few minutes."}"""))
        // Version mismatch straight from healthz.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"protocolVersion":39}"""))
        server.start()

        client = RealTetherClient(settings = InMemorySettings(), httpClient = OkHttpClient(), scope = scope)
        val base = server.url("/").toString()

        val bad = runBlocking { client.login(base, "wrong") }
        assertTrue(bad is LoginResult.BadPassword)
        assertEquals("That password is not correct.", (bad as LoginResult.BadPassword).message)

        val limited = runBlocking { client.login(base, "wrong") }
        assertTrue(limited is LoginResult.RateLimited)

        val mismatch = runBlocking { client.login(base, "pw") }
        assertEquals(LoginResult.VersionMismatch(39), mismatch)

        // Unreachable server.
        val unreachable = runBlocking { client.login("http://127.0.0.1:1", "pw") }
        assertTrue(unreachable is LoginResult.Unreachable)
    }
}

private fun CoroutineScope.launchCollect(
    client: RealTetherClient,
    onError: (String) -> Unit,
) = launch { client.errors.collect { onError(it) } }
