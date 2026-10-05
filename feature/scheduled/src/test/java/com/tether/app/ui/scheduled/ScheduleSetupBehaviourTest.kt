package com.tether.app.ui.scheduled

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.tether.app.client.CreateErrorReply
import com.tether.app.client.ScheduledActionsState
import com.tether.app.client.WorktreeSetupPreview
import com.tether.app.client.WorktreeSourceInfo
import com.tether.app.client.WorktreeSourceReply
import com.tether.app.protocol.OrNull
import com.tether.app.protocol.ScheduledActionInput
import com.tether.app.ui.components.ConsentTags
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-m7ef (tether PR #241, scheduled-actions-view.tsx 1bf4a465): an isolated schedule runs its project's
 * setup only if the owner approved it when SAVING. Saving asks the server what a run would resolve (an
 * intent `worktree-inspect`), and sends the schedule with the consent the answer carries: "none" when the
 * default create declares nothing, after "Approve setup and save" when it does, `null` for "Save without
 * setup" and for every non-isolated save. Phone (412dp) and tablet (1280dp).
 */
abstract class ScheduleSetupBehaviourBase(private val viewport: Int) {
    @get:Rule val rule = createComposeRule()

    private val rec = ScheduledRecorder()
    private val checks = mutableListOf<Pair<String, String>>()
    private val reply = mutableStateOf<WorktreeSourceReply?>(null)
    private val error = mutableStateOf<CreateErrorReply?>(null)
    private var sends = true
    private var ids = 0
    private val digest = "sha256:" + "ab".repeat(32)

    private fun show(state: ScheduledActionsState = ScheduledStates.populated) {
        rule.setContent {
            TetherTheme(com.tether.app.ui.theme.TetherSkin.Studio.mode) {
                CompositionLocalProvider(LocalReducedMotion provides true) { Host(state) }
            }
        }
        rule.waitForIdle()
    }

    @Composable
    private fun Host(state: ScheduledActionsState) {
        ScheduledActionsView(
            state = state,
            providerEntries = ScheduledStates.entries,
            currentWorkspace = "/home/op/projects/tether",
            workspaceRoot = "/home/op",
            pinnedProjects = emptyList(),
            handlers = rec.handlers.copy(onCheckSetup = { cwd, requestId -> sends.also { if (it) checks += cwd to requestId } }),
            now = ScheduledFixtures.NOW,
            zone = ScheduledFixtures.UTC,
            locale = ScheduledFixtures.US,
            viewportWidth = viewport,
            clock = { ScheduledFixtures.NOW },
            setupReply = reply.value,
            createError = error.value,
            newRequestId = { "chk-${++ids}" },
        )
    }

    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    /** The text of the first node tagged [tag] (a tag may repeat: the body, then the port script's digest line). */
    private fun shown(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true)[0].fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()

    private fun openIsolatedEditor() {
        rule.onNodeWithTag(ScheduledTags.New).performClick()
        rule.onNodeWithTag(ScheduleEditorTags.Name).performTextInput("Morning triage")
        rule.onNodeWithTag(ScheduleEditorTags.Prompt).performTextInput("Review the new issues.")
        rule.onNodeWithTag(ScheduleEditorTags.Worktree).performScrollTo().performClick()
    }

    private fun answer(preview: WorktreeSetupPreview?, requestId: String = "chk-1", isRepo: Boolean = true) {
        rule.runOnIdle { reply.value = WorktreeSourceReply(WorktreeSourceInfo(cwd = "/home/op/projects/tether", isRepo = isRepo, setupPreview = preview), requestId, 1) }
        rule.waitForIdle()
    }

    private fun preview(
        commands: List<String> = listOf("pnpm install"),
        consent: String? = digest,
        error: String? = null,
        hidden: Boolean = false,
        portScript: String? = null,
    ) = WorktreeSetupPreview("branch-off", "origin", "origin/main", "c".repeat(40), commands, listOf("make clean"), portScript, true, if (portScript != null) "e".repeat(64) else null, hidden, digest, digest, consent, error)

    private val none = preview(commands = emptyList(), consent = "none")

    private fun saved(): List<ScheduledActionInput> = rec.creates + rec.updates.map { it.second }

    @Test fun aNonIsolatedSaveSendsAnExplicitNullConsentAndAsksNothing() {
        show()
        rule.onNodeWithTag(ScheduledTags.New).performClick()
        rule.onNodeWithTag(ScheduleEditorTags.Name).performTextInput("Morning triage")
        rule.onNodeWithTag(ScheduleEditorTags.Prompt).performTextInput("Review the new issues.")
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        rule.waitForIdle()
        assertTrue(checks.isEmpty())
        assertEquals(listOf(OrNull<String>(null)), rec.creates.map { it.setupConsent })
        assertEquals(0, count(ScheduleEditorTags.Dialog))
    }

    @Test fun anIsolatedSaveChecksFirstAndSavesNothingUntilTheAnswer() {
        show()
        openIsolatedEditor()
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        rule.waitForIdle()
        assertEquals(listOf("/home/op/projects/tether" to "chk-1"), checks)
        assertEquals("Checking what this project's setup would run…", shown(ScheduleEditorTags.SetupChecking))
        assertTrue("nothing is saved while it checks", saved().isEmpty())
        rule.onNodeWithTag(ScheduleEditorTags.Submit).assertIsNotEnabled()
        assertEquals(1, count(ScheduleEditorTags.Dialog))
    }

    @Test fun nothingDeclaredSavesWithConsentNoneAndClosesTheEditor() {
        show()
        openIsolatedEditor()
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        answer(none)
        assertEquals(listOf(OrNull("none")), saved().map { it.setupConsent })
        assertEquals(true, saved().single().useWorktree)
        assertEquals(0, count(ScheduleEditorTags.Dialog))
    }

    @Test fun declaredSetupIsShownAndApprovingSavesExactlyTheScheduleConsent() {
        show()
        openIsolatedEditor()
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        answer(preview(portScript = "/opt/ports.sh"))
        rule.onNodeWithTag(ScheduleEditorTags.SetupConfirm).performScrollTo().assertExists()
        assertEquals("This project's setup will run on this host before each run", shown(ConsentTags.Title))
        assertTrue(shown(ConsentTags.Body).startsWith("Its committed tether.json on origin/main declares setup that runs outside the agent's sandbox."))
        assertEquals("pnpm install", shown("schedule-setup-command:0"))
        assertEquals("/opt/ports.sh", shown("schedule-port-script:0"))
        // Teardown is not approved here: it is not drawn.
        assertEquals(0, rule.onAllNodesWithTag("schedule-teardown-command:0").fetchSemanticsNodes().size)
        assertTrue(saved().isEmpty())
        rule.onNodeWithTag(ScheduleEditorTags.SetupApprove).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf(OrNull(digest)), saved().map { it.setupConsent })
        assertEquals(0, count(ScheduleEditorTags.Dialog))
    }

    @Test fun saveWithoutSetupSavesAnExplicitNullConsent() {
        show()
        openIsolatedEditor()
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        answer(preview())
        rule.onNodeWithTag(ScheduleEditorTags.SetupWithout).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf(OrNull<String>(null)), saved().map { it.setupConsent })
        assertEquals(true, saved().single().useWorktree)
    }

    @Test fun backReturnsToTheEditorAndSavesNothing() {
        show()
        openIsolatedEditor()
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        answer(preview())
        rule.onNodeWithTag(ScheduleEditorTags.SetupBack).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(0, count(ScheduleEditorTags.SetupConfirm))
        assertEquals(1, count(ScheduleEditorTags.Dialog))
        rule.onNodeWithTag(ScheduleEditorTags.Submit).assertIsEnabled()
        assertTrue(saved().isEmpty())
    }

    @Test fun hiddenCharactersAreWarnedAboutAndDrawnAsTokens() {
        show()
        openIsolatedEditor()
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        answer(preview(commands = listOf("echo аpi ‮ok"), hidden = true))
        rule.onNodeWithTag(ConsentTags.Hidden).performScrollTo().assertExists()
        assertEquals("echo U+0430pi U+202Eok", shown("schedule-setup-command:0"))
    }

    @Test fun anyEditWhileConfirmingCancelsTheCheck() {
        show()
        openIsolatedEditor()
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        answer(preview())
        rule.onNodeWithTag(ScheduleEditorTags.Name).performScrollTo().performTextReplacement("Renamed")
        rule.waitForIdle()
        assertEquals(0, count(ScheduleEditorTags.SetupConfirm))
        rule.onNodeWithTag(ScheduleEditorTags.Submit).assertIsEnabled()
        assertTrue(saved().isEmpty())
    }

    @Test fun aRefusedCheckShowsTheServersWordsAndSavesNothing() {
        show()
        openIsolatedEditor()
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        answer(preview(consent = null, error = "origin has no default branch"))
        assertEquals("origin has no default branch", shown(ScheduleEditorTags.Error))
        assertTrue(saved().isEmpty())
        rule.onNodeWithTag(ScheduleEditorTags.Submit).assertIsEnabled()
        // Not a repository: the web's words.
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        answer(null, requestId = "chk-2", isRepo = false)
        assertEquals("That folder is not a Git repository, so an isolated schedule cannot run there.", shown(ScheduleEditorTags.Error))
        assertTrue(saved().isEmpty())
    }

    @Test fun anAnswerThatIsNotThisChecksOwnIsIgnored() {
        show()
        openIsolatedEditor()
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        answer(none, requestId = "someone-else")
        assertTrue(saved().isEmpty())
        assertEquals("still checking", "Checking what this project's setup would run…", shown(ScheduleEditorTags.SetupChecking))
        answer(none, requestId = "chk-1")
        assertEquals(1, saved().size)
    }

    @Test fun aRefusalOfTheCheckEndsItWithTheErrorsWords() {
        show()
        openIsolatedEditor()
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        // An older error does not end it; a newer requestId-less one does (a validator refusal).
        rule.runOnIdle { error.value = CreateErrorReply("stale", 0, null) }
        rule.waitForIdle()
        assertEquals("still checking", 1, count(ScheduleEditorTags.SetupChecking))
        rule.runOnIdle { error.value = CreateErrorReply("worktree-inspect.cwd refused", 7, null) }
        rule.waitForIdle()
        assertEquals("worktree-inspect.cwd refused", shown(ScheduleEditorTags.Error))
        assertEquals(0, count(ScheduleEditorTags.SetupChecking))
        assertTrue(saved().isEmpty())
    }

    @Test fun aCheckThatCouldNotBeSentSaysSoAndSavesNothing() {
        sends = false
        show()
        openIsolatedEditor()
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        rule.waitForIdle()
        assertEquals("The secure link is reconnecting — the schedule was not saved.", shown(ScheduleEditorTags.Error))
        assertTrue(saved().isEmpty())
        rule.onNodeWithTag(ScheduleEditorTags.Submit).assertIsEnabled()
    }

    @Test fun editingAnIsolatedScheduleChecksAgainAndUpdatesWithTheNewConsent() {
        show()
        rule.onNode(hasText("Edit") and hasAnyAncestor(hasTestTag(ScheduledTags.row("triage")))).performScrollTo().performClick()
        rule.onNodeWithTag(ScheduleEditorTags.Worktree).performScrollTo().performClick()
        rule.onNodeWithTag(ScheduleEditorTags.Submit).performClick()
        answer(preview())
        rule.onNodeWithTag(ScheduleEditorTags.SetupApprove).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("triage"), rec.updates.map { it.first })
        assertEquals(OrNull(digest), rec.updates.single().second.setupConsent)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ScheduleSetupPhoneBehaviourTest : ScheduleSetupBehaviourBase(412)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-240dpi")
class ScheduleSetupTabletBehaviourTest : ScheduleSetupBehaviourBase(1280)
