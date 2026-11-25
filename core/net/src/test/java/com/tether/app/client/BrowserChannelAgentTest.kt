package com.tether.app.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pane socket's agent state, tab ids and profile pick, against lib/browser-agent-ui.ts and
 * hooks/use-browser.ts at 0bd07fdd, frame for frame. The clock is the test's own.
 */
class BrowserChannelAgentTest {
    private val opener = FakeBrowserOpener()
    private var now = 100_000L

    private fun opened(): Pair<BrowserChannel, FakeBrowserSocket> {
        val channel = BrowserChannel(opener, "s1", clock = { now })
        channel.connect()
        val socket = opener.last
        socket.serverOpens()
        socket.sent.clear()
        return channel to socket
    }

    private fun BrowserChannel.agentNow() = agent.value

    private fun start(socket: FakeBrowserSocket, box: String = ""","box":{"x":10,"y":20,"width":30,"height":40}""", kind: String = "click") =
        socket.serverSends("""{"t":"agent","phase":"start","kind":"$kind"$box}""")

    // ---- agent frames ----------------------------------------------------------------------

    @Test
    fun startDrawsAMarkerForOnePointSixSecondsAndAnnouncesWhenFresh() {
        val (channel, socket) = opened()
        start(socket)
        val a = channel.agentNow()
        assertTrue(a.running)
        assertEquals("click", a.kind)
        assertEquals(now, a.startedAt)
        assertEquals(AgentMarker(1, "click", AgentBox(10.0, 20.0, 30.0, 40.0), now + 1600), a.marker)
        assertEquals(BrowserAgentUi.AGENT_ANNOUNCEMENT, a.announce)
        assertEquals(now, a.announcedAt)
        assertEquals(1600L, channel.agentDeadlineMs())
        // At the deadline the marker is gone; the caption stays while the action runs.
        now += 1600
        channel.expireAgent()
        assertNull(channel.agentNow().marker)
        assertTrue(channel.agentNow().running)
    }

    @Test
    fun endHoldsTheCaptionForNineHundredMillisecondsThenLetsGo() {
        val (channel, socket) = opened()
        start(socket)
        now += 300
        socket.serverSends("""{"t":"agent","phase":"end","kind":"click"}""")
        assertTrue(channel.agentNow().running)
        assertEquals(100_000L + 900, channel.agentNow().settleAt)
        assertEquals(600L, channel.agentDeadlineMs()) // the settle (600) comes before the marker (1300)
        now += 600
        channel.expireAgent()
        assertFalse(channel.agentNow().running)
        assertNull(channel.agentNow().settleAt)
        // A slow action ends at once.
        now += 5000
        start(socket)
        now += 1000
        socket.serverSends("""{"t":"agent","phase":"end"}""")
        assertFalse(channel.agentNow().running)
        assertNull(channel.agentNow().settleAt)
    }

    @Test
    fun endWhileIdleChangesNothing() {
        val (channel, socket) = opened()
        val before = channel.agentNow()
        socket.serverSends("""{"t":"agent","phase":"end","kind":"click"}""")
        assertEquals(before, channel.agentNow())
    }

    @Test
    fun tookOverReplacesTheCaptionClearsTheMarkerAndAnnouncesEveryTime() {
        val (channel, socket) = opened()
        start(socket)
        now += 10
        socket.serverSends("""{"t":"agent","phase":"took_over","idleMs":3000}""")
        val a = channel.agentNow()
        assertTrue(a.tookOver)
        assertNull(a.marker)
        assertEquals(BrowserAgentUi.TOOK_OVER_DETAIL, a.announce)
        assertEquals(now, a.announcedAt)
        val caption = BrowserAgentUi.caption(a)!!
        assertTrue(caption.tookOver)
        assertEquals("You took over", caption.text)
        // While the operator holds the browser nothing is drawn and nothing is announced for the agent.
        now += 10
        start(socket)
        assertNull(channel.agentNow().marker)
        assertEquals(BrowserAgentUi.TOOK_OVER_DETAIL, channel.agentNow().announce)
        // A second took_over announces again.
        now += 10
        socket.serverSends("""{"t":"agent","phase":"took_over","idleMs":3000}""")
        assertEquals(now, channel.agentNow().announcedAt)
    }

    @Test
    fun resumedClearsTookOverAndAnnouncesEveryTimeButOnlyWhenTookOver() {
        val (channel, socket) = opened()
        val before = channel.agentNow()
        socket.serverSends("""{"t":"agent","phase":"resumed"}""")
        assertEquals(before, channel.agentNow())
        socket.serverSends("""{"t":"agent","phase":"took_over","idleMs":3000}""")
        now += 50
        socket.serverSends("""{"t":"agent","phase":"resumed"}""")
        assertFalse(channel.agentNow().tookOver)
        assertEquals(BrowserAgentUi.RESUMED_ANNOUNCEMENT, channel.agentNow().announce)
        assertEquals(now, channel.agentNow().announcedAt)
    }

