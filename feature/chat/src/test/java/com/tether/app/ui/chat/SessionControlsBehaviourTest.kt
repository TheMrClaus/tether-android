package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.tether.app.client.CodexSnapshot
import com.tether.app.client.ControlResult
import com.tether.app.client.ModeVocabulary
import com.tether.app.client.OpencodeSnapshot
import com.tether.app.client.ProviderControlsState
import com.tether.app.client.SessionControl
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.chat.SessionControlFixtures.Recorder
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Frames for a window to open; well under [CONSENT_ARM_DELAY_MS]. */
internal const val NAV_SETTLE_MS = 200L

/** The composer host shared by the phone and tablet behaviour tests. */
internal class ControlsHost(private val rule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, ComponentActivity>) {
    val recorder = Recorder()
    var session by mutableStateOf<AgentSession?>(SessionControlFixtures.claude)
    var controls by mutableStateOf<ServerMessage.SessionControls?>(SessionControlFixtures.claudeControls)
    var lock by mutableStateOf<ConsentLock?>(null)
    var codex by mutableStateOf<ProviderControlsState<CodexSnapshot>?>(null)
    var opencode by mutableStateOf<ProviderControlsState<OpencodeSnapshot>?>(null)
    var origin by mutableStateOf("https://tether.test")
    val requests = mutableListOf<String>()
    val prompts = mutableListOf<String>()

    fun show() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ComposerHost(TetherSkin.Machine) {
                Composer(
                    session = session,
                    projection = ComposerFixtures.idle.projection,
                    controls = controls,
                    serverNow = { ComposerFixtures.BUSY_NOW },
                    onSend = { text, _ -> prompts += text; true },
                    onInterrupt = {},
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = {},
                    onRequestControls = { requests += "session-controls" },
                    liveness = ComposerLiveness.Live,
                    controlActions = recorder.actions(lock, codex, opencode, origin),
                )
            }
        }
        settle()
    }

    /** One frame (an input event lands on it), then [ms] more. */
    fun settle(ms: Long = NAV_SETTLE_MS) {
        rule.mainClock.advanceTimeBy(16)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    /** Past the T6.3 arming delay. */
    fun arm() = settle(CONSENT_ARM_DELAY_MS + 100)

    /** A tap, then a few frames: a sheet or menu window needs them to open (far under the arming delay). */
    fun click(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        settle()
    }
}

