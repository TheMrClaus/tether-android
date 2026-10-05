package com.tether.app.client

import java.util.Base64
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A `/ws-browser` socket the test drives by hand: what the client sent, and the server's side. */
class FakeBrowserSocket : BrowserSocket {
    val sent = mutableListOf<String>()
    var closed = false
    lateinit var listener: BrowserSocketListener

    override fun send(text: String): Boolean {
        sent += text
        return !closed
    }

    override fun close() {
        closed = true
    }

    fun serverOpens() = listener.onOpen(this)
    fun serverSends(text: String) = listener.onMessage(text)
}

class FakeBrowserOpener : BrowserSocketOpener {
    val opened = mutableListOf<Pair<String, FakeBrowserSocket>>()

    override fun open(sessionId: String, listener: BrowserSocketListener): BrowserSocketOpen {
        val socket = FakeBrowserSocket().also { it.listener = listener }
        opened += sessionId to socket
        return BrowserSocketOpen.Opened(socket)
    }

    val last: FakeBrowserSocket get() = opened.last().second
}

/** T8.6: the `/ws-browser` client against use-browser.ts (90fbb9f) frame for frame. */
class BrowserChannelTest {
    private val opener = FakeBrowserOpener()

    private fun opened(): Pair<BrowserChannel, FakeBrowserSocket> {
        val channel = BrowserChannel(opener, "s1")
        channel.connect()
        val socket = opener.last
        socket.serverOpens()
        return channel to socket
    }

    @Test
    fun connectOpensTheSessionsChannelAndSendsOpen() {
        val channel = BrowserChannel(opener, "s1")
        assertFalse(channel.ui.value.connected)
        channel.connect()
        assertEquals("s1", opener.opened.single().first)
        // Nothing goes out before the socket is open (use-browser.ts :118-121).
        assertFalse(channel.navigate("https://a.test"))
        assertTrue(opener.last.sent.isEmpty())
        opener.last.serverOpens()
        assertTrue(channel.ui.value.connected)
        assertNull(channel.ui.value.error)
        // :130: `{t:"open"}` exactly, no url key.
        assertEquals(listOf("""{"t":"open"}"""), opener.last.sent)
        // A second connect() never opens a second socket.
        channel.connect()
        assertEquals(1, opener.opened.size)
    }

    @Test
    fun outgoingFramesMatchTheWebs() {
        val (channel, socket) = opened()
        socket.sent.clear()
        channel.navigate("https://localhost:3000")
        channel.setViewport(390, 844, mobile = true)
        channel.close()
        channel.open(null)
        channel.setPickMode(true)
        channel.hover(3, 4)
        channel.pick(5, 6, screenshot = true)
        assertEquals(
            listOf(
                """{"t":"navigate","url":"https://localhost:3000"}""",
                """{"t":"viewport","width":390,"height":844,"mobile":true,"deviceScaleFactor":1}""",
                """{"t":"close"}""",
                """{"t":"open","url":null}""",
                """{"t":"pick-mode","on":true}""",
                """{"t":"hover","x":3,"y":4}""",
                """{"t":"pick","x":5,"y":6,"screenshot":true}""",
            ),
            socket.sent,
        )
        assertTrue("pick mode flips locally at once", channel.ui.value.state.pickMode)
    }

    @Test
    fun inputFramesLeaveOutAbsentFields() {
        val (channel, socket) = opened()
        socket.sent.clear()
        channel.sendInput(BrowserInput("mousePressed", x = 10, y = 20, button = "left", buttons = 1, clickCount = 1, modifiers = 0))
        channel.sendInput(BrowserInput("mouseWheel", x = 1, y = 2, deltaX = 0.0, deltaY = 100.0, modifiers = 8))
        channel.sendInput(BrowserInput("keyDown", key = "a", code = "KeyA", text = "a", modifiers = 0))
        assertEquals(
            listOf(
                """{"t":"input","event":{"type":"mousePressed","x":10,"y":20,"button":"left","buttons":1,"clickCount":1,"modifiers":0}}""",
                """{"t":"input","event":{"type":"mouseWheel","x":1,"y":2,"deltaX":0.0,"deltaY":100.0,"modifiers":8}}""",
                """{"t":"input","event":{"type":"keyDown","modifiers":0,"key":"a","code":"KeyA","text":"a"}}""",
            ),
            socket.sent,
        )
        assertEquals(1 or 2 or 4 or 8, BrowserInput.modifiers(alt = true, ctrl = true, meta = true, shift = true))
        assertEquals(8, BrowserInput.modifiers(alt = false, ctrl = false, meta = false, shift = true))
    }