    @Test
    fun theAgentSentenceIsAnnouncedOnlyWhenFreshNotWhileRunningAndNotWithinEightSeconds() {
        val (channel, socket) = opened()
        start(socket)
        val first = channel.agentNow().announcedAt
        // Running: a second start is not announced.
        now += 20_000
        start(socket)
        assertEquals(first, channel.agentNow().announcedAt)
        now += 1000
        socket.serverSends("""{"t":"agent","phase":"end"}""")
        assertFalse(channel.agentNow().running)
        // Idle but 8 s or less since the last: not announced; just over 8 s: announced.
        val last = channel.agentNow().announcedAt
        now = last + 8000
        start(socket)
        assertEquals(last, channel.agentNow().announcedAt)
        now += 1000
        socket.serverSends("""{"t":"agent","phase":"end"}""")
        now = last + 8001
        start(socket)
        assertEquals(now, channel.agentNow().announcedAt)
    }

    @Test
    fun aNonStringKindIsActionAndALongKindIsCutToForty() {
        val (channel, socket) = opened()
        socket.serverSends("""{"t":"agent","phase":"start","kind":7}""")
        assertEquals("action", channel.agentNow().kind)
        socket.serverSends("""{"t":"agent","phase":"start"}""")
        assertEquals("action", channel.agentNow().kind)
        socket.serverSends("""{"t":"agent","phase":"start","kind":"${"k".repeat(60)}"}""")
        assertEquals("k".repeat(40), channel.agentNow().kind)
    }

    @Test
    fun aBadBoxStartsWithoutAMarker() {
        val (channel, socket) = opened()
        val bad = listOf(
            ""","box":{"x":1,"y":2,"width":-1,"height":4}""",
            ""","box":{"x":1,"y":2,"width":3}""",
            ""","box":{"x":"1","y":2,"width":3,"height":4}""",
            ""","box":{"x":1e999,"y":2,"width":3,"height":4}""",
            ""","box":5""",
            ""","box":null""",
            "",
        )
        for (box in bad) {
            start(socket, box)
            assertTrue(box, channel.agentNow().running)
            assertNull(box, channel.agentNow().marker)
        }
        // A point (size 0) is a valid box.
        start(socket, ""","box":{"x":5,"y":6,"width":0,"height":0}""")
        assertEquals(AgentBox(5.0, 6.0, 0.0, 0.0), channel.agentNow().marker!!.box)
        val p = BrowserAgentUi.placement(AgentBox(5.0, 6.0, 0.0, 0.0), 100, 200)
        assertTrue(p.point)
        assertEquals(5.0, p.left, 1e-9)
        assertEquals(3.0, p.top, 1e-9)
    }

    @Test
    fun anUnknownPhaseOrNonStringPhaseChangesNothing() {
        val (channel, socket) = opened()
        val before = channel.agentNow()
        socket.serverSends("""{"t":"agent","phase":"dance"}""")
        socket.serverSends("""{"t":"agent","phase":5}""")
        socket.serverSends("""{"t":"agent"}""")
        assertEquals(before, channel.agentNow())
    }

    @Test
    fun theAgentStateIsResetOnSocketOpenAndOnClosed() {
        val (channel, socket) = opened()
        start(socket)
        socket.serverSends("""{"t":"closed","reason":"closed by operator"}""")
        assertEquals(BrowserAgentUi.INITIAL, channel.agentNow())
        start(socket)
        assertTrue(channel.agentNow().running)
        // A new socket open (as a reconnecting pane) starts clean: drive onOpen again on the same listener.
        socket.serverOpens()
        assertEquals(BrowserAgentUi.INITIAL, channel.agentNow())
    }

    @Test
    fun captionTitleNamesTheKindInPlainWords() {
        val a = BrowserAgentUi.reduce(BrowserAgentUi.INITIAL, kotlinx.serialization.json.Json.parseToJsonElement("""{"phase":"start","kind":"tabs"}""") as kotlinx.serialization.json.JsonObject, 5000)
        assertEquals("Agent is using the browser (tab list)", BrowserAgentUi.caption(a)!!.title)
        assertEquals("Agent", BrowserAgentUi.caption(a)!!.text)
    }

    // ---- tab id and switch -------------------------------------------------------------------

    @Test
    fun theStateFrameCarriesTabIds() {
        val (channel, socket) = opened()
        socket.serverSends("""{"t":"state","url":"https://a.test","tabs":[{"id":"t-9","index":1,"active":true,"url":"https://a.test","title":"A"},{"id":"t-10","index":2,"active":false,"url":"","title":""}]}""")
        val tabs = channel.ui.value.state.tabs
        assertEquals(listOf("t-9", "t-10"), tabs.map { it.id })
        assertEquals("t-9", channel.ui.value.state.activeTab!!.id)
    }

    @Test
    fun switchTabSendsTheWebsExactInputFrame() {
        val (channel, socket) = opened()
        assertTrue(channel.switchTab("t-10"))
        assertEquals(listOf("""{"t":"input","event":{"type":"tab","action":"switch","id":"t-10"}}"""), socket.sent)
    }

