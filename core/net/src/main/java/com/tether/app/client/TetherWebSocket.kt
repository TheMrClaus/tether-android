package com.tether.app.client

import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.Socket
import okio.buffer

/**
 * ta-coik.16: the app's WebSocket to its Tether server, in place of OkHttp's `RealWebSocket`.
 *
 * Why: the server takes a message of up to 32 MiB (protocol-validate LIMITS.WS_FRAME_BYTES, its
 * `maxPayload`) and the web sends up to about 24 MiB of attachments in one. OkHttp's WebSocket
 * closes the connection rather than queue past a private 16 MiB bound, and cannot split a message
 * into frames, so the app could not send what the web sends. This sends a large message as RFC 6455
 * §5.4 fragments (a text frame, then continuation frames), which the server's `ws` reassembles; a
 * message of at most [FRAGMENT_BYTES] is one frame, exactly as before.
 *
 * Same transport, same trust: the upgrade is an ordinary call on the caller's [OkHttpClient] (the
 * client's own TLS trust, hostname verification, certificate pinning and connection specs, the
 * caller's credential headers on the [Request], its redirect policy), over HTTP/1.1, through OkHttp's
 * public upgrade API ([Response.socket]). Frames then go on that one socket; nothing else is opened.
 *
 * The rules this keeps (each has a test in TetherWebSocketTest):
 *  - every client frame is masked with a fresh 4-byte key from a [SecureRandom] (RFC 6455 §5.3;
 *    OkHttp drew them from `java.util.Random`);
 *  - ONE writer thread puts frames on the socket, in the order the messages were sent; a fragmented
 *    message is never interleaved with another data frame, and the only frames between its fragments
 *    are the control frames RFC 6455 §5.4 allows there (a pong answering the server's heartbeat, a
 *    ping when the client has a ping interval) — never a close, which waits for the message to end;
 *  - what is held is bounded: one message at most [SERVER_MESSAGE_BYTES] (the server's own bound),
 *    all queued messages at most [MAX_QUEUE_BYTES], each counted whole until its last fragment is
 *    out; a send past either closes the socket (1001) and is refused, OkHttp's contract at its own
 *    bound; the writer's own memory is one fragment;
 *  - [cancel] mid-message closes the socket at once (the server discards the incomplete message);
 *    [close] lets the queued messages finish, then sends the close frame;
 *  - nothing here logs, and no payload is put in an exception message.
 *
 * The listener sees what OkHttp's showed it: [WebSocketListener.onOpen] once the upgrade is checked,
 * then messages, [WebSocketListener.onClosing] for the server's close, and exactly one of
 * [WebSocketListener.onClosed] / [WebSocketListener.onFailure]; never under this socket's lock.
 * Server frames are read as OkHttp read them (unmasked, no reserved bits, control frames final and
 * at most 125 bytes, continuation only inside a message). No extension is offered, so none is taken.
 */
