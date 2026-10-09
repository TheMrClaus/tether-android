package com.tether.app.client

import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ServerSocketFactory
import javax.net.SocketFactory
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.CertificatePinner
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ta-coik.16: the app's own WebSocket ([TetherWebSocket]). Three layers:
 *  - the frame encoder and reader against the RFC 6455 §5.7 examples and every length form;
 *  - the wire, read raw off a loopback socket by an independent decoder written here: masking with
 *    a fresh SecureRandom key per frame, fragment boundaries, strict order, the only control frames
 *    between fragments, close after the message, cancel mid-message, the queue bound, the handshake;
 *  - a conforming peer (OkHttp's server side in MockWebServer, which rejects an unmasked or
 *    misordered frame), over the caller's TLS trust and pinning, with a message past OkHttp's old
 *    16 MiB bound both ways.
 */
class TetherWebSocketTest {

    private val closers = ArrayList<Closeable>()

    @After
    fun tearDown() {
        closers.reversed().forEach { runCatching { it.close() } }
    }

    // ------------------------------------------------------------------
    // The encoder: RFC 6455 §5.7 and the length forms
    // ------------------------------------------------------------------

    private fun encode(fin: Boolean, opcode: Int, payload: ByteString, key: ByteString, offset: Int = 0, length: Int = payload.size): ByteString {
        val sink = Buffer()
        WebSocketFrames.writeClientFrame(sink, fin, opcode, payload, offset, length, key.toByteArray(), ByteArray(maxOf(length, 1)))
        return sink.readByteString()
    }

    private val rfcKey = "37fa213d".decodeHex()

    @Test
    fun aMaskedTextFrameIsRfc6455sExample() {
        // §5.7: "A single-frame masked text message".
        assertEquals("818537fa213d7f9f4d5158".decodeHex(), encode(true, WebSocketFrames.OPCODE_TEXT, "Hello".encodeUtf8(), rfcKey))
    }

    @Test
    fun aMaskedPongIsRfc6455sExample() {
        // §5.7: "Pong response (masked), body 'Hello'".
        assertEquals("8a8537fa213d7f9f4d5158".decodeHex(), encode(true, WebSocketFrames.OPCODE_PONG, "Hello".encodeUtf8(), rfcKey))
    }

    @Test
    fun aFragmentedMessageIsATextFrameWithoutFinThenAFinalContinuation() {
        val hello = "Hello".encodeUtf8()
        // §5.7's fragmented example, masked: FIN clear + text, then FIN + continuation.
        val first = encode(false, WebSocketFrames.OPCODE_TEXT, hello, rfcKey, 0, 3)
        val last = encode(true, WebSocketFrames.OPCODE_CONTINUATION, hello, rfcKey, 3, 2)
        assertEquals("018337fa213d7f9f4d".decodeHex(), first)
        assertEquals("808237fa213d5b95".decodeHex(), last)
        assertEquals("Hello".encodeUtf8(), hello) // the payload is never masked in place
    }

    @Test
    fun everyLengthFormIsEncodedAtItsBoundary() {
        val key = "a1b2c3d4".decodeHex()
        for ((length, header) in listOf(
            0 to "8180",
            125 to "81fd",
            126 to "81fe007e",
            65535 to "81feffff",
            65536 to "81ff0000000000010000",
        )) {
            val payload = ByteArray(length) { (it * 31).toByte() }.toByteString()
            val frame = encode(true, WebSocketFrames.OPCODE_TEXT, payload, key)
            val headerBytes = header.decodeHex()
            assertEquals("header for $length", headerBytes, frame.substring(0, headerBytes.size))
            assertEquals("key for $length", key, frame.substring(headerBytes.size, headerBytes.size + 4))
            val masked = frame.substring(headerBytes.size + 4)
            assertEquals(length, masked.size)
            assertEquals("payload for $length", payload, unmask(masked.toByteArray(), key.toByteArray()).toByteString())
        }
    }

    @Test
    fun aControlFrameMustBeFinalAndShort() {
        val key = rfcKey
        assertThrows<IllegalArgumentException> { encode(false, WebSocketFrames.OPCODE_PING, ByteString.EMPTY, key) }
        assertThrows<IllegalArgumentException> { encode(true, WebSocketFrames.OPCODE_PING, ByteArray(126).toByteString(), key) }
        assertEquals(2 + 4 + 125, encode(true, WebSocketFrames.OPCODE_PING, ByteArray(125).toByteString(), key).size)
    }

