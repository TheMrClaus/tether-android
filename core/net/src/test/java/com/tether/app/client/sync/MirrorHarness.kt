package com.tether.app.client.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.ConnectionState
import com.tether.app.client.CredentialKeySource
import com.tether.app.client.AesGcmCredentialCipher
import com.tether.app.client.InMemorySettings
import com.tether.app.client.ManualScheduler
import com.tether.app.client.RealTetherClient
import com.tether.app.client.readyFrame
import com.tether.app.client.testBackoff
import com.tether.app.client.type
import com.tether.app.mirror.AndroidMirrorDbFactory
import com.tether.app.mirror.Hydration
import com.tether.app.mirror.HydratedSession
import com.tether.app.mirror.JournalMirror
import com.tether.app.mirror.MirrorKeyStore
import com.tether.app.protocol.TetherJson
import com.tether.app.protocol.tree.JsObj
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
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

/** A software AES key standing in for the Keystore key-encryption key; it survives "process death". */
class SoftwareKek : CredentialKeySource {
    @Volatile var key: SecretKey? = null
    @Volatile var destroyed = 0

    /** Test seam (ta-hra R3): runs before every key lookup, e.g. to hold a "stuck Keystore". */
    @Volatile var beforeExistingKey: (() -> Unit)? = null

    /** The thread of every [destroyKey], in order. */
    val destroyThreads = java.util.concurrent.ConcurrentLinkedQueue<Thread>()

    override fun existingKey(): SecretKey? {
        beforeExistingKey?.invoke()
        return key
    }

    override fun getOrCreateKey(): SecretKey =
        key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }

    override fun destroyKey() {
        destroyThreads.add(Thread.currentThread())
        key = null
        destroyed++
    }
}

/**
 * T13.1: a MockWebServer "Tether" and a sequence of app PROCESSES, each a fresh
 * RealTetherClient + JournalMirror over the same files, the same software KEK (the Keystore
 * outlives a process) and the same settings (DataStore outlives a process). [kill] is process
 * death: nothing uncommitted survives, the old client is cut off and never acts again.
 */
