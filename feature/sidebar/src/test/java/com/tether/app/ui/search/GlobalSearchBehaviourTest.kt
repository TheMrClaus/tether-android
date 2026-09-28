package com.tether.app.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.SearchHit
import com.tether.app.ui.GlobalSearchForm
import com.tether.app.ui.SessionDrawer
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.sidebar.RecordingClient
import com.tether.app.ui.sidebar.SidebarFixtures
import com.tether.app.ui.sidebar.choiceFor
import com.tether.app.ui.sidebar.frame
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T5.3 end to end: the global search modal as dashboard.tsx hosts it — the 220ms debounce, the
 * filters on the wire, the web's labels (hint, "Searching…", count, no match), a superseded reply
 * that never lands, and opening a result (a live session is selected; a history is resumed and
 * marked seen; either way the query arms that conversation's find bar and the modal closes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class GlobalSearchBehaviourTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures

    private fun hit(id: String, name: String, cwd: String = F.APP, snippet: String = "…the parity notes…", matchCount: Int = 3, profileId: String? = null) =
        SearchHit(historyId = id, provider = "codex", name = name, cwd = cwd, updatedAt = System.currentTimeMillis() - 150 * 60_000, snippet = snippet, matchCount = matchCount, profileId = profileId)

    private class Host(val vm: TetherViewModel, val client: RecordingClient) {
        var drawerClosed = 0
    }

    private fun host(sessions: List<com.tether.app.protocol.model.AgentSession> = emptyList(), withDrawer: Boolean = false): Host {
        val client = RecordingClient(sessions = sessions)
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        val host = Host(vm, client)
        rule.setContent {
            val list by client.sessions.collectAsStateWithLifecycle()
            val selectedId by vm.selectedSessionId.collectAsStateWithLifecycle()
            TetherTheme(choiceFor(TetherSkin.Machine)) {
              androidx.compose.runtime.CompositionLocalProvider(com.tether.app.ui.theme.LocalReducedMotion provides true) {
                if (withDrawer) {
                    SessionDrawer(vm = vm, prefs = prefs, sessions = list, selectedId = selectedId, workspaceRoot = F.ROOT, onSelect = vm::selectSession, onClose = { host.drawerClosed++ })
                }
                GlobalSearchHost(vm = vm, prefs = prefs, sessions = list, workspaceRoot = F.ROOT, onCloseDrawer = { host.drawerClosed++ })
              }
            }
        }
        rule.waitForIdle()
        return host
    }

    private fun Host.open() {
        vm.openGlobalSearch()
        rule.waitForIdle()
    }

    private fun RecordingClient.globalFrames(): List<JsonObject> = frames.filter { (it["type"] as JsonPrimitive).content == "global-search" }

    @Test fun theQueryIsSearchedOnce220msAfterTheLastKeystroke() {
        val h = host()
        h.open()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(GlobalSearchTags.Input).performTextInput("par")
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag(GlobalSearchTags.Input).performTextReplacement("parity")
        rule.mainClock.advanceTimeBy(200)
        assertEquals("nothing within the debounce", emptyList<JsonObject>(), h.client.globalFrames())
        rule.mainClock.advanceTimeBy(60)
        val sent = h.client.globalFrames().single()
        assertEquals("parity", sent["query"]!!.jsonPrimitive.content)
        // No filter chosen: nothing but the id and the query (use-tether.ts:1421-1429).
        assertEquals(setOf("type", "requestId", "query"), sent.keys)
        rule.mainClock.autoAdvance = true
    }

    @Test fun theFiltersRideAlongAndSinceIsStampedWhenItFires() {
        val h = host()
        h.open()
        rule.onNodeWithTag(GlobalSearchTags.chip("codex")).performClick()
        rule.onNodeWithTag(GlobalSearchTags.chip("claude")).performClick()
        rule.onNodeWithTag(GlobalSearchTags.TimeWindow).performClick()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Past week")).performClick()
        rule.onNodeWithTag(GlobalSearchTags.Scope).performClick()
        val before = System.currentTimeMillis()
        h.client.frames.clear()
        rule.onNodeWithTag(GlobalSearchTags.Input).performTextInput("parity")
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        val after = System.currentTimeMillis()
        val sent = h.client.globalFrames().last()
        // Chips in the order they were switched on (toggleProvider appends).
        assertEquals(listOf("codex", "claude"), sent["providers"]!!.jsonArray.map { it.jsonPrimitive.content })
        val since = sent["since"]!!.jsonPrimitive.long
        val week = 7L * 24 * 60 * 60 * 1000
        assertTrue("since $since", since in (before - week)..(after - week))
        assertEquals(F.ROOT, sent["cwd"]!!.jsonPrimitive.content)
        assertEquals(GlobalSearchForm("parity", listOf("codex", "claude"), "7d", true), h.vm.globalSearchForm.value)
        // A chip tapped again is off again.
        rule.onNodeWithTag(GlobalSearchTags.chip("codex")).performClick()
        assertEquals(listOf("claude"), h.vm.globalSearchForm.value.providers)
    }

    @Test fun theLabelsFollowTheWebAndASupersededReplyNeverLands() {
        val h = host()
        h.open()
        rule.onNodeWithText(GlobalSearchCopy.Hint).assertIsDisplayed()
        rule.onNodeWithTag(GlobalSearchTags.Input).performTextInput("parity")
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        // Pending for the query typed: the spinner, and the count of what is held (none yet) —
        // global-search.tsx:104-111 says "Searching…" only while the typed text is AHEAD of the
        // pending request.
        rule.onNodeWithTag(GlobalSearchTags.Spinner).assertIsDisplayed()
        rule.onNodeWithText("0 conversations").assertIsDisplayed()
        val first = h.client.globalSearchResults.value.requestId
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(GlobalSearchTags.Input).performTextReplacement("parity n")
        rule.mainClock.advanceTimeBy(50)
        rule.onNodeWithText(GlobalSearchCopy.Searching).assertIsDisplayed()
        rule.onNodeWithTag(GlobalSearchTags.Input).performTextReplacement("parity")
        rule.mainClock.advanceTimeBy(400)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val latest = h.client.globalSearchResults.value.requestId
        assertTrue("the same text is searched again under a new id", latest > first)

        // The server answers: one conversation, with its snippet and match count.
        h.client.globalReply(latest, "parity", listOf(hit("h-1", "Parity notes thread")))
        rule.waitForIdle()
        rule.onNodeWithText("1 conversation").assertIsDisplayed()
        rule.onNodeWithTag(GlobalSearchTags.hit("h-1")).assertIsDisplayed()
        rule.onNodeWithText("…the parity notes… · 3 matches").assertIsDisplayed()
        assertTrue(rule.onAllNodes(hasTestTag(GlobalSearchTags.Spinner)).fetchSemanticsNodes().isEmpty())

        // A new query: the previous hits stay listed while it is pending (the web spreads `prev`).
        rule.onNodeWithTag(GlobalSearchTags.Input).performTextReplacement("zz-none")
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithTag(GlobalSearchTags.Spinner).assertIsDisplayed()
        rule.onNodeWithTag(GlobalSearchTags.hit("h-1")).assertIsDisplayed()
        // The slow reply to the FIRST request arrives now: dropped.
        h.client.globalReply(first, "parity", listOf(hit("h-stale", "Stale thread")))
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasTestTag(GlobalSearchTags.hit("h-stale"))).fetchSemanticsNodes().isEmpty())
        h.client.globalReply(h.client.globalSearchResults.value.requestId, "zz-none", emptyList())
        rule.waitForIdle()
        rule.onNodeWithText(GlobalSearchCopy.noMatches("zz-none")).assertIsDisplayed()
        rule.onNodeWithText("0 conversations").assertIsDisplayed()

        // Back under two characters: the hint again, and the results are invalidated.
        rule.onNodeWithTag(GlobalSearchTags.Input).performTextReplacement("z")
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithText(GlobalSearchCopy.Hint).assertIsDisplayed()
    }

    @Test fun openingAHistoryHitResumesItMarksItSeenAndArmsTheFind() {
        val h = host()
        h.open()
        rule.onNodeWithTag(GlobalSearchTags.Input).performTextInput("  parity ")
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        h.client.globalReply(h.client.globalSearchResults.value.requestId, "parity", listOf(hit("h-9", "Old parity run", profileId = "work")))
        rule.waitForIdle()
        h.client.frames.clear()
        rule.onNodeWithTag(GlobalSearchTags.hit("h-9")).performClick()
        rule.waitForIdle()
        val resume = h.client.frames.single { (it["type"] as JsonPrimitive).content == "resume" }
        assertEquals(frame("""{"type":"resume","historyId":"h-9","cwd":"${F.APP}","profileId":"work"}"""), resume)
        assertTrue(h.client.types().toString(), "mark-seen" in h.client.types())
        assertEquals("h-9", h.vm.openingHistoryId.value)
        // The trimmed query, keyed to that conversation.
        val request = h.vm.findRequest.value!!
        assertEquals("parity" to "h-9", request.query to request.historyId)
        assertFalse(h.vm.globalSearchOpen.value)
        assertEquals(1, h.drawerClosed)
        assertTrue("closing clears the results", h.client.globalSearchResults.value.hits.isEmpty())
        assertTrue(rule.onAllNodes(hasTestTag(GlobalSearchTags.Dialog)).fetchSemanticsNodes().isEmpty())
    }

    @Test fun openingALiveHitSelectsItsSessionWithoutAResume() {
        val live = F.live("s-live", "Parity notes thread", cwd = F.APP, ago = 3, historyId = "h-1")
        val h = host(sessions = listOf(live))
        h.open()
        rule.onNodeWithTag(GlobalSearchTags.Input).performTextInput("parity")
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        h.client.globalReply(h.client.globalSearchResults.value.requestId, "parity", listOf(hit("h-1", "Parity notes thread")))
        rule.waitForIdle()
        h.client.frames.clear()
        rule.onNodeWithTag(GlobalSearchTags.hit("h-1")).performClick()
        rule.waitForIdle()
        assertEquals("s-live", h.vm.selectedSessionId.value)
        assertEquals(listOf("attach"), h.client.types().filter { it == "attach" || it == "resume" || it == "mark-seen" })
        assertNull(h.vm.openingHistoryId.value)
        assertEquals("h-1", h.vm.findRequest.value!!.historyId)
        assertEquals(1, h.drawerClosed)
    }

    @Test fun closeClearsTheResultsButTheFormSurvivesAReopen() {
        val h = host()
        h.open()
        rule.onNodeWithTag(GlobalSearchTags.Input).performTextInput("parity")
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        val id = h.client.globalSearchResults.value.requestId
        rule.onNodeWithTag(GlobalSearchTags.Close).performClick()
        rule.waitForIdle()
        assertFalse(h.vm.globalSearchOpen.value)
        assertTrue(h.client.globalSearchResults.value.requestId > id)
        h.client.globalReply(id, "parity", listOf(hit("h-late", "Late")))
        assertTrue(h.client.globalSearchResults.value.hits.isEmpty())
        // Reopened: the same text, searched again after the debounce.
        h.client.frames.clear()
        h.open()
        rule.onNodeWithTag(GlobalSearchTags.Input).assertIsDisplayed()
        rule.onNode(hasText("parity")).assertIsDisplayed()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        assertEquals("parity", h.client.globalFrames().single()["query"]!!.jsonPrimitive.content)
    }

    @Test fun aTapOnTheBackdropClosesButATapInsideThePanelDoesNot() {
        val h = host()
        h.open()
        rule.onNodeWithText(GlobalSearchCopy.Hint).performClick()
        rule.waitForIdle()
        assertTrue("a tap inside the panel keeps it open", h.vm.globalSearchOpen.value)
        val window = rule.onNode(androidx.compose.ui.test.isRoot() and androidx.compose.ui.test.hasAnyDescendant(hasTestTag(GlobalSearchTags.Dialog)))
        window.performTouchInput { click(androidx.compose.ui.geometry.Offset(centerX, bottom - 20f)) }
        rule.waitForIdle()
        assertFalse(h.vm.globalSearchOpen.value)
    }

    @Test fun talkBackReadsEachControlAndEachHitOnItsOwn() {
        val h = host()
        h.open()
        rule.onNodeWithTag(GlobalSearchTags.Input).performTextInput("parity")
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        h.client.globalReply(h.client.globalSearchResults.value.requestId, "parity", listOf(hit("h-1", "Parity notes thread"), hit("h-2", "Second thread", matchCount = 1)))
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Close search")).assertIsDisplayed()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Search query")).assertIsDisplayed()
        val codex = rule.onNodeWithTag(GlobalSearchTags.chip("codex")).fetchSemanticsNode().config
        assertEquals("Codex", codex[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].single())
        assertEquals(androidx.compose.ui.state.ToggleableState.Off, codex[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState])
        val first = rule.onNodeWithTag(GlobalSearchTags.hit("h-1")).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].single()
        assertTrue(first, first.startsWith("Parity notes thread, 2h, ~/parity-app, …the parity notes…, 3 matches"))
        val second = rule.onNodeWithTag(GlobalSearchTags.hit("h-2")).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].single()
        assertFalse("one match is not counted", second.contains("matches"))
        // 44dp targets: the hit rows and the chips.
        assertTrue(rule.onNodeWithTag(GlobalSearchTags.hit("h-1")).fetchSemanticsNode().size.height >= 44 * 2.625f - 1)
    }

    @Test fun theSidebarKeyOpensTheModal() {
        val h = host(sessions = F.drawerSessions, withDrawer = true)
        assertFalse(h.vm.globalSearchOpen.value)
        rule.onNodeWithText("Search all conversations…").performClick()
        rule.waitForIdle()
        assertTrue(h.vm.globalSearchOpen.value)
        rule.onNodeWithTag(GlobalSearchTags.Dialog).assertIsDisplayed()
    }
}
