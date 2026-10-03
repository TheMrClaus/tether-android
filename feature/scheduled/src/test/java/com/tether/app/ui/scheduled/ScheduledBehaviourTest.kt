package com.tether.app.ui.scheduled

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.tether.app.client.ProviderCatalogEntry
import com.tether.app.client.ScheduledActionsState
import com.tether.app.client.ScheduledContinuation
import com.tether.app.protocol.ScheduledActionInput
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.mode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the view asked its hosts to send. */
class ScheduledRecorder {
    val creates = mutableListOf<ScheduledActionInput>()
    val updates = mutableListOf<Pair<String, ScheduledActionInput>>()
    val controls = mutableListOf<Pair<String, String>>()
    val cancels = mutableListOf<Pair<String, Long>>()
    val opens = mutableListOf<String>()
    val sent: Int get() = creates.size + updates.size + controls.size + cancels.size

    val handlers = ScheduledActionsHandlers(
        onCreate = { creates += it },
        onUpdate = { id, s -> updates += id to s },
        onControl = { id, a -> controls += id to a },
        onCancelContinuation = { id, at -> cancels += id to at },
        onOpenSession = { opens += it },
    )
}

/** The states the behaviour tests and the goldens draw. */
object ScheduledStates {
    private val f = ScheduledFixtures
    val consent = JsonObject(mapOf("setupConsent" to JsonPrimitive("sha256:" + "a".repeat(64))))

    val populated = ScheduledActionsState(
        schedules = listOf(
            f.schedule(
                id = "triage", runs = listOf(f.run("r1", "succeeded", f.NOW - 26 * 3_600_000, sessionId = "sess-triage")),
                unknown = consent,
            ),
            f.schedule(
                id = "nightly", name = "Nightly build check", prompt = "Run the full build and report anything red.", provider = "codex",
                cron = "30 2 * * *", timeZone = "Europe/Rome", maxRuns = 30,
                runs = listOf(f.run("r2", "running", null, sessionId = "sess-nightly")),
            ),
            f.schedule(
                id = "report", name = "Weekly report", prompt = "Summarize the week.", status = "paused", nextRunAt = null, cron = "0 9 * * 1",
                runs = listOf(f.run("r3", "failed", f.NOW - 2 * 86_400_000, error = "Engine exited with code 1 before the first turn.")),
            ),
            f.schedule(
                id = "once", name = "Release notes", prompt = "Draft the release notes.", status = "completed", cron = "0 9 1 10 *", maxRuns = 1, nextRunAt = null,
                runs = listOf(f.run("r4", "succeeded", f.NOW - 2 * 86_400_000, sessionId = "sess-once")),
            ),
        ),
        continuations = listOf(ScheduledContinuation("sess-limit", "Refactor the sidebar", "claude", "/home/op/projects/tether", f.NOW + 40 * 60_000, f.NOW + 42 * 60_000)),
        loaded = true,
    )
    val empty = ScheduledActionsState(loaded = true)
    val entries: List<ProviderCatalogEntry> = listOf(f.claude, f.codexWork)
}

@Composable
fun ScheduledUnderTest(
    state: ScheduledActionsState,
    recorder: ScheduledRecorder,
    viewport: Int,
    ui: ScheduledUiState = rememberScheduledUiState(),
    entries: List<ProviderCatalogEntry> = ScheduledStates.entries,
    skin: com.tether.app.ui.theme.TetherSkin = com.tether.app.ui.theme.TetherSkin.Studio,
) {
    TetherTheme(skin.mode) {
        CompositionLocalProvider(LocalReducedMotion provides true) {
            ScheduledActionsView(
                state = state,
                providerEntries = entries,
                currentWorkspace = "/home/op/projects/tether",
                workspaceRoot = "/home/op",
                pinnedProjects = listOf("/home/op/projects/site"),
                handlers = recorder.handlers,
                ui = ui,
                now = ScheduledFixtures.NOW,
                zone = ScheduledFixtures.UTC,
                locale = ScheduledFixtures.US,
                viewportWidth = viewport,
                clock = { ScheduledFixtures.NOW },
            )
        }
    }
}

