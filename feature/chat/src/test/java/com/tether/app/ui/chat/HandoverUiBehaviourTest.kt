package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import com.tether.app.client.Handover
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #273 (A1 §3-5): the Hand over confirmation, the one HandoffNotice (pending / to / from), the read-only handover-request
 * queue row and the attribution labels. The menu row and the full send flow are in the shell module's tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class HandoverUiBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<String>()
    private val removed = mutableListOf<String>()
    private val edited = mutableListOf<String>()

    // ---- the confirmation ----

    @Test
    fun theConfirmationSaysTheOwnersWordsCancelIsFocusedAndHandOverActsOnce() {
        var confirms = 0
        var cancels = 0
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                androidx.compose.runtime.CompositionLocalProvider(LocalReducedMotion provides true) {
                    HandOverDialog(identity = "s1", eligible = true, endable = true, onConfirm = { confirms++ }, onCancel = { cancels++ })
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Hand this session over to a new lead?").assertExists()
        rule.onNodeWithText(
            "Tether will ask the current lead to write a handover prompt and stop. A new session with the same settings and permissions " +
                "will take over the run from that prompt. The current session becomes read-only.",
        ).assertExists()
        rule.onNodeWithTag(HANDOVER_CANCEL_TAG).assertIsFocused()
        rule.onNodeWithTag(HANDOVER_CONFIRM_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, confirms)
        assertEquals(0, cancels)
    }

    @Test
    fun cancelIsFocusedWhenAKeyboardUserOpensTheDialog() {
        rule.setContent {
            val input = androidx.compose.ui.platform.LocalInputModeManager.current
            androidx.compose.runtime.SideEffect { input.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                HandOverDialog(identity = "s1", eligible = true, endable = true, onConfirm = {}, onCancel = {})
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(HANDOVER_CANCEL_TAG).assertIsFocused()
    }

    @Test
    fun cancelIsFocusedWhenATapOpensTheDialogAndEnterOnItSendsNothing() {
        var confirms = 0
        var cancels = 0
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                HandOverDialog(identity = "s1", eligible = true, endable = true, onConfirm = { confirms++ }, onCancel = { cancels++ })
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(HANDOVER_CANCEL_TAG).assertIsFocused()
        rule.onNodeWithTag(HANDOVER_CANCEL_TAG).performKeyInput { keyDown(androidx.compose.ui.input.key.Key.Enter); keyUp(androidx.compose.ui.input.key.Key.Enter) }
        rule.waitForIdle()
        assertEquals(0, confirms)
        assertEquals(1, cancels)
    }

    @Test
    fun cancelCancelsAndSendsNothing() {
        var confirms = 0
        var cancels = 0
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                HandOverDialog(identity = "s1", eligible = true, endable = true, onConfirm = { confirms++ }, onCancel = { cancels++ })
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(HANDOVER_CANCEL_TAG).performClick()
        rule.waitForIdle()
        assertEquals(0, confirms)
        assertEquals(1, cancels)
    }

    @Test
    fun anIneligibleSessionClosesTheDialogThroughCancelAndNeverConfirms() {
        var confirms = 0
        var cancels = 0
        var eligible by mutableStateOf(true)
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                HandOverDialog(identity = "s1", eligible = eligible, endable = true, onConfirm = { confirms++ }, onCancel = { cancels++ })
            }
        }
        rule.waitForIdle()
        assertEquals(0, cancels)
        rule.runOnIdle { eligible = false }
        rule.waitForIdle()
        assertEquals(1, cancels)
        assertEquals(0, confirms)
    }

    // ---- the one notice ----

    private fun composer(
        session: AgentSession,
        target: AgentSession? = null,
        folded: ChatFixtures.Folded = ComposerFixtures.idle,
        underWay: Boolean = false,
    ): @androidx.compose.runtime.Composable () -> Unit = {
        ComposerHost(TetherSkin.Studio) {
            Composer(
                session = session,
                projection = folded.projection,
                controls = null,
                serverNow = { ComposerFixtures.BUSY_NOW },
                onSend = { _, _ -> true },
                onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                onQueueEdit = { _, text -> edited += text },
                onQueueRemove = { removed += it },
                onRequestControls = {},
                liveness = ComposerLiveness.Live,
                handoffTarget = target,
                onOpenSession = { opened += it },
                handoverUnderWay = underWay,
            )
        }
    }

    @Test
    fun theUnderWayNoticeSitsAboveAComposerThatStaysUsable() {
        rule.setContent(composer(ComposerFixtures.session, underWay = true))
        rule.waitForIdle()
        rule.onNodeWithTag("handoff-notice-pending").assertExists()
        rule.onNodeWithText("Handover under way — a new lead takes over when this lead finishes.").assertExists()
        rule.onNodeWithText("Stop cancels it · new messages go to the new lead.").assertExists()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
    }

    @Test
    fun noUnderWayNoticeOnceHandedOff() {
        val target = ComposerFixtures.session.copy(id = "sess-0002", name = "Parser rewrite")
        rule.setContent(composer(ComposerFixtures.session.copy(handedOffTo = target.id), target, underWay = true))
        rule.waitForIdle()
        rule.onAllNodesWithTag("handoff-notice-pending").assertCountEquals(0)
        rule.onNodeWithTag("handoff-notice-to").assertExists()
    }

    @Test
    fun theToLinkOpensTheSuccessorAndAnUnlistedOneIsWordsOnly() {
        val target = ComposerFixtures.session.copy(id = "sess-0002", name = "Parser rewrite")
        rule.setContent(composer(ComposerFixtures.session.copy(handedOffTo = target.id), target))
        rule.waitForIdle()
        rule.onNodeWithTag("handoff-link").performClick()
        assertEquals(listOf("sess-0002"), opened)
    }

    @Test
    fun anUnlistedSuccessorReadsAnotherSessionWithNoLink() {
        rule.setContent(composer(ComposerFixtures.session.copy(handedOffTo = "gone"), null))
        rule.waitForIdle()
        rule.onNodeWithText("Handed off to another session").assertExists()
        rule.onAllNodesWithTag("handoff-link").assertCountEquals(0)
    }

    @Test
    fun focusInTheComposerMovesToTheLinkWhenTheComposerIsReplaced() {
        val target = ComposerFixtures.session.copy(id = "sess-0002", name = "Parser rewrite")
        var session by mutableStateOf(ComposerFixtures.session)
        var peer by mutableStateOf<AgentSession?>(null)
        var input: androidx.compose.ui.input.InputModeManager? = null
        rule.setContent {
            input = androidx.compose.ui.platform.LocalInputModeManager.current
            ComposerHost(TetherSkin.Studio) {
                Composer(
                    session = session,
                    projection = ComposerFixtures.idle.projection,
                    controls = null,
                    serverNow = { ComposerFixtures.BUSY_NOW },
                    onSend = { _, _ -> true },
                    onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = {},
                    onRequestControls = {},
                    liveness = ComposerLiveness.Live,
                    handoffTarget = peer,
                    onOpenSession = { opened += it },
                )
            }
        }
        rule.waitForIdle()
        // A keyboard user (touch mode takes no focus onto a link, as a View does).
        rule.runOnUiThread { input!!.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
        rule.onNode(hasSetTextAction()).requestFocus()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).assertIsFocused()
        rule.runOnIdle { peer = target; session = session.copy(handedOffTo = target.id) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("handoff-link").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.onNodeWithTag("handoff-link").assertIsFocused()
    }

    @Test
    fun focusElsewhereIsLeftAloneWhenTheComposerIsReplaced() {
        val target = ComposerFixtures.session.copy(id = "sess-0002", name = "Parser rewrite")
        var session by mutableStateOf(ComposerFixtures.session)
        rule.setContent {
            ComposerHost(TetherSkin.Studio) {
                Composer(
                    session = session,
                    projection = ComposerFixtures.idle.projection,
                    controls = null,
                    serverNow = { ComposerFixtures.BUSY_NOW },
                    onSend = { _, _ -> true },
                    onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = {},
                    onRequestControls = {},
                    liveness = ComposerLiveness.Live,
                    handoffTarget = target,
                    onOpenSession = { opened += it },
                )
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { session = session.copy(handedOffTo = target.id) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("handoff-link").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        val focused = rule.onNodeWithTag("handoff-link").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused)
        assertTrue("no focus was in the composer, so none is taken: $focused", focused != true)
    }

    // ---- the queue row ----

    @Test
    fun theHandoverRequestRowIsReadOnlyHasNoEditNoInterruptAndRemoveCancels() {
        rule.setContent(composer(ComposerFixtures.session, folded = HandoverUiFixtures.busyWithRequest))
        rule.waitForIdle()
        rule.onNodeWithTag(HANDOVER_QUEUE_ROW_TAG).assertExists()
        rule.onNodeWithText(HANDOVER_QUEUE_WAIT_COPY).assertExists()
        rule.onNodeWithContentDescription(HANDOVER_QUEUE_ROW_DESCRIPTION).assertExists()
        // Only the operator's own row (one) offers Edit; the instruction row offers none and no Interrupt now.
        rule.onAllNodesWithContentDescription("Edit queued message").assertCountEquals(1)
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG).assertCountEquals(0)
        rule.onNodeWithContentDescription(HANDOVER_QUEUE_REMOVE_DESCRIPTION).performClick()
        rule.waitForIdle()
        assertEquals(listOf("handover-q"), removed)
        assertTrue(edited.isEmpty())
    }

    // ---- the transcript: the from strip and the attribution ----

    private fun transcript(folded: ChatFixtures.Folded, from: String?, peer: AgentSession?, richCodex: Boolean = false) {
        rule.setContent {
            ChatHost(TetherSkin.Studio) {
                ChatTranscript(
                    projection = folded.projection,
                    tree = folded.tree,
                    showThinking = false,
                    onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone,
                    listState = LazyListState(),
                    showTimeline = false,
                    handedOffFrom = from,
                    handoffPeer = peer,
                    onOpenSession = { opened += it },
                    richCodex = richCodex,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun theSuccessorsFirstItemIsContinuedFromWithALinkAndItsPromptRowIsLabelled() {
        val fixture = HandoverUiFixtures.successorTranscript
        transcript(fixture, "src-1", HandoverUiFixtures.source)
        rule.onNodeWithTag("handoff-notice-from").assertExists()
        rule.onNodeWithText("Continued from ").assertExists()
        rule.onNodeWithTag("handoff-link").performClick()
        assertEquals(listOf("src-1"), opened)
        // TalkBack reads the label, never "You".
        rule.onNodeWithContentDescription("Handover from Alpha Lead").assertExists()
        rule.onNodeWithTag("handover-attribution", useUnmergedTree = true).assertExists()
    }

    @Test
    fun anUnlistedSourceReadsAnEarlierSessionWithNoLink() {
        transcript(HandoverUiFixtures.successorTranscript, "src-1", null)
        rule.onNodeWithText("Continued from an earlier session").assertExists()
        rule.onAllNodesWithTag("handoff-link").assertCountEquals(0)
    }

    @Test
    fun aSessionThatContinuedNothingDrawsNoStripAndNoLabel() {
        transcript(ChatFixtures.idle, null, null)
        rule.onAllNodesWithTag("handoff-notice-from").assertCountEquals(0)
        rule.onAllNodesWithTag("handover-attribution", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun theInstructionRowFromTetherIsLabelled() {
        val fixture = HandoverUiFixtures.instructionTranscript(ChatFixtures.idle.projection.tetherSessionId)
        transcript(fixture, null, null)
        rule.onNodeWithContentDescription("Handover instruction · from Tether").assertExists()
    }

    @Test
    fun theCancelledNoticeReadsAsTetherWithNoCodeAndNoRawWords() {
        transcript(HandoverUiFixtures.cancelledNotice, null, null, richCodex = true)
        rule.onNodeWithText("The handover did not complete. Nothing more will be sent automatically.").assertExists()
        rule.onAllNodesWithText("raw server words that are never shown").assertCountEquals(0)
        rule.onAllNodesWithText("handover_cancelled", substring = true).assertCountEquals(0)
    }
}
