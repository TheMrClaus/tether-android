package com.tether.app.client

import com.tether.app.client.BrowserChannel.UrlNotice
import com.tether.app.client.BrowserChannel.UrlNoticeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `state` frame's optional `urlNotice`, and the console-block `error` frame, as the web pane reads them. */
class BrowserChannelUrlNoticeTest {
    private val opener = FakeBrowserOpener()

    private fun opened(): Pair<BrowserChannel, FakeBrowserSocket> {
        val channel = BrowserChannel(opener, "s1")
        channel.connect()
        opener.last.serverOpens()
        return channel to opener.last
    }

    private fun state(notice: String?) =
        """{"t":"state","url":"http://10.0.0.5:3000/","title":"x"${if (notice != null) ""","urlNotice":$notice""" else ""}}"""

    @Test
    fun aDirectNonPublicNoticeIsDecoded() {
        val (channel, socket) = opened()
        socket.serverSends(state("""{"kind":"non_public","host":"10.0.0.5:3000","indirect":false}"""))
        assertEquals(UrlNotice(UrlNoticeKind.NonPublic, "10.0.0.5:3000", false), channel.ui.value.state.urlNotice)
    }

    @Test
    fun anIndirectNoticeAndTheConsoleKindAreDecoded() {
        val (channel, socket) = opened()
        socket.serverSends(state("""{"kind":"non_public","host":"localhost:9000","indirect":true}"""))
        assertEquals(UrlNotice(UrlNoticeKind.NonPublic, "localhost:9000", true), channel.ui.value.state.urlNotice)
        socket.serverSends(state("""{"kind":"console_blocked","host":"127.0.0.1:4173","indirect":false}"""))
        assertEquals(UrlNotice(UrlNoticeKind.ConsoleBlocked, "127.0.0.1:4173", false), channel.ui.value.state.urlNotice)
    }

    @Test
    fun aFrameWithoutTheFieldClearsTheNotice() {
        val (channel, socket) = opened()
        socket.serverSends(state("""{"kind":"non_public","host":"10.0.0.5","indirect":false}"""))
        socket.serverSends(state(null))
        assertNull(channel.ui.value.state.urlNotice)
    }

    @Test
    fun anUnknownKindOrMalformedValueIsNoNoticeAndNeverCrashes() {
        val (channel, socket) = opened()
        for (bad in listOf("""{"kind":"other","host":"h","indirect":false}""", "\"text\"", "7", "null", "[]", """{"host":"h"}""", """{"kind":3}""")) {
            socket.serverSends(state(bad))
            assertNull(bad, channel.ui.value.state.urlNotice)
        }
        socket.serverSends(state("""{"kind":"non_public","indirect":"true"}"""))
        assertEquals(UrlNotice(UrlNoticeKind.NonPublic, "", false), channel.ui.value.state.urlNotice)
    }

    @Test
    fun theConsoleBlockErrorFrameSetsTheNoticeNotTheError() {
        val (channel, socket) = opened()
        socket.serverSends("""{"t":"error","message":"${BrowserChannel.CONSOLE_BLOCKED_MESSAGE}"}""")
        assertEquals(UrlNotice(UrlNoticeKind.ConsoleBlocked, "", false), channel.ui.value.state.urlNotice)
        assertNull(channel.ui.value.error)
    }

    @Test
    fun theConsoleBlockErrorKeepsAnExistingNoticeAndOtherErrorsAreUnchanged() {
        val (channel, socket) = opened()
        val shown = """{"kind":"console_blocked","host":"localhost:4173","indirect":true}"""
        socket.serverSends(state(shown))
        socket.serverSends("""{"t":"error","message":"${BrowserChannel.CONSOLE_BLOCKED_MESSAGE}"}""")
        assertEquals(UrlNotice(UrlNoticeKind.ConsoleBlocked, "localhost:4173", true), channel.ui.value.state.urlNotice)
        socket.serverSends("""{"t":"error","message":"navigation failed"}""")
        assertEquals("navigation failed", channel.ui.value.error)
        assertEquals(UrlNoticeKind.ConsoleBlocked, channel.ui.value.state.urlNotice?.kind)
    }
}