/**
 * T9.3: components/scheduled-actions-view.tsx's behaviour, on the phone (412dp) and the tablet
 * (1280dp) layout: the tabs and their counts, every row action and what it sends, the two-tap
 * Delete and its 4 s disarm, the editor's create / edit / validation / close, and the view's state
 * across a recreation.
 */
abstract class ScheduledBehaviourBase(private val viewport: Int) {
    @get:Rule val rule = createComposeRule()

    protected val rec = ScheduledRecorder()

    protected fun show(state: ScheduledActionsState = ScheduledStates.populated, entries: List<ProviderCatalogEntry> = ScheduledStates.entries) {
        rule.setContent { ScheduledUnderTest(state, rec, viewport, entries = entries) }
        rule.waitForIdle()
    }

    private fun inRow(id: String, text: String) = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(ScheduledTags.row(id))))

    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    /** An option of the open select menu (its row reads its label as its description). */
    private fun pick(label: String) = rule.onNode(hasContentDescription(label) and hasAnyAncestor(isPopup())).performClick()

    @Test fun theTabsSplitTheListsAndCountThem() {
        show()
        rule.onNodeWithTag(ScheduledTags.tab(ScheduledTab.Schedules)).assertIsSelected()
        rule.onNode(hasText("3") and hasAnyAncestor(hasTestTag(ScheduledTags.tab(ScheduledTab.Schedules))), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("1") and hasAnyAncestor(hasTestTag(ScheduledTags.tab(ScheduledTab.Completed))), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("1") and hasAnyAncestor(hasTestTag(ScheduledTags.tab(ScheduledTab.Continuations))), useUnmergedTree = true).assertExists()
        assertEquals(1, count(ScheduledTags.row("triage")))
        assertEquals(0, count(ScheduledTags.row("once")))
        rule.onNodeWithTag(ScheduledTags.tab(ScheduledTab.Completed)).performClick()
        rule.onNodeWithTag(ScheduledTags.tab(ScheduledTab.Completed)).assertIsSelected()
        rule.onNodeWithTag(ScheduledTags.tab(ScheduledTab.Schedules)).assertIsNotSelected()
        assertEquals(1, count(ScheduledTags.row("once")))
        // A completed schedule has no Run now and no Pause; it keeps Edit and Delete.
        inRow("once", "Run now").assertDoesNotExist()
        inRow("once", "Pause").assertDoesNotExist()
        inRow("once", "Edit").assertExists()
        inRow("once", "1 completed").assertExists()
        rule.onNodeWithTag(ScheduledTags.tab(ScheduledTab.Continuations)).performClick()
        assertEquals(1, count(ScheduledTags.continuation("sess-limit")))
        rule.onNodeWithText("Continues this conversation automatically in 42 minutes.").assertExists()
        rule.onNodeWithText("Limit resets in 40 minutes").assertExists()
    }

    @Test fun aRowShowsItsCadenceNextRunLastRunAndStatus() {
        show()
        inRow("triage", "Weekdays at 09:00").assertExists()
        inRow("triage", "Next run").assertExists()
        inRow("triage", "in 3 hours").assertExists()
        inRow("triage", "Last ran yesterday").assertExists()
        inRow("triage", "Active").assertExists()
        inRow("nightly", "Running").assertExists()
        inRow("nightly", "Running now").assertExists()
        // The pill and the next column's caption.
        assertEquals(2, rule.onAllNodes(hasText("Paused") and hasAnyAncestor(hasTestTag(ScheduledTags.row("report")))).fetchSemanticsNodes().size)
        inRow("report", "No run queued").assertExists()
        inRow("report", "Last run failed · 2 days ago").assertExists()
        inRow("report", "Engine exited with code 1 before the first turn.").assertExists()
    }

    @Test fun runNowPauseAndResumeSendTheirControls() {
        show()
        inRow("triage", "Run now").performScrollTo().assertIsEnabled().performClick()
        inRow("triage", "Pause").performScrollTo().performClick()
        inRow("report", "Resume").performScrollTo().performClick()
        // A schedule whose newest run is running cannot be run again.
        inRow("nightly", "Run now").performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(listOf("triage" to "run", "triage" to "pause", "report" to "resume"), rec.controls)
    }

    @Test fun deleteTakesTwoTapsAndDisarmsAfterFourSeconds() {
        show()
        inRow("triage", "Delete").performScrollTo().performClick()
        inRow("triage", "Confirm delete").assertExists()
        assertEquals(emptyList<Pair<String, String>>(), rec.controls)
        rule.mainClock.advanceTimeBy(ScheduledRules.DELETE_ARM_MS + 100)
        rule.waitForIdle()
        inRow("triage", "Delete").assertExists()
        inRow("triage", "Delete").performScrollTo().performClick()
        inRow("triage", "Confirm delete").performClick()
        assertEquals(listOf("triage" to "delete"), rec.controls)
        // Arming one row disarms another.
        inRow("report", "Delete").performScrollTo().performClick()
        inRow("nightly", "Delete").performScrollTo().performClick()
        inRow("report", "Delete").assertExists()
        inRow("nightly", "Confirm delete").assertExists()
        assertEquals(1, rec.controls.size)
    }

    @Test fun openLastSessionAndOpenConversationHandTheSessionOver() {
        show()
        inRow("triage", "Open last session").performScrollTo().performClick()
        // No session on the paused row's failed run: no key.
        inRow("report", "Open last session").assertDoesNotExist()
        rule.onNodeWithTag(ScheduledTags.tab(ScheduledTab.Continuations)).performClick()
        rule.onNodeWithText("Open conversation").performClick()
        assertEquals(listOf("sess-triage", "sess-limit"), rec.opens)
        assertEquals(0, rec.sent)
    }

    @Test fun cancelDismissesTheContinuationsLimitResume() {
        show()
        rule.onNodeWithTag(ScheduledTags.tab(ScheduledTab.Continuations)).performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("sess-limit" to ScheduledFixtures.NOW + 40 * 60_000), rec.cancels)
    }

    @Test fun aNewScheduleIsCreatedWithTheWebsDefaults() {
        show()
        rule.onNodeWithTag(ScheduledTags.New).performClick()
        assertEquals(1, count(ScheduleEditorTags.Dialog))
        rule.onNodeWithTag(ScheduleEditorTags.Name).performTextInput("Morning triage")
        rule.onNodeWithTag(ScheduleEditorTags.Prompt).performTextInput("Review the new issues.")
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        rule.waitForIdle()
        assertEquals(
            listOf(
                ScheduledActionInput(
                    "Morning triage", "Review the new issues.", "/home/op/projects/tether", "claude", null, "claude-opus", null, null, null,
                    false, "0 9 * * 1-5", "UTC", null,
                ),
            ),
            rec.creates,
        )
        assertEquals(0, count(ScheduleEditorTags.Dialog))
    }

    @Test fun everyEditorChoiceReachesTheFrame() {
        show()
        rule.onNodeWithTag(ScheduledTags.New).performClick()
        rule.onNodeWithTag(ScheduleEditorTags.Name).performTextInput("Nightly")
        rule.onNodeWithTag(ScheduleEditorTags.Prompt).performTextInput("Run the checks")
        rule.onNodeWithTag(ScheduleEditorTags.Workspace).performTextReplacement("")
        rule.onNodeWithTag(ScheduleEditorTags.WorkspaceSuggestions).performClick()
        pick("/home/op/projects/site")
        rule.onNodeWithTag(ScheduleEditorTags.Effort).performScrollTo().performClick()
        pick("High")
        rule.onNodeWithTag(ScheduleEditorTags.Mode).performScrollTo().performClick()
        pick("Auto approve")
        rule.onNodeWithTag(ScheduleEditorTags.Sandbox).performScrollTo().performClick()
        pick("Full access")
        rule.onNodeWithTag(ScheduleEditorTags.Cadence).performScrollTo().performClick()
        pick("Custom cron…")
        rule.onNodeWithTag(ScheduleEditorTags.Cron).performScrollTo().performTextReplacement("30 2 * * *")
        rule.onNodeWithTag(ScheduleEditorTags.TimeZone).performScrollTo().performTextReplacement("Europe/Rome")
        rule.onNodeWithTag(ScheduleEditorTags.RunLimit).performScrollTo().performTextInput("3x")
        rule.onNodeWithTag(ScheduleEditorTags.Worktree).performScrollTo().performClick()
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        rule.waitForIdle()
        assertEquals(
            ScheduledActionInput(
                "Nightly", "Run the checks", "/home/op/projects/site", "claude", null, "claude-opus", "high", "bypassPermissions", "off",
                true, "30 2 * * *", "Europe/Rome", 3,
            ),
            rec.creates.single(),
        )
    }

    @Test fun anotherAgentTakesItsOwnDefaults() {
        show()
        rule.onNodeWithTag(ScheduledTags.New).performClick()
        rule.onNodeWithTag(ScheduleEditorTags.Name).performTextInput("n")
        rule.onNodeWithTag(ScheduleEditorTags.Prompt).performTextInput("p")
        rule.onNodeWithTag(ScheduleEditorTags.Agent).performScrollTo().performClick()
        pick("Codex (work)")
        rule.onNodeWithTag(ScheduleEditorTags.Effort).assertDoesNotExist()
        rule.onNodeWithText("Server tier, else the provider's own").assertExists()
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        val input = rec.creates.single()
        assertEquals(listOf("codex", "work", null), listOf(input.provider, input.profileId, input.model))
    }

    @Test fun runOnceLocksTheLimitAndZoneAndSendsAPinnedCron() {
        show()
        rule.onNodeWithTag(ScheduledTags.New).performClick()
        rule.onNodeWithTag(ScheduleEditorTags.Name).performTextInput("Release notes")
        rule.onNodeWithTag(ScheduleEditorTags.Prompt).performTextInput("Draft them.")
        rule.onNodeWithTag(ScheduleEditorTags.Cadence).performScrollTo().performClick()
        pick("Run once…")
        rule.onNodeWithTag(ScheduleEditorTags.RunLimit).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(ScheduleEditorTags.TimeZone).assertIsNotEnabled()
        rule.onNodeWithText("Locked — runs once").assertExists()
        val runAt = rule.onNodeWithTag(ScheduleEditorTags.RunAt).performScrollTo().fetchSemanticsNode()
        assertEquals("10/04/2026, 09:00 AM", runAt.config.getOrNull(SemanticsProperties.StateDescription))
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        val input = rec.creates.single()
        assertEquals(listOf("0 9 4 10 *", "UTC", 1), listOf(input.cron, input.timeZone, input.maxRuns))
    }

    @Test fun aMissingFieldShowsTheWebsErrorAndSendsNothing() {
        show()
        rule.onNodeWithTag(ScheduledTags.New).performClick()
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        rule.onNodeWithTag(ScheduleEditorTags.Error).assertExists()
        rule.onNodeWithText("Name, prompt, and workspace are required.").assertExists()
        rule.onNodeWithTag(ScheduleEditorTags.Name).performTextInput("n")
        rule.onNodeWithTag(ScheduleEditorTags.Prompt).performTextInput("p")
        rule.onNodeWithTag(ScheduleEditorTags.Cadence).performScrollTo().performClick()
        pick("Custom cron…")
        rule.onNodeWithTag(ScheduleEditorTags.Cron).performScrollTo().performTextReplacement("0 9 * *")
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        rule.onNodeWithText("Cron cadence must contain five fields.").assertExists()
        assertEquals(0, rec.sent)
        assertEquals(1, count(ScheduleEditorTags.Dialog))
    }

    @Test fun anEditIsPrefilledAndSendsBackWhatItDoesNotModel() {
        show()
        inRow("triage", "Edit").performScrollTo().performClick()
        rule.onNodeWithText("Edit schedule").assertExists()
        rule.onNode(hasText("Morning issue triage") and hasTestTag(ScheduleEditorTags.Name)).assertExists()
        rule.onNodeWithTag(ScheduleEditorTags.Name).performTextReplacement("Morning triage")
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        val (id, input) = rec.updates.single()
        assertEquals("triage", id)
        assertEquals(ScheduledStates.populated.schedules.first().toInput().copy(name = "Morning triage"), input)
        assertEquals(ScheduledStates.consent, input.extra)
    }

    @Test fun closingTheEditorSendsNothing() {
        show()
        rule.onNodeWithTag(ScheduledTags.New).performClick()
        rule.onNodeWithTag(ScheduleEditorTags.Name).performTextInput("n")
        rule.onNodeWithTag(ScheduleEditorTags.Cancel).performClick()
        assertEquals(0, count(ScheduleEditorTags.Dialog))
        inRow("triage", "Edit").performScrollTo().performClick()
        rule.onNodeWithTag(ScheduleEditorTags.Close).performClick()
        assertEquals(0, count(ScheduleEditorTags.Dialog))
        assertEquals(0, rec.sent)
    }

    @Test fun withNoAgentReadyNothingCanBeCreated() {
        show(ScheduledStates.empty, entries = listOf(ScheduledFixtures.loading, ScheduledFixtures.gemini))
        rule.onNodeWithTag(ScheduledTags.New).assertIsNotEnabled()
        rule.onNodeWithText("No recurring schedules").assertExists()
        rule.onNodeWithTag(ScheduledTags.EmptyCreate).assertIsNotEnabled()
    }

    @Test fun theEmptyStatesCreateAndExplain() {
        show(ScheduledStates.empty)
        rule.onNodeWithTag(ScheduledTags.EmptyCreate).assertIsEnabled().performClick()
        assertEquals(1, count(ScheduleEditorTags.Dialog))
        rule.onNodeWithTag(ScheduleEditorTags.Close).performClick()
        rule.onNodeWithTag(ScheduledTags.tab(ScheduledTab.Completed)).performClick()
        rule.onNodeWithText("No completed schedules").assertExists()
        rule.onNodeWithTag(ScheduledTags.tab(ScheduledTab.Continuations)).performClick()
        rule.onNodeWithText("No scheduled continuations").assertExists()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ScheduledPhoneBehaviourTest : ScheduledBehaviourBase(412)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-240dpi")
class ScheduledTabletBehaviourTest : ScheduledBehaviourBase(1280)

/** A recreation (rotation, process death) keeps the tab and the open editor with what was typed. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ScheduledRecreationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theTabAndTheOpenEditorSurviveARecreation() {
        val rec = ScheduledRecorder()
        val restoration = StateRestorationTester(rule)
        restoration.setContent { ScheduledUnderTest(ScheduledStates.populated, rec, 412) }
        rule.onNodeWithTag(ScheduledTags.tab(ScheduledTab.Completed)).performClick()
        rule.onNode(hasText("Edit") and hasAnyAncestor(hasTestTag(ScheduledTags.row("once")))).performScrollTo().performClick()
        rule.onNodeWithTag(ScheduleEditorTags.Name).performTextReplacement("Release notes v2")
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithTag(ScheduledTags.tab(ScheduledTab.Completed)).assertIsSelected()
        rule.onNode(hasText("Release notes v2") and hasTestTag(ScheduleEditorTags.Name)).assertExists()
        rule.onNodeWithText("Edit schedule").assertExists()
        // The restored editor still edits the same schedule, in its Run once mode with its own
        // Run at (the completed one-off's 1 Oct, now past): its submit is refused as the web's is.
        rule.onNodeWithTag(ScheduleEditorTags.RunLimit).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        rule.onNodeWithText("Pick a time in the future.").assertExists()
        assertEquals(0, rec.sent)
    }
}