    @Test
    fun framesAreDecodedFromBase64Jpeg() {
        val (channel, socket) = opened()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        val b64 = Base64.getEncoder().encodeToString(jpeg)
        socket.serverSends("""{"t":"frame","id":7,"data":"$b64","metadata":{"deviceWidth":390}}""")
        val first = channel.frames.value!!
        assertArrayEquals(jpeg, first.jpeg)
        // Not base64, or empty: nothing is drawn; the last good frame stays.
        socket.serverSends("""{"t":"frame","id":8,"data":"%%%"}""")
        socket.serverSends("""{"t":"frame","id":9,"data":""}""")
        assertEquals(first.id, channel.frames.value!!.id)
        socket.serverSends("""{"t":"frame","id":10,"data":"$b64"}""")
        assertTrue(channel.frames.value!!.id > first.id)
        // Garbage is ignored, as JSON.parse's catch.
        socket.serverSends("not json")
        assertTrue(channel.ui.value.connected)
    }

    @Test
    fun stateNavigatedClosedAndErrorFoldAsTheHookDoes() {
        val (channel, socket) = opened()
        socket.serverSends(
            """{"t":"state","sessionId":"s1","url":"https://a.test/","title":"A","viewport":{"width":390,"height":844,"mobile":true,"deviceScaleFactor":1},"pickMode":false,"loading":true}""",
        )
        var s = channel.ui.value.state
        assertEquals("https://a.test/", s.url)
        assertEquals("A", s.title)
        assertEquals(BrowserChannel.Viewport(390, 844, true, 1.0), s.viewport)
        assertTrue(s.loading)
        socket.serverSends("""{"t":"navigated","url":"https://b.test/","title":"B"}""")
        s = channel.ui.value.state
        assertEquals("https://b.test/", s.url)
        assertEquals("B", s.title)
        assertFalse(s.loading)
        assertEquals(390, s.viewport.width)
        // `state` without a viewport keeps the previous one; pickMode/loading are Boolean(x).
        socket.serverSends("""{"t":"state","url":"https://c.test/","title":"","pickMode":1}""")
        s = channel.ui.value.state
        assertEquals(390, s.viewport.width)
        assertTrue(s.pickMode)
        assertFalse(s.loading)
        socket.serverSends("""{"t":"error","message":"navigation failed"}""")
        assertEquals("navigation failed", channel.ui.value.error)
        assertTrue("an error frame does not drop the link", channel.ui.value.connected)
        socket.serverSends("""{"t":"error"}""")
        assertEquals("browser error", channel.ui.value.error)
        socket.serverSends("""{"t":"closed","reason":"closed by operator"}""")
        assertEquals(BrowserChannel.State(), channel.ui.value.state)
        assertEquals(BrowserChannel.DEFAULT_VIEWPORT, BrowserChannel.State().viewport)
    }

    @Test
    fun pickMessagesReachThePartTwoSeam() {
        val (channel, socket) = opened()
        val got = runBlocking {
            // Undispatched: the collector subscribes before the server speaks.
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { channel.pickEvents.first() } }
            socket.serverSends("""{"t":"hover","box":{"x":1,"y":2,"width":3,"height":4},"label":"div"}""")
            waiter.await()
        }
        assertEquals(Json.parseToJsonElement("""{"t":"hover","box":{"x":1,"y":2,"width":3,"height":4},"label":"div"}""").jsonObject, got)
    }

    @Test
    fun aFailedSocketSaysSoAndStopsSending() {
        val (channel, socket) = opened()
        socket.listener.onFailure()
        assertFalse(channel.ui.value.connected)
        assertEquals(BrowserChannel.CONNECTION_ERROR, channel.ui.value.error)
        socket.sent.clear()
        assertFalse(channel.navigate("https://a.test"))
        assertTrue(socket.sent.isEmpty())
    }

    @Test
    fun aServerCloseDropsTheLinkWithoutAnError() {
        val (channel, socket) = opened()
        socket.listener.onClosed()
        assertFalse(channel.ui.value.connected)
        assertNull(channel.ui.value.error)
    }

    @Test
    fun disposeClosesAndANewPaneReconnects() {
        val (channel, socket) = opened()
        channel.dispose()
        assertTrue(socket.closed)
        assertFalse(channel.ui.value.connected)
        // A disposed channel never reopens, and late callbacks are ignored.
        channel.connect()
        assertEquals(1, opener.opened.size)
        socket.serverSends("""{"t":"error","message":"late"}""")
        assertNull(channel.ui.value.error)
        // Reopening the pane is a new channel: a new socket, `open` again (use-browser.ts :124-132).
        val (again, second) = opened()
        assertEquals(2, opener.opened.size)
        assertTrue(again.ui.value.connected)
        assertEquals(listOf("""{"t":"open"}"""), second.sent)
    }

    @Test
    fun aRefusedOpenShowsWhy() {
        val channel = BrowserChannel(BrowserSocketOpener.Unavailable, "s1")
        channel.connect()
        assertFalse(channel.ui.value.connected)
        assertEquals(BrowserSocketOpener.SIGNED_OUT, channel.ui.value.error)
    }
}
