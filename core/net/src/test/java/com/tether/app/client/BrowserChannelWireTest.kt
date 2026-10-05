package com.tether.app.client

import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8.6: the real `/ws-browser` upgrade. server.mjs (90fbb9f :8745-8765) admits it exactly as `/ws`:
 * the same principal (cookie or device token) and, for the ambient cookie, the same console Origin.
 * So the upgrade carries the credential and Origin the app's `/ws` upgrade carried, and the
 * session in `?sessionId=`; then frames flow both ways on the app's own transport.
 */
class BrowserChannelWireTest {
    private val h = ConnectionHarness()
    private val serverSockets = LinkedBlockingQueue<WebSocket>()
    private val serverReceived = LinkedBlockingQueue<String>()

    @After fun tearDown() {
        while (true) runCatching { (serverSockets.poll() ?: break).close(1000, null) }
        h.close()
    }

    private val browserServer = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            serverSockets.put(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            serverReceived.put(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    private fun take(): RecordedRequest = h.server.takeRequest(20, TimeUnit.SECONDS)!!

    /** Signed in over a live `/ws`; returns that upgrade. */
    private fun connected(deviceToken: String? = null): RecordedRequest {
        h.newClient(deviceToken = deviceToken)
        h.enqueueConnect()
        h.client.start()
        h.handshake(h.nextSocket())
        assertEquals("/api/auth/session", take().path)
        return take().also { assertEquals("/ws", it.path) }
    }

    private fun openBrowser(): Pair<BrowserChannel, RecordedRequest> {
        h.server.enqueue(MockResponse().withWebSocketUpgrade(browserServer))
        val channel = BrowserChannel(h.client.browserSockets, "session 1")
        channel.connect()
        val upgrade = take()
        h.await(channel.ui) { it.connected }
        return channel to upgrade
    }

    @Test
    fun theCookieUpgradeCarriesTheSocketsCredentialAndOrigin() {
        val ws = connected()
        val (channel, upgrade) = openBrowser()
        assertEquals("/ws-browser?sessionId=session%201", upgrade.path)
        assertEquals("tether_session=cookie", upgrade.getHeader("Cookie"))
        assertNull(upgrade.getHeader("Authorization"))
        assertNotNull(ws.getHeader("Origin"))
        assertEquals(ws.getHeader("Origin"), upgrade.getHeader("Origin"))

        // Client → server: `open` on connect, then what the pane asks for.
        assertEquals("""{"t":"open"}""", serverReceived.poll(20, TimeUnit.SECONDS))
        channel.navigate("https://localhost:3000")
        assertEquals("""{"t":"navigate","url":"https://localhost:3000"}""", serverReceived.poll(20, TimeUnit.SECONDS))

        // Server → client: a screencast frame arrives decoded.
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 8, 7)
        val server = serverSockets.poll(20, TimeUnit.SECONDS)!!
        server.send("""{"t":"frame","id":1,"data":"${Base64.getEncoder().encodeToString(jpeg)}","metadata":{}}""")
        val frame = h.await(channel.frames) { it != null }!!
        assertArrayEquals(jpeg, frame.jpeg)

        // Closing the pane closes the socket; the server sees a normal close.
        channel.dispose()
        h.await(channel.ui) { !it.connected }
    }

    @Test
    fun aDeviceTokenUpgradeCarriesTheBearer() {
        val ws = connected(deviceToken = "tok")
        val (_, upgrade) = openBrowser()
        assertEquals("/ws-browser?sessionId=session%201", upgrade.path)
        assertEquals("Bearer tok", upgrade.getHeader("Authorization"))
        assertEquals(ws.getHeader("Authorization"), upgrade.getHeader("Authorization"))
        assertNull(upgrade.getHeader("Cookie"))
        // The /ws upgrade sends the console Origin with either credential; so does this one.
        assertEquals(ws.getHeader("Origin"), upgrade.getHeader("Origin"))
    }

    @Test
    fun aRefusedUpgradeIsAConnectionError() {
        connected()
        h.server.enqueue(MockResponse().setResponseCode(401))
        val channel = BrowserChannel(h.client.browserSockets, "s1")
        channel.connect()
        assertEquals("/ws-browser?sessionId=s1", take().path)
        val ui = h.await(channel.ui) { it.error != null }
        assertEquals(BrowserChannel.CONNECTION_ERROR, ui.error)
        assertTrue(!ui.connected)
    }

    @Test
    fun signedOutOpensNothing() {
        h.newClient(configured = false)
        val channel = BrowserChannel(h.client.browserSockets, "s1")
        channel.connect()
        assertEquals(BrowserSocketOpener.SIGNED_OUT, channel.ui.value.error)
        assertEquals(0, h.server.requestCount)
    }
}