    // ---- profile ---------------------------------------------------------------------------

    @Test
    fun connectSendsTheProfileQueryThenOpen() {
        val channel = BrowserChannel(opener, "s1", clock = { now })
        channel.connect()
        opener.last.serverOpens()
        assertEquals(listOf("""{"t":"profile"}""", """{"t":"open"}"""), opener.last.sent)
        opener.last.sent.clear()
        assertTrue(channel.queryProfile())
        assertEquals(listOf("""{"t":"profile"}"""), opener.last.sent)
    }

    @Test
    fun pickProfileSendsAnIdOrNullInTheWebsForm() {
        val (channel, socket) = opened()
        channel.pickProfile("p-1")
        channel.pickProfile(null)
        assertEquals(listOf("""{"t":"profile","profileId":"p-1"}""", """{"t":"profile","profileId":null}"""), socket.sent)
    }

    @Test
    fun theStateFrameProfileIsDecodedAbsentAndNullAreTheDefault() {
        val (channel, socket) = opened()
        socket.serverSends("""{"t":"state","url":"u","profile":{"id":"p-1","name":"Work"}}""")
        assertEquals(BrowserChannel.PaneProfile("p-1", "Work"), channel.ui.value.state.profile)
        socket.serverSends("""{"t":"state","url":"u"}""")
        assertNull(channel.ui.value.state.profile)
        socket.serverSends("""{"t":"state","url":"u","profile":{"id":"p-2","name":"Mail"}}""")
        socket.serverSends("""{"t":"state","url":"u","profile":null}""")
        assertNull(channel.ui.value.state.profile)
        // Malformed is the default on a state frame.
        socket.serverSends("""{"t":"state","url":"u","profile":{"id":"p-2","name":"Mail"}}""")
        socket.serverSends("""{"t":"state","url":"u","profile":{"id":5,"name":"Mail"}}""")
        assertNull(channel.ui.value.state.profile)
    }

    @Test
    fun theProfileFrameSetsClearsOrLeavesTheProfileAndClearsTheError() {
        val (channel, socket) = opened()
        socket.serverSends("""{"t":"profile","profile":{"id":"p-1","name":"Work"}}""")
        assertEquals(BrowserChannel.PaneProfile("p-1", "Work"), channel.ui.value.state.profile)
        // Malformed or absent: no change (undefined).
        socket.serverSends("""{"t":"profile","profile":{"id":"p-1"}}""")
        socket.serverSends("""{"t":"profile"}""")
        assertEquals(BrowserChannel.PaneProfile("p-1", "Work"), channel.ui.value.state.profile)
        socket.serverSends("""{"t":"profile","profile":null}""")
        assertNull(channel.ui.value.state.profile)
    }

    @Test
    fun theProfileSurvivesClosedAsOnTheWeb() {
        val (channel, socket) = opened()
        socket.serverSends("""{"t":"profile","profile":{"id":"p-1","name":"Work"}}""")
        socket.serverSends("""{"t":"closed"}""")
        assertEquals(BrowserChannel.PaneProfile("p-1", "Work"), channel.ui.value.state.profile)
        assertEquals("about:blank", channel.ui.value.state.url)
    }

    @Test
    fun aRefusedPickGoesToProfileErrorNotTheGenericError() {
        val (channel, socket) = opened()
        channel.pickProfile("p-1")
        socket.serverSends("""{"t":"error","message":"This profile is in use by the session Build."}""")
        assertEquals("This profile is in use by the session Build.", channel.ui.value.profileError)
        assertNull(channel.ui.value.error)
        // The next error is generic again (the pending mark is spent).
        socket.serverSends("""{"t":"error","message":"boom"}""")
        assertEquals("boom", channel.ui.value.error)
        assertEquals("This profile is in use by the session Build.", channel.ui.value.profileError)
        // A new pick clears the old sentence; an answer frame clears it too.
        channel.pickProfile(null)
        assertNull(channel.ui.value.profileError)
        socket.serverSends("""{"t":"error"}""")
        assertEquals(BrowserChannel.PROFILE_ERROR_FALLBACK, channel.ui.value.profileError)
        channel.pickProfile("p-1")
        socket.serverSends("""{"t":"profile","profile":{"id":"p-1","name":"Work"}}""")
        assertNull(channel.ui.value.profileError)
        socket.serverSends("""{"t":"error","message":"late"}""")
        assertEquals("late", channel.ui.value.error)
        assertNull(channel.ui.value.profileError)
    }

    @Test
    fun aPickThatCouldNotBeSentLeavesNothingPending() {
        val channel = BrowserChannel(opener, "s1", clock = { now })
        assertFalse(channel.pickProfile("p-1"))
        channel.connect()
        opener.last.serverOpens()
        opener.last.serverSends("""{"t":"error","message":"boom"}""")
        assertEquals("boom", channel.ui.value.error)
        assertNull(channel.ui.value.profileError)
        assertNotNull(channel.ui.value)
    }
}
