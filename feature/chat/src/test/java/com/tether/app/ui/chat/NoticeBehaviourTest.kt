package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import com.tether.app.client.ControlResult
import com.tether.app.client.NoticeResult
import com.tether.app.client.SessionControl
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T6.6 behaviour: a notice's X sends exactly its dismiss key once per link, and only on a tap; the
 * limit card's keys send exactly their action for THIS prompt's `resetsAt`, armed, once; the
 * scheduled row's X sends `dismiss`; nothing is sent by composition or by state that moves; a lock
 * says why and sends nothing. The composer of a handed-off or read-only session takes no input.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class NoticeBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val rec = NoticeFixtures.Recorder()
    private var fixture by mutableStateOf(NoticeFixtures.sessionNotices)
    private var actions by mutableStateOf(rec.actions())
    private var rich by mutableStateOf(false)

    private fun show(f: ChatFixtures.Folded, richCodex: Boolean = false) {
        fixture = f
        rich = richCodex
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ChatHost(TetherSkin.Machine) {
                ChatTranscript(
                    projection = fixture.projection,
                    tree = fixture.tree,
                    showThinking = false,
                    onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone,
                    listState = LazyListState(),
                    richCodex = rich,
                    notices = actions,
                    showTimeline = false,
                )
            }
        }
        settle()
    }

    private fun settle(ms: Long = NAV_SETTLE_MS) {
        rule.mainClock.advanceTimeBy(16)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun arm() = settle(CONSENT_ARM_DELAY_MS + 100)

    @Test
    fun aTapOnTheXSendsExactlyItsKeyOnce() {
        show(NoticeFixtures.sessionNotices)
        rule.onAllNodesWithTag("notice-dismiss").assertCountEquals(3)
        rule.onNodeWithContentDescription("Dismiss external-advancement notice").performClick()
        settle()
        rule.onNodeWithContentDescription("Dismiss external-advancement notice").performClick()
        settle()
        assertEquals(listOf("ext-1"), rec.dismissed)
        rule.onNodeWithContentDescription("Dismiss external-advancement notice")
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Dismissing…"))
    }

    @Test
    fun aRefusedDismissalReleasesTheXAndSaysWhy() {
        rec.dismissResult = NoticeResult.NotLive
        show(NoticeFixtures.sessionNotices)
        rule.onNodeWithContentDescription("Dismiss external-advancement notice").performClick()
        settle()
        rule.onNodeWithContentDescription("Dismiss external-advancement notice").assertIsEnabled()
        assertEquals(1, rec.refusals.size)
        assertTrue(rec.refusals.single().contains("not dismissed"))
    }

    @Test
    fun nothingIsSentByCompositionOrByNoticesThatMove() {
        show(NoticeFixtures.sessionNotices)
        arm()
        fixture = NoticeFixtures.limit
        settle()
        fixture = NoticeFixtures.scheduled
        arm()
        fixture = NoticeFixtures.claudeFallback
        arm()
        actions = rec.actions(link = "link-2")
        arm()
        assertTrue("no tap, no frame: ${rec.dismissed} ${rec.controls}", rec.dismissed.isEmpty() && rec.controls.isEmpty())
    }

    @Test
    fun anOfflineOrCatchingUpXSaysWhyAndSendsNothing() {
        actions = rec.actions(lock = NoticeLock.CatchingUp)
        show(NoticeFixtures.sessionNotices)
        rule.onAllNodesWithTag("notice-dismiss")[0]
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, NoticeLock.CatchingUp.copy))
            .performClick()
        settle()
        assertTrue(rec.dismissed.isEmpty())
    }

    @Test
    fun aReadOnlySessionMayStillDismiss() {
        // The server allows dismiss-notice read-only; only the limit keys take the control lock.
        actions = rec.actions(controlLock = ConsentLock.ReadOnly)
        show(NoticeFixtures.claudeFallback)
        rule.onNodeWithContentDescription("Dismiss notice").performClick()
        settle()
        assertEquals(1, rec.dismissed.size)
        assertTrue(rec.dismissed.single().startsWith("provider_notice:t1:"))
    }

    @Test
    fun theCodexTurnsCompactionAndNoticesAreEachDismissable() {
        show(NoticeFixtures.codexNotices, richCodex = true)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("compaction-notice"))
        rule.onNodeWithContentDescription("Dismiss context-compacted notice").performClick()
        settle()
        assertEquals(listOf("context_compacted:t1:cmp-1"), rec.dismissed)
    }

    @Test
    fun theLimitCardIsArmedAndSendsExactlyItsChoiceOnce() {
        show(NoticeFixtures.limit)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-card"))
        // Just appeared: not yet armed.
        rule.onNodeWithTag("rate-limit-schedule").assertIsNotEnabled().performClick()
        settle()
        assertTrue(rec.controls.isEmpty())
        arm()
        rule.onNodeWithTag("rate-limit-schedule").assertIsEnabled().performClick()
        settle()
        rule.onNodeWithTag("rate-limit-resume-now").performClick()
        settle()
        assertEquals(listOf(SessionControl.RateLimitResume(NoticeFixtures.RESETS_AT, "schedule")), rec.controls)
        rule.onNodeWithTag("rate-limit-status").assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("Choice sent. Waiting for the server."))))
        rule.onNodeWithTag("rate-limit-dismiss").assertIsNotEnabled()
    }

    @Test
    fun aRefusedLimitChoiceReArmsTheKeysAndSaysWhy() {
        rec.controlResult = ControlResult.NotOffered
        show(NoticeFixtures.limit)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-card"))
        arm()
        rule.onNodeWithTag("rate-limit-dismiss").performClick()
        arm()
        assertEquals(listOf(SessionControl.RateLimitResume(NoticeFixtures.RESETS_AT, "dismiss")), rec.controls)
        assertEquals(listOf("That limit prompt is no longer active — nothing was sent."), rec.refusals)
        rule.onNodeWithTag("rate-limit-dismiss").assertIsEnabled()
    }

    @Test
    fun aLockedLimitCardSaysWhyAndSendsNothing() {
        actions = rec.actions(controlLock = ConsentLock.HandedOff)
        show(NoticeFixtures.limit)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-card"))
        arm()
        rule.onNodeWithTag("rate-limit-resume-now").assertIsNotEnabled().performClick()
        settle()
        rule.onNodeWithTag("rate-limit-status").assertExists()
        assertTrue(rec.controls.isEmpty())
    }

    @Test
    fun theScheduledRowsXCancelsThatResume() {
        show(NoticeFixtures.scheduled)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-scheduled"))
        rule.onNodeWithText("Automatic resume scheduled for 2:03 AM UTC.").assertExists()
        rule.onNodeWithContentDescription("Cancel scheduled resume").performClick()
        settle()
        rule.onNodeWithContentDescription("Cancel scheduled resume").performClick()
        settle()
        assertEquals(listOf(SessionControl.RateLimitResume(NoticeFixtures.RESETS_AT, "dismiss")), rec.controls)
    }

    @Test
    fun anInterruptedTurnSaysWhoInterruptedIt() {
        show(NoticeFixtures.interrupted)
        rule.onNodeWithText("Interrupted at 01:01:42 by your message · 1 tool stopped · 2 background tasks killed").assertExists()
    }
}