    @Test
    fun theAcceptHeaderIsRfc6455sExample() {
        // §1.3: key "dGhlIHNhbXBsZSBub25jZQ==" is answered with "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=".
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocketFrames.acceptHeader("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    // ------------------------------------------------------------------
    // The reader: server frames
    // ------------------------------------------------------------------

    private class ReadLog : WebSocketFrames.Reader.Callback {
        val events = ArrayList<String>()
        override fun onText(text: String) { events += "text:$text" }
        override fun onBinary(bytes: ByteString) { events += "binary:${bytes.size}" }
        override fun onPing(payload: ByteString) { events += "ping:${payload.utf8()}" }
        override fun onPong(payload: ByteString) { events += "pong:${payload.utf8()}" }
        override fun onClose(code: Int, reason: String) { events += "close:$code:$reason" }
    }

    private fun read(hex: String, frames: Int = 1): List<String> {
        val log = ReadLog()
        val reader = WebSocketFrames.Reader(Buffer().write(hex.decodeHex()), log)
        repeat(frames) { reader.processNextFrame() }
        return log.events
    }

    @Test
    fun theReaderReadsRfc6455sExamples() {
        assertEquals(listOf("text:Hello"), read("810548656c6c6f"))
        // Fragmented, with a ping between the fragments (§5.4 allows it there).
        assertEquals(listOf("ping:Hello", "text:Hello"), read("010348656c" + "890548656c6c6f" + "80026c6f"))
        assertEquals(listOf("pong:Hello"), read("8a0548656c6c6f"))
        assertEquals(listOf("binary:256"), read("827e0100" + "00".repeat(256)))
        assertEquals(listOf("binary:65536"), read("827f0000000000010000" + "00".repeat(65536)))
        assertEquals(listOf("close:1000:bye"), read("880503e8627965"))
        assertEquals(listOf("close:1005:"), read("8800"))
    }

    @Test
    fun theReaderRefusesWhatAServerMayNotSend() {
        for ((what, hex) in listOf(
            "a masked frame" to "818537fa213d7f9f4d5158",
            "a continuation with no message" to "80026c6f",
            "a new message inside a message" to "010348656c" + "81026c6f",
            "a reserved bit" to "c10548656c6c6f",
            "a fragmented control frame" to "0900",
            "a long control frame" to "897e007e" + "00".repeat(126),
            "a reserved opcode" to "830100",
            "a reserved control opcode" to "8b00",
            "a one-byte close payload" to "880103",
            "a reserved close code" to "880203ed",
            "a close code under 1000" to "880203e7",
            "a negative 64-bit length" to "817f8000000000000000",
        )) {
            try {
                read(hex)
                fail("$what was accepted")
            } catch (_: ProtocolException) {
            }
        }
    }

    // ------------------------------------------------------------------
    // The wire, read raw
    // ------------------------------------------------------------------

    private class Frame(val fin: Boolean, val opcode: Int, val key: ByteArray, val payload: ByteArray)

    private class Recorder : WebSocketListener() {
        val events = LinkedBlockingQueue<String>()
        val messages = LinkedBlockingQueue<String>()
        @Volatile var failure: Throwable? = null
        @Volatile var failureCode: Int? = null

        override fun onOpen(webSocket: WebSocket, response: Response) = events.put("open")
        override fun onMessage(webSocket: WebSocket, text: String) = messages.put(text)
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = events.put("closing:$code")
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = events.put("closed:$code")
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failure = t
            failureCode = response?.code
            events.put("failure:${t.javaClass.simpleName}")
        }

        fun next(): String = events.poll(20, TimeUnit.SECONDS) ?: error("no listener event")
        fun nothingMore() = assertNull(events.poll(300, TimeUnit.MILLISECONDS))
    }

    /** A loopback peer that does the server's handshake by hand and reads client frames raw. */
    private inner class RawServer : Closeable {
        private val listening = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        lateinit var socket: Socket
        private lateinit var input: DataInputStream
        private lateinit var output: OutputStream
        val requestLines = ArrayList<String>()

        init {
            closers += this
        }

        val url: String get() = "http://127.0.0.1:${listening.localPort}/ws"

        fun accept(accept: (String) -> String = ::rfcAccept, extraHeaders: String = "", answerAfterMs: Long = 0) {
            socket = listening.accept()
            socket.soTimeout = 20_000
            input = DataInputStream(socket.getInputStream().buffered())
            output = socket.getOutputStream()
            while (true) {
                val line = readLine()
                if (line.isEmpty()) break
                requestLines += line
            }
            if (answerAfterMs > 0) Thread.sleep(answerAfterMs)
            val key = requestLines.first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
            output.write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: ${accept(key)}\r\n$extraHeaders\r\n").toByteArray(),
            )
            output.flush()
        }

        private fun readLine(): String {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) throw EOFException()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
            }
        }

        /** One client frame, unmasked here; it must be masked. */
        fun readFrame(): Frame {
            val b0 = input.readUnsignedByte()
            val b1 = input.readUnsignedByte()
            assertTrue("a client frame must be masked", b1 and 0x80 != 0)
            assertEquals("no reserved bits", 0, b0 and 0x70)
            var length = (b1 and 0x7f).toLong()
            if (length == 126L) length = input.readUnsignedShort().toLong()
            else if (length == 127L) length = input.readLong()
            val key = ByteArray(4).also { input.readFully(it) }
            val payload = ByteArray(length.toInt()).also { input.readFully(it) }
            return Frame(b0 and 0x80 != 0, b0 and 0x0f, key, unmask(payload, key))
        }

        /** The next frame, or null once the client's side of the connection is gone. */
        fun readFrameOrNull(): Frame? = try {
            readFrame()
        } catch (_: EOFException) {
            null
        } catch (_: IOException) {
            null
        }

        fun writeFrame(opcode: Int, payload: ByteArray, fin: Boolean = true) {
            val header = Buffer().writeByte((if (fin) 0x80 else 0) or opcode)
            when {
                payload.size <= 125 -> header.writeByte(payload.size)
                payload.size <= 0xffff -> header.writeByte(126).writeShort(payload.size)
                else -> header.writeByte(127).writeLong(payload.size.toLong())
            }
            output.write(header.readByteArray())
            output.write(payload)
            output.flush()
        }

        fun closePayload(code: Int, reason: String = ""): ByteArray = Buffer().writeShort(code).writeUtf8(reason).readByteArray()

        override fun close() {
            runCatching { socket.close() }
            listening.close()
        }
    }

    private fun rfcAccept(key: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))

    /** Every SecureRandom output, in order (the real one underneath). */
    private class RecordingRandom : SecureRandom() {
        private val real = SecureRandom()
        val outputs = CopyOnWriteArrayList<ByteArray>()
        override fun nextBytes(bytes: ByteArray) {
            real.nextBytes(bytes)
            outputs += bytes.copyOf()
        }
    }

    private fun connect(
        url: String,
        listener: WebSocketListener,
        client: OkHttpClient = OkHttpClient(),
        random: SecureRandom = SecureRandom(),
        maxQueueBytes: Long = TetherWebSocket.MAX_QUEUE_BYTES,
        request: Request.Builder.() -> Unit = {},
    ): TetherWebSocket {
        val ws = TetherWebSocket.connect(client, Request.Builder().url(url).apply(request).build(), listener, random, maxQueueBytes = maxQueueBytes)
        closers += Closeable { ws.cancel() }
        return ws
    }

    private fun text(size: Int, seed: Int = 0): String = buildString(size) { for (i in 0 until size) append('a' + (i * 7 + seed) % 26) }

    @Test
    fun everyFrameIsMaskedWithItsOwnSecureRandomKey() {
        val server = RawServer()
        val random = RecordingRandom()
        val events = Recorder()
        val ws = connect(server.url, events, random = random)
        server.accept()
        assertEquals("open", events.next())
        val message = text(3 * TetherWebSocket.FRAGMENT_BYTES + 10)
        assertTrue(ws.send("one"))
        assertTrue(ws.send(message))
        assertTrue(ws.send("two"))
        val frames = List(6) { server.readFrame() }
        // The handshake key came first from the same SecureRandom (16 bytes), as sent.
        val handshakeKey = random.outputs[0]
        assertEquals(16, handshakeKey.size)
        assertTrue(server.requestLines.contains("Sec-WebSocket-Key: ${Base64.getEncoder().encodeToString(handshakeKey)}"))
        // Then one fresh 4-byte draw per frame, in frame order, and it is that frame's key.
        val keys = random.outputs.drop(1)
        assertEquals(frames.size, keys.size)
        frames.forEachIndexed { i, f -> assertArrayEquals("frame $i", keys[i], f.key) }
        assertEquals("no two frames share a key", frames.size, frames.map { it.key.toByteString() }.toSet().size)
        assertEquals("one", String(frames[0].payload))
        assertEquals(message, frames.subList(1, 5).joinToString("") { String(it.payload) })
        assertEquals("two", String(frames[5].payload))
    }

    @Test
    fun aMessageIsFragmentedAtExactlyTheFragmentSize() {
        val server = RawServer()
        val events = Recorder()
        val ws = connect(server.url, events)
        server.accept()
        assertEquals("open", events.next())
        val f = TetherWebSocket.FRAGMENT_BYTES
        // A message of at most one fragment is one frame, as it always was.
        for (size in listOf(0, 1, f)) {
            assertTrue(ws.send(text(size)))
            val frame = server.readFrame()
            assertTrue("one final frame for $size", frame.fin)
            assertEquals(WebSocketFrames.OPCODE_TEXT, frame.opcode)
            assertEquals(size, frame.payload.size)
        }
        // One byte more is two frames: the fragment size, then the rest.
        val over = text(f + 1)
        assertTrue(ws.send(over))
        val a = server.readFrame()
        val b = server.readFrame()
        assertEquals(listOf(false to WebSocketFrames.OPCODE_TEXT, true to WebSocketFrames.OPCODE_CONTINUATION), listOf(a.fin to a.opcode, b.fin to b.opcode))
        assertEquals(listOf(f, 1), listOf(a.payload.size, b.payload.size))
        assertEquals(over, String(a.payload) + String(b.payload))
        // A binary message keeps its opcode on the first frame only.
        val bytes = ByteArray(2 * f) { it.toByte() }.toByteString()
        assertTrue(ws.send(bytes))
        val c = server.readFrame()
        val d = server.readFrame()
        assertEquals(listOf(false to WebSocketFrames.OPCODE_BINARY, true to WebSocketFrames.OPCODE_CONTINUATION), listOf(c.fin to c.opcode, d.fin to d.opcode))
        assertEquals(bytes, (c.payload + d.payload).toByteString())
        // Multi-byte characters may be split between fragments; the message is reassembled whole.
        val wide = "€".repeat(f / 3 + 5)
        assertTrue(ws.send(wide))
        val e1 = server.readFrame()
        val e2 = server.readFrame()
        assertEquals(wide, String(e1.payload + e2.payload, Charsets.UTF_8))
    }

    @Test
    fun messagesGoInOrderAndAreNeverInterleaved() {
        val server = RawServer()
        val events = Recorder()
        val ws = connect(server.url, events)
        server.accept()
        assertEquals("open", events.next())
        val sent = List(40) { i -> if (i % 3 == 0) text(TetherWebSocket.FRAGMENT_BYTES * 2 + i, i) else "small-$i" }
        // From several threads at once: each message is still whole and contiguous on the wire.
        val threads = sent.chunked(10).map { chunk -> Thread { chunk.forEach { assertTrue(ws.send(it)) } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val received = ArrayList<String>()
        var current: StringBuilder? = null
        while (received.size < sent.size) {
            val f = server.readFrame()
            if (current == null) {
                assertEquals("a message starts with a text frame", WebSocketFrames.OPCODE_TEXT, f.opcode)
                current = StringBuilder()
            } else {
                assertEquals("inside a message only continuations", WebSocketFrames.OPCODE_CONTINUATION, f.opcode)
            }
            current.append(String(f.payload))
            if (f.fin) {
                received += current.toString()
                current = null
            }
        }
        assertEquals(sent.toSet(), received.toSet())
        // Each sending thread's own messages kept their order.
        sent.chunked(10).forEach { chunk -> assertEquals(chunk, received.filter { it in chunk }) }
    }

    @Test
    fun theServersHeartbeatIsAnsweredBetweenTheFragmentsOfALongMessage() {
        val server = RawServer()
        val events = Recorder()
        val ws = connect(server.url, events)
        server.accept()
        assertEquals("open", events.next())
        val message = text(32 * 1024 * 1024)
        assertTrue(ws.send(message))
        // The message has begun (its first frame is here); the rest is still being written when the
        // server pings, as the Tether server does every 30 s.
        val frames = arrayListOf(server.readFrame())
        server.writeFrame(WebSocketFrames.OPCODE_PING, "beat".toByteArray())
        while (true) {
            val f = server.readFrame()
            frames += f
            if (f.opcode != WebSocketFrames.OPCODE_PONG && f.fin) break
        }
        val pongAt = frames.indexOfFirst { it.opcode == WebSocketFrames.OPCODE_PONG }
        assertTrue("the pong went between fragments, not after the message: at $pongAt of ${frames.size}", pongAt in 1 until frames.size - 1)
        assertEquals("beat", String(frames[pongAt].payload))
        assertTrue(frames[pongAt].fin)
        val data = frames.filterIndexed { i, _ -> i != pongAt }
        assertEquals(WebSocketFrames.OPCODE_TEXT, data.first().opcode)
        assertTrue(data.drop(1).all { it.opcode == WebSocketFrames.OPCODE_CONTINUATION })
        assertTrue(data.dropLast(1).none { it.fin })
        assertEquals(message.length, data.sumOf { it.payload.size })
        assertEquals(message, data.joinToString("") { String(it.payload) })
        // The writer lets go of the message once its last fragment is out (just after the server has it).
        awaitQueue(ws, 0L)
    }

    @Test
    fun aCloseWaitsForTheMessageBeingSentAndEndsCleanly() {
        val server = RawServer()
        val events = Recorder()
        val ws = connect(server.url, events)
        server.accept()
        assertEquals("open", events.next())
        val message = text(32 * 1024 * 1024)
        assertTrue(ws.send(message))
        assertTrue(ws.close(1000, "bye"))
        assertFalse("nothing is sent after the close", ws.send("late"))
        // The message is still going out ahead of the close when the server's heartbeat comes: as
        // with OkHttp, it is answered (the close frame is not out yet).
        val frames = arrayListOf(server.readFrame())
        server.writeFrame(WebSocketFrames.OPCODE_PING, "beat".toByteArray())
        while (true) {
            val f = server.readFrame()
            frames += f
            if (f.opcode == WebSocketFrames.OPCODE_CLOSE) break
        }
        val pong = frames.single { it.opcode == WebSocketFrames.OPCODE_PONG }
        assertEquals("beat", String(pong.payload))
        val data = frames.filter { it.opcode == WebSocketFrames.OPCODE_TEXT || it.opcode == WebSocketFrames.OPCODE_CONTINUATION }
        assertEquals(frames.size - 2, data.size)
        assertEquals(message, data.joinToString("") { String(it.payload) })
        assertTrue(data.last().fin)
        assertTrue("the close is last", frames.indexOf(pong) < frames.size - 1)
        assertEquals(Buffer().writeShort(1000).writeUtf8("bye").readByteString(), frames.last().payload.toByteString())
        server.writeFrame(WebSocketFrames.OPCODE_CLOSE, server.closePayload(1000))
        assertEquals("closing:1000", events.next())
        assertEquals("closed:1000", events.next())
        events.nothingMore()
        assertNull("the socket is released", server.readFrameOrNull())
    }

    @Test
    fun aCloseQueuedBehindMessagesGoesAfterAllOfThem() {
        val server = RawServer()
        val events = Recorder()
        // Queued before the upgrade completes: the order is the queue's alone.
        val ws = connect(server.url, events)
        val first = text(3 * TetherWebSocket.FRAGMENT_BYTES, 1)
        val second = text(2 * TetherWebSocket.FRAGMENT_BYTES + 7, 2)
        assertTrue(ws.send(first))
        assertTrue(ws.send(second))
        assertTrue(ws.close(1000, null))
        server.accept()
        assertEquals("open", events.next())
        val frames = ArrayList<Frame>()
        while (true) {
            val f = server.readFrame()
            frames += f
            if (f.opcode == WebSocketFrames.OPCODE_CLOSE) break
        }
        assertEquals(listOf(1, 0, 0, 1, 0, 0, 8), frames.map { it.opcode })
        assertEquals(first, frames.subList(0, 3).joinToString("") { String(it.payload) })
        assertEquals(second, frames.subList(3, 6).joinToString("") { String(it.payload) })
    }

    @Test
    fun aServerThatNeverAnswersTheCloseIsCutOffAfterTheCloseTimeout() {
        val server = RawServer()
        val events = Recorder()
        val ws = connect(server.url, events, client = OkHttpClient.Builder().webSocketCloseTimeout(300, TimeUnit.MILLISECONDS).build())
        server.accept()
        assertEquals("open", events.next())
        assertTrue(ws.close(1000, null))
        assertEquals(WebSocketFrames.OPCODE_CLOSE, server.readFrame().opcode)
        assertTrue(events.next().startsWith("failure:"))
        events.nothingMore()
    }

    @Test
    fun aCancelMidMessageEndsTheSocketAtOnceAndOnlyOnce() {
        val server = RawServer()
        val events = Recorder()
        val ws = connect(server.url, events)
        server.accept()
        assertEquals("open", events.next())
        assertTrue(ws.send(text(32 * 1024 * 1024)))
        val first = server.readFrame()
        assertFalse(first.fin)
        ws.cancel()
        assertTrue(events.next().startsWith("failure:"))
        events.nothingMore()
        // The server never sees the message end: no final frame, then the connection is gone.
        while (true) {
            val f = server.readFrameOrNull() ?: break
            assertFalse("the message must not complete after a cancel", f.fin)
        }
        assertFalse("nothing is sent after a cancel", ws.send("after"))
        assertFalse(ws.close(1000, null))
    }

    @Test
    fun aSendPastTheQueueBoundClosesGoingAwayAndIsRefused() {
        val server = RawServer()
        val events = Recorder()
        // Before the upgrade completes nothing is written, so the queue holds what was sent.
        val ws = connect(server.url, events, maxQueueBytes = 1000)
        assertTrue(ws.send(text(900)))
        assertEquals(900L, ws.queueSize())
        assertFalse("past the bound", ws.send(text(101)))
        assertFalse("and closing now", ws.send("x"))
        server.accept()
        assertEquals("open", events.next())
        val queued = server.readFrame()
        assertEquals(900, queued.payload.size)
        val close = server.readFrame()
        assertEquals(WebSocketFrames.OPCODE_CLOSE, close.opcode)
        assertEquals(1001, Buffer().write(close.payload).readShort().toInt())
    }

    /** A writer that breaks (here: its random source) fails the socket loudly, never silently. */
    @Test
    fun aBrokenWriterFailsTheSocketAndTheListenerHearsIt() {
        val server = RawServer()
        val events = Recorder()
        val draws = java.util.concurrent.atomic.AtomicInteger()
        val breaking = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                // The handshake key is drawn; the first frame's mask key is not.
                if (draws.incrementAndGet() > 1) throw IllegalStateException("no entropy")
                super.nextBytes(bytes)
            }
        }
        val ws = connect(server.url, events, random = breaking)
        server.accept()
        assertEquals("open", events.next())
        assertTrue(ws.send("x"))
        assertEquals("failure:IllegalStateException", events.next())
        events.nothingMore()
        assertFalse(ws.send("y"))
        assertNull("nothing unmasked went out, and the socket is closed", server.readFrameOrNull())
    }

    private fun awaitQueue(ws: WebSocket, expected: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (ws.queueSize() != expected && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(expected, ws.queueSize())
    }

    /**
     * r2 (security F2): the memory bound is on what is HELD. A message stays in memory whole until its
     * last fragment is out, so it counts whole until then; before, it dropped out fragment by
     * fragment and the queue could take another message the bound had no room for.
     */
    @Test
    fun aMessageCountsWholeInTheQueueUntilItsLastFragmentIsOut() {
        val server = RawServer()
        val events = Recorder()
        val mib = 1024 * 1024
        val ws = connect(server.url, events, maxQueueBytes = 28L * mib)
        server.accept()
        assertEquals("open", events.next())
        val message = text(24 * mib)
        assertTrue(ws.send(message))
        // The server takes the first fragment and then stops reading: the rest (well past the
        // loopback buffers) cannot all be out.
        val frames = arrayListOf(server.readFrame())
        Thread.sleep(200)
        assertEquals("still held, so still counted whole", 24L * mib, ws.queueSize())
        assertFalse("24 + 5 MiB would pass the 28 MiB bound", ws.send(text(5 * mib)))
        assertFalse("and the socket is closing", ws.send("x"))
        while (true) {
            val f = server.readFrame()
            frames += f
            if (f.opcode == WebSocketFrames.OPCODE_CLOSE) break
        }
        assertEquals(message, frames.dropLast(1).joinToString("") { String(it.payload) })
        assertEquals(1001, Buffer().write(frames.last().payload).readShort().toInt())
        awaitQueue(ws, 0L)
    }

    /**
     * r2 (security F2): one message over the server's own bound (LIMITS.WS_FRAME_BYTES, 32 MiB; its ws
     * `maxPayload` would close with 1009) is refused, OkHttp's contract for a send the socket cannot
     * take: false, and a graceful close (1001); nothing of it reaches the wire. The bound itself goes.
     */
    @Test
    fun aSingleMessageOverTheServersBoundIsRefusedAndTheBoundItselfGoes() {
        val bound = TetherWebSocket.SERVER_MESSAGE_BYTES.toInt()

        val atBound = RawServer()
        val okEvents = Recorder()
        val ok = connect(atBound.url, okEvents)
        atBound.accept()
        assertEquals("open", okEvents.next())
        assertTrue(ok.send(text(bound)))
        var received = 0L
        while (true) {
            val f = atBound.readFrame()
            received += f.payload.size
            if (f.fin) break
        }
        assertEquals(bound.toLong(), received)

        val over = RawServer()
        val overEvents = Recorder()
        val refused = connect(over.url, overEvents)
        over.accept()
        assertEquals("open", overEvents.next())
        assertFalse(refused.send(text(bound + 1)))
        assertEquals("nothing of it was queued", 0L, refused.queueSize())
        assertFalse(refused.send("after"))
        val close = over.readFrame()
        assertEquals("the close is the only frame", WebSocketFrames.OPCODE_CLOSE, close.opcode)
        assertEquals(1001, Buffer().write(close.payload).readShort().toInt())
        // A binary message is held to the same bound.
        val binary = RawServer()
        val binaryEvents = Recorder()
        val bin = connect(binary.url, binaryEvents)
        binary.accept()
        assertEquals("open", binaryEvents.next())
        assertFalse(bin.send(ByteArray(bound + 1).toByteString()))
        assertEquals(WebSocketFrames.OPCODE_CLOSE, binary.readFrame().opcode)
    }

    /**
     * r2 (security F1): the shared client's network interceptors never see the upgrade (OkHttp's own
     * WebSocket call skipped them too); its application interceptors do, as with OkHttp.
     */
    @Test
    fun theUpgradeSkipsNetworkInterceptorsAndKeepsApplicationOnes() {
        val server = RawServer()
        val events = Recorder()
        val network = java.util.concurrent.atomic.AtomicInteger()
        val application = java.util.concurrent.atomic.AtomicInteger()
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor { chain -> network.incrementAndGet(); chain.proceed(chain.request()) }
            .addInterceptor { chain -> application.incrementAndGet(); chain.proceed(chain.request()) }
            .build()
        val ws = connect(server.url, events, client = client)
        server.accept()
        assertEquals("open", events.next())
        assertTrue(ws.send("hi"))
        assertEquals("hi", String(server.readFrame().payload))
        assertEquals("no network interceptor ran", 0, network.get())
        assertEquals(1, application.get())
        assertEquals("the caller's client is untouched", 1, client.networkInterceptors.size)
    }

    /**
     * r2 (security F1): a call timeout on the shared client (a whole-call deadline for ordinary
     * requests) does not apply to the upgrade: a slow 101 still opens, and the socket outlives it.
     */
    @Test
    fun aCallTimeoutOnTheSharedClientDoesNotApplyToTheSocket() {
        val server = RawServer()
        val events = Recorder()
        val client = OkHttpClient.Builder().callTimeout(200, TimeUnit.MILLISECONDS).build()
        val ws = connect(server.url, events, client = client)
        server.accept(answerAfterMs = 500)
        assertEquals("open", events.next())
        Thread.sleep(300)
        assertTrue(ws.send("still here"))
        assertEquals("still here", String(server.readFrame().payload))
        events.nothingMore()
    }

    @Test
    fun aServerPingIsAnsweredWithItsPayload() {
        val server = RawServer()
        val events = Recorder()
        connect(server.url, events)
        server.accept()
        assertEquals("open", events.next())
        server.writeFrame(WebSocketFrames.OPCODE_PING, "p1".toByteArray())
        val pong = server.readFrame()
        assertEquals(WebSocketFrames.OPCODE_PONG, pong.opcode)
        assertEquals("p1", String(pong.payload))
        server.writeFrame(WebSocketFrames.OPCODE_TEXT, "hi".toByteArray())
        assertEquals("hi", events.messages.poll(20, TimeUnit.SECONDS))
    }

    @Test
    fun aServerPingIsToldToTheHookBeforeItsPongAndNeverReachesTheListenerAsAMessage() {
        val server = RawServer()
        val events = Recorder()
        val told = java.util.concurrent.LinkedBlockingQueue<TetherWebSocket>()
        val ws = TetherWebSocket.connect(OkHttpClient(), Request.Builder().url(server.url).build(), events, onPing = { told.put(it) })
        closers += Closeable { ws.cancel() }
        server.accept()
        assertEquals("open", events.next())
        assertTrue("no ping yet", told.isEmpty())
        server.writeFrame(WebSocketFrames.OPCODE_PING, "beat".toByteArray())
        assertTrue("the hook saw this socket", told.poll(20, TimeUnit.SECONDS) === ws)
        assertEquals(WebSocketFrames.OPCODE_PONG, server.readFrame().opcode)
        assertTrue("a ping is not a message", events.messages.isEmpty())
    }

    @Test
    fun aClientPingIntervalPingsAndAMissingPongFailsTheSocket() {
        val server = RawServer()
        val events = Recorder()
        connect(server.url, events, client = OkHttpClient.Builder().pingInterval(100, TimeUnit.MILLISECONDS).build())
        server.accept()
        assertEquals("open", events.next())
        val ping = server.readFrame()
        assertEquals(WebSocketFrames.OPCODE_PING, ping.opcode)
        // Not answered: the next interval fails the socket.
        assertEquals("failure:SocketTimeoutException", events.next())
        assertTrue(events.failure is SocketTimeoutException)
    }

    @Test
    fun anUpgradeAnswerThatDoesNotProveTheKeyFails() {
        val server = RawServer()
        val events = Recorder()
        connect(server.url, events)
        server.accept(accept = { rfcAccept(it + "x") })
        assertEquals("failure:ProtocolException", events.next())
        assertEquals(101, events.failureCode)
        events.nothingMore()
    }

    @Test
    fun anExtensionOrSubprotocolNobodyOfferedFailsTheUpgrade() {
        for (header in listOf("Sec-WebSocket-Extensions: permessage-deflate\r\n", "Sec-WebSocket-Protocol: chat\r\n")) {
            val server = RawServer()
            val events = Recorder()
            connect(server.url, events)
            server.accept(extraHeaders = header)
            assertEquals(header, "failure:ProtocolException", events.next())
        }
    }

    @Test
    fun theUpgradeOffersNoExtension() {
        val server = RawServer()
        val events = Recorder()
        connect(server.url, events)
        server.accept()
        assertEquals("open", events.next())
        assertTrue(server.requestLines.none { it.startsWith("Sec-WebSocket-Extensions", ignoreCase = true) })
        assertTrue(server.requestLines.contains("Sec-WebSocket-Version: 13"))
        assertTrue(server.requestLines.any { it.equals("Upgrade: websocket", ignoreCase = true) })
    }

    // ------------------------------------------------------------------
    // A conforming peer: OkHttp's server side, plain and over TLS
    // ------------------------------------------------------------------

    private fun mockServer(): MockWebServer = MockWebServer().also { s -> closers += Closeable { s.shutdown() } }

    private class ServerSide : WebSocketListener() {
        val sockets = LinkedBlockingQueue<WebSocket>()
        val messages = LinkedBlockingQueue<String>()
        override fun onOpen(webSocket: WebSocket, response: Response) = sockets.put(webSocket)
        override fun onMessage(webSocket: WebSocket, text: String) = messages.put(text)
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    /**
     * ta-xy3q: sockets with a fixed, large receive buffer, for a test that moves tens of MiB over
     * loopback. The kernel's receive-window autotuning on loopback (a 64 KiB MTU, so a 32 KiB MSS) can
     * pin a fresh connection's window under one MSS (`ss`: `rwnd_limited` 100%, window 24 KiB); the
     * sender then moves about 120 KB/s, so 20 MiB takes minutes, in about one run in four. That is the
     * host's TCP, not this socket's framing or writes (the writer was parked in the socket write, the
     * server's reader in its read, bytes arriving all along). A buffer fixed before the connection
     * opens takes the connection out of autotuning, so the window is open from the first byte.
     */
    private class BigReceiveWindow(private val bytes: Int = 8 * 1024 * 1024) {
        val sockets: SocketFactory = object : SocketFactory() {
            private val delegate = getDefault()
            private fun <S : Socket> S.sized(): S = apply { receiveBufferSize = bytes }
            override fun createSocket(): Socket = delegate.createSocket().sized()
            override fun createSocket(host: String, port: Int): Socket = delegate.createSocket(host, port).sized()
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                delegate.createSocket(host, port, localHost, localPort).sized()
            override fun createSocket(host: InetAddress, port: Int): Socket = delegate.createSocket(host, port).sized()
            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
                delegate.createSocket(address, port, localAddress, localPort).sized()
        }
        val serverSockets: ServerSocketFactory = object : ServerSocketFactory() {
            private val delegate = getDefault()
            private fun ServerSocket.sized(): ServerSocket = apply { receiveBufferSize = bytes }
            override fun createServerSocket(): ServerSocket = delegate.createServerSocket().sized()
            override fun createServerSocket(port: Int): ServerSocket = delegate.createServerSocket().sized().apply { bind(java.net.InetSocketAddress(port)) }
            override fun createServerSocket(port: Int, backlog: Int): ServerSocket =
                delegate.createServerSocket().sized().apply { bind(java.net.InetSocketAddress(port), backlog) }
            override fun createServerSocket(port: Int, backlog: Int, ifAddress: InetAddress): ServerSocket =
                delegate.createServerSocket().sized().apply { bind(java.net.InetSocketAddress(ifAddress, port), backlog) }
        }
    }

    @Test
    fun aMessagePastOkHttpsOld16MiBRoundTripsThroughAConformingServer() {
        val window = BigReceiveWindow()
        val server = mockServer()
        server.serverSocketFactory = window.serverSockets
        val side = ServerSide()
        server.enqueue(MockResponse().withWebSocketUpgrade(side))
        server.start()
        val events = Recorder()
        val ws = connect(server.url("/ws").toString(), events, client = OkHttpClient.Builder().socketFactory(window.sockets).build())
        assertEquals("open", events.next())
        val serverSocket = side.sockets.poll(20, TimeUnit.SECONDS)!!
        val up = text(20 * 1024 * 1024, 3)
        assertTrue(ws.send(up))
        assertTrue(ws.send("after"))
        assertEquals(up, side.messages.poll(60, TimeUnit.SECONDS))
        assertEquals("after", side.messages.poll(20, TimeUnit.SECONDS))
        // And the other way: the reader takes a server message of the same size.
        val down = text(12 * 1024 * 1024, 5)
        assertTrue(serverSocket.send(down))
        assertEquals(down, events.messages.poll(60, TimeUnit.SECONDS))
        assertTrue(ws.close(1000, null))
        assertEquals("closing:1000", events.next())
        assertEquals("closed:1000", events.next())
    }

    @Test
    fun theUpgradeCarriesTheCallersCredentialAndFollowsNoRedirect() {
        val server = mockServer()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"))
        server.start()
        val events = Recorder()
        connect(server.url("/ws").toString(), events, client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()) {
            header("Authorization", "Bearer device-token")
            header("Origin", "http://example.test")
        }
        assertEquals("failure:ProtocolException", events.next())
        assertEquals(302, events.failureCode)
        val upgrade = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/ws", upgrade.path)
        assertEquals("Bearer device-token", upgrade.getHeader("Authorization"))
        assertEquals("http://example.test", upgrade.getHeader("Origin"))
        assertEquals("websocket", upgrade.getHeader("Upgrade"))
        assertEquals("the redirect was not followed", 1, server.requestCount)
    }

    @Test
    fun aRefusedUpgradeReportsTheResponse() {
        val server = mockServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        val events = Recorder()
        connect(server.url("/ws").toString(), events)
        assertEquals("failure:ProtocolException", events.next())
        assertEquals(401, events.failureCode)
    }

    private class Tls(server: MockWebServer) {
        val root: HeldCertificate = HeldCertificate.Builder().certificateAuthority(0).build()
        val leaf: HeldCertificate = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName(server.hostName).build()
        val trusting: HandshakeCertificates = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build()

        init {
            val serverCerts = HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build()
            server.useHttps(serverCerts.sslSocketFactory(), false)
        }

        // "localhost" can resolve to 127.0.0.1 and ::1 (it does on the CI runner) while the server listens on one of
        // them; OkHttp tries the next address after a failure and would then throw that address's ConnectException
        // instead of the handshake failure. Resolve to exactly the address the server bound.
        private val address: InetAddress = InetAddress.getByName(server.hostName)
        private val onlyTheServersAddress = Dns { listOf(address) }

        fun client(pinner: CertificatePinner = CertificatePinner.DEFAULT): OkHttpClient =
            OkHttpClient.Builder().dns(onlyTheServersAddress).sslSocketFactory(trusting.sslSocketFactory(), trusting.trustManager).certificatePinner(pinner).build()

        /** A client whose trust is a different, test-made CA: hermetic (never the JDK's cacerts), and it must reject [leaf]. */
        fun clientTrustingAnotherCa(): OkHttpClient {
            val elsewhere = HeldCertificate.Builder().certificateAuthority(0).build()
            val certs = HandshakeCertificates.Builder().addTrustedCertificate(elsewhere.certificate).build()
            return OkHttpClient.Builder().dns(onlyTheServersAddress).sslSocketFactory(certs.sslSocketFactory(), certs.trustManager).build()
        }
    }

    @Test
    fun overTlsTheUpgradeUsesTheCallersTrustAndPins() {
        val server = mockServer()
        server.start()
        val tls = Tls(server)
        val side = ServerSide()
        val url = server.url("/ws").toString()
        assertTrue(url.startsWith("https://"))

        // Trusted by the caller's client, pinned to the right key: it opens and carries a message.
        server.enqueue(MockResponse().withWebSocketUpgrade(side))
        val pinned = CertificatePinner.Builder().add(server.hostName, CertificatePinner.pin(tls.leaf.certificate)).build()
        val ok = Recorder()
        val ws = connect(url, ok, client = tls.client(pinned))
        assertEquals("open", ok.next())
        assertTrue(ws.send("over tls"))
        assertEquals("over tls", side.messages.poll(20, TimeUnit.SECONDS))
        ws.close(1000, null)

        // A client that does not trust the server's CA never upgrades.
        server.enqueue(MockResponse().withWebSocketUpgrade(side))
        val untrusted = Recorder()
        connect(url, untrusted, client = tls.clientTrustingAnotherCa())
        assertTrue(untrusted.next().startsWith("failure:"))
        assertTrue("${untrusted.failure}", untrusted.failure is SSLHandshakeException)
        assertTrue(
            "the handshake failed on the server's certificate, not for another reason: ${untrusted.failure}",
            generateSequence(untrusted.failure) { it.cause }.any { it is CertificateException || it is CertPathValidatorException },
        )

        // Trusted but pinned to another key: refused by the caller's pinner.
        server.enqueue(MockResponse().withWebSocketUpgrade(side))
        val other = HeldCertificate.Builder().build()
        val wrongPin = CertificatePinner.Builder().add(server.hostName, CertificatePinner.pin(other.certificate)).build()
        val mispinned = Recorder()
        connect(url, mispinned, client = tls.client(wrongPin))
        assertEquals("failure:SSLPeerUnverifiedException", mispinned.next())
        assertTrue(mispinned.failure is SSLPeerUnverifiedException)
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw e
        }
        fail("expected ${T::class.java.simpleName}")
    }
}

private fun unmask(payload: ByteArray, key: ByteArray): ByteArray = ByteArray(payload.size) { i -> (payload[i].toInt() xor key[i and 3].toInt()).toByte() }