class MirrorHarness(
    private val batchWindowMs: Long = 100,
    val rotateAfterWrites: Long = 1L shl 28,
    /** false = today's client with no mirror (the reference run of the conformance gate). */
    private val withMirror: Boolean = true,
    var checkpointEvery: Int = 2_000,
    var checkpointAtTurnEnd: Int = 500,
    /** The writer's dispatcher (a test may pass one that never runs: a stuck writer). */
    var mirrorDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
    /** The client's bound on a mirror bind: generous here, so a loaded CI box does not turn the mirror off. */
    var bindTimeoutMs: Long = 30_000,
    /** The client's bound on a hydration read (generous by default, for the same reason). */
    var hydrateTimeoutMs: Long = 30_000,
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val server = MockWebServer()
    val kek = SoftwareKek()
    val keyFile = File(context.noBackupFilesDir, MirrorKeyStore.KEY_FILE)
    val dbFactory = AndroidMirrorDbFactory(context)
    val now = AtomicLong(1_000_000)
    val sockets = LinkedBlockingQueue<WebSocket>()
    val received = LinkedBlockingQueue<String>()
    lateinit var settings: InMemorySettings
    lateinit var origin: String

    private val noDelayHttp: OkHttpClient = OkHttpClient.Builder().socketFactory(
        object : javax.net.SocketFactory() {
            private val base = javax.net.SocketFactory.getDefault()
            override fun createSocket(): java.net.Socket = base.createSocket().apply { tcpNoDelay = true }
            override fun createSocket(host: String?, port: Int): java.net.Socket = base.createSocket(host, port).apply { tcpNoDelay = true }
            override fun createSocket(host: String?, port: Int, local: java.net.InetAddress?, localPort: Int): java.net.Socket =
                base.createSocket(host, port, local, localPort).apply { tcpNoDelay = true }
            override fun createSocket(host: java.net.InetAddress?, port: Int): java.net.Socket = base.createSocket(host, port).apply { tcpNoDelay = true }
            override fun createSocket(address: java.net.InetAddress?, port: Int, local: java.net.InetAddress?, localPort: Int): java.net.Socket =
                base.createSocket(address, port, local, localPort).apply { tcpNoDelay = true }
        },
    ).build()

    inner class Process {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scheduler = ManualScheduler()
        val mirrorOrNull: JournalMirror? = if (!withMirror) null else JournalMirror(
            dbFactory = dbFactory,
            keyStore = MirrorKeyStore(keyFile, AesGcmCredentialCipher(kek)),
            reducerVersion = REDUCER_VERSION,
            scope = scope,
            clock = { now.get() },
            batchWindowMs = batchWindowMs,
            rotateAfterWrites = rotateAfterWrites,
            checkpointEvery = checkpointEvery,
            checkpointAtTurnEnd = checkpointAtTurnEnd,
            dispatcher = mirrorDispatcher ?: Dispatchers.IO.limitedParallelism(1),
        )
        val mirror: JournalMirror get() = mirrorOrNull!!
        val client = RealTetherClient(
            settings = settings,
            httpClient = noDelayHttp,
            scope = scope,
            clock = { now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = scheduler,
            mirror = mirrorOrNull,
        ).also {
            it.mirrorBindTimeoutMs = bindTimeoutMs
            it.mirrorHydrateTimeoutMs = hydrateTimeoutMs
        }
        var ws: WebSocket? = null
    }

    var process: Process? = null
        private set
    val client: RealTetherClient get() = process!!.client
    val mirror: JournalMirror get() = process!!.mirror
    val ws: WebSocket get() = process!!.ws!!

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            sockets.put(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.put(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    fun startServer(cookie: String = "cookie") {
        // Loopback + Nagle + delayed ACK costs ~40 ms per tiny frame round trip; the gate
        // makes thousands of them. TCP_NODELAY on both ends (test transport only).
        server.serverSocketFactory = object : javax.net.ServerSocketFactory() {
            private fun noDelay() = object : java.net.ServerSocket() {
                override fun accept(): java.net.Socket = super.accept().apply { tcpNoDelay = true }
            }
            override fun createServerSocket(): java.net.ServerSocket = noDelay()
            override fun createServerSocket(port: Int): java.net.ServerSocket = noDelay().apply { bind(java.net.InetSocketAddress(port)) }
            override fun createServerSocket(port: Int, backlog: Int): java.net.ServerSocket =
                noDelay().apply { bind(java.net.InetSocketAddress(port), backlog) }
            override fun createServerSocket(port: Int, backlog: Int, address: java.net.InetAddress?): java.net.ServerSocket =
                noDelay().apply { bind(java.net.InetSocketAddress(address, port), backlog) }
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        settings = InMemorySettings(initialBaseUrl = base, initialCookie = cookie)
        origin = com.tether.app.client.serverOrigin(base)!!
    }

    fun enqueueConnect() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"authenticated":true}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
    }

    /** A new process: start(), connect, and (unless [handshake] is false) ready -> hello. */
    /** A new process with no credential stored: start() only, nothing connects. */
    fun bootSignedOut(): Process {
        check(process == null) { "kill the running process first" }
        val p = Process()
        process = p
        p.client.start()
        return p
    }

    fun boot(ready: String = readyFrame(), handshake: Boolean = true, beforeStart: (Process) -> Unit = {}): Process {
        check(process == null) { "kill the running process first" }
        enqueueConnect()
        val p = Process()
        process = p
        beforeStart(p)
        p.client.start()
        p.ws = sockets.poll(10, TimeUnit.SECONDS).also { assertNotNull("client never reached the ws upgrade", it) }
        if (handshake) {
            p.ws!!.send(ready)
            expectFrame("hello")
            await(p.client.connection) { it == ConnectionState.Connected }
        }
        return p
    }

    /**
     * Process death. [flushFirst] = the write-behind batch made it to disk (a clean kill);
     * false = whatever was still in the batch window is lost.
     */
    fun kill(flushFirst: Boolean) {
        val p = process ?: return
        runBlocking {
            p.mirrorOrNull?.let { m ->
                if (flushFirst) m.flush()
                m.abandon()
            }
        }
        p.scope.cancel()
        runCatching { p.ws?.close(1000, null) }
        process = null
        // Frames the dead process sent are not the next one's.
        Thread.sleep(20)
        received.clear()
    }

    fun frame(): JsonObject {
        val text = received.poll(10, TimeUnit.SECONDS)
        assertNotNull("expected a client frame; connection=${process?.client?.connection?.value}", text)
        return TetherJson.parseToJsonElement(text!!) as JsonObject
    }

    fun expectFrame(type: String): JsonObject {
        val f = frame()
        assertEquals("unexpected frame $f", type, f.type())
        return f
    }

    /** Every frame the client sent up to a barrier of its own (proof by wire order). */
    fun framesUntilBarrier(): List<JsonObject> {
        client.pin("barrier-${System.nanoTime()}", true)
        val out = mutableListOf<JsonObject>()
        while (true) {
            val f = frame()
            if (f.type() == "pin" && f["sessionId"]!!.jsonPrimitive.content.startsWith("barrier-")) return out
            out += f
        }
    }

    /** Every frame sent on [ws] before this one has been handled once the client shows the barrier. */
    fun serverBarrier() {
        val id = "barrier-${System.nanoTime()}"
        ws.send(
            """{"type":"created","session":{"id":"$id","provider":"claude","name":"b","cwd":"/w","status":"ready",
               "startedAt":1,"updatedAt":1,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"}}""",
        )
        await(client.sessions) { list -> list.any { it.id == id } }
    }

    fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(10_000) { flow.first(predicate) } }

    /** `fold(DB.base, DB.tail)` after every write so far is committed; null = no base. */
    fun dbFold(sessionId: String): JsObj? = dbSession(sessionId)?.let(MirrorLink::rebuild)

    fun dbSession(sessionId: String): HydratedSession? = runBlocking {
        mirror.flush()
        (mirror.hydrate(origin, sessionId) as? Hydration.Loaded)?.session
    }

    fun close() {
        kill(flushFirst = false)
        server.shutdown()
        for (name in dbFactory.existing()) dbFactory.delete(name)
        keyFile.delete()
    }
}
