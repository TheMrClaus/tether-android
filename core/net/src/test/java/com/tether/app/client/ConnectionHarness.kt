package com.tether.app.client

import com.tether.app.protocol.TetherJson
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail

/**
 * Deterministic test [Scheduler]: nothing runs until the test fires it, so a
 * reconnect delay, a ping timeout or the background grace is an inspectable
 * value and a synchronous step, never a real sleep.
 */
class ManualScheduler : Scheduler {
    class Task(val delayMs: Long, private val body: () -> Unit) {
        @Volatile var cancelled = false
        @Volatile var fired = false
        fun fire() {
            check(!cancelled && !fired) { "task ($delayMs ms) is not pending" }
            fired = true
            body()
        }
    }

    private val tasks = mutableListOf<Task>()

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val t = Task(delayMs, task)
        synchronized(tasks) { tasks += t }
        return Cancellable { t.cancelled = true }
    }

    fun pending(): List<Task> = synchronized(tasks) { tasks.filter { !it.cancelled && !it.fired } }

    fun history(): List<Task> = synchronized(tasks) { tasks.toList() }

    /** Waits (for client threads to get there, not for time) until a matching task is pending. */
    fun await(predicate: (Long) -> Boolean): Task {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            pending().firstOrNull { predicate(it.delayMs) }?.let { return it }
            Thread.sleep(5)
        }
        fail("no pending task matched; pending=${pending().map { it.delayMs }}")
        error("unreachable")
    }
}

/** Backoff delays with this base never collide with the ping (8 s) or grace (60 s) timers. */
fun testBackoff() = Backoff(baseMs = 1_100, capMs = 30_000, random = { 0.0 })

fun isReconnectDelay(ms: Long) = ms != ConnectionTimings.PING_TIMEOUT_MS && ms != ConnectionTimings.BACKGROUND_GRACE_MS &&
    ms != NodeRegistryRules.REQUEST_TIMEOUT_MS

const val HEALTH_132 = """{"ok":true,"protocolVersion":132,"nativeProtocolFloor":129}"""

fun readyFrame(
    protocolVersion: Int = 132,
    floor: Int? = 129,
    workspaceRoot: String? = null,
): String = buildString {
    append("""{"type":"ready","protocolVersion":$protocolVersion,""")
    if (floor != null) append(""""nativeProtocolFloor":$floor,""")
    append(""""sessions":[],"providers":[],""")
    append(""""workspaceRoot":${if (workspaceRoot == null) "null" else "\"$workspaceRoot\""}}""")
}

/**
 * A MockWebServer "Tether" plus a RealTetherClient on a [ManualScheduler] and a
 * manual clock. Frames the client sends land in [received] in wire order.
 */
class ConnectionHarness {
    val server = MockWebServer()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sockets = LinkedBlockingQueue<WebSocket>()
    val received = LinkedBlockingQueue<String>()
    val log = java.util.concurrent.ConcurrentLinkedQueue<String>()
    val serverCloses = LinkedBlockingQueue<Int>()
    val scheduler = ManualScheduler()
    val now = AtomicLong(1_000_000)
    lateinit var client: RealTetherClient
    lateinit var settings: InMemorySettings

    /** Runs on the server's reader thread before a client frame is recorded (a test may block it). */
    @Volatile var onServerMessage: ((String) -> Unit)? = null

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            sockets.put(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            log.add("${System.identityHashCode(webSocket)}:${text.take(40)}")
            onServerMessage?.invoke(text)
            received.put(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            serverCloses.put(code)
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            serverCloses.put(-1)
        }
    }