internal class TetherWebSocket private constructor(
    private val originalRequest: Request,
    private val listener: WebSocketListener,
    private val onPing: ((TetherWebSocket) -> Unit)?,
    private val random: SecureRandom,
    private val pingIntervalMillis: Long,
    private val closeTimeoutMillis: Long,
    private val fragmentBytes: Int,
    private val maxQueueBytes: Long,
) : WebSocket {

    private val key: String = ByteArray(16).also { random.nextBytes(it) }.toByteString().base64()
    private lateinit var call: Call

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    // --- guarded by [lock] ---
    private var socket: Socket? = null
    private val messages = ArrayDeque<Outgoing>()
    private val pongs = ArrayDeque<ByteString>()
    private var queueSize = 0L
    private var enqueuedClose = false
    private var closeWritten = false
    private var closeDeadlineNanos = 0L
    private var receivedCloseCode = -1
    private var receivedCloseReason = ""
    private var readerDone = false
    private var failed = false
    private var finished = false
    private var nextPingNanos = 0L
    private var awaitingPong = false
    private var sentPings = 0

    private sealed class Outgoing {
        class Message(val opcode: Int, val data: ByteString) : Outgoing()
        class Close(val code: Int, val reason: ByteString?) : Outgoing()
    }

    override fun request(): Request = originalRequest

    override fun queueSize(): Long = lock.withLock { queueSize }

    override fun send(text: String): Boolean = send(text.encodeUtf8(), WebSocketFrames.OPCODE_TEXT)

    override fun send(bytes: ByteString): Boolean = send(bytes, WebSocketFrames.OPCODE_BINARY)

    private fun send(data: ByteString, opcode: Int): Boolean = lock.withLock {
        if (failed || finished || enqueuedClose) return false
        // OkHttp's contract for a send the socket cannot take (WebSocket.send): refused (false) and a
        // graceful close (1001). Here that is a message over the server's own bound (r2, security
        // F2: the server would drop it with 1009 anyway), or one that would take the queue past
        // its bound. [queueSize] counts every message whole until its final fragment is out.
        if (data.size > SERVER_MESSAGE_BYTES || queueSize + data.size > maxQueueBytes) {
            closeLocked(WebSocketFrames.CLOSE_GOING_AWAY, null)
            return false
        }
        queueSize += data.size
        messages.add(Outgoing.Message(opcode, data))
        changed.signalAll()
        true
    }

    override fun close(code: Int, reason: String?): Boolean {
        WebSocketFrames.closeCodeProblem(code)?.let { throw IllegalArgumentException(it) }
        val reasonBytes = reason?.encodeUtf8()
        require(reasonBytes == null || reasonBytes.size <= WebSocketFrames.CLOSE_REASON_MAX) { "close reason is over ${WebSocketFrames.CLOSE_REASON_MAX} bytes" }
        return lock.withLock { closeLocked(code, reasonBytes) }
    }

    private fun closeLocked(code: Int, reason: ByteString?): Boolean {
        if (failed || finished || enqueuedClose) return false
        enqueuedClose = true
        messages.add(Outgoing.Close(code, reason))
        changed.signalAll()
        return true
    }

    /** Ends the socket now: the handshake is abandoned, or the connection is closed under the writer. */
    override fun cancel() {
        call.cancel()
    }

    // ------------------------------------------------------------------
    // The handshake and the reader thread
    // ------------------------------------------------------------------

    private fun start(client: OkHttpClient) {
        val upgrade = originalRequest.newBuilder()
            .header("Upgrade", "websocket")
            .header("Connection", "Upgrade")
            .header("Sec-WebSocket-Key", key)
            .header("Sec-WebSocket-Version", "13")
            .build()
        // The caller's client (trust, pinning, connect/read/write timeouts, application interceptors,
        // redirect policy), over HTTP/1.1 only: an upgrade does not exist on HTTP/2. r2 (security
        // F1): without the network interceptors, which OkHttp's own WebSocket call never ran (they
        // would see, and could hold or rewrite, the raw upgraded exchange), and without a call
        // timeout, so no whole-call deadline meant for ordinary requests applies to the upgrade.
        val upgradeClient = client.newBuilder()
            .eventListener(EventListener.NONE)
            .protocols(listOf(Protocol.HTTP_1_1))
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .apply { networkInterceptors().clear() }
            .build()
        call = upgradeClient.newCall(upgrade)
        Thread({ runReader() }, "Tether WebSocket reader").apply { isDaemon = true }.start()
    }

    private fun runReader() {
        val response = try {
            call.execute()
        } catch (e: Exception) {
            fail(e, null)
            return
        }
        val upgraded = try {
            checkUpgrade(response)
        } catch (e: IOException) {
            fail(e, response)
            runCatching { response.close() }
            response.socket?.cancel()
            return
        }
        val source = upgraded.source.buffer()
        val sink = upgraded.sink.buffer()
        lock.withLock {
            socket = upgraded
            if (pingIntervalMillis > 0) nextPingNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(pingIntervalMillis)
        }
        Thread({ runWriter(sink) }, "Tether WebSocket writer").apply { isDaemon = true }.start()
        val reader = WebSocketFrames.Reader(source, readerCallback)
        try {
            listener.onOpen(this, response)
            while (lock.withLock { receivedCloseCode == -1 && !failed && !finished }) reader.processNextFrame()
        } catch (e: Exception) {
            fail(e, null)
            return
        }
        val closedNow = lock.withLock {
            readerDone = true
            changed.signalAll()
            closeWritten
        }
        if (closedNow) finish()
    }

    @Throws(IOException::class)
    private fun checkUpgrade(response: Response): Socket {
        if (response.code != 101) throw ProtocolException("Expected HTTP 101 response but was '${response.code} ${response.message}'")
        val connection = response.header("Connection")
        if (!"Upgrade".equals(connection, ignoreCase = true)) throw ProtocolException("Expected 'Connection' header value 'Upgrade' but was '$connection'")
        val upgrade = response.header("Upgrade")
        if (!"websocket".equals(upgrade, ignoreCase = true)) throw ProtocolException("Expected 'Upgrade' header value 'websocket' but was '$upgrade'")
        val accept = response.header("Sec-WebSocket-Accept")
        if (accept != WebSocketFrames.acceptHeader(key)) throw ProtocolException("Unexpected 'Sec-WebSocket-Accept' header value")
        // RFC 6455 §4.1: nothing was offered, so an extension or a subprotocol in the answer fails it.
        if (!response.header("Sec-WebSocket-Extensions").isNullOrBlank()) throw ProtocolException("Unexpected 'Sec-WebSocket-Extensions' in the upgrade response")
        if (!response.header("Sec-WebSocket-Protocol").isNullOrBlank()) throw ProtocolException("Unexpected 'Sec-WebSocket-Protocol' in the upgrade response")
        return response.socket ?: throw ProtocolException("The upgrade response carries no socket")
    }

    // ta-coik.28 (decided: no cap on an INCOMING message). The browser's WebSocket has none (hooks/use-tether.ts
    // :720 `new WebSocket`, :755 the "message" listener takes whatever arrives), and the server's `maxPayload`
    // (server.mjs 29537e0 :10090, LIMITS.WS_FRAME_BYTES) bounds frames TO the server, not the ones it sends.
    // Nothing proves a server send cannot exceed any bound chosen here, and a cap would make the app take
    // less than the browser does, so none is set.
    private val readerCallback = object : WebSocketFrames.Reader.Callback {
        override fun onText(text: String) = listener.onMessage(this@TetherWebSocket, text)

        override fun onBinary(bytes: ByteString) = listener.onMessage(this@TetherWebSocket, bytes)

        override fun onPing(payload: ByteString) {
            // ta-nl5m: the server's heartbeat is a sign of life the listener never sees as a message;
            // told first, outside the lock, whatever happens to the pong.
            onPing?.invoke(this@TetherWebSocket)
            lock.withLock {
                // As OkHttp: answered until the close frame is out (a long message ahead of a queued
                // close still has the server's heartbeat answered), never after it or a failure.
                if (failed || finished || closeWritten) return
                pongs.add(payload)
                changed.signalAll()
            }
        }

        override fun onPong(payload: ByteString) {
            lock.withLock { awaitingPong = false }
        }

        override fun onClose(code: Int, reason: String) {
            lock.withLock {
                receivedCloseCode = code
                receivedCloseReason = reason
            }
            listener.onClosing(this@TetherWebSocket, code, reason)
        }
    }

    // ------------------------------------------------------------------
    // The writer thread: the only one that puts bytes on the socket
    // ------------------------------------------------------------------

    private class Control(val opcode: Int, val payload: ByteString)

    private fun runWriter(sink: BufferedSink) {
        val scratch = ByteArray(maxOf(fragmentBytes, WebSocketFrames.CONTROL_PAYLOAD_MAX.toInt()))
        val maskKey = ByteArray(4)
        fun frame(fin: Boolean, opcode: Int, payload: ByteString, offset: Int, length: Int) {
            // RFC 6455 §5.3: a fresh, unpredictable key for every frame.
            random.nextBytes(maskKey)
            WebSocketFrames.writeClientFrame(sink, fin, opcode, payload, offset, length, maskKey, scratch)
            sink.flush()
        }
        try {
            while (true) {
                when (val step = lock.withLock { nextStepLocked(block = true) }) {
                    STOP -> return
                    PING_TIMED_OUT -> return failPingTimeout()
                    CLOSE_DONE -> return finish()
                    // The server never answered the close: end it (the reader then reports the failure).
                    CLOSE_TIMED_OUT -> return cancel()
                    is Control -> frame(true, step.opcode, step.payload, 0, step.payload.size)
                    is Outgoing.Message -> {
                        val data = step.data
                        var offset = 0
                        do {
                            val length = minOf(fragmentBytes, data.size - offset)
                            val last = offset + length == data.size
                            frame(last, if (offset == 0) step.opcode else WebSocketFrames.OPCODE_CONTINUATION, data, offset, length)
                            offset += length
                            // r2 (security F2): the message is held whole until its last fragment is
                            // out, so it counts whole until then (the bound is on memory held).
                            if (last) lock.withLock { queueSize -= data.size }
                            // RFC 6455 §5.4: control frames, and only they, may go between fragments.
                            while (!last) {
                                when (val between = lock.withLock { nextStepLocked(block = false) }) {
                                    null -> break
                                    STOP -> return
                                    PING_TIMED_OUT -> return failPingTimeout()
                                    is Control -> frame(true, between.opcode, between.payload, 0, between.payload.size)
                                    else -> error("only a control frame goes between fragments")
                                }
                            }
                        } while (!last)
                    }
                    is Outgoing.Close -> {
                        val payload = Buffer().apply {
                            writeShort(step.code)
                            step.reason?.let { write(it) }
                        }.readByteString()
                        frame(true, WebSocketFrames.OPCODE_CLOSE, payload, 0, payload.size)
                        lock.withLock {
                            closeWritten = true
                            closeDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(closeTimeoutMillis)
                        }
                    }
                    else -> error("unknown writer step")
                }
            }
        } catch (e: Exception) {
            // An I/O error, an interrupt, or a bug: the socket fails (and the listener hears it),
            // never a writer that is silently gone while the socket looks open.
            fail(e, null)
        }
    }

    private fun pingDueLocked(): Boolean = nextPingNanos != 0L && System.nanoTime() - nextPingNanos >= 0

    /**
     * Under [lock]: what the writer does next. A control frame first (the server's pongs in order,
     * then a ping that is due); with [block] false, only that (null: none), which is all that may go
     * between the fragments of a message. With [block], else the next queued message or close, the
     * end of the close handshake, or a wait until one of them.
     */
    private fun nextStepLocked(block: Boolean): Any? {
        while (true) {
            if (failed || finished) return STOP
            // Nothing at all follows the close frame on the wire.
            if (closeWritten) pongs.clear()
            pongs.poll()?.let { return Control(WebSocketFrames.OPCODE_PONG, it) }
            if (!closeWritten && pingDueLocked()) {
                if (awaitingPong) return PING_TIMED_OUT
                awaitingPong = true
                sentPings++
                nextPingNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(pingIntervalMillis)
                return Control(WebSocketFrames.OPCODE_PING, ByteString.EMPTY)
            }
            if (!block) return null
            if (closeWritten) {
                if (readerDone) return CLOSE_DONE
                val left = closeDeadlineNanos - System.nanoTime()
                if (left <= 0) return CLOSE_TIMED_OUT
                changed.awaitNanos(left)
                continue
            }
            messages.poll()?.let { return it }
            if (nextPingNanos != 0L) changed.awaitNanos(maxOf(1L, nextPingNanos - System.nanoTime())) else changed.await()
        }
    }

    private fun failPingTimeout() {
        val after = lock.withLock { sentPings - 1 }
        fail(SocketTimeoutException("sent ping but didn't receive pong within ${pingIntervalMillis}ms (after $after successful ping/pongs)"), null)
    }

    // ------------------------------------------------------------------
    // The two ends: closed (both close frames crossed) or failed
    // ------------------------------------------------------------------

    private fun finish() {
        val code: Int
        val reason: String
        val toRelease: Socket?
        lock.withLock {
            if (failed || finished) return
            finished = true
            changed.signalAll()
            code = receivedCloseCode
            reason = receivedCloseReason
            toRelease = socket
        }
        try {
            listener.onClosed(this, code, reason)
        } finally {
            toRelease?.cancel()
        }
    }

    private fun fail(e: Exception, response: Response?) {
        val toCancel: Socket?
        lock.withLock {
            if (failed || finished) return
            failed = true
            messages.clear()
            pongs.clear()
            changed.signalAll()
            toCancel = socket
        }
        try {
            listener.onFailure(this, e, response)
        } finally {
            // The upgraded socket if there is one, else the call (still connecting, or never upgraded).
            if (toCancel != null) toCancel.cancel() else call.cancel()
        }
    }

    companion object {
        /** The server's own bound on one message (protocol-validate LIMITS.WS_FRAME_BYTES, its `maxPayload`). */
        const val SERVER_MESSAGE_BYTES: Long = 32L * 1024 * 1024

        /**
         * What may wait in the queue at once: two of the largest messages the server takes. A send
         * past it closes the socket (1001), as OkHttp's did past its 16 MiB.
         */
        const val MAX_QUEUE_BYTES: Long = 2 * SERVER_MESSAGE_BYTES

        /** A message larger than this goes as fragments of this size (the last one shorter). */
        const val FRAGMENT_BYTES: Int = 64 * 1024

        // Writer steps (see nextStepLocked).
        private val STOP = Any()
        private val PING_TIMED_OUT = Any()
        private val CLOSE_DONE = Any()
        private val CLOSE_TIMED_OUT = Any()

        /** Starts the upgrade of [request] on [client] (on a thread of its own) and returns the socket. */
        fun connect(
            client: OkHttpClient,
            request: Request,
            listener: WebSocketListener,
            random: SecureRandom = SecureRandom(),
            fragmentBytes: Int = FRAGMENT_BYTES,
            maxQueueBytes: Long = MAX_QUEUE_BYTES,
            onPing: ((TetherWebSocket) -> Unit)? = null,
        ): TetherWebSocket {
            require(request.method == "GET") { "Request must be GET: ${request.method}" }
            require(fragmentBytes > 0)
            val ws = TetherWebSocket(
                originalRequest = request,
                listener = listener,
                onPing = onPing,
                random = random,
                pingIntervalMillis = client.pingIntervalMillis.toLong(),
                closeTimeoutMillis = client.webSocketCloseTimeout.toLong(),
                fragmentBytes = fragmentBytes,
                maxQueueBytes = maxQueueBytes,
            )
            ws.start(client)
            return ws
        }
    }
}

