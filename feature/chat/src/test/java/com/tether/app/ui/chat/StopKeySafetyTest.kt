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
 * T6.4 round 2. M1: a Stop tap never lands on another command's key when the rows move (keyed rows,
 * an arming delay, re-arming after a move). The "Stopping…" latch is one per command, shared by the
 * bar and the sheet. L3: a stop is bound to the server origin its row was drawn for. L2: repeated
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
            TetherTheme(choiceFor(TetherSkin.Machine)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = session, projection = projections[session.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
            }
        }
        rule.waitForIdle()
        return vm
    }

    private fun arm() {
        rule.mainClock.advanceTimeBy(CONSENT_ARM_DELAY_MS + 100)
        rule.waitForIdle()
    }

    private fun stopOf(n: Int) = rule.onAllNodesWithTag("bg-command-stop")[n]

    // ---- M1 ----------------------------------------------------------------------------------------

    @Test fun aNewKeyIsDisarmedFor500msThenStopsNormally() {
        val client = ChatTestClient().also { it.show(session, folded(cmd("a", "running", 2_000))) }
        host(client)
        rule.mainClock.autoAdvance = false
        stopOf(0).assertIsNotEnabled().performClick()
        rule.mainClock.advanceTimeBy(CONSENT_ARM_DELAY_MS - 100)
        rule.waitForIdle()
        stopOf(0).assertIsNotEnabled().performClick()
        assertTrue("nothing before the key armed", client.stopCalls.isEmpty())
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
        stopOf(0).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1:a"), client.stopCalls)
    }

    @Test fun aRowShiftUnderAPendingTapSendsNothingToTheOtherCommand() {
        val client = ChatTestClient().also { it.show(session, folded(cmd("a", "running", 2_000), cmd("b", "running", 3_000))) }
        host(client)
        arm()
        // The operator aims at B's Stop (the lower row; the deck grows upward from the composer) …
        val aimed = stopOf(1).fetchSemanticsNode().boundsInRoot.center
        // … and B finishes in that instant: A's row slides down under the finger.
        rule.runOnIdle {
            val tree = foldTree(client.projectionTrees.value.getValue("s1"), cmd("b", "finished", 3_000))
            client.show(session, ChatFixtures.Folded(checkNotNull(LegacyProjectionAdapter.adaptOnce(tree)), tree))
        }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(64) // two frames: the rows recompose and move, far under the arming delay
        rule.waitForIdle()
        rule.onAllNodesWithTag("bg-command-stop").assertCountEquals(1)
        assertEquals("A's key is now where B's was", aimed.y, stopOf(0).fetchSemanticsNode().boundsInRoot.center.y, 2f)
        rule.onRoot().performTouchInput { click(aimed) }
        rule.mainClock.advanceTimeBy(64)
        rule.waitForIdle()
        assertTrue("the tap meant for B never stops A: ${client.stopCalls}", client.stopCalls.isEmpty())
        stopOf(0).assertIsNotEnabled()
        // A's key, now still, re-arms and then stops A as usual.
        rule.mainClock.advanceTimeBy(CONSENT_ARM_DELAY_MS + 100)
        rule.waitForIdle()
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
        // A command C launched earlier is listed first (the fold's order): B's "Stopping…" stays B's.
        rule.runOnIdle {
            val tree = foldTree(client.projectionTrees.value.getValue("s1"), cmd("c", "running", 4_000))
            client.show(session, ChatFixtures.Folded(checkNotNull(LegacyProjectionAdapter.adaptOnce(tree)), tree))
        }
        rule.waitForIdle()
        arm()
        stopOf(0).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Stopping ${commandLabel("npm run b")}"))).assertIsNotEnabled()
        stopOf(1).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1:b", "s1:c"), client.stopCalls)
    }

    // ---- the shared latch ------------------------------------------------------------------------

    @Test fun aStopFromTheBarShowsStoppingInTheSheet() {
        val client = ChatTestClient().also { it.show(session, folded(cmd("a", "running", 2_000))) }
        host(client)
        arm()
        stopOf(0).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("bg-command-open").performClick()
        rule.waitForIdle()
        arm()
        rule.onAllNodesWithTag("bg-command-stop").assertCountEquals(2)
        stopOf(1).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Stopping ${commandLabel("npm run a")}"))).assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertEquals("one stop per command, whichever key was tapped", listOf("s1:a"), client.stopCalls)
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
        assertEquals("\u2068\u202Eevil…\u2069", commandLabel("\u202Eevil\r\nx"))
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
            ChatHost(TetherSkin.Machine) {
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
            ChatHost(TetherSkin.Machine) {
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
            ChatHost(TetherSkin.Machine) {
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
            ChatHost(TetherSkin.Machine) {
                val run = collectSubagentRuns(longRun(40)).single()
                SubagentRunTab(run, showThinking = false, pending = emptyList(), pendingQuestions = emptyList(), answeredIds = emptySet(), focus = RunFocus(run.runId, "e10", 1), onFocusShown = { cleared++ })
            }
        }
        rule.waitForIdle()
        assertEquals(1, cleared)
        rule.onNode(hasText("\"e10\"", substring = true), useUnmergedTree = true).assertIsDisplayed()
    }
}
