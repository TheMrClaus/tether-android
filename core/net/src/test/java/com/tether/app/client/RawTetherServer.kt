package com.tether.app.client

import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * W7 H0 instrument (R-B1): a loopback "Tether" that speaks the auth probe over plain HTTP and then the
 * WebSocket upgrade by hand, so a test can write a WS control frame (a server PING, which MockWebServer
 * cannot) and read the raw frames the client's REAL socket path puts on the wire (a PONG, which it cannot
 * show). Every accepted upgrade becomes a [RawWs] in [sockets].
 */
class RawTetherServer : Closeable {
    private val listening = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val sockets = LinkedBlockingQueue<RawWs>()
    private val open = java.util.concurrent.CopyOnWriteArrayList<Socket>()

    val baseUrl: String get() = "http://127.0.0.1:${listening.localPort}"

    private val acceptor = Thread {
        try {
            while (true) {
                val s = listening.accept()
                open += s
                Thread { serve(s) }.apply { isDaemon = true }.start()
            }
        } catch (_: IOException) {
        }
    }.apply { isDaemon = true; start() }

    private fun serve(socket: Socket) {
        try {
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = socket.getOutputStream()
            while (true) {
                val lines = ArrayList<String>()
                while (true) {
                    val line = readLine(input)
                    if (line.isEmpty()) break
                    lines += line
                }
                val key = lines.firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }?.substringAfter(':')?.trim()
                if (key == null) {
                    val body = """{"authenticated":true}""".toByteArray()
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray())
                    output.write(body)
                    output.flush()
                    continue
                }
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                )
                output.write(
                    ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(),
                )
                output.flush()
                sockets.put(RawWs(socket, input, output))
                return
            }
        } catch (_: IOException) {
        }
    }

    private fun readLine(input: DataInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) throw EOFException()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    fun nextSocket(): RawWs = sockets.poll(20, TimeUnit.SECONDS) ?: error("client never reached the ws upgrade")

    override fun close() {
        runCatching { listening.close() }
        open.forEach { runCatching { it.close() } }
    }
}

/** One accepted upgrade. A reader thread files every client frame: data into [texts], control frames into [controls]. */
class RawWs(private val socket: Socket, input: DataInputStream, private val output: OutputStream) {
    /** A client control frame as read off the wire, stamped (System.nanoTime) when it was read. */
    class Control(val opcode: Int, val payload: ByteArray, val readAtNanos: Long)

    val texts = LinkedBlockingQueue<String>()
    val controls = LinkedBlockingQueue<Control>()
    @Volatile var ended = false

    init {
        Thread {
            try {
                var partial: java.io.ByteArrayOutputStream? = null
                while (true) {
                    val b0 = input.readUnsignedByte()
                    val b1 = input.readUnsignedByte()
                    var length = (b1 and 0x7f).toLong()
                    if (length == 126L) length = input.readUnsignedShort().toLong() else if (length == 127L) length = input.readLong()
                    val key = ByteArray(4).also { input.readFully(it) }
                    val payload = ByteArray(length.toInt()).also { input.readFully(it) }
                    for (i in payload.indices) payload[i] = (payload[i].toInt() xor key[i % 4].toInt()).toByte()
                    val at = System.nanoTime()
                    val fin = b0 and 0x80 != 0
                    when (val opcode = b0 and 0x0f) {
                        0x1, 0x2 -> if (fin) texts.put(String(payload)) else partial = java.io.ByteArrayOutputStream().apply { write(payload) }
                        0x0 -> {
                            partial!!.write(payload)
                            if (fin) texts.put(String(partial.toByteArray()))
                        }
                        else -> controls.put(Control(opcode, payload, at))
                    }
                }
            } catch (_: IOException) {
            } finally {
                ended = true
            }
        }.apply { isDaemon = true; start() }
    }

    @Synchronized
    fun writeFrame(opcode: Int, payload: ByteArray) {
        val header = java.io.ByteArrayOutputStream()
        header.write(0x80 or opcode)
        when {
            payload.size <= 125 -> header.write(payload.size)
            payload.size <= 0xffff -> { header.write(126); header.write(payload.size shr 8); header.write(payload.size and 0xff) }
            else -> { header.write(127); for (s in 56 downTo 0 step 8) header.write(((payload.size.toLong() shr s) and 0xff).toInt()) }
        }
        output.write(header.toByteArray())
        output.write(payload)
        output.flush()
    }

    fun send(text: String) = writeFrame(0x1, text.toByteArray())

    fun ping(payload: String) = writeFrame(0x9, payload.toByteArray())

    /** The next client text frame of [type] (others are skipped), or null after [seconds]. */
    fun nextText(seconds: Long = 20): String? = texts.poll(seconds, TimeUnit.SECONDS)

    fun nextControl(seconds: Long = 5): Control? = controls.poll(seconds, TimeUnit.SECONDS)

    fun close() = runCatching { socket.close() }
}