    /** The next connect attempt: auth probe ok, then the WS upgrade. */
    fun enqueueConnect(probeDelayMs: Long = 0) {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"authenticated":true}""")
                .setHeadersDelay(probeDelayMs, TimeUnit.MILLISECONDS),
        )
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
    }

    fun enqueueAuthFailure(code: Int = 500) {
        server.enqueue(MockResponse().setResponseCode(code).setBody("{}"))
    }

    fun newClient(
        backoff: Backoff = testBackoff(),
        configured: Boolean = true,
        deviceToken: String? = null,
    ): RealTetherClient {
        server.start()
        settings = if (deviceToken != null) {
            InMemorySettings(initialBaseUrl = server.url("/").toString().trimEnd('/'), initialDeviceToken = deviceToken)
        } else if (configured) {
            InMemorySettings(initialBaseUrl = server.url("/").toString().trimEnd('/'), initialCookie = "cookie")
        } else {
            InMemorySettings()
        }
        client = RealTetherClient(
            settings = settings,
            httpClient = OkHttpClient(),
            scope = scope,
            clock = { now.get() },
            backoff = backoff,
            // The sweeper is not under test here; keep it out of the way.
            sweepIntervalMs = 3_600_000,
            scheduler = scheduler,
        )
        return client
    }

    fun nextSocket(): WebSocket {
        val ws = sockets.poll(20, TimeUnit.SECONDS)
        assertNotNull("client never reached the ws upgrade", ws)
        return ws!!
    }

    fun frame(): JsonObject {
        val text = received.poll(20, TimeUnit.SECONDS)
        assertNotNull(
            "expected a client frame; connection=${client.connection.value} " +
                "pending=${scheduler.pending().map { it.delayMs }} requests=${server.requestCount} log=${log.toList()}",
            text,
        )
        return TetherJson.parseToJsonElement(text!!) as JsonObject
    }

    fun expectFrame(type: String): JsonObject {
        val f = frame()
        assertEquals("unexpected frame $f", type, f.type())
        return f
    }

    /**
     * Ordering barrier from the client side: send a frame nobody else sends
     * and read up to it. Everything before it is returned — proof by wire
     * order that nothing else went out, with no timing involved.
     */
    fun framesUntilBarrier(): List<JsonObject> {
        client.pin("barrier-${now.get()}", true)
        val out = mutableListOf<JsonObject>()
        while (true) {
            val f = frame()
            if (f.type() == "pin" && f["sessionId"]!!.jsonPrimitive.content.startsWith("barrier-")) return out
            out += f
        }
    }

    /**
     * Ordering barrier from the server side: every frame sent on [ws] before
     * this one has been handled once the client shows the barrier session.
     */
    fun serverBarrier(ws: WebSocket) {
        val id = "barrier-${System.nanoTime()}"
        ws.send(
            """{"type":"created","session":{"id":"$id","provider":"claude","name":"b","cwd":"/w","status":"ready",
               "startedAt":1,"updatedAt":1,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"}}""",
        )
        await(client.sessions) { list -> list.any { it.id == id } }
    }

    /** ready -> hello, and the client reports Connected. */
    fun handshake(ws: WebSocket, ready: String = readyFrame()): JsonObject {
        ws.send(ready)
        val hello = expectFrame("hello")
        await(client.connection) { it == ConnectionState.Connected }
        return hello
    }

    fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(20_000) { flow.first(predicate) } }

    fun close() {
        if (::client.isInitialized) client.stop()
        while (true) {
            val ws = sockets.poll() ?: break
            // MockWebServer's server-side cancel() can NPE; close is enough here.
            runCatching { ws.close(1000, null) }
        }
        scope.cancel()
        server.shutdown()
    }
}

fun JsonObject.type(): String? = this["type"]?.jsonPrimitive?.content

fun snapshotFrame(
    sessionId: String,
    throughSeq: Long,
    state: String? = """{"tetherSessionId":"$sessionId","provider":"claude","cwd":"/w"}""",
    reset: Boolean = false,
    trimmedBefore: Int? = null,
): String = buildString {
    append("""{"type":"snapshot","sessionId":"$sessionId","throughSeq":$throughSeq""")
    if (state != null) append(""","state":$state""")
    if (reset) append(""","reset":true""")
    if (trimmedBefore != null) append(""","trimmedBefore":$trimmedBefore""")
    append("}")
}

fun turnStartedEvent(sessionId: String, turnId: String, seq: Long, key: String? = null): String =
    """{"type":"event","sessionId":"$sessionId","event":{"type":"turn_started","turnId":"$turnId",""" +
        (if (key != null) """"idempotencyKey":"$key",""" else "") +
        """"seq":$seq,"ts":$seq}}"""
