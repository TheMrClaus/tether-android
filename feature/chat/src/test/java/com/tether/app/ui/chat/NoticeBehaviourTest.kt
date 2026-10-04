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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
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
 * limit card's keys send exactly their action for THIS prompt's `resetsAt`, on the first tap (no arm
 * delay, ta-coik.13; a press across a change of prompt or session is dropped), once; the
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
            ChatHost(TetherSkin.StudioDark) {
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

    private fun arm() = settle(SETTLE_MS)

    @Test
    fun eachTapOnTheXSendsExactlyItsKeyOnce() {
        show(NoticeFixtures.sessionNotices)
        rule.onAllNodesWithTag("notice-dismiss").assertCountEquals(3)
        // ta-coik.13: notice-dismiss-button.tsx 90fbb9f :12-20, the X acts on the first click (no arm delay).
        rule.onNodeWithContentDescription("Dismiss external-advancement notice").assertIsEnabled().performClick()
        settle()
        assertEquals(listOf("ext-1"), rec.dismissed)
        // ta-coik.22: no "Dismissing…" latch, as on the web: the X stays live and a second tap sends again.
        rule.onNodeWithContentDescription("Dismiss external-advancement notice")
            .assertIsEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            .performClick()
        settle()
        assertEquals(listOf("ext-1", "ext-1"), rec.dismissed)
    }

    @Test
    fun aRefusedDismissalReleasesTheXAndSaysWhy() {
        rec.dismissResult = NoticeResult.NotLive
        show(NoticeFixtures.sessionNotices)
        arm()
        rule.onNodeWithContentDescription("Dismiss external-advancement notice").performClick()
        settle()
        rule.onNodeWithContentDescription("Dismiss external-advancement notice").assertIsEnabled()
        assertEquals(1, rec.refusals.size)
        // ta-coik.23 r2: NotLive is a control drawn for another server; the app's words for that.
        assertEquals(listOf("Nothing was sent: the app is now signed in to another server."), rec.refusals)
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

    /**
     * ta-coik.23: the web's X is never disabled (notice-dismiss-button.tsx 90fbb9f :12-20). A tap the
     * client could not send (the link is down) reaches it and adds no words of the card's own: the
     * client says the link is reconnecting, as the web's `send` does.
     */
    @Test
    fun theXIsNeverLockedAndALinkRefusalIsLeftToTheClientsWords() {
        rec.dismissResult = NoticeResult.NotConnected
        show(NoticeFixtures.sessionNotices)
        rule.onAllNodesWithTag("notice-dismiss")[0]
            .assertIsEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            .performClick()
        settle()
        assertEquals(1, rec.dismissed.size)
        assertTrue(rec.refusals.isEmpty())
    }

    @Test
    fun theCodexTurnsCompactionAndNoticesAreEachDismissable() {
        show(NoticeFixtures.codexNotices, richCodex = true)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("compaction-notice"))
        arm()
        rule.onNodeWithContentDescription("Dismiss context-compacted notice").performClick()
        settle()
        assertEquals(listOf("context_compacted:t1:cmp-1"), rec.dismissed)
    }

    @Test
    fun theLimitCardActsOnTheFirstTapAndSendsExactlyItsChoiceOnce() {
        show(NoticeFixtures.limit)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-card"))
        // ta-coik.13: chat-view.tsx 90fbb9f :1384-1394, the web's keys act on the first click.
        rule.onNodeWithTag("rate-limit-schedule").assertIsEnabled().performClick()
        settle()
        rule.onNodeWithTag("rate-limit-resume-now").performClick()
        settle()
        assertEquals(listOf(SessionControl.RateLimitResume(NoticeFixtures.RESETS_AT, "schedule")), rec.controls)
        rule.onNodeWithTag("rate-limit-status").assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("Choice sent. Waiting for the server."))))
        rule.onNodeWithTag("rate-limit-dismiss").assertIsNotEnabled()
    }

    /**
     * ta-coik.24: taps that land in ONE frame (no recomposition between them, so every key is still
     * drawn enabled) send one choice, as the web's `if (!submitted && …)` guard (chat-view.tsx 90fbb9f
     * :1371) does: RateLimitCard.choose's own `sent != null` check, not the keys' enabled state.
     */
    @Test
    fun tapsInOneFrameSendExactlyOneChoice() {
        show(NoticeFixtures.limit)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-card"))
        settle()
        // Clicks delivered as semantics actions with the clock held: no frame, so no recomposition,
        // between them (a touch injection would advance the clock and redraw the keys disabled).
        rule.onNodeWithTag("rate-limit-schedule").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("rate-limit-schedule").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("rate-limit-resume-now").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        assertEquals(listOf(SessionControl.RateLimitResume(NoticeFixtures.RESETS_AT, "schedule")), rec.controls)
    }

    /**
     * ta-coik.22: as on the web (chat-view.tsx 90fbb9f :1361-1371), a sent choice rests the card's keys
     * for 4 s while the server's event is awaited, then they re-enable, so a choice a half-open link
     * swallowed can be made again. Each tap is one frame.
     */
    @Test
    fun theLimitCardsKeysReEnableAfterFourSecondsAsOnTheWeb() {
        show(NoticeFixtures.limit)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-card"))
        rule.onNodeWithTag("rate-limit-resume-now").assertIsEnabled().performClick()
        settle()
        assertEquals(listOf(SessionControl.RateLimitResume(NoticeFixtures.RESETS_AT, "resume-now")), rec.controls)
        rule.onNodeWithTag("rate-limit-dismiss").assertIsNotEnabled()
        // ta-coik.23: the web's literal 4_000 ms (chat-view.tsx 90fbb9f :1367), pinned here, never derived.
        assertEquals(4_000L, RATE_LIMIT_RETRY_MS)
        // Just short of 4 s after the tap: still resting.
        rule.mainClock.advanceTimeBy(4_000L - NAV_SETTLE_MS - 300)
        rule.waitForIdle()
        rule.onNodeWithTag("rate-limit-schedule").assertIsNotEnabled()
        rule.onNodeWithTag("rate-limit-dismiss").assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertEquals(1, rec.controls.size)
        // Past 4 s: live again, the "sent" line gone, and the next tap sends.
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithTag("rate-limit-status").assertDoesNotExist()
        rule.onNodeWithTag("rate-limit-schedule").assertIsEnabled()
        rule.onNodeWithTag("rate-limit-dismiss").assertIsEnabled().performClick()
        settle()
        assertEquals(
            listOf(SessionControl.RateLimitResume(NoticeFixtures.RESETS_AT, "resume-now"), SessionControl.RateLimitResume(NoticeFixtures.RESETS_AT, "dismiss")),
            rec.controls,
        )
    }

    @Test
    fun aRefusedLimitChoiceReleasesTheKeysAndSaysWhy() {
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

    /**
     * ta-coik.23 r2: the client's NotLive now means only "drawn for another server"; the X, the
     * card and the cancel say that in the app's words for it, never "Catching up".
     */
    @Test
    fun aControlDrawnForAnotherServerSaysSoInTheAppsWords() {
        rec.dismissResult = NoticeResult.NotLive
        rec.controlResult = ControlResult.NotLive
        show(NoticeFixtures.limit)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-card"))
        rule.onNodeWithTag("rate-limit-schedule").performClick()
        settle()
        assertEquals(listOf("Nothing was sent: the app is now signed in to another server."), rec.refusals)
        rule.onNodeWithTag("rate-limit-schedule").assertIsEnabled()
        assertEquals("Nothing was sent: the app is now signed in to another server.", noticeRefusalCopy(NoticeResult.NotLive))
        assertEquals("Nothing was sent: the app is now signed in to another server.", rateLimitRefusalCopy(ControlResult.NotLive))
    }

    @Test
    fun theScheduledResumeCancelIsNeverLockedAndEachTapSends() {
        show(NoticeFixtures.scheduled)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-scheduled"))
        rule.onNodeWithContentDescription("Cancel scheduled resume")
            .assertIsEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            .performClick()
        settle()
        assertEquals(listOf(SessionControl.RateLimitResume(NoticeFixtures.RESETS_AT, "dismiss")), rec.controls)
    }

    /** [f] as another session's (same dismiss keys, same resetsAt). */
    private fun asSession(f: ChatFixtures.Folded, id: String) = f.copy(projection = f.projection.copy(tetherSessionId = id))

    @Test
    fun aSessionSwitchNeverInheritsTheLimitCardOrTheX() {
        show(NoticeFixtures.limit)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-card"))
        arm()
        rule.onNodeWithTag("rate-limit-schedule").performClick()
        settle()
        rule.onNodeWithTag("rate-limit-status").assertExists()
        // Switch to B on the same link: same resetsAt, same status.
        fixture = asSession(NoticeFixtures.limit, "s2")
        actions = rec.actions(sessionId = "s2")
        settle()
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-card"))
        rule.onNodeWithTag("rate-limit-status").assertDoesNotExist()
        // Not latched: B's card is answerable at once (ta-coik.13: no arm delay).
        assertEquals(listOf(SessionControl.RateLimitResume(NoticeFixtures.RESETS_AT, "schedule")), rec.controls)
        rule.onNodeWithTag("rate-limit-dismiss").assertIsEnabled()

        // The notices' X likewise: A's latched X is not B's.
        fixture = NoticeFixtures.sessionNotices
        actions = rec.actions()
        arm()
        rule.onNodeWithContentDescription("Dismiss external-advancement notice").performClick()
        settle()
        assertEquals(listOf("ext-1"), rec.dismissed)
        fixture = asSession(NoticeFixtures.sessionNotices, "s2")
        actions = rec.actions(sessionId = "s2")
        settle()
        rule.onNodeWithContentDescription("Dismiss external-advancement notice")
            .assertIsEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun theScheduledRowsXCancelsThatResume() {
        show(NoticeFixtures.scheduled)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-scheduled"))
        rule.onNodeWithText("Automatic resume scheduled for 2:03 AM UTC.").assertExists()
        // ta-coik.13: the first tap cancels (no arm delay). ta-coik.22: no "Cancelling…" latch, as on
        // the web (chat-view.tsx 90fbb9f :3707-3714): the X stays live and each tap sends.
        rule.onNodeWithContentDescription("Cancel scheduled resume").assertIsEnabled().performClick()
        settle()
        assertEquals(listOf(SessionControl.RateLimitResume(NoticeFixtures.RESETS_AT, "dismiss")), rec.controls)
        rule.onNodeWithContentDescription("Cancel scheduled resume")
            .assertIsEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            .performClick()
        settle()
        assertEquals(List(2) { SessionControl.RateLimitResume(NoticeFixtures.RESETS_AT, "dismiss") }, rec.controls)
    }

    @Test
    fun theRowsOwnStateIsScopedToTheSessionEvenInOneSlot() {
        // Drawn outside the list (one composition slot, as a reused lazy slot would be): switching
        // the session behind the same resetsAt / dismiss key starts every control unsent.
        rule.mainClock.autoAdvance = false
        val limit = rateLimitPrompt(NoticeFixtures.limit.tree)!!
        val scheduled = rateLimitPrompt(NoticeFixtures.scheduled.tree)!!
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                androidx.compose.runtime.CompositionLocalProvider(LocalNoticeActions provides actions) {
                    // The card last: its "Choice sent" line would move anything below it.
                    androidx.compose.foundation.layout.Column {
                        NoticeDismissButton("ext-1", "Dismiss external-advancement notice")
                        ScheduledResumeRow(scheduled, zone = ChatFixtures.zone)
                        RateLimitCard(limit, zone = ChatFixtures.zone)
                    }
                }
            }
        }
        settle()
        arm()
        rule.onNodeWithContentDescription("Dismiss external-advancement notice").performClick()
        rule.onNodeWithContentDescription("Cancel scheduled resume").performClick()
        rule.onNodeWithTag("rate-limit-resume-now").performClick()
        settle()
        assertEquals(2, rec.controls.size)
        assertEquals(listOf("ext-1"), rec.dismissed)
        actions = rec.actions(sessionId = "s2")
        settle()
        rule.onNodeWithTag("rate-limit-status").assertDoesNotExist()
        rule.onNodeWithTag("rate-limit-resume-now").assertIsEnabled()
        rule.onNodeWithContentDescription("Cancel scheduled resume").assertIsEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        rule.onNodeWithContentDescription("Dismiss external-advancement notice").assertIsEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun aPressAcrossASessionSwitchInOneSlotIsDropped() {
        // ta-coik.13's stale-tap guard: each control is keyed by (session, prompt or key, link); a
        // press that began on session A's control and lifts on session B's sends nothing for B.
        rule.mainClock.autoAdvance = false
        val limit = rateLimitPrompt(NoticeFixtures.limit.tree)!!
        val scheduled = rateLimitPrompt(NoticeFixtures.scheduled.tree)!!
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                androidx.compose.runtime.CompositionLocalProvider(LocalNoticeActions provides actions) {
                    androidx.compose.foundation.layout.Column {
                        NoticeDismissButton("ext-1", "Dismiss external-advancement notice")
                        ScheduledResumeRow(scheduled, zone = ChatFixtures.zone)
                        RateLimitCard(limit, zone = ChatFixtures.zone)
                    }
                }
            }
        }
        settle()
        var session = 1
        fun next() { session++; actions = rec.actions(sessionId = "s$session") }
        rule.pressAcross({ rule.onNodeWithContentDescription("Dismiss external-advancement notice") }, settle = { settle() }) { next() }
        rule.pressAcross({ rule.onNodeWithContentDescription("Cancel scheduled resume") }, settle = { settle() }) { next() }
        rule.pressAcross({ rule.onNodeWithTag("rate-limit-resume-now") }, settle = { settle() }) { next() }
        rule.pressAcross({ rule.onNodeWithTag("rate-limit-dismiss") }, settle = { settle() }) { next() }
        assertTrue("a press across a session switch sent: ${rec.dismissed} ${rec.controls}", rec.dismissed.isEmpty() && rec.controls.isEmpty())
        // A fresh tap on each acts at once.
        rule.onNodeWithContentDescription("Dismiss external-advancement notice").performClick()
        rule.onNodeWithContentDescription("Cancel scheduled resume").performClick()
        settle()
        assertEquals(listOf("ext-1"), rec.dismissed)
        assertEquals(1, rec.controls.size)
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

    private val interrupts = mutableListOf<String>()

    private fun show(
        session: AgentSession,
        handoffTarget: AgentSession?,
        folded: ChatFixtures.Folded = ComposerFixtures.idle,
        liveness: ComposerLiveness = ComposerLiveness.Live,
    ) {
        rule.setContent {
            ComposerHost(TetherSkin.StudioDark) {
                Composer(
                    session = session,
                    projection = folded.projection,
                    controls = null,
                    serverNow = { ComposerFixtures.BUSY_NOW },
                    onSend = { text, _ -> prompts += text; true },
                    onInterrupt = { interrupts += session.id; com.tether.app.client.InterruptResult.Sent },
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = {},
                    onRequestControls = {},
                    liveness = liveness,
                    handoffTarget = handoffTarget,
                    onOpenSession = { opened += it },
                )
            }
        }
        rule.waitForIdle()
    }

    /** T13.2's composer liveness for a saved copy (Interrupt locked, the run row "Was running"). */
    private val savedCopy = ComposerLiveness(
        interruptLock = stopLockCopy(ConsentLock.Offline),
        stale = com.tether.app.client.SessionSync(com.tether.app.client.Freshness.Saved, null),
    )

    // ---- r3: the read-only / handoff locks composed with T13.2's liveness and Interrupt lock ----

    @Test
    fun aBusyHandedOffSourceHasNoInterruptKeyEvenLive() {
        show(ComposerFixtures.session.copy(handedOffTo = target.id), target, ComposerFixtures.queued, ComposerLiveness.Live)
        rule.onNodeWithTag("composer-handoff-lock").assertExists()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onAllNodesWithTag(INTERRUPT_KEY_TAG).assertCountEquals(0)
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG).assertCountEquals(0)
        rule.onAllNodesWithTag(STALE_RUN_TAG).assertCountEquals(0)
        assertTrue(interrupts.isEmpty())
    }

    @Test
    fun aBusyHandedOffSavedCopyKeepsTheLockRowAndSaysWasRunning() {
        show(ComposerFixtures.session.copy(handedOffTo = target.id), target, ComposerFixtures.queued, savedCopy)
        rule.onNodeWithTag("composer-handoff-lock").assertExists()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onAllNodesWithTag(INTERRUPT_KEY_TAG).assertCountEquals(0)
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG).assertCountEquals(0)
        rule.onNodeWithTag(STALE_RUN_TAG).assertExists()
        assertTrue(interrupts.isEmpty())
    }

    @Test
    fun aBusyReadOnlySavedCopyKeepsTheReplayFlagAndSaysWasRunning() {
        show(ComposerFixtures.session.copy(readOnly = true, provider = "codex"), null, ComposerFixtures.queued, savedCopy)
        rule.onNodeWithTag("composer-read-only").assertExists()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onAllNodesWithTag(INTERRUPT_KEY_TAG).assertCountEquals(0)
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG).assertCountEquals(0)
        rule.onNodeWithTag(STALE_RUN_TAG).assertExists()
        assertTrue(interrupts.isEmpty())
    }

    @Test
    fun aBusyDrivableSavedCopyKeepsItsInputButBothInterruptKeysAreLocked() {
        // The control case: neither T6.6 lock applies, so the composer is drawn and T13.2's lock holds.
        show(ComposerFixtures.session, null, ComposerFixtures.queued, savedCopy)
        rule.onAllNodesWithTag("composer-handoff-lock").assertCountEquals(0)
        rule.onAllNodesWithTag("composer-read-only").assertCountEquals(0)
        rule.onNodeWithText(PLACEHOLDER_BUSY).assertExists()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).assertIsNotEnabled().performClick()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertTrue(interrupts.isEmpty())
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
    fun turningItOnSendsOnTheFirstTap() {
        // ta-coik.7: the web's toggle (chat-view.tsx:2546-2548) sends set-auto-continue-on-limit at once.
        h.show()
        h.arm()
        openAutoContinueSheet()
        h.arm()
        rule.onNodeWithText("On", useUnmergedTree = true).performClick()
        h.settle()
        assertEquals(listOf<SessionControl>(SessionControl.AutoContinueOnLimit(true)), h.recorder.sent)
        rule.onNodeWithText(AUTO_CONTINUE_ON_FLASH).assertExists()
        rule.onAllNodesWithTag("escalation-confirm").assertCountEquals(0)
        rule.onNodeWithText("Turn on \u2068Auto-continue\u2069?").assertDoesNotExist()
    }

    @Test
    fun aRefusedGrantIsSaid() {
        h.recorder.resultFor = { ControlResult.NotLive }
        h.show()
        h.arm()
        openAutoContinueSheet()
        h.arm()
        rule.onNodeWithText("On", useUnmergedTree = true).performClick()
        h.settle()
        assertEquals(listOf<SessionControl>(SessionControl.AutoContinueOnLimit(true)), h.recorder.sent)
        rule.onNodeWithText(OTHER_SERVER_NOT_SENT).assertExists()
        rule.onNodeWithText(AUTO_CONTINUE_ON_FLASH).assertDoesNotExist()
    }

    @Test
    fun theSheetRowsActOnTheFirstTap() {
        // ta-coik.9: session-settings-sheet.tsx's Off / On rows have no arm delay.
        h.session = SessionControlFixtures.claude.copy(autoContinueOnLimit = true)
        h.show()
        openAutoContinueSheet()
        rule.onNodeWithTag("control-option-false").assertIsEnabled().performClick()
        h.settle(0)
        assertEquals(listOf<SessionControl>(SessionControl.AutoContinueOnLimit(false)), h.recorder.sent)
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

/** T6.6: the Auto-continue key in the wide row (from 64rem): sent on the first tap (ta-coik.7, ta-coik.9). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class AutoContinueTabletBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val h = ControlsHost(rule)

    @Test
    fun theKeySendsOnTheFirstTap() {
        h.show()
        h.click("control-auto-continue")
        assertEquals(listOf<SessionControl>(SessionControl.AutoContinueOnLimit(true)), h.recorder.sent)
        rule.onAllNodesWithTag("escalation-confirm").assertCountEquals(0)
        rule.onNodeWithText("Turn on \u2068Auto-continue\u2069?").assertDoesNotExist()
        h.session = SessionControlFixtures.claude.copy(autoContinueOnLimit = true)
        h.settle()
        h.click("control-auto-continue")
        assertEquals(SessionControl.AutoContinueOnLimit(false), h.recorder.sent.last())
    }
}