/** RFC 6455 framing for a client: the frame encoder (always masked) and the server-frame reader. */
internal object WebSocketFrames {
    const val OPCODE_CONTINUATION = 0x0
    const val OPCODE_TEXT = 0x1
    const val OPCODE_BINARY = 0x2
    const val OPCODE_CLOSE = 0x8
    const val OPCODE_PING = 0x9
    const val OPCODE_PONG = 0xA

    const val CONTROL_PAYLOAD_MAX = 125L
    const val CLOSE_REASON_MAX = 123
    const val CLOSE_GOING_AWAY = 1001
    const val CLOSE_NO_STATUS = 1005

    private const val FIN = 0x80
    private const val RSV_MASK = 0x70
    private const val OPCODE_MASK = 0x0F
    private const val CONTROL_FLAG = 0x08
    private const val MASK_FLAG = 0x80
    private const val LENGTH_MASK = 0x7F
    private const val LENGTH_16 = 126
    private const val LENGTH_64 = 127
    private const val ACCEPT_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    /** RFC 6455 §4.2.2: the `Sec-WebSocket-Accept` the server must answer [key] with. */
    fun acceptHeader(key: String): String = (key + ACCEPT_MAGIC).encodeUtf8().sha1().base64()

    /** Why [code] may not be sent or received in a close frame (RFC 6455 §7.4), or null. */
    fun closeCodeProblem(code: Int): String? = when {
        code < 1000 || code >= 5000 -> "Code must be in range [1000,5000): $code"
        code in 1004..1006 || code in 1015..2999 -> "Code $code is reserved and may not be used."
        else -> null
    }