/** T6.6: the composer of a session Tether cannot drive, and the Auto-continue toggle. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ComposerLockBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val target = ComposerFixtures.session.copy(id = "sess-0002", name = "Parser rewrite")
    private val opened = mutableListOf<String>()
    private val prompts = mutableListOf<String>()

    private fun show(session: AgentSession, handoffTarget: AgentSession?) {
        rule.setContent {
            ComposerHost(TetherSkin.Machine) {
                Composer(
                    session = session,
                    projection = ComposerFixtures.idle.projection,
                    controls = null,
                    serverNow = { ComposerFixtures.BUSY_NOW },
                    onSend = { text, _ -> prompts += text; true },
                    onInterrupt = {},
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = {},
                    onRequestControls = {},
                    handoffTarget = handoffTarget,
                    onOpenSession = { opened += it },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun aHandedOffSourceLinksToItsTargetAndTakesNoInput() {
        show(ComposerFixtures.session.copy(handedOffTo = target.id), target)
        rule.onNodeWithTag("composer-handoff-lock").assertExists()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithText("Parser rewrite").performClick()
        assertEquals(listOf(target.id), opened)
        assertTrue(prompts.isEmpty())
    }

    @Test
    fun aHandedOffSourceWhoseTargetIsGoneSaysAnotherSession() {
        show(ComposerFixtures.session.copy(handedOffTo = "gone"), null)
        rule.onNodeWithText("another session").assertExists()
        rule.onNodeWithTag("handoff-link").assertDoesNotExist()
    }

    @Test
    fun aReadOnlySessionSaysWhyAndTakesNoInput() {
        show(ComposerFixtures.session.copy(readOnly = true, provider = "codex"), null)
        rule.onNodeWithTag("composer-read-only").assertExists()
        rule.onNodeWithText("Replay only — configure the codex engine to continue this conversation.").assertExists()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }
}

/** T6.6 / ta-6u4: the Auto-continue toggle on the phone sheet (the web's session-settings-sheet). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class AutoContinueBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val h = ControlsHost(rule)

    private fun openAutoContinueSheet() {
        h.click("session-settings-trigger")
        rule.onNodeWithText("Auto-continue", useUnmergedTree = true).performClick()
        h.settle()
    }

    @Test
    fun turningItOnOnlyAsksAndTheArmedConfirmationSendsTheGrant() {
        h.show()
        h.arm()
        openAutoContinueSheet()
        h.arm()
        rule.onNodeWithText("On", useUnmergedTree = true).performClick()
        h.settle()
        assertTrue("choosing On only asks", h.recorder.sent.isEmpty())
        rule.onNodeWithText("Turn on \u2068Auto-continue\u2069?").assertExists()
        rule.onNodeWithText(AUTO_CONTINUE_CONFIRM_BODY).assertExists()
        // The confirm key is armed too.
        h.click("escalation-confirm")
        assertTrue(h.recorder.sent.isEmpty())
        h.arm()
        h.click("escalation-confirm")
        assertEquals(listOf<SessionControl>(SessionControl.AutoContinueOnLimit(true, confirmed = true)), h.recorder.sent)
    }

    @Test
    fun theConfirmationIsBoundToTheToggleItWasOpenedFor() {
        h.show()
        h.arm()
        openAutoContinueSheet()
        h.arm()
        rule.onNodeWithText("On", useUnmergedTree = true).performClick()
        h.settle()
        // Another device turned it on under the dialog: confirming sends nothing, and it closes.
        h.session = SessionControlFixtures.claude.copy(autoContinueOnLimit = true)
        h.arm()
        h.click("escalation-confirm")
        assertTrue(h.recorder.sent.isEmpty())
        rule.onNodeWithText("Turn on \u2068Auto-continue\u2069?").assertDoesNotExist()
    }

    @Test
    fun theSheetRowsAreArmed() {
        h.session = SessionControlFixtures.claude.copy(autoContinueOnLimit = true)
        h.show()
        h.arm()
        openAutoContinueSheet()
        // A tap aimed at what was there before the list appeared lands on nothing.
        rule.onNodeWithTag("control-option-false").assertIsNotEnabled().performClick()
        h.settle(0)
        assertTrue(h.recorder.sent.isEmpty())
    }

    @Test
    fun turningItOffWithdrawsTheGrantWithoutAsking() {
        h.session = SessionControlFixtures.claude.copy(autoContinueOnLimit = true)
        h.show()
        h.arm()
        openAutoContinueSheet()
        h.arm()
        rule.onNodeWithText("Off", useUnmergedTree = true).performClick()
        h.settle()
        assertEquals(listOf<SessionControl>(SessionControl.AutoContinueOnLimit(false)), h.recorder.sent)
        rule.onNodeWithText("Turn on \u2068Auto-continue\u2069?").assertDoesNotExist()
    }

    @Test
    fun pickingTheCurrentValueSendsNothing() {
        h.show()
        h.arm()
        h.click("session-settings-trigger")
        rule.onNodeWithText("Auto-continue", useUnmergedTree = true).performClick()
        h.settle()
        rule.onNodeWithText("Off", useUnmergedTree = true).performClick()
        h.settle()
        assertTrue(h.recorder.sent.isEmpty())
    }

    @Test
    fun anotherProviderHasNoToggle() {
        h.session = SessionControlFixtures.claude.copy(provider = "pi")
        h.show()
        h.arm()
        h.click("session-settings-trigger")
        rule.onNodeWithText("Auto-continue").assertDoesNotExist()
    }
}

/** T6.6: the Auto-continue key in the wide row (from 64rem): armed, and a grant that asks first. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class AutoContinueTabletBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val h = ControlsHost(rule)

    @Test
    fun theKeyIsArmedAndOnlyAsks() {
        h.show()
        h.click("control-auto-continue")
        assertTrue("the key is armed", h.recorder.sent.isEmpty())
        rule.onNodeWithText("Turn on \u2068Auto-continue\u2069?").assertDoesNotExist()
        h.arm()
        h.click("control-auto-continue")
        assertTrue(h.recorder.sent.isEmpty())
        rule.onNodeWithText("Turn on \u2068Auto-continue\u2069?").assertExists()
        h.arm()
        h.click("escalation-confirm")
        assertEquals(listOf<SessionControl>(SessionControl.AutoContinueOnLimit(true, confirmed = true)), h.recorder.sent)
        h.session = SessionControlFixtures.claude.copy(autoContinueOnLimit = true)
        h.arm()
        h.click("control-auto-continue")
        assertEquals(SessionControl.AutoContinueOnLimit(false), h.recorder.sent.last())
    }
}
