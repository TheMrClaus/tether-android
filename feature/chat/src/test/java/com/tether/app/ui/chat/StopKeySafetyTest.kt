package com.tether.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.js
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T6.4 round 2. M1: a Stop press never lands on another command's key when the rows move (keyed rows
 * and ta-coik.13's stale-tap guard; no arm delay, as on the web: chat-view.tsx 90fbb9f :3866-3875,
 * a first tap stops). ta-coik.22: no "Stopping…" latch, as on the web: every Stop key stays "Stop"
 * and live while its command runs, also on a saved copy. L3: a stop is bound to the server origin its row was drawn for. L2: repeated
 * lazy keys never crash the chat screen. L1: a run tab follows new steps only at the bottom, and a
 * denial's focus request is handed back once used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class StopKeySafetyTest {
    @get:Rule val rule = createComposeRule()

    private val session = SubagentFixtures.session

    private fun cmd(id: String, status: String, at: Long): AgentEvent = evNullTurn("background_command_updated", ts = at) {
        put("commandId", id); put("command", "npm run $id"); put("cwd", "/w"); put("logFile", "/w/$id.log"); put("status", status); put("startedAt", at)
        if (status != "running") { put("exitCode", 0); put("endedAt", at + 1) }
    }

    private fun folded(vararg events: AgentEvent): ChatFixtures.Folded = ChatFixtures.fold(*ChatFixtures.turn("t1", "Run two things.", "Both started.", 1_000), *events)

    private fun host(client: ChatTestClient): TetherViewModel {
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = session, projection = projections[session.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
            }
        }
        rule.waitForIdle()
        return vm
    }

    private fun arm() {
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private fun stopOf(n: Int) = rule.onAllNodesWithTag("bg-command-stop")[n]

    // ---- M1 ----------------------------------------------------------------------------------------

    @Test fun aNewKeyStopsOnTheFirstTap() {
        // ta-coik.13: the web's Stop (chat-view.tsx 90fbb9f :3866-3875) acts on the first click.
        val client = ChatTestClient().also { it.show(session, folded(cmd("a", "running", 2_000))) }
        host(client)
        rule.mainClock.autoAdvance = false
        stopOf(0).assertIsEnabled().performClick()
        rule.mainClock.advanceTimeBy(64)
        rule.waitForIdle()
        assertEquals(listOf("s1:a"), client.stopCalls)
    }

    @Test fun aRowShiftUnderAPendingPressSendsNothingToTheOtherCommand() {
        val client = ChatTestClient().also { it.show(session, folded(cmd("a", "running", 2_000), cmd("b", "running", 3_000))) }
        host(client)
        arm()
        // The operator presses B's Stop (the lower row; the deck grows upward from the composer) …
        val aimed = stopOf(1).fetchSemanticsNode().boundsInRoot.center
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput { down(aimed) }
        rule.mainClock.advanceTimeBy(16)
        rule.waitForIdle()
        // … and B finishes while the finger is down: A's row slides down under it.
        rule.runOnIdle {
            val tree = foldTree(client.projectionTrees.value.getValue("s1"), cmd("b", "finished", 3_000))
            client.show(session, ChatFixtures.Folded(checkNotNull(LegacyProjectionAdapter.adaptOnce(tree)), tree))
        }
        rule.mainClock.advanceTimeBy(64) // two frames: the rows recompose and move
        rule.waitForIdle()
        rule.onAllNodesWithTag("bg-command-stop").assertCountEquals(1)
        assertEquals("A's key is now where B's was", aimed.y, stopOf(0).fetchSemanticsNode().boundsInRoot.center.y, 2f)
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(64)
        rule.waitForIdle()
        assertTrue("the press meant for B never stops A: ${client.stopCalls}", client.stopCalls.isEmpty())
        // A fresh tap stops A at once.
        stopOf(0).assertIsEnabled().performClick()
        rule.mainClock.advanceTimeBy(64)
        rule.waitForIdle()
        assertEquals(listOf("s1:a"), client.stopCalls)
    }

    @Test fun aNewCommandAboveDoesNotInheritAnotherRowsState() {
        val client = ChatTestClient().also { it.show(session, folded(cmd("b", "running", 3_000))) }
        host(client)
        arm()
        stopOf(0).performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1:b"), client.stopCalls)
        // A command C launched earlier is listed first (the fold's order): each key stays its own command's.
        rule.runOnIdle {
            val tree = foldTree(client.projectionTrees.value.getValue("s1"), cmd("c", "running", 4_000))
            client.show(session, ChatFixtures.Folded(checkNotNull(LegacyProjectionAdapter.adaptOnce(tree)), tree))
        }
        rule.waitForIdle()
        arm()
        stopOf(0).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, stopName("npm run b"))).assertIsEnabled()
        stopOf(1).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, stopName("npm run c"))).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1:b", "s1:c"), client.stopCalls)
    }

    // ---- ta-coik.22: no "Stopping…" latch (chat-view.tsx 90fbb9f :3866-3875, :1719) -------------------

    private fun stopName(command: String) = listOf("Stop ${commandLabel(command)}")

    private fun label(n: Int): String =
        rule.onAllNodesWithTag("bg-command-stop", useUnmergedTree = true)[n].fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()

    /** The web's key reads "Stop" and is live after a click; a second tap asks for a second stop at once. */
    @Test fun aStoppedCommandsKeyStaysLiveAndStopsAgainAtOnce() {
        val client = ChatTestClient().also { it.show(session, folded(cmd("a", "running", 2_000))) }
        host(client)
        arm()
        rule.mainClock.autoAdvance = false
        stopOf(0).performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(listOf("s1:a"), client.stopCalls)
        stopOf(0).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, stopName("npm run a"))).assertIsEnabled().performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(listOf("s1:a", "s1:a"), client.stopCalls)
        assertTrue("never \"Stopping…\"", rule.onAllNodes(hasText("Stopping", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    /** The bar's stop leaves the sheet's key live too (the web's sheet key has no state of its own). */
    @Test fun aStopFromTheBarLeavesTheSheetsKeyLive() {
        val client = ChatTestClient().also { it.show(session, folded(cmd("a", "running", 2_000))) }
        host(client)
        arm()
        stopOf(0).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("bg-command-open").performClick()
        rule.waitForIdle()
        arm()
        rule.onAllNodesWithTag("bg-command-stop").assertCountEquals(2)
        stopOf(1).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, stopName("npm run a"))).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1:a", "s1:a"), client.stopCalls)
    }

    /**
     * ta-coik.22 (item 4): on a saved copy (the link dropped) and while catching up the web's Stop key
     * stays live (a click goes to the socket, which sends only when it is open). Here too: the key
     * is live and named for its command, and a tap asks the client, which re-checks the link.
     */
    @Test fun theStopKeyStaysLiveOnACopyThatIsNotLive() {
        val client = ChatTestClient().also { it.show(session, folded(cmd("a", "running", 2_000))) }
        host(client)
        arm()
        rule.runOnIdle {
            client.link.value = com.tether.app.client.ConnectionState.Disconnected
            client.live.value = emptySet()
        }
        rule.waitForIdle()
        client.stopResult = com.tether.app.client.StopCommandResult.NotConnected
        stopOf(0).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, stopName("npm run a"))).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1:a"), client.stopCalls)
        // Connected again but not yet live (catching up): still live.
        rule.runOnIdle { client.link.value = com.tether.app.client.ConnectionState.Connected }
        rule.waitForIdle()
        client.stopResult = com.tether.app.client.StopCommandResult.Sent
        stopOf(0).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1:a", "s1:a"), client.stopCalls)
    }

    /**
     * ta-coik.22 r2: the web draws the running commands' Stop live on a read-only session too
     * (chat-view.tsx 90fbb9f :3852-3876) and sends on click; the server answers its refusal.
     */
    @Test fun aReadOnlySessionsStopKeyIsLiveAsOnTheWeb() {
        val readOnly = session.copy(readOnly = true)
        val client = ChatTestClient().also { it.show(readOnly, folded(cmd("a", "running", 2_000))) }
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = readOnly, projection = projections[readOnly.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
            }
        }
        rule.waitForIdle()
        arm()
        stopOf(0).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1:a"), client.stopCalls)
        assertEquals("Stop ${commandLabel("npm run a")}", label(0))
    }

    /** ta-coik.22 r2 (item 4): a handed-off source's running command keeps a live Stop, as on the web. */
    @Test fun aHandedOffSessionsStopKeyIsLiveAsOnTheWeb() {
        val handedOff = session.copy(handedOffTo = "s9")
        val client = ChatTestClient().also { it.show(handedOff, folded(cmd("a", "running", 2_000))) }
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = handedOff, projection = projections[handedOff.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
            }
        }
        rule.waitForIdle()
        arm()
        stopOf(0).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1:a"), client.stopCalls)
        assertEquals(listOf<String?>(TEST_ORIGIN), client.stopOrigins)
    }

    @Test fun theStopFollowsTheServerOriginItIsDrawnFor() {
        val client = ChatTestClient().also { it.show(session, folded(cmd("a", "running", 2_000))) }
        host(client)
        arm()
        stopOf(0).performClick()
        rule.waitForIdle()
        // Connected and live throughout: only the server behind the link changes.
        rule.runOnIdle { client.origin.value = "https://other.example" }
        rule.waitForIdle()
        arm()
        stopOf(0).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, stopName("npm run a"))).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1:a", "s1:a"), client.stopCalls)
        assertEquals(listOf<String?>(TEST_ORIGIN, "https://other.example"), client.stopOrigins)
    }

    @Test fun switchingAwayAndBackKeepsTheKeyLive() {
        val other = session.copy(id = "s2", name = "Other")
        val client = ChatTestClient().also {
            it.show(other, folded())
            it.show(session, folded(cmd("a", "running", 2_000)))
        }
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        var shown by mutableStateOf(session)
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = shown, projection = projections[shown.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
            }
        }
        rule.waitForIdle()
        arm()
        stopOf(0).performClick()
        rule.waitForIdle()
        rule.runOnIdle { shown = other }
        rule.waitForIdle()
        rule.runOnIdle { shown = session }
        rule.waitForIdle()
        arm()
        stopOf(0).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, stopName("npm run a"))).assertIsEnabled()
    }

    // ---- round 3: the sheet's key while output grows; a slide -------------------------------------

    @Test fun theSheetsStopStaysLiveWhileShortOutputKeepsGrowing() {
        val client = ChatTestClient().also { it.show(session, folded(cmd("a", "running", 2_000))) }
        host(client)
        rule.onNodeWithTag("bg-command-open").performClick()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        var tree = client.projectionTrees.value.getValue("s1")
        var armedAt = -1
        var disarmedAgainAt = -1
        for (frame in 1..48) {
            if (frame % 12 == 0) {
                val n = frame / 12
                rule.runOnIdle {
                    tree = foldTree(tree, evNullTurn("background_command_output", ts = 9) { put("commandId", "a"); put("stream", "stdout"); put("text", "line $n\n") })
                    client.show(session, ChatFixtures.Folded(checkNotNull(LegacyProjectionAdapter.adaptOnce(tree)), tree))
                }
            }
            rule.mainClock.advanceTimeByFrame()
            Thread.sleep(8) // the sampled rebuild runs on a background dispatcher: let it land
            rule.waitForIdle()
            val sheetKey = rule.onAllNodesWithTag("bg-command-stop").fetchSemanticsNodes().last()
            val disabled = sheetKey.config.contains(SemanticsProperties.Disabled)
            if (armedAt < 0 && !disabled) armedAt = frame
            if (armedAt > 0 && disabled) disarmedAgainAt = frame
        }
        // The output really grew while we watched (each line lengthens the sheet): let the last
        // sampled rebuild land, then every line is there.
        repeat(20) {
            rule.mainClock.advanceTimeByFrame()
            Thread.sleep(8)
            rule.waitForIdle()
        }
        rule.onNode(hasText("line 4"), useUnmergedTree = true).assertIsDisplayed()
        assertEquals("the sheet's Stop is live from its first frame (ta-coik.13)", 1, armedAt)
        assertEquals("growing output never disables the key", -1, disarmedAgainAt)
        rule.onAllNodesWithTag("bg-command-stop").fetchSemanticsNodes().last().let { assertTrue(!it.config.contains(SemanticsProperties.Disabled)) }
    }

    @Test fun aKeyThatSlidStillStopsOnTheFirstTap() {
        // ta-coik.13: no re-arm after a move (the web has none); the slid key is still this command's.
        var offset by mutableStateOf(0)
        val command = BackgroundCommandView("a", "npm run a", "/w", "/w/a.log", "running", null, null, 1.0, false, JsArr.EMPTY)
        val calls = mutableListOf<String>()
        val actions = CommandActions(null, {}, { id -> calls += id; com.tether.app.client.StopCommandResult.Sent })
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.offset(y = androidx.compose.ui.unit.Dp(offset.toFloat()))) {
                    RunningCommandsBar(listOf(command), actions)
                }
            }
        }
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
        stopOf(0).assertIsEnabled()
        // 1dp a frame: no single frame moves it 4dp, but five frames do.
        repeat(5) {
            rule.runOnIdle { offset += 1 }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        stopOf(0).assertIsEnabled().performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(listOf("a"), calls)
    }

    @Test fun aLabelDropsBidiControlsAndLeadingBlankLines() {
        assertEquals("\u2068rm -rf build\u2069", commandLabel("\n  \n\u202Erm -rf build\u2069"))
        assertEquals("\u2068ls…\u2069", commandLabel("\r\nls\n\u2066pwd"))
        assertEquals("\u2068echo\u2069", commandLabel("echo\n\n"))
        // Round 4: directional marks go too (they could reorder the words inside the label) …
        assertEquals("\u2068rm -rf build\u2069", commandLabel("\u200Frm -rf\u200F build"))
        assertEquals("\u2068ab\u2069", commandLabel("a\u200Eb\u061C"))
        // … lines of only invisible (format) characters count as blank …
        assertEquals("\u2068ls\u2069", commandLabel("\u200B\u2060\nls\n\u200B"))
        // … and a command with nothing visible still gets a name.
        assertEquals("\u2068(blank command)\u2069", commandLabel("\n \u200B\n\u202E"))
        assertEquals("\u2068(blank command)\u2069", commandLabel(""))
        // Round 5: blank-looking letters, the braille blank, tag characters and variation selectors
        // are invisible too: skipped as first lines, never the reason for a "…".
        assertEquals("\u2068ls\u2069", commandLabel("\u3164\n\u2800\n\u115F\u1160\uFFA0\nls"))
        assertEquals("\u2068ls\u2069", commandLabel("\uDB40\uDC41\uDB40\uDC7F\nls\n\uFE0F\u3164"))
        assertEquals("\u2068(blank command)\u2069", commandLabel("\u3164\n\uDB40\uDC20"))
    }

    // ---- L3 ------------------------------------------------------------------------------------------

    @Test fun aStopIsBoundToTheOriginItsRowWasDrawnFor() {
        val client = ChatTestClient().also { it.show(session, folded(cmd("a", "running", 2_000))) }
        host(client)
        arm()
        stopOf(0).performClick()
        rule.waitForIdle()
        assertEquals(listOf<String?>(TEST_ORIGIN), client.stopOrigins)
    }

    @Test fun aCommandLabelIsItsFirstLineInsideABidiIsolate() {
        assertEquals("\u2068npm test\u2069", commandLabel("npm test"))
        assertEquals("\u2068set -e…\u2069", commandLabel("set -e  \nrm -rf build"))
        assertEquals("\u2068a…\u2069", commandLabel("a\u2028b"))
        assertEquals("\u2068evil…\u2069", commandLabel("\u202Eevil\r\nx"))
    }

    // ---- L2 ------------------------------------------------------------------------------------------

    @Test fun aCommandIdThatSpellsAnotherRowsKeyRendersBoth() {
        // Turn "bg-c" has the row "bg-c/bg-c:m0"; command "c/bg-c:m0" would key "bg-c/bg-c:m0" too.
        val tree = foldTree(freshTree(), *ChatFixtures.turn("bg-c", "Prompt", "Reply", 1_000), cmd("c/bg-c:m0", "finished", 500))
        val projection = checkNotNull(LegacyProjectionAdapter.adaptOnce(tree))
        val keys = buildChatItems(projection, tree, showThinking = false).map { it.key }
        assertTrue(keys.size > keys.toSet().size) // the collision is real
        assertEquals(keys.size, uniqueLazyKeys(keys).toSet().size)
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                ChatTranscript(projection = projection, tree = tree, showThinking = false, onFetchTurns = { _, _ -> }, zone = ChatFixtures.zone)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("bg-command-chip").assertIsDisplayed()
    }

    private fun agentTree(order: List<String>, blocks: List<String> = listOf("task-1")): JsObj {
        val entries = order.toSet().associateWith { k ->
            JsObj.of("key" to js(k), "kind" to js("tool"), "name" to js("Read"), "input" to JsObj.of("file_path" to js(k)), "done" to JsBool.TRUE, "output" to js("ok"))
        }
        val thread = JsObj.of("order" to JsArr.of(order.map(::JsStr)), "entries" to JsObj.from(entries))
        val block = JsObj.of("blockId" to js("task-1"), "kind" to js("tool"), "name" to js("Agent"), "input" to JsObj.of("description" to js("Read")), "subagent" to thread, "done" to JsBool.FALSE)
        val turn = JsObj.of("turnId" to js("t1"), "status" to js("running"), "blocks" to JsArr.of(blocks.map(::JsStr)), "blocksById" to JsObj.of("task-1" to block))
        return freshTree().with("turnOrder" to JsArr.of(JsStr("t1")), "turnsById" to JsObj.of("t1" to turn), "activeTurnId" to js("t1"))
    }

    @Test fun aRepeatedStepKeyAndARepeatedRunRenderWithoutCrashing() {
        val tree = agentTree(order = listOf("e1", "e2", "e1"), blocks = listOf("task-1", "task-1"))
        val runs = collectSubagentRuns(tree)
        assertEquals("a repeated block is one run", 1, runs.size)
        val rows = panelRows(runs.single(), subagentRunEntries(runs.single(), showThinking = false))
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertEquals(4, rows.size) // head + 3 steps (the repeat still shows, as on the web)
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                androidx.compose.foundation.layout.Column {
                    SubagentTabs(runs, runs.single().runId, onSelect = {})
                    SubagentRunTab(runs.single(), showThinking = false, pending = emptyList(), pendingQuestions = emptyList(), answeredIds = emptySet())
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("subrun-panel").assertIsDisplayed()
    }

    // ---- L1 ------------------------------------------------------------------------------------------

    private fun longRun(steps: Int): JsObj = agentTree(order = (1..steps).map { "e$it" })

    @Test fun aRunTabFollowsNewStepsOnlyWhileAtTheBottom() {
        var tree by mutableStateOf(longRun(60))
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                SubagentRunTab(collectSubagentRuns(tree).single(), showThinking = false, pending = emptyList(), pendingQuestions = emptyList(), answeredIds = emptySet())
            }
        }
        rule.waitForIdle()
        rule.onNode(hasText("e60", substring = true), useUnmergedTree = true).assertIsDisplayed()
        // The reader drags up to read: a new step does not pull the view away.
        rule.onNodeWithTag("subrun-panel").performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.runOnIdle { tree = longRun(61) }
        rule.waitForIdle()
        // Following would have brought the new step on screen; staying put leaves it uncomposed.
        rule.onAllNodes(hasText("\"e61\"", substring = true), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun aDenialFocusIsHandedBackOnceItsStepIsShown() {
        var cleared = 0
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                val run = collectSubagentRuns(longRun(40)).single()
                SubagentRunTab(run, showThinking = false, pending = emptyList(), pendingQuestions = emptyList(), answeredIds = emptySet(), focus = RunFocus(run.runId, "e10", 1), onFocusShown = { cleared++ })
            }
        }
        rule.waitForIdle()
        assertEquals(1, cleared)
        rule.onNode(hasText("\"e10\"", substring = true), useUnmergedTree = true).assertIsDisplayed()
    }
}