    /**
     * RFC 6455 §5.2: one client frame, [length] bytes of [payload] from [offset], masked under
     * [maskKey] (§5.3). [scratch] (at least [length] bytes) carries the masked copy; [payload] itself
     * is never changed.
     */
    fun writeClientFrame(
        sink: BufferedSink,
        fin: Boolean,
        opcode: Int,
        payload: ByteString,
        offset: Int,
        length: Int,
        maskKey: ByteArray,
        scratch: ByteArray,
    ) {
        require(opcode and OPCODE_MASK.inv() == 0) { "opcode" }
        require(maskKey.size == 4) { "mask key" }
        require(offset >= 0 && length >= 0 && offset + length <= payload.size && length <= scratch.size) { "range" }
        if (opcode and CONTROL_FLAG != 0) require(fin && length <= CONTROL_PAYLOAD_MAX) { "a control frame is final and at most 125 bytes" }
        sink.writeByte((if (fin) FIN else 0) or opcode)
        when {
            length <= CONTROL_PAYLOAD_MAX -> sink.writeByte(MASK_FLAG or length)
            length <= 0xFFFF -> {
                sink.writeByte(MASK_FLAG or LENGTH_16)
                sink.writeShort(length)
            }
            else -> {
                sink.writeByte(MASK_FLAG or LENGTH_64)
                sink.writeLong(length.toLong())
            }
        }
        sink.write(maskKey)
        payload.copyInto(offset, scratch, 0, length)
        for (i in 0 until length) scratch[i] = (scratch[i].toInt() xor maskKey[i and 3].toInt()).toByte()
        sink.write(scratch, 0, length)
    }

