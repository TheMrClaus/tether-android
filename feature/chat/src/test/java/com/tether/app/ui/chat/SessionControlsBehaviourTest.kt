package com.tether.app.ui.chat

import com.tether.app.testsupport.runPrefsWrite

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.tether.app.client.CodexSnapshot
import com.tether.app.client.SessionControlsGuard
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

/** Frames for a window to open. */
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
    /** ta-coik.55: the per-server preference store the Model menu's pin key writes (null: no pin key). */
    var prefs: com.tether.app.ui.prefs.UiPrefs? = null
    var serverUrl by mutableStateOf("https://tether.test")

    fun show() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val store = prefs
            val pins by androidx.compose.runtime.remember(store) {
                store?.preferencesFor(snapshotFlow { serverUrl })?.map { it.pinnedModels } ?: flowOf(emptyList())
            }.collectAsState(emptyList())
            val scope = rememberCoroutineScope()
            ComposerHost(TetherSkin.StudioDark) {
                Composer(
                    session = session,
                    projection = ComposerFixtures.idle.projection,
                    controls = controls,
                    serverNow = { ComposerFixtures.BUSY_NOW },
                    onSend = { text, _ -> prompts += text; true },
                    onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = {},
                    onRequestControls = { requests += "session-controls" },
                    liveness = ComposerLiveness.Live,
                    controlActions = recorder.actions(lock, codex, opencode, origin),
                    pinnedModels = pins,
                    onToggleModelPin = store?.let { s -> { id -> scope.launch { s.toggleModelPin(com.tether.app.client.serverOrigin(serverUrl), id) } } },
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

    /** Let the composition settle ([SETTLE_MS]); no control has an arm delay (ta-coik.9, ta-coik.13). */
    fun arm() = settle(SETTLE_MS)

    /** A tap, then a few frames: a sheet or menu window needs them to open. */
    fun click(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        settle()
    }

    /**
     * ta-coik.9: a press that begins on [tag], then [change] lands (the control changes under the
     * finger), then the finger lifts.
     */
    fun pressAcross(tag: String, change: () -> Unit) {
        rule.onNodeWithTag(tag).performTouchInput { down(center) }
        settle(16)
        change()
        settle(16)
        rule.onNodeWithTag(tag).performTouchInput { up() }
        settle()
    }

    /** ta-coik.9: what the recorded taps put on the wire ([SessionControlsGuard.frame]). */
    fun frames(): List<JsonObject> = recorder.sent.map { SessionControlsGuard.frame(ComposerFixtures.SESSION_ID, it, "op-1").toJsonObject() }
}

/** ta-coik.9: the web's frame for a [type] with [body] (hooks/use-tether.ts:1693-1728). */
internal fun webFrame(type: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
    put("type", type)
    put("sessionId", ComposerFixtures.SESSION_ID)
    body()
}

/**
 * T7.2 behaviour on a phone (the web below 64rem): the one settings key in the toolbar opens the
 * session sheet; a pick sends exactly its value through [SessionControlActions.onControl]; every row
 * acts on the first tap (ta-coik.9, no arm delay); nothing received, restored or recomposed sends
 * anything; a locked session sends nothing.
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
    fun providerKeysActOnTheFirstTapForTheCatalogTheyShow() {
        // ta-coik.9: codex-controls.tsx:317-320, a plain button (disabled only while busy): no arm delay.
        h.session = SessionControlFixtures.codex
        h.controls = null
        h.codex = SessionControlFixtures.codexState
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Provider controls")
        h.click("codex-compact")
        assertEquals(listOf<SessionControl>(SessionControl.CodexCompaction("catalog-3")), h.recorder.sent)
        // The catalog is re-read: the next tap, at once, acts for the new snapshot.
        h.codex = SessionControlFixtures.codexStateNext
        h.settle(16)
        h.click("codex-compact")
        assertEquals(listOf<SessionControl>(SessionControl.CodexCompaction("catalog-3"), SessionControl.CodexCompaction("catalog-4")), h.recorder.sent)
    }

    @Test
    fun aPressBegunBeforeANewCatalogIsDropped() {
        // ta-coik.9's kept stale-tap guard: a press that began on the catalog-3 key never acts for catalog-4.
        h.session = SessionControlFixtures.codex
        h.controls = null
        h.codex = SessionControlFixtures.codexState
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Provider controls")
        h.pressAcross("codex-compact") { h.codex = SessionControlFixtures.codexStateNext }
        assertTrue("sent ${h.recorder.sent}", h.recorder.sent.isEmpty())
        h.click("codex-compact")
        assertEquals(listOf<SessionControl>(SessionControl.CodexCompaction("catalog-4")), h.recorder.sent)
    }

    @Test
    fun aServerRefusalIsSaidAndNothingElseOpens() {
        // ta-coik.7: a refused switch is said in words, as before; there is no confirmation to open.
        h.session = SessionControlFixtures.opencode
        h.controls = SessionControlFixtures.opencodeControls
        h.opencode = SessionControlFixtures.opencodeState
        h.recorder.resultFor = { c -> if (c is SessionControl.OpencodeMode) ControlResult.NotOffered else ControlResult.Sent }
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Provider controls")
        h.click("opencode-apply-mode")
        assertEquals(listOf<SessionControl>(SessionControl.OpencodeMode("default", "oc-1")), h.recorder.sent)
        rule.onNodeWithText("That option is no longer offered — the setting was not changed.").assertExists()
        rule.onAllNodesWithTag("escalation-confirm").assertCountEquals(0)
        rule.onAllNodesWithText("Turn on", substring = true).assertCountEquals(0)
    }

    @Test
    fun aPermissiveAgentCalledPlanIsSentFromThePanelOnTheFirstTap() {
        // ta-coik.7: opencode-serve-controls.tsx:132-139 applies any agent on the tap.
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
        h.click("opencode-apply-mode")
        assertEquals(listOf<SessionControl>(SessionControl.OpencodeMode("planx", "oc-1")), h.recorder.sent)
        rule.onAllNodesWithTag("escalation-confirm").assertCountEquals(0)
        rule.onAllNodesWithText("Turn on", substring = true).assertCountEquals(0)
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
        h.click("control-option-on")
        assertEquals(listOf(SessionControl.Effort("low"), SessionControl.FastMode(true)), h.recorder.sent)
        // ta-coik.9: the frames are the web's (hooks/use-tether.ts:1716-1717).
        assertEquals(webFrame("set-fast-mode") { put("enabled", true) }, h.frames().last())
    }

    @Test
    fun modeRowsActOnTheFirstTap() {
        // ta-coik.9: the web's Mode select has no arm delay (chat-view.tsx:4352-4367, chooseMode :2501-2503).
        h.show()
        h.click("session-settings-trigger")
        rule.onNodeWithTag("sheet-row-Mode").performClick()
        h.settle()
        rule.onNodeWithTag("control-option-plan").assertIsEnabled().performClick()
        h.settle(0)
        assertEquals(listOf<SessionControl>(SessionControl.Mode("plan")), h.recorder.sent)
        assertEquals(listOf(webFrame("set-mode") { put("permissionMode", "plan") }), h.frames())
    }

    @Test
    fun aPressOnAModeRowWhoseOptionChangedUnderTheFingerIsDropped() {
        // ta-coik.9's kept stale-tap guard: the row a press began on now holds another option.
        h.session = SessionControlFixtures.opencode
        h.controls = SessionControlFixtures.opencodeControls
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Mode")
        val swapped = SessionControlFixtures.opencodeControls.copy(modes = SessionControlFixtures.opencodeControls.modes!!.reversed())
        h.pressAcross("control-option-plan") { h.controls = swapped }
        assertTrue("sent ${h.recorder.sent}", h.recorder.sent.isEmpty())
        h.click("control-option-plan")
        assertEquals(listOf<SessionControl>(SessionControl.Mode("plan")), h.recorder.sent)
    }

    @Test
    fun autoIsSentOnTheFirstTap() {
        // ta-coik.7: chat-view.tsx:2500-2510, Auto is a Mode row like any other.
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Mode")
        h.click("control-option-${ModeVocabulary.AUTO}")
        assertEquals(listOf<SessionControl>(SessionControl.Mode(ModeVocabulary.AUTO)), h.recorder.sent)
        rule.onAllNodesWithTag("escalation-confirm").assertCountEquals(0)
        rule.onAllNodesWithText("Turn on", substring = true).assertCountEquals(0)
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
    fun aBareModelIsForwardedToTheCliAndOpensNoModelList() {
        // ta-9cp: chat-view.tsx 29537e0 :3081 (`&& arg`): only `/model <arg>` is native; a bare `/model`
        // is a read, forwarded as prompt text so the operator sees the CLI's own list of ids.
        h.show()
        rule.onNodeWithContentDescription("Message the agent").performTextInput("/model")
        h.settle()
        // Enter on the open slash menu completes the name (acceptCommand), leaving `/model `; the next Enter sends.
        rule.onNodeWithContentDescription("Message the agent").performImeAction()
        h.settle()
        rule.onNodeWithContentDescription("Message the agent").assertTextEquals("/model ")
        assertTrue(h.prompts.isEmpty())
        rule.onNodeWithContentDescription("Message the agent").performImeAction()
        h.settle()
        assertEquals(listOf("/model"), h.prompts)
        assertTrue("no control sent: ${h.recorder.sent}", h.recorder.sent.isEmpty())
        rule.onAllNodesWithTag("sheet-row-Model").assertCountEquals(0)
        rule.onAllNodesWithTag("control-option-claude-sonnet-5").assertCountEquals(0)
    }

    @Test
    fun pickingModelInTheSlashMenuFillsTheDraftAndOpensNoModelList() {
        // ta-9cp: chat-view.tsx 29537e0 :3126-3134 acceptCommand: `/model ` invites the id the picker may not carry.
        h.show()
        rule.onNodeWithContentDescription("Message the agent").performTextInput("/mod")
        h.settle()
        rule.onNodeWithContentDescription("/model [model], Switch the model for this session, Tether").performClick()
        h.settle()
        rule.onNodeWithContentDescription("Message the agent").assertTextEquals("/model ")
        assertTrue(h.prompts.isEmpty() && h.recorder.sent.isEmpty())
        rule.onAllNodesWithTag("sheet-row-Model").assertCountEquals(0)
        rule.onAllNodesWithTag("control-option-claude-sonnet-5").assertCountEquals(0)
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
        rule.onNodeWithTag("codex-compact").performClick()
        h.settle()
        assertEquals(listOf<SessionControl>(SessionControl.CodexCompaction("catalog-3")), h.recorder.sent)
    }

    @Test
    fun codexAutoApproveIsSentOnTheFirstTap() {
        // ta-coik.7: chat-view.tsx:2552-2555 -> set-approval-policy "never", no confirmation.
        h.session = SessionControlFixtures.codex
        h.controls = null
        h.codex = SessionControlFixtures.codexState
        h.show()
        h.click("session-settings-trigger")
        h.click("sheet-row-Auto approve")
        h.click("control-option-true")
        assertEquals(listOf<SessionControl>(SessionControl.CodexAutoApprove(true, "catalog-3")), h.recorder.sent)
        rule.onAllNodesWithTag("escalation-confirm").assertCountEquals(0)
        rule.onAllNodesWithText("Turn on", substring = true).assertCountEquals(0)
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
        // ta-coik.9: the Mode menu's rows act on the first tap, as the web's TetherSelect does.
        assertEquals(listOf(SessionControl.Model("claude-haiku-5"), SessionControl.Mode("acceptEdits")), h.recorder.sent)
        assertEquals(webFrame("set-mode") { put("permissionMode", "acceptEdits") }, h.frames().last())
    }

    @Test
    fun theModelMenuPinsAndUnpinsALegacyModelOnThisServerAndRegroupsInPlace() {
        // ta-coik.55: tether-select.tsx 90fbb9f :271-310 + chat-view.tsx :2281-2307 — the pin key on a
        // pinnable (Legacy models) or pinned row toggles the id in this server's pinnedModels; the menu
        // stays open and re-groups; nothing goes on the wire and the model is not chosen.
        val store = MemoryPrefsStore()
        h.prefs = com.tether.app.ui.prefs.UiPrefs.on(store)
        h.serverUrl = "https://a.example"
        h.show()
        h.click("control-model")
        rule.onNodeWithTag("control-pin-claude-opus-4-1")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Pin Opus 4.1")))
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Legacy models").assertExists()
        // Advertised rows carry no pin key.
        rule.onAllNodesWithTag("control-pin-claude-sonnet-5").assertCountEquals(0)
        h.click("control-pin-claude-opus-4-1")
        assertEquals(listOf("claude-opus-4-1"), pinsOf(h.prefs!!, "https://a.example"))
        assertTrue("pinned on B too: ${pinsOf(h.prefs!!, "https://b.example")}", pinsOf(h.prefs!!, "https://b.example").isEmpty())
        // Still open, promoted to the main list (the group had only this row, so it is gone), now an Unpin key.
        rule.onNodeWithContentDescription("Legacy models").assertDoesNotExist()
        rule.onNodeWithTag("control-pin-claude-opus-4-1")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Unpin Opus 4.1")))
        h.click("control-pin-claude-opus-4-1")
        assertTrue(pinsOf(h.prefs!!, "https://a.example").isEmpty())
        rule.onNodeWithContentDescription("Legacy models").assertExists()
        assertTrue("sent ${h.recorder.sent}", h.recorder.sent.isEmpty())
        // The row itself still chooses the model.
        h.click("control-option-claude-opus-4-1")
        assertEquals(listOf<SessionControl>(SessionControl.Model("claude-opus-4-1")), h.recorder.sent)
    }

    @Test
    fun aPinIsReadAndWrittenPerServer() {
        val store = MemoryPrefsStore()
        h.prefs = com.tether.app.ui.prefs.UiPrefs.on(store)
        runPrefsWrite { h.prefs!!.toggleModelPin(com.tether.app.client.serverOrigin("https://b.example"), "claude-opus-4-1") }
        h.serverUrl = "https://a.example"
        h.show()
        h.click("control-model")
        // B's pin is not A's: on A the row is still in the Legacy group, pinnable.
        rule.onNodeWithContentDescription("Legacy models").assertExists()
        rule.onNodeWithTag("control-pin-claude-opus-4-1")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Pin Opus 4.1")))
        h.serverUrl = "https://b.example"
        h.settle()
        rule.onNodeWithContentDescription("Legacy models").assertDoesNotExist()
        h.click("control-pin-claude-opus-4-1")
        assertTrue(pinsOf(h.prefs!!, "https://b.example").isEmpty())
        assertTrue(pinsOf(h.prefs!!, "https://a.example").isEmpty())
    }

    private fun pinsOf(prefs: com.tether.app.ui.prefs.UiPrefs, url: String): List<String> =
        kotlinx.coroutines.runBlocking { prefs.preferencesFor(flowOf(url)).first().pinnedModels }

    @Test
    fun theOpencodeAutoToggleTurnsOnAndOffOnTheTap() {
        // ta-coik.7: chat-view.tsx:2550-2561 toggleAuto, no confirmation either way.
        h.session = SessionControlFixtures.opencode
        h.controls = SessionControlFixtures.opencodeControls
        h.show()
        // ta-coik.9: the first tap toggles (chat-view.tsx:4384-4387), no arm delay.
        h.click("control-auto")
        assertEquals(listOf<SessionControl>(SessionControl.Mode(ModeVocabulary.AUTO)), h.recorder.sent)
        rule.onAllNodesWithTag("escalation-confirm").assertCountEquals(0)
        rule.onAllNodesWithText("Turn on", substring = true).assertCountEquals(0)
        h.session = SessionControlFixtures.opencode.copy(approvalPolicy = "never")
        h.settle()
        rule.onNodeWithText("Auto-approves permission requests that are not explicitly denied").assertExists()
        h.click("control-auto")
        assertEquals(SessionControl.Mode("default"), h.recorder.sent.last())
    }

    @Test
    fun aPressOnTheAutoChipThatFlippedUnderTheFingerIsDropped() {
        // ta-coik.9's kept stale-tap guard: another device turned Auto on while the finger was down.
        h.session = SessionControlFixtures.opencode
        h.controls = SessionControlFixtures.opencodeControls
        h.show()
        h.pressAcross("control-auto") { h.session = SessionControlFixtures.opencode.copy(approvalPolicy = "never") }
        assertTrue("sent ${h.recorder.sent}", h.recorder.sent.isEmpty())
        h.click("control-auto")
        assertEquals(listOf<SessionControl>(SessionControl.Mode("default")), h.recorder.sent)
    }

    @Test
    fun theAutoContinueKeyActsOnTheFirstTapWithTheWebsFrame() {
        // ta-coik.9: chat-view.tsx:4401-4405 -> set-auto-continue-on-limit (hooks/use-tether.ts:1726-1727).
        h.show()
        h.click("control-auto-continue")
        assertEquals(listOf(webFrame("set-auto-continue-on-limit") { put("enabled", true) }), h.frames())
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
    fun aPermissiveAgentCalledPlanIsSentFromTheRowOnTheFirstTap() {
        h.session = SessionControlFixtures.opencode
        h.controls = SessionControlFixtures.sneakyOpencodeControls
        h.show()
        h.click("control-mode")
        rule.onNodeWithTag("control-option-planx").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Plan (planx), Plans only (really: everything)")))
        h.click("control-option-planx")
        assertEquals(listOf<SessionControl>(SessionControl.Mode("planx")), h.recorder.sent)
        rule.onAllNodesWithTag("escalation-confirm").assertCountEquals(0)
        rule.onAllNodesWithText("Turn on", substring = true).assertCountEquals(0)
    }

    @Test
    fun fastModeIsReachableFromTheWideRow() {
        h.show()
        h.click("control-fast")
        assertTrue("opening is a read", h.recorder.sent.isEmpty())
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

/** ta-coik.55: an in-memory preferences store (the per-server pin writes land here). */
internal class MemoryPrefsStore : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
    private val disk = kotlinx.coroutines.flow.MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
    override val data: kotlinx.coroutines.flow.Flow<androidx.datastore.preferences.core.Preferences> = disk

    override suspend fun updateData(
        transform: suspend (t: androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences,
    ): androidx.datastore.preferences.core.Preferences = transform(disk.value).also { disk.value = it }
}
