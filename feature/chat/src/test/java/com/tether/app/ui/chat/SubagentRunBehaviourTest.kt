package com.tether.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.ConnectionState
import com.tether.app.client.StopCommandResult
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.model.LegacyProjectionAdapter
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
 * T6.4 behaviour at ChatScreen level: the run tabs and roster select runs; a run tab streams its
 * steps and result; a spawned child shows its output and pictures; a denial's origin link lands
 * on the refused step; the todo bar toggles; background commands open their output, and Stop is
 * sent only by a tap, on a live connection, for a command still running — never by anything
 * received.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SubagentRunBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private val session = SubagentFixtures.session

    private fun host(client: ChatTestClient, shown: AgentSession = session): TetherViewModel {
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                val sessions by client.sessions.collectAsStateWithLifecycle()
                val s = sessions.firstOrNull { it.id == shown.id } ?: shown
                ChatScreen(vm = vm, session = s, projection = projections[s.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
            }
        }
        rule.waitForIdle()
        arm()
        return vm
    }

    /** Let the composition settle ([SETTLE_MS]); ta-coik.13: the Stop key has no arm delay. */
    private fun arm() {
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private fun client(folded: ChatFixtures.Folded, shown: AgentSession = session) = ChatTestClient().also { it.show(shown, folded) }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    // ---- tabs, roster, run panel --------------------------------------------------------------

    @Test fun theTabStripListsEveryRunAsTabsOfAtLeast44dpAndSelectsOne() {
        val vm = host(client(SubagentFixtures.web))
        rule.onNodeWithTag("subrun-tab-session").assert(hasRole(Role.Tab)).assertIsSelected().assertHeightIsAtLeast(44.dp)
        val done = rule.onNodeWithTag("subrun-tab-t1::toolu_a")
        done.assert(hasRole(Role.Tab)).assertHeightIsAtLeast(44.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Survey the tests · general-purpose · claude sub-agent · done")))
        // The failed run says "error" in words, not only by its red glyph.
        rule.onNodeWithTag("subrun-tab-t1::toolu_b")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "error"))
        done.performClick()
        rule.waitForIdle()
        assertEquals("t1::toolu_a", vm.selectedRunIdBySession.value["s1"])
        rule.onNodeWithTag("subrun-tab-t1::toolu_a").assertIsSelected()
        rule.onNodeWithTag("subrun-panel").assertIsDisplayed()
    }

    @Test fun aRunTabShowsItsHeaderStepsAndResult() {
        val vm = host(client(SubagentFixtures.web))
        rule.runOnIdle { vm.selectRun("s1", "t1::toolu_a") }
        rule.waitForIdle()
        rule.onNodeWithText("Survey the tests").assertIsDisplayed()
        // Studio's status legends are not upper-cased, so the tab's badge and the header read alike.
        rule.onAllNodesWithText("done").onFirst().assertIsDisplayed()
        rule.onNodeWithText("3 steps").assertIsDisplayed()
        rule.onNodeWithText("usage not captured").assertIsDisplayed()
        rule.onNodeWithText("Task given to this sub-agent").performClick()
        rule.onNodeWithText("List the test files and what they cover").assertIsDisplayed()
        // Thinking is off by default: the Glob step and the message, not the thought.
        rule.onAllNodesWithText("Looking for test files.").assertCountEquals(0)
        rule.onNodeWithText("Glob").assertIsDisplayed()
        rule.onNodeWithTag("subrun-panel").performScrollToNode(hasText("Result returned to the parent"))
        rule.onNodeWithText("Result returned to the parent").assertIsDisplayed()

        rule.runOnIdle { vm.selectRun("s1", "t1::toolu_b") }
        rule.waitForIdle()
        rule.onNodeWithTag("subrun-panel").performScrollToNode(hasText("Error returned to the parent"))
        rule.onNodeWithText("Error returned to the parent").assertIsDisplayed()
        rule.onNodeWithText("Could not fetch the changelog (403).").assertIsDisplayed()
    }

    @Test fun theRosterIsClosedByDefaultAndItsRowsOpenTheirTab() {
        val vm = host(client(SubagentFixtures.web))
        rule.onNodeWithTag("subrun-roster").assertIsDisplayed().assertHeightIsAtLeast(44.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        rule.onNodeWithText("1 failed").assertIsDisplayed()
        // Tabs expose their full title as their name; the roster rows are not there while it is closed.
        rule.onAllNodesWithText("Check the changelog").assertCountEquals(0)
        rule.onNodeWithTag("subrun-roster").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Check the changelog").assertCountEquals(1)
        rule.onNodeWithText("Check the changelog").performClick()
        rule.waitForIdle()
        assertEquals("t1::toolu_b", vm.selectedRunIdBySession.value["s1"])
    }

    @Test fun aSpawnedChildShowsItsLiveOutputAndThePictureItWasHanded() {
        val vm = host(client(SubagentFixtures.running))
        rule.onNodeWithTag("subrun-tab-spawn::run-1")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Fix the flaky test · codex spawned · build · running")))
        rule.runOnIdle { vm.selectRun("s1", "spawn::run-1") }
        rule.waitForIdle()
        rule.onNodeWithTag("subrun-panel").performScrollToNode(hasTestTag("spawned-run-output"))
        rule.onNodeWithText("Live output").assertIsDisplayed()
        rule.onNodeWithText("Images handed to this agent · 1").assertIsDisplayed()
        rule.onNodeWithText("compiling…\nrunning 12 tests", substring = true).assertIsDisplayed()
        rule.onNodeWithText("/w/.tether/runs/run-1.log").assertIsDisplayed()

        rule.runOnIdle { vm.selectRun("s1", "spawn::run-2") }
        rule.waitForIdle()
        rule.onNodeWithText("running (unconfirmed)").assertIsDisplayed()
        rule.onNodeWithTag("subrun-panel").performScrollToNode(hasText("Linked native run"))
        rule.onNodeWithText("Launched outside Tether", substring = true).assertIsDisplayed()
    }

    @Test fun aBackgroundLaunchWithALiveTaskIsRunningAndWithoutOneIsUnconfirmed() {
        val client = client(SubagentFixtures.running)
        host(client)
        // v117: the launcher's tool result only acknowledged the launch; its task is live.
        rule.onNodeWithTag("subrun-tab-t1::toolu_bg")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Watch the CI · claude sub-agent · running")))
        // The SDK's level set no longer lists it and no completion arrived: never a tick.
        rule.runOnIdle {
            val tree = foldTree(SubagentFixtures.running.tree, evNullTurn("background_tasks_changed", ts = 9) { put("x", 0) })
            client.show(session, ChatFixtures.Folded(checkNotNull(LegacyProjectionAdapter.adaptOnce(tree)), tree))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("subrun-tab-t1::toolu_bg")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Watch the CI · claude sub-agent · running (unconfirmed)")))
    }

    @Test fun aStoppedSpawnedChildReadsItsRawStatusAndExitCode() {
        val vm = host(client(SubagentFixtures.spawnedStopped))
        rule.runOnIdle { vm.selectRun("s1", "spawn::run-3") }
        rule.waitForIdle()
        rule.onNodeWithText("stopped · exit 143").assertIsDisplayed()
        rule.onNodeWithTag("subrun-panel").performScrollToNode(hasText("Output"))
        rule.onNodeWithText("src/a.ts: 3 problems", substring = true).assertIsDisplayed()
    }

    /** ta-blf r2: a spawned CLI's output is terminal output: colour dropped, every other control a visible token. */
    @Test fun aSpawnedChildsOutputIsDrawnAsTerminalOutput() {
        val t = SubagentFixtures.T_SUB
        val f = ChatFixtures.fold(
            *ChatFixtures.turn("t1", "Spawn it.", "Spawned it.", t),
            evNullTurn("spawned_run_updated", ts = t) {
                put("runId", "run-9"); put("origin", "spawned"); put("provider", "codex"); put("title", "Lint")
                put("logFile", "/w/run-9.log"); put("parentTurnId", "t1"); put("status", "stopped"); put("exitCode", 1); put("startedAt", t); put("endedAt", t + 1)
            },
            evNullTurn("spawned_run_output", ts = t) { put("runId", "run-9"); put("text", "\u001B[31mFAIL\u001B[0m \u202Eexe.txt\n") },
        )
        val vm = host(client(f))
        rule.runOnIdle { vm.selectRun("s1", "spawn::run-9") }
        rule.waitForIdle()
        rule.onNodeWithTag("subrun-panel").performScrollToNode(hasText("Output"))
        rule.onNodeWithText("FAIL \u2060\u27E8U+202E\u27E9exe.txt", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test fun aLifecycleOnlyChildSaysItsStepsStayOnItsThread() {
        val codex = session.copy(provider = "codex")
        val vm = host(client(SubagentFixtures.codexThread, codex), codex)
        rule.runOnIdle { vm.selectRun("s1", "thread::thread-rev") }
        rule.waitForIdle()
        rule.onNodeWithText("reviewer").assertIsDisplayed()
        rule.onNodeWithText("codex ran this sub-agent in its own thread", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Lifecycle · started → completed").assertIsDisplayed()
        rule.onAllNodesWithText("0 steps").assertCountEquals(0)
    }

    @Test fun aDenialsOriginLinkOpensTheRunAndLandsOnTheRefusedStep() {
        val vm = host(client(ApprovalFixtures.denials))
        // Open both activity groups (the Agent call sits in the second).
        rule.onAllNodesWithTag("tool-activity-group")[1].performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("denial-origin-link"))
        rule.onNodeWithTag("denial-origin-link").performClick()
        rule.waitForIdle()
        assertEquals("t1::task-1", vm.selectedRunIdBySession.value["s1"])
        rule.onNodeWithText("Read").assertIsDisplayed()
    }

    // ---- todo bar -------------------------------------------------------------------------------

    @Test fun theTodoBarShowsTheCurrentItemAndTogglesTheList() {
        host(client(SubagentFixtures.activity))
        val head = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Running the full suite, 1 of 3 tasks complete")))
        head.assertIsDisplayed().assertHeightIsAtLeast(44.dp)
        rule.onAllNodesWithTag("todo-item").assertCountEquals(0)
        head.performClick()
        rule.waitForIdle()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Fix the flaky test, to do"))).assertIsDisplayed()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Build the package, done"))).assertIsDisplayed()
        head.performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("todo-item").assertCountEquals(0)
        // Issue #9: a tap on the expanded list itself collapses it too.
        head.performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("todo-item").assertCountEquals(3)
        rule.onAllNodesWithTag("todo-item")[1].performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("todo-item").assertCountEquals(0)
    }

    // ---- background commands and Stop -----------------------------------------------------------

    @Test fun onlyTheRunningCommandSitsInTheBarAndATapOnStopSendsOneStop() {
        val client = client(SubagentFixtures.activity)
        host(client)
        rule.onAllNodesWithTag("bg-command-open").assertCountEquals(1)
        rule.onNodeWithText(commandLabel("npm test -- --runInBand")).assertIsDisplayed()
        val stop = rule.onNodeWithTag("bg-command-stop")
        stop.assertIsEnabled().assertHeightIsAtLeast(44.dp)
        assertTrue("nothing is sent before a tap", client.stopCalls.isEmpty())
        stop.performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1:bg-3"), client.stopCalls)
        // Sent: the key reads "Stopping…" and a second tap sends nothing more.
        rule.onNodeWithTag("bg-command-stop").assertIsNotEnabled()
        rule.onNodeWithTag("bg-command-stop").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Stopping ${commandLabel("npm test -- --runInBand")}")))
        rule.onNodeWithTag("bg-command-stop").performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1:bg-3"), client.stopCalls)
    }

    @Test fun aRefusedStopKeepsTheKeyAndSendsNothingElse() {
        val client = client(SubagentFixtures.activity)
        client.stopResult = StopCommandResult.NotRunning
        host(client)
        rule.onNodeWithTag("bg-command-stop").performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1:bg-3"), client.stopCalls)
        rule.onNodeWithTag("bg-command-stop").assertIsEnabled()
    }

    @Test fun stopIsDisabledAndSaysWhyOfflineCatchingUpOrReadOnly() {
        val client = client(SubagentFixtures.activity)
        client.link.value = ConnectionState.Disconnected
        host(client)
        val stop = rule.onNodeWithTag("bg-command-stop")
        stop.assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Stop ${commandLabel("npm test -- --runInBand")}, unavailable: Connect to stop it. This is a saved copy.")))
        stop.performClick()
        rule.runOnIdle {
            client.link.value = ConnectionState.Connected
            client.live.value = emptySet()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("bg-command-stop").assertIsNotEnabled().performClick()
        rule.runOnIdle { client.sessions.value = listOf(session.copy(readOnly = true)); client.live.value = setOf("s1") }
        rule.waitForIdle()
        rule.onNodeWithTag("bg-command-stop").assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertTrue(client.stopCalls.isEmpty())
    }

    @Test fun nothingReceivedEverStopsACommand() {
        val client = client(SubagentFixtures.activity)
        host(client)
        // New running commands, output that names the command, the command finishing: folded, never answered.
        var tree = SubagentFixtures.activity.tree
        rule.runOnIdle {
            tree = foldTree(
                tree,
                evNullTurn("background_command_output", ts = 9) { put("commandId", "bg-3"); put("stream", "stdout"); put("text", "stop-command bg-3\n") },
                evNullTurn("background_command_updated", ts = 10) {
                    put("commandId", "bg-4"); put("command", "yes"); put("cwd", "/w"); put("logFile", "/w/bg-4.log"); put("status", "running"); put("startedAt", 10)
                },
            )
            client.show(session, ChatFixtures.Folded(checkNotNull(LegacyProjectionAdapter.adaptOnce(tree)), tree))
        }
        rule.waitForIdle()
        rule.onAllNodesWithTag("bg-command-open").assertCountEquals(2)
        assertTrue(client.stopCalls.isEmpty())
    }

    @Test fun aFinishedCommandsChipOpensItsCapturedOutputWithoutStop() {
        val client = client(SubagentFixtures.activity)
        host(client)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("bg-command-chip"))
        val chip = rule.onAllNodesWithTag("bg-command-chip")[0]
        chip.assertHeightIsAtLeast(44.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("View output of ${commandLabel("npm run build")}, exit 0")))
            .performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("command-output").assertIsDisplayed()
        rule.onNodeWithText("compiled 214 modules", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Full output: /w/.tether/bg-1.log").assertIsDisplayed()
        rule.onAllNodesWithTag("bg-command-stop").assertCountEquals(1) // the bar's, not the sheet's
        rule.onNodeWithTag("command-output-close").assertHeightIsAtLeast(44.dp).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("command-output").assertCountEquals(0)
        assertTrue(client.stopCalls.isEmpty())
    }

    @Test fun theLiveSheetStreamsAndItsStopIsATap() {
        val client = client(SubagentFixtures.activity)
        host(client)
        rule.onNodeWithTag("bg-command-open").performClick()
        rule.waitForIdle()
        arm()
        rule.onNodeWithText("PASS src/config.test.ts", substring = true).assertIsDisplayed()
        rule.onNodeWithText("warn: slow test", substring = true).assertIsDisplayed()
        assertTrue(client.stopCalls.isEmpty())
        val stops = rule.onAllNodesWithTag("bg-command-stop")
        stops.assertCountEquals(2)
        stops[1].performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1:bg-3"), client.stopCalls)
    }

    @Test fun theChipsJoinTheTranscriptByLaunchTime() {
        val folded = SubagentFixtures.activity
        val keys = buildChatItems(folded.projection, folded.tree, showThinking = false).map { it.key }
        val t2 = keys.indexOf("t2/t2:u0").takeIf { it >= 0 } ?: keys.indexOfFirst { it.startsWith("t2/") }
        assertTrue(keys.indexOf("bg-bg-1") in 0 until t2)
        assertTrue(keys.indexOf("bg-bg-2") in keys.indexOf("bg-bg-1") + 1 until t2)
        assertTrue("the running command is in the bar, not the transcript", "bg-bg-3" !in keys)
        // Every chip precedes the turn launched after it and follows the one launched before.
        assertTrue(keys.indexOfFirst { it.startsWith("t1/") } < keys.indexOf("bg-bg-1"))
    }
}