    /**
     * Reads server frames as OkHttp's WebSocketReader does for a client with no extension: unmasked
     * frames only, no reserved bits, control frames final and at most 125 bytes (and answered between
     * the fragments of a message), a message begun by a text or binary frame and continued only by
     * continuation frames. Not thread-safe: the reader thread alone uses it.
     */
    class Reader(private val source: BufferedSource, private val callback: Callback) {
        interface Callback {
            fun onText(text: String)
            fun onBinary(bytes: ByteString)
            fun onPing(payload: ByteString)
            fun onPong(payload: ByteString)
            fun onClose(code: Int, reason: String)
        }

        private var receivedClose = false
        private var opcode = 0
        private var length = 0L
        private var fin = false
        private var control = false
        private val controlBuffer = Buffer()
        private val messageBuffer = Buffer()

        /** One frame: a control frame, or a whole message (with the control frames between its fragments). */
        @Throws(IOException::class)
        fun processNextFrame() {
            readHeader()
            if (control) readControlFrame() else readMessage()
        }

        @Throws(IOException::class)
        private fun readHeader() {
            if (receivedClose) throw IOException("closed")
            // As OkHttp: no read timeout while waiting for a frame to begin.
            val b0: Int
            val timeoutBefore = source.timeout().timeoutNanos()
            source.timeout().clearTimeout()
            try {
                b0 = source.readByte().toInt() and 0xFF
            } finally {
                source.timeout().timeout(timeoutBefore, TimeUnit.NANOSECONDS)
            }
            opcode = b0 and OPCODE_MASK
            fin = b0 and FIN != 0
            control = b0 and CONTROL_FLAG != 0
            if (control && !fin) throw ProtocolException("Control frames must be final.")
            if (b0 and RSV_MASK != 0) throw ProtocolException("Unexpected reserved flag")
            val b1 = source.readByte().toInt() and 0xFF
            if (b1 and MASK_FLAG != 0) throw ProtocolException("Server-sent frames must not be masked.")
            length = (b1 and LENGTH_MASK).toLong()
            if (length == LENGTH_16.toLong()) {
                length = (source.readShort().toInt() and 0xFFFF).toLong()
            } else if (length == LENGTH_64.toLong()) {
                length = source.readLong()
                if (length < 0) throw ProtocolException("Frame length over 0x7FFFFFFFFFFFFFFF")
            }
            if (control && length > CONTROL_PAYLOAD_MAX) throw ProtocolException("Control frame must be less than ${CONTROL_PAYLOAD_MAX}B.")
        }

