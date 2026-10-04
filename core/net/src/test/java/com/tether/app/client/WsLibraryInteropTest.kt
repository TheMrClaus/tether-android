package com.tether.app.client

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import com.tether.app.protocol.TetherJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * ta-coik.16: [TetherWebSocket] against the Tether server's own WebSocket library (`ws`, configured
 * as server.mjs configures it: `maxPayload` = LIMITS.WS_FRAME_BYTES, 32 MiB). Skipped unless
 * /tmp/ta-ws-interop-url.txt names a running loopback echo server (first line, `ws://…`), e.g.
 *
 * ```
 * // server.mjs — node, with the tether checkout's node_modules
 * import { createRequire } from "node:module"; import { createHash } from "node:crypto";
 * const { WebSocketServer } = createRequire(process.env.TETHER_NODE_MODULES + "/")("ws");
 * const wss = new WebSocketServer({ host: "127.0.0.1", port: 0, maxPayload: 32 * 1024 * 1024 });
 * wss.on("listening", () => console.log(`ws://127.0.0.1:${wss.address().port}/ws`));
 * wss.on("connection", (s) => {
 *   let pongs = 0; s.on("pong", () => pongs++); s.on("error", () => {});
 *   const beat = setInterval(() => s.ping(), 20); s.on("close", () => clearInterval(beat));
 *   s.on("message", (d) => s.send(JSON.stringify({ bytes: d.length, sha256: createHash("sha256").update(d).digest("hex"), pongs })));
 * });
 * ```
 *
 * It proves the server side reassembles the app's fragments byte for byte (the web's largest send
 * and the bound itself), answers its heartbeat pings meanwhile, and drops one byte more (1009).
 */
class WsLibraryInteropTest {

    private class Events : WebSocketListener() {
        val replies = LinkedBlockingQueue<JsonObject>()
        val ends = LinkedBlockingQueue<String>()
        override fun onOpen(webSocket: WebSocket, response: Response) = ends.put("open")
        override fun onMessage(webSocket: WebSocket, text: String) = replies.put(TetherJson.parseToJsonElement(text) as JsonObject)
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            ends.put("closing:$code")
            webSocket.close(1000, null)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = ends.put("failure:${t.javaClass.simpleName}")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun text(size: Int, seed: Int): String = buildString(size) { for (i in 0 until size) append('a' + (i * 13 + seed) % 26) }

    @Test
    fun theServersWsLibraryReassemblesEverythingUpToItsBoundAndNotMore() {
        val conf = File("/tmp/ta-ws-interop-url.txt")
        assumeTrue("ws interop server not present", conf.exists())
        val url = conf.readLines().first().trim()
        val events = Events()
        val ws = TetherWebSocket.connect(OkHttpClient(), Request.Builder().url(url).build(), events)
        try {
            assertEquals("open", events.ends.poll(20, TimeUnit.SECONDS))
            // The web's largest send (24 MiB of base64 plus its envelope), then the bound itself.
            var pongs = 0L
            for (size in listOf(24 * 1024 * 1024 + 4096, TetherWebSocket.SERVER_MESSAGE_BYTES.toInt())) {
                val message = text(size, size % 7)
                assertTrue(ws.send(message))
                val reply = events.replies.poll(60, TimeUnit.SECONDS)!!
                assertEquals(size.toLong(), reply["bytes"]!!.jsonPrimitive.content.toLong())
                assertEquals(sha256(message.toByteArray()), reply["sha256"]!!.jsonPrimitive.content)
                pongs = reply["pongs"]!!.jsonPrimitive.content.toLong()
                println("ws interop: $size bytes reassembled, $pongs heartbeat pongs answered so far")
            }
            assertTrue("the heartbeat was answered", pongs > 0)
            // One byte past the bound: ws closes with 1009 (message too big).
            assertTrue(ws.send(text(TetherWebSocket.SERVER_MESSAGE_BYTES.toInt() + 1, 1)))
            assertEquals("closing:1009", events.ends.poll(60, TimeUnit.SECONDS))
        } finally {
            ws.cancel()
        }
    }
}