/**
 * T7.2 behaviour on a phone (the web below 64rem): the one settings key in the toolbar opens the
 * session sheet; a pick sends exactly its value through [SessionControlActions.onControl]; Mode rows
 * are armed; the most permissive posture needs the confirmation; nothing received, restored or
 * recomposed sends anything; a locked session sends nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SessionControlsPhoneBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val h = ControlsHost(rule)

    @Test
    fun nothingIsSentByCompositionOrByStateThatMoves() {
        h.show()
        h.arm()
        // The session moves under the row: another device picked Auto and a new model, fast mode
        // came on, the controls were re-read, the session went away and came back.
        h.session = SessionControlFixtures.claude.copy(permissionMode = "bypassPermissions", model = "claude-sonnet-5", fastModeState = "on")
        h.settle()
        h.controls = SessionControlFixtures.claudeControls.copy(defaultReasoningEffort = "low")
        h.settle()
        h.session = null
        h.settle()
        h.session = SessionControlFixtures.claude
        h.arm()
        assertTrue("sent ${h.recorder.sent}", h.recorder.sent.isEmpty())
    }

    @Test
    fun theKeyOpensTheHubAndAModelPickSendsThatModel() {
        h.show()
        rule.onNodeWithTag("session-settings-trigger").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Session settings: Opus (1M context)")))
        h.click("session-settings-trigger")
        assertTrue("opening the sheet is a read only", h.recorder.sent.isEmpty())
        h.click("sheet-row-Model")
        h.click("control-option-claude-sonnet-5")
        assertEquals(listOf<SessionControl>(SessionControl.Model("claude-sonnet-5")), h.recorder.sent)
        rule.onNodeWithText("Model set to Sonnet.").assertExists()
        assertTrue("the picker re-reads the controls", h.requests.isNotEmpty())
    }

    @Test
    fun aModelThatCannotExpressTheEffortClearsItFirst() {
        h.session = SessionControlFixtures.claude.copy(reasoningEffort = "high")
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Model")
        h.click("control-option-claude-sonnet-5")
        // Round 2 (L3): the effort is cleared only once the model itself went out.
        assertEquals(listOf(SessionControl.Model("claude-sonnet-5"), SessionControl.Effort("")), h.recorder.sent)
    }

    @Test
    fun aRefusedModelLeavesTheEffortAlone() {
        h.session = SessionControlFixtures.claude.copy(reasoningEffort = "high")
        h.recorder.result = ControlResult.NotOffered
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Model")
        h.click("control-option-claude-sonnet-5")
        assertEquals(listOf<SessionControl>(SessionControl.Model("claude-sonnet-5")), h.recorder.sent)
    }

    @Test
    fun anUnknownStoredModeWarnsOnTheKeyAndIsNotSelectable() {
        h.session = SessionControlFixtures.claude.copy(permissionMode = "dontAsk")
        h.show()
        rule.onNodeWithTag("session-settings-trigger").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Session settings: Opus (1M context), unknown mode")),
        )
        rule.onNodeWithText("Unknown", useUnmergedTree = true).assertExists()
        h.click("session-settings-trigger")
        rule.onNodeWithTag("sheet-row-Mode").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Mode: Unknown mode (dontAsk)")))
        h.click("sheet-row-Mode")
        h.arm()
        rule.onNodeWithTag("control-option-dontAsk").assertIsNotEnabled()
        assertTrue(h.recorder.sent.isEmpty())
    }

    @Test
    fun providerKeysReArmWhenTheCatalogIsReplaced() {
        h.session = SessionControlFixtures.codex
        h.controls = null
        h.codex = SessionControlFixtures.codexState
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Provider controls")
        h.click("codex-compact")
        assertTrue("armed: the first tap as the panel appears lands on nothing", h.recorder.sent.isEmpty())
        h.arm()
        h.click("codex-compact")
        assertEquals(listOf<SessionControl>(SessionControl.CodexCompaction("catalog-3")), h.recorder.sent)
        // The catalog is re-read: the keys re-arm for the new snapshot.
        h.codex = SessionControlFixtures.codexStateNext
        h.settle(16)
        h.click("codex-compact")
        assertEquals(1, h.recorder.sent.size)
        h.arm()
        h.click("codex-compact")
        assertEquals(SessionControl.CodexCompaction("catalog-4"), h.recorder.sent.last())
    }

    @Test
    fun aClientAskingForConfirmationOpensTheDialogForThatControl() {
        h.session = SessionControlFixtures.opencode
        h.controls = SessionControlFixtures.opencodeControls
        h.opencode = SessionControlFixtures.opencodeState
        h.recorder.resultFor = { c -> if (c is SessionControl.OpencodeMode && !c.confirmed) ControlResult.NeedsConfirmation else ControlResult.Sent }
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Provider controls")
        h.arm()
        h.click("opencode-apply-mode")
        rule.onNodeWithText("Confirm the change to continue.").assertDoesNotExist()
        rule.onNodeWithText("Turn on \u2068Build\u2069?").assertExists()
        h.arm()
        h.click("escalation-confirm")
        assertEquals(
            listOf<SessionControl>(SessionControl.OpencodeMode("default", "oc-1"), SessionControl.OpencodeMode("default", "oc-1", confirmed = true)),
            h.recorder.sent,
        )
    }

    @Test
    fun aPermissiveAgentCalledPlanIsConfirmedFromThePanel() {
        h.session = SessionControlFixtures.opencode
        h.controls = SessionControlFixtures.sneakyOpencodeControls
        h.opencode = SessionControlFixtures.sneakyOpencodeState
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Provider controls")
        rule.onNodeWithContentDescription("opencode agent/mode").performClick()
        h.settle()
        rule.onNodeWithContentDescription("Plan (planx), Plans only (really: everything)").performClick()
        h.settle()
        h.arm()
        h.click("opencode-apply-mode")
        assertTrue("confirmation first", h.recorder.sent.isEmpty())
        rule.onNodeWithText("Turn on \u2068Plan (planx)\u2069?").assertExists()
        rule.onNodeWithText("Plans only (really: everything). It stays on for this session until you switch it back.").assertExists()
        rule.onNodeWithText("Session: \u2068Sketch the sync outbox\u2069").assertExists()
        h.arm()
        h.click("escalation-confirm")
        assertEquals(listOf<SessionControl>(SessionControl.OpencodeMode("planx", "oc-1", confirmed = true)), h.recorder.sent)
    }

    @Test
    fun effortAndFastSendTheirChoice() {
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Effort")
        h.click("control-option-low")
        rule.onNodeWithText("Reasoning effort set to low.").assertExists()
        h.click("session-settings-trigger")
        h.click("sheet-row-Fast")
        h.arm()
        h.click("control-option-on")
        assertEquals(listOf(SessionControl.Effort("low"), SessionControl.FastMode(true)), h.recorder.sent)
    }

    @Test
    fun modeRowsAreArmed() {
        h.show()
        h.click("session-settings-trigger")
        rule.onNodeWithTag("sheet-row-Mode").performClick()
        h.settle()
        // A tap aimed at what was there before the list appeared lands on nothing.
        rule.onNodeWithTag("control-option-plan").assertIsNotEnabled().performClick()
        h.settle(0)
        assertTrue(h.recorder.sent.isEmpty())
        h.arm()
        h.click("control-option-plan")
        assertEquals(listOf<SessionControl>(SessionControl.Mode("plan")), h.recorder.sent)
    }

    @Test
    fun autoNeedsTheArmedConfirmation() {
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Mode")
        h.arm()
        h.click("control-option-${ModeVocabulary.AUTO}")
        assertTrue("choosing Auto only asks", h.recorder.sent.isEmpty())
        rule.onNodeWithText("Turn on \u2068Auto\u2069?").assertExists()
        // The confirm key is armed too.
        h.click("escalation-confirm")
        assertTrue(h.recorder.sent.isEmpty())
        h.arm()
        h.click("escalation-confirm")
        assertEquals(listOf<SessionControl>(SessionControl.Mode(ModeVocabulary.AUTO, confirmed = true)), h.recorder.sent)
        rule.onNodeWithText("Turn on \u2068Auto\u2069?").assertDoesNotExist()
    }

    @Test
    fun cancellingTheConfirmationSendsNothing() {
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Mode")
        h.arm()
        h.click("control-option-${ModeVocabulary.AUTO}")
        h.arm()
        rule.onNodeWithText("Cancel").performClick()
        h.arm()
        assertTrue(h.recorder.sent.isEmpty())
        rule.onNodeWithText("Turn on \u2068Auto\u2069?").assertDoesNotExist()
    }

    @Test
    fun stoppingTheAppClosesTheAutoConfirmation() {
        // T6.6 r4: the Auto question shares the composer's one pending confirmation; the app going
        // to the background closes it, with the link and the lock unchanged.
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Mode")
        h.arm()
        h.click("control-option-${ModeVocabulary.AUTO}")
        h.arm()
        rule.onNodeWithText("Turn on \u2068Auto\u2069?").assertExists()
        val confirmAt = rule.screenCentreOf("escalation-confirm")
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        h.arm()
        rule.onNodeWithText("Turn on \u2068Auto\u2069?").assertDoesNotExist()
        rule.onAllNodesWithTag("escalation-confirm").assertCountEquals(0)
        rule.tapScreenAt(confirmAt)
        h.arm()
        assertTrue("sent ${h.recorder.sent}", h.recorder.sent.isEmpty())
    }

    @Test
    fun aLockedSessionSendsNothing() {
        h.lock = ConsentLock.Offline
        h.show()
        h.click("session-settings-trigger")
        rule.onNodeWithText("Connect to change session settings.").assertExists()
        h.click("sheet-row-Model")
        h.click("control-option-claude-sonnet-5")
        h.click("session-settings-trigger")
        h.click("sheet-row-Mode")
        h.arm()
        h.click("control-option-plan")
        assertTrue("sent ${h.recorder.sent}", h.recorder.sent.isEmpty())
    }

    @Test
    fun aRefusedTapIsSaidInWords() {
        h.recorder.result = ControlResult.NotConnected
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Model")
        h.click("control-option-claude-sonnet-5")
        rule.onNodeWithText("Not connected — the setting was not changed.").assertExists()
        rule.onNodeWithText("Model set to Sonnet.").assertDoesNotExist()
    }

    @Test
    fun aTypedModelIdPassesThroughAndProseIsRefused() {
        h.show()
        rule.onNodeWithContentDescription("Message the agent").performTextInput("/model claude-haiku-9")
        h.settle()
        rule.onNodeWithContentDescription("Message the agent").performImeAction()
        h.settle()
        assertEquals(listOf<SessionControl>(SessionControl.Model("claude-haiku-9", typed = true)), h.recorder.sent)
        rule.onNodeWithText("Model set to claude-haiku-9 — not in the known list, so the CLI validates it on the next turn.").assertExists()
        rule.onNodeWithContentDescription("Message the agent").performTextInput("/model fix the tests")
        h.settle()
        rule.onNodeWithContentDescription("Message the agent").performImeAction()
        h.settle()
        assertEquals(1, h.recorder.sent.size)
        rule.onNodeWithText("“fix the tests” doesn’t look like a model id. Try /model to see what the CLI offers.").assertExists()
    }

    @Test
    fun aTypedIdTheClientRefusesGetsItsOwnReason() {
        h.recorder.result = ControlResult.NotOffered
        h.show()
        rule.onNodeWithContentDescription("Message the agent").performTextInput("/model claude-haiku-9")
        h.settle()
        rule.onNodeWithContentDescription("Message the agent").performImeAction()
        h.settle()
        rule.onNodeWithText("“claude-haiku-9” wasn’t accepted as a model id for this session — the model was not changed.").assertExists()
        rule.onNodeWithText("That option is no longer offered — the setting was not changed.").assertDoesNotExist()
    }

    @Test
    fun aTypedIdIsPinnedOnOpencodeToo() {
        // Round 3 (F2): chat-view.tsx:3015 — every engine with a model select but Codex.
        h.session = SessionControlFixtures.opencode
        h.controls = SessionControlFixtures.opencodeControls
        h.show()
        rule.onNodeWithContentDescription("Message the agent").performTextInput("/model gpt-9-preview")
        h.settle()
        rule.onNodeWithContentDescription("Message the agent").performImeAction()
        h.settle()
        assertEquals(listOf<SessionControl>(SessionControl.Model("gpt-9-preview", typed = true)), h.recorder.sent)
    }

    @Test
    fun aTypedIdOnCodexIsAnOrdinaryMessage() {
        h.session = SessionControlFixtures.codex
        h.controls = null
        h.codex = SessionControlFixtures.codexState
        h.show()
        rule.onNodeWithContentDescription("Message the agent").performTextInput("/model gpt-9")
        h.settle()
        rule.onNodeWithContentDescription("Message the agent").performImeAction()
        h.settle()
        assertTrue("no model control for Codex", h.recorder.sent.isEmpty())
        assertEquals(listOf("/model gpt-9"), h.prompts)
    }

    @Test
    fun theEffortConfirmationNeverCarriesRawServerText() {
        // Round 4 (F1): a listed effort of U+202E + 10k characters.
        val evil = "\u202E" + "e".repeat(10_000)
        h.controls = SessionControlFixtures.claudeControls.copy(
            models = SessionControlFixtures.claudeControls.models.map {
                if (it.value == "claude-opus-5[1m]") it.copy(variants = listOf(com.tether.app.protocol.ModelVariantOption(evil, evil), com.tether.app.protocol.ModelVariantOption("high", "high"))) else it
            },
        )
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Effort")
        rule.onNodeWithTag("control-option-$evil").performClick()
        h.settle()
        assertEquals(listOf<SessionControl>(SessionControl.Effort(evil)), h.recorder.sent)
        val notice = rule.onAllNodes(androidx.compose.ui.test.hasText("Reasoning effort set to", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().single().config[SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue("${notice.length} chars", notice.length <= 105)
        assertTrue(!notice.contains('\u202E'))
    }

    @Test
    fun aRefusedEffortClearIsSaid() {
        h.session = SessionControlFixtures.claude.copy(reasoningEffort = "high")
        h.recorder.resultFor = { c -> if (c is SessionControl.Effort) ControlResult.NotConnected else ControlResult.Sent }
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Model")
        h.click("control-option-claude-sonnet-5")
        rule.onNodeWithText("The model changed, but its reasoning effort was not reset: Not connected — the setting was not changed.").assertExists()
    }

    @Test
    fun aConfirmationWhoseWordsChangedIsShownAgainNotSent() {
        // Round 3 (I-a): the agent is renamed while the dialog is open.
        h.session = SessionControlFixtures.opencode
        h.controls = SessionControlFixtures.sneakyOpencodeControls
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Mode")
        h.arm()
        h.click("control-option-planx")
        rule.onNodeWithText("Turn on \u2068Plan (planx)\u2069?").assertExists()
        h.controls = SessionControlFixtures.sneakyOpencodeControls.copy(modes = listOf(com.tether.app.protocol.ModeOption("planx", "Everything", "Runs every tool")))
        h.arm()
        h.click("escalation-confirm")
        assertTrue("the changed words are shown first", h.recorder.sent.isEmpty())
        rule.onNodeWithText("Turn on \u2068Everything (planx)\u2069?").assertExists()
        h.arm()
        h.click("escalation-confirm")
        assertEquals(listOf<SessionControl>(SessionControl.Mode("planx", confirmed = true)), h.recorder.sent)
    }

    @Test
    fun aListedModelNameResolves() {
        h.show()
        rule.onNodeWithContentDescription("Message the agent").performTextInput("/model sonnet")
        h.settle()
        rule.onNodeWithContentDescription("Message the agent").performImeAction()
        h.settle()
        assertEquals(listOf<SessionControl>(SessionControl.Model("claude-sonnet-5")), h.recorder.sent)
    }

    @Test
    fun anAccessibilityActionIsATap() {
        h.show()
        rule.onNodeWithTag("session-settings-trigger").performSemanticsAction(SemanticsActions.OnClick)
        h.settle()
        rule.onNodeWithTag("sheet-row-Model").performSemanticsAction(SemanticsActions.OnClick)
        h.settle()
        rule.onNodeWithTag("control-option-claude-haiku-5").performSemanticsAction(SemanticsActions.OnClick)
        h.settle()
        assertEquals(listOf<SessionControl>(SessionControl.Model("claude-haiku-5")), h.recorder.sent)
    }

    @Test
    fun aReadOnlySessionShowsWhatWasRestoredAndNoKey() {
        h.session = SessionControlFixtures.claude.copy(readOnly = true, model = "claude-opus-4-1", permissionMode = "plan")
        h.show()
        rule.onNodeWithTag("session-settings-trigger").assertDoesNotExist()
        rule.onNodeWithText("Restored from the latest native turn").assertExists()
        rule.onNodeWithContentDescription("Restored permission mode: plan").assertIsNotEnabled()
        assertTrue(h.recorder.sent.isEmpty())
    }

    @Test
    fun codexProviderControlsAreTapOnly() {
        h.session = SessionControlFixtures.codex
        h.controls = null
        h.codex = SessionControlFixtures.codexState
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Provider controls")
        assertEquals("opening the panel re-reads the catalogs", 1, h.recorder.codexReads)
        h.arm()
        rule.onNodeWithTag("codex-compact").performClick()
        h.settle()
        assertEquals(listOf<SessionControl>(SessionControl.CodexCompaction("catalog-3")), h.recorder.sent)
    }

    @Test
    fun codexAutoApproveNeedsTheConfirmation() {
        h.session = SessionControlFixtures.codex
        h.controls = null
        h.codex = SessionControlFixtures.codexState
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Auto approve")
        h.arm()
        h.click("control-option-true")
        assertTrue(h.recorder.sent.isEmpty())
        h.arm()
        h.click("escalation-confirm")
        assertEquals(listOf<SessionControl>(SessionControl.CodexAutoApprove(true, "catalog-3", confirmed = true)), h.recorder.sent)
    }
}

/** T7.2 behaviour from 64rem: the pill row above the well's footer. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class SessionControlsTabletBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val h = ControlsHost(rule)

    @Test
    fun thePillsSendTheChoice() {
        h.show()
        rule.onNodeWithTag("session-settings-trigger").assertDoesNotExist()
        h.click("control-model")
        h.click("control-option-claude-haiku-5")
        h.click("control-mode")
        h.click("control-option-acceptEdits")
        assertEquals("Mode rows are armed", listOf<SessionControl>(SessionControl.Model("claude-haiku-5")), h.recorder.sent)
        h.arm()
        h.click("control-option-acceptEdits")
        assertEquals(listOf(SessionControl.Model("claude-haiku-5"), SessionControl.Mode("acceptEdits")), h.recorder.sent)
    }

    @Test
    fun theOpencodeAutoToggleAsksFirstAndTurnsOffWithoutAsking() {
        h.session = SessionControlFixtures.opencode
        h.controls = SessionControlFixtures.opencodeControls
        h.show()
        h.click("control-auto")
        assertTrue("the toggle is armed", h.recorder.sent.isEmpty())
        h.arm()
        h.click("control-auto")
        assertTrue(h.recorder.sent.isEmpty())
        h.arm()
        h.click("escalation-confirm")
        assertEquals(listOf<SessionControl>(SessionControl.Mode(ModeVocabulary.AUTO, confirmed = true)), h.recorder.sent)
        h.session = SessionControlFixtures.opencode.copy(approvalPolicy = "never")
        h.arm()
        rule.onNodeWithText("Auto-approves permission requests that are not explicitly denied").assertExists()
        h.click("control-auto")
        assertEquals(SessionControl.Mode("default"), h.recorder.sent.last())
    }

    @Test
    fun codexModelKeepsTheEffortTheNewModelSupports() {
        h.session = SessionControlFixtures.codex.copy(reasoningEffort = "high")
        h.controls = null
        h.codex = SessionControlFixtures.codexState
        h.show()
        h.click("control-model")
        h.click("control-option-gpt-5.5-mini")
        h.click("control-effort")
        h.click("control-option-medium")
        assertEquals(
            listOf<SessionControl>(SessionControl.CodexModelSelection("gpt-5.5-mini", "low", "catalog-3"), SessionControl.CodexModelSelection("gpt-5.5", "medium", "catalog-3")),
            h.recorder.sent,
        )
    }

    @Test
    fun aPermissiveAgentCalledPlanIsConfirmedFromTheRow() {
        h.session = SessionControlFixtures.opencode
        h.controls = SessionControlFixtures.sneakyOpencodeControls
        h.show()
        h.click("control-mode")
        h.arm()
        rule.onNodeWithTag("control-option-planx").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Plan (planx), Plans only (really: everything)")))
        h.click("control-option-planx")
        assertTrue(h.recorder.sent.isEmpty())
        rule.onNodeWithText("Turn on \u2068Plan (planx)\u2069?").assertExists()
        h.arm()
        h.click("escalation-confirm")
        assertEquals(listOf<SessionControl>(SessionControl.Mode("planx", confirmed = true)), h.recorder.sent)
    }

    @Test
    fun fastModeIsReachableFromTheWideRow() {
        h.show()
        h.click("control-fast")
        assertTrue("opening is a read", h.recorder.sent.isEmpty())
        h.arm()
        h.click("control-option-on")
        assertEquals(listOf<SessionControl>(SessionControl.FastMode(true)), h.recorder.sent)
    }

    @Test
    fun beforeTheCodexCatalogArrivesTheSelectsAreDisabled() {
        h.session = SessionControlFixtures.codex
        h.controls = null
        h.codex = ProviderControlsState(null, true, null)
        h.show()
        rule.onNodeWithTag("control-model").assertIsNotEnabled()
        rule.onNodeWithTag("control-mode").assertIsNotEnabled()
        assertTrue(h.recorder.sent.isEmpty())
    }
}