        @Throws(IOException::class)
        private fun readControlFrame() {
            if (length > 0) source.readFully(controlBuffer, length)
            when (opcode) {
                OPCODE_PING -> callback.onPing(controlBuffer.readByteString())
                OPCODE_PONG -> callback.onPong(controlBuffer.readByteString())
                OPCODE_CLOSE -> {
                    var code = CLOSE_NO_STATUS
                    var reason = ""
                    val size = controlBuffer.size
                    if (size == 1L) throw ProtocolException("Malformed close payload length of 1.")
                    if (size != 0L) {
                        code = controlBuffer.readShort().toInt() and 0xFFFF
                        reason = controlBuffer.readUtf8()
                        closeCodeProblem(code)?.let { throw ProtocolException(it) }
                    }
                    receivedClose = true
                    callback.onClose(code, reason)
                }
                else -> throw ProtocolException("Unknown control opcode: ${Integer.toHexString(opcode)}")
            }
        }

        @Throws(IOException::class)
        private fun readMessage() {
            val messageOpcode = opcode
            if (messageOpcode != OPCODE_TEXT && messageOpcode != OPCODE_BINARY) {
                throw ProtocolException("Unknown opcode: ${Integer.toHexString(messageOpcode)}")
            }
            while (true) {
                if (length > 0) source.readFully(messageBuffer, length)
                if (fin) break
                // Control frames may come between fragments; the next data frame must continue this one.
                while (true) {
                    readHeader()
                    if (!control) break
                    readControlFrame()
                    if (receivedClose) throw IOException("closed")
                }
                if (opcode != OPCODE_CONTINUATION) throw ProtocolException("Expected continuation opcode. Got: ${Integer.toHexString(opcode)}")
            }
            if (messageOpcode == OPCODE_TEXT) callback.onText(messageBuffer.readUtf8()) else callback.onBinary(messageBuffer.readByteString())
        }
    }
}
