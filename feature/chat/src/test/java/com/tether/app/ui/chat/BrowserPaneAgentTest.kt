package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.tether.app.client.BrowserChannel
import com.tether.app.client.BrowserProfilesReply
import com.tether.app.client.BrowserProfilesRequest
import com.tether.app.protocol.BrowserProfileInfo
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the pane shows of the agent and of the session's browser choices, driven through the real
 * channel and a fake socket (browser-pane.tsx 0bd07fdd): the marker at the agent's box for 1.6 s,
 * the "Agent" pill and "You took over", the tab picker, the profile row, and the live regions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class BrowserPaneAgentTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val opener = PaneOpener()
    private var now = 10_000L
    private var listCalls = 0
    private val requests = mutableListOf<BrowserProfilesRequest>()

    private fun show(profiles: (suspend (BrowserProfilesRequest) -> BrowserProfilesReply)? = null): BrowserChannel {
        val channel = BrowserChannel(opener, "s1") { now }
        channel.connect()
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = WellHeightPhone) {
                BrowserPane(channel, onClose = {}, modifier = Modifier.fillMaxSize(), browserProfiles = profiles)
            }
        }
        rule.waitForIdle()
        opener.sockets.last().sent.clear()
        return channel
    }

    private val socket get() = opener.sockets.last()

    private fun server(text: String) {
        rule.runOnIdle { socket.listener.onMessage(text) }
        rule.waitForIdle()
    }

    private val viewport = """"viewport":{"width":390,"height":844,"mobile":true,"deviceScaleFactor":1}"""

    private fun page() = server("""{"t":"state","url":"https://a.test/",$viewport}""")

    private fun tabs(active: Int, withIds: Boolean = true): String {
        val names = listOf("Docs", "Build", "Mail")
        return names.mapIndexed { i, n ->
            """{"index":${i + 1},"active":${i + 1 == active},"url":"https://$n.test/","title":"$n"${if (withIds) ""","id":"t${i + 1}"""" else ""}}"""
        }.joinToString(",", "[", "]")
    }

    private fun desc(tag: String): String? = rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

    private fun liveRegion(tag: String): LiveRegionMode? = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)

    private fun text(node: SemanticsNode): String = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: ""

    /** A node's own box in dp (position in the root and size; `getBoundsInRoot` is clipped to what is visible). */
    private class Box4(val left: Float, val top: Float, val wd: Float, val ht: Float)

    private fun boxOf(tag: String): Box4 {
        val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
        val d = rule.density.density
        return Box4(node.positionInRoot.x / d, node.positionInRoot.y / d, node.size.width / d, node.size.height / d)
    }

    // ---- the marker ----

    @Test
    fun theMarkerSitsOnTheStageAtTheAgentsBox() {
        show()
        page()
        server("""{"t":"agent","phase":"start","kind":"click","box":{"x":39,"y":84.4,"width":78,"height":42.2}}""")
        val stage = boxOf(BrowserPaneTags.Stage)
        val marker = boxOf(BrowserPaneTags.Marker)
        val w = stage.wd
        val h = stage.ht
        assertEquals(stage.left + w * 0.10f, marker.left, 0.6f)
        assertEquals(stage.top + h * 0.10f, marker.top, 0.6f)
        assertEquals(w * 0.20f, marker.wd, 0.6f)
        assertEquals(h * 0.05f, marker.ht, 0.6f)
    }

    @Test
    fun aPointIsATwentyDpCircleCentredOnIt() {
        show()
        page()
        server("""{"t":"agent","phase":"start","kind":"click","box":{"x":195,"y":422,"width":0,"height":0}}""")
        val stage = boxOf(BrowserPaneTags.Stage)
        val marker = boxOf(BrowserPaneTags.Marker)
        assertEquals(20f, marker.wd, 0.6f)
        assertEquals(stage.left + stage.wd / 2 - 10f, marker.left, 0.6f)
        assertEquals(stage.top + stage.ht / 2 - 10f, marker.top, 0.6f)
    }

    @Test
    fun theMarkerGoesAfterOnePointSixSecondsOfTheClock() {
        show()
        page()
        server("""{"t":"agent","phase":"start","kind":"click","box":{"x":39,"y":84,"width":78,"height":42}}""")
        rule.onNodeWithTag(BrowserPaneTags.Marker).assertIsDisplayed()
        now += 1500
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        rule.onNodeWithTag(BrowserPaneTags.Marker).assertExists()
        now += 100
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
        rule.onNodeWithTag(BrowserPaneTags.Marker).assertDoesNotExist()
    }

    @Test
    fun aStartWithNoBoxDrawsNoMarkerButTheAgentPill() {
        show()
        page()
        server("""{"t":"agent","phase":"start","kind":"type"}""")
        rule.onNodeWithTag(BrowserPaneTags.Marker).assertDoesNotExist()
        rule.onNodeWithTag(BrowserPaneTags.AgentPill).assertExists()
    }

    // ---- the pill ----

    @Test
    fun theAgentPillSaysAgentAndReadsTheWholeSentence() {
        show()
        page()
        server("""{"t":"agent","phase":"start","kind":"click"}""")
        rule.onNodeWithTag(BrowserPaneTags.AgentPill).assertExists()
        assertEquals("Agent is using the browser", desc(BrowserPaneTags.AgentPill))
    }

    @Test
    fun tookOverReplacesThePillAndTheMarkerAndResumedBringsTheAgentPillBack() {
        show()
        page()
        server("""{"t":"agent","phase":"start","kind":"click","box":{"x":39,"y":84,"width":78,"height":42}}""")
        rule.onNodeWithTag(BrowserPaneTags.Marker).assertExists()
        server("""{"t":"agent","phase":"took_over","idleMs":3000}""")
        rule.onNodeWithTag(BrowserPaneTags.Marker).assertDoesNotExist()
        assertEquals("You took over the browser. The agent waits until you stop, then it can carry on.", desc(BrowserPaneTags.AgentPill))
        server("""{"t":"agent","phase":"resumed"}""")
        // Resumed clears the took-over state: the agent's own pill is back (it never ended), no marker.
        assertEquals("Agent is using the browser", desc(BrowserPaneTags.AgentPill))
        rule.onNodeWithTag(BrowserPaneTags.Marker).assertDoesNotExist()
        now += 1000
        server("""{"t":"agent","phase":"end","kind":"click"}""")
        now += 1000
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        rule.onNodeWithTag(BrowserPaneTags.AgentPill).assertDoesNotExist()
    }

    // ---- the tab picker ----

    @Test
    fun theTabPickerIsA48dpTargetThatSendsTheTabInputWithTheId() {
        show()
        server("""{"t":"state","url":"https://a.test/",$viewport,"tabs":${tabs(1)}}""")
        rule.onNodeWithTag(BrowserPaneTags.TabIndicator).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(BrowserPaneTags.TabIndicator).assertTextEquals("Tab 1 of 3 · Docs")
        rule.onNodeWithTag(BrowserPaneTags.TabIndicator).performClick()
        rule.onNodeWithTag(BrowserPaneTags.tabOption("t3")).performClick()
        assertEquals(listOf("""{"t":"input","event":{"type":"tab","action":"switch","id":"t3"}}"""), socket.sent)
    }

    @Test
    fun aTabWithNoIdSendsNothingAndIsNeverAnnounced() {
        show()
        server("""{"t":"state","url":"https://a.test/",$viewport,"tabs":${tabs(1, withIds = false)}}""")
        server("""{"t":"state","url":"https://a.test/",$viewport,"tabs":${tabs(2, withIds = false)}}""")
        assertEquals("", desc(BrowserPaneTags.LiveTab))
        rule.onNodeWithTag(BrowserPaneTags.TabIndicator).performClick()
        rule.onNodeWithTag(BrowserPaneTags.tabOption("none-3")).performClick()
        assertEquals(emptyList<String>(), socket.sent)
    }

    @Test
    fun theTabSentenceIsSaidOnEachActiveTabChangeAndNotOnTheFirstList() {
        show()
        server("""{"t":"state","url":"https://a.test/",$viewport,"tabs":${tabs(1)}}""")
        assertEquals("", desc(BrowserPaneTags.LiveTab))
        server("""{"t":"state","url":"https://a.test/",$viewport,"tabs":${tabs(2)}}""")
        assertEquals("Switched to tab 2 of 3: Build", desc(BrowserPaneTags.LiveTab))
        server("""{"t":"state","url":"https://a.test/",$viewport,"tabs":${tabs(3)}}""")
        assertEquals("Switched to tab 3 of 3: Mail", desc(BrowserPaneTags.LiveTab))
    }

    // ---- the profile row ----

    private val listed = BrowserProfilesReply(
        ok = true,
        profiles = listOf(BrowserProfileInfo("p1", "Work"), BrowserProfileInfo("p2", "Mail", heldBy = "Build")),
    )

    @Test
    fun theSelectListsTheDefaultTheNamesAndWhoHoldsOne() {
        show { request -> requests += request; listed }
        page()
        rule.onNodeWithTag(BrowserPaneTags.ProfileSelect).assertExists()
        rule.onNodeWithTag(BrowserPaneTags.ProfileHint).assertTextEquals("Using: Throwaway profile (default). Changing the profile restarts the open browser on it.")
        rule.onNodeWithTag(BrowserPaneTags.ProfileSelect).performClick()
        assertEquals(BrowserProfilesRequest.List, requests.first())
        rule.onNodeWithTag(BrowserPaneTags.profileOption(null)).assertExists()
        assertEquals("Work", text(rule.onNodeWithTag(BrowserPaneTags.profileOption("p1")).fetchSemanticsNode()))
        assertEquals("Mail (in use by Build)", text(rule.onNodeWithTag(BrowserPaneTags.profileOption("p2")).fetchSemanticsNode()))
    }

    @Test
    fun aPickSendsTheProfileFrameAndTheDefaultSendsNull() {
        show { listed }
        page()
        rule.onNodeWithTag(BrowserPaneTags.ProfileSelect).performClick()
        rule.onNodeWithTag(BrowserPaneTags.profileOption("p1")).performClick()
        rule.onNodeWithTag(BrowserPaneTags.ProfileSelect).performClick()
        rule.onNodeWithTag(BrowserPaneTags.profileOption(null)).performClick()
        assertEquals(listOf("""{"t":"profile","profileId":"p1"}""", """{"t":"profile","profileId":null}"""), socket.sent)
    }

    @Test
    fun theSessionsOwnProfileShowsInTheHintAndTheListEvenWhenTheListLacksIt() {
        show { BrowserProfilesReply(ok = true) }
        server("""{"t":"state","url":"https://a.test/",$viewport,"profile":{"id":"p9","name":"Old"}}""")
        rule.onNodeWithTag(BrowserPaneTags.ProfileHint).assertTextEquals("Using: Old. Changing the profile restarts the open browser on it.")
        rule.onNodeWithTag(BrowserPaneTags.ProfileSelect).performClick()
        assertEquals("Old", text(rule.onNodeWithTag(BrowserPaneTags.profileOption("p9")).fetchSemanticsNode()))
    }

    @Test
    fun aFailedListKeepsTheSelectWithTheDefault() {
        show { BrowserProfilesReply.Unavailable }
        page()
        rule.onNodeWithTag(BrowserPaneTags.ProfileSelect).assertExists()
        rule.onNodeWithTag(BrowserPaneTags.ProfileSelect).performClick()
        rule.onNodeWithTag(BrowserPaneTags.profileOption(null)).assertExists()
        rule.onNodeWithTag(BrowserPaneTags.profileOption("p1")).assertDoesNotExist()
    }

    @Test
    fun aServerBelowTheProfilesProtocolDrawsNoProfileRow() {
        show(null)
        page()
        rule.onNodeWithTag(BrowserPaneTags.ProfileSelect).assertDoesNotExist()
        rule.onNodeWithTag(BrowserPaneTags.ProfileHint).assertDoesNotExist()
    }

    @Test
    fun aRefusedPickShowsTheServersWordsAndTheLabelStaysOnTheDefault() {
        show { listed }
        page()
        rule.onNodeWithTag(BrowserPaneTags.ProfileSelect).performClick()
        rule.onNodeWithTag(BrowserPaneTags.profileOption("p2")).performClick()
        server("""{"t":"error","message":"The browser profile \"Mail\" is in use by the session \"Build\"."}""")
        rule.onNodeWithTag(BrowserPaneTags.ProfileRefusal).assertTextEquals("The browser profile \"Mail\" is in use by the session \"Build\".")
        rule.onNodeWithTag(BrowserPaneTags.ProfileHint).assertTextEquals("Using: Throwaway profile (default). Changing the profile restarts the open browser on it.")
        rule.onNodeWithTag(BrowserPaneTags.StatusError).assertDoesNotExist()
    }

    // ---- live regions ----

    @Test
    fun theLiveRegionsAreTheWebsRowByRow() {
        show { listed }
        server("""{"t":"state","url":"https://localhost:3000/",$viewport,"urlNotice":{"kind":"non_public","host":"localhost:3000","indirect":false}}""")
        assertEquals(LiveRegionMode.Polite, liveRegion(BrowserPaneTags.UrlNotice))
        assertEquals(LiveRegionMode.Polite, liveRegion(BrowserPaneTags.LiveAgent))
        assertEquals(LiveRegionMode.Polite, liveRegion(BrowserPaneTags.LiveTab))
        rule.onNodeWithTag(BrowserPaneTags.ProfileSelect).performClick()
        rule.onNodeWithTag(BrowserPaneTags.profileOption("p2")).performClick()
        server("""{"t":"error","message":"in use"}""")
        assertEquals(LiveRegionMode.Assertive, liveRegion(BrowserPaneTags.ProfileRefusal))
    }

    @Test
    fun theErrorRowIsAPoliteLiveRegion() {
        show()
        page()
        server("""{"t":"error","message":"Chromium could not be started."}""")
        assertEquals(LiveRegionMode.Polite, liveRegion(BrowserPaneTags.StatusError))
    }

    @Test
    fun theAgentSentenceIsAnnouncedInThePoliteRegionAndTookOverEveryTime() {
        show()
        page()
        server("""{"t":"agent","phase":"start","kind":"click"}""")
        assertEquals("The agent is using the browser.", desc(BrowserPaneTags.LiveAgent))
        server("""{"t":"agent","phase":"took_over","idleMs":3000}""")
        assertEquals("You took over the browser. The agent waits until you stop, then it can carry on.", desc(BrowserPaneTags.LiveAgent))
        server("""{"t":"agent","phase":"resumed"}""")
        assertEquals("The agent can use the browser again.", desc(BrowserPaneTags.LiveAgent))
    }

    @Test
    fun theViewportGroupHasTheWebsName() {
        show()
        page()
        assertEquals("Viewport size", desc(BrowserPaneTags.Segmented))
    }
}
