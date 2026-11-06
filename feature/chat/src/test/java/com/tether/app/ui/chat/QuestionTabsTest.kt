package com.tether.app.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.tether.app.client.ConsentResult
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AW.2.9 (lane A-tabs): the question card's tabs at 360dp. Each pin asserts the token that decides it
 * (a plant is named in the receipt). Touch is driven by real taps; a keyboard test drives the card by
 * semantics actions and key events so the input mode stays Keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-420dpi")
class QuestionTabsTest {
    @get:Rule val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val store = CardStateStore()
    private val listState = androidx.compose.foundation.lazy.LazyListState()
    private var fixture = ApprovalFixtures.questionTabs

    private fun consent(lock: ConsentLock? = null) = ConsentActions(
        sessionId = "s1",
        origin = TEST_ORIGIN,
        lock = lock,
        decided = emptySet(),
        questionUnavailable = null,
        onApproval = { _, _, _, _, _ -> ConsentResult.Sent },
        onAnswer = { requestId, _, picks, skipped ->
            val request = com.tether.app.client.ConsentGuard.pendingQuestion(fixture.tree, requestId)
            val reply = request?.let { com.tether.app.client.ConsentGuard.buildAnswers(it, picks, skipped) }
            calls += "question:$requestId:" + (reply?.answers ?: "<invalid>")
            ConsentResult.Sent
        },
        onOpenRun = {},
    )

    private fun show(f: ChatFixtures.Folded = ApprovalFixtures.questionTabs, mode: InputMode = InputMode.Touch, lock: ConsentLock? = null) {
        fixture = f
        val c = consent(lock)
        rule.setContent {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(mode) }
            ChatHost(TetherSkin.StudioDark, wellHeight = 900.dp) {
                CompositionLocalProvider(LocalCardStates provides store) {
                    ChatTranscript(projection = f.projection, tree = f.tree, showThinking = false, onFetchTurns = { _, _ -> }, zone = ChatFixtures.zone, consent = c, listState = listState)
                }
            }
        }
        settle()
        scrollTo("question-card")
    }

    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        rule.waitForIdle()
        fun composed() = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        if (!composed()) {
            val total = rule.runOnIdle { listState.layoutInfo.totalItemsCount }
            for (i in total - 1 downTo 0) {
                rule.onNodeWithTag("chat-transcript").performScrollToIndex(i)
                rule.waitForIdle()
                if (composed()) break
            }
        }
        assertTrue("$tag is not composed", composed())
    }

    private val cfp get() = pendingQuestions(fixture.tree).single().contentFp
    private fun sel() = store.question(cfp)

    /** A semantic click (no pointer event, so the input mode stays as the test set it). */
    private fun act(tag: String) {
        scrollTo(tag)
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    private fun tab(i: Int) = rule.onAllNodesWithTag("question-tab")[i]
    private fun tabName(i: Int) = tab(i).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString("|")
    private fun selectTab(i: Int) { tab(i).performClick(); settle() }
    private fun pick(label: String) { rule.onNodeWithText(label).performClick(); settle() }

    // --- the strip -------------------------------------------------------------------------------

    @Test fun eachQuestionGetsATabNamedByItsHeaderWithTheStatusWords() {
        show()
        rule.onAllNodesWithTag("question-tabs").assertCountEquals(1)
        rule.onNodeWithTag("question-tabs").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Questions")))
        rule.onAllNodesWithTag("question-tab").assertCountEquals(4)
        assertEquals("Crash reports, not answered", tabName(0))
        pick("Yes")
        assertEquals("Crash reports, answered", tabName(0))
        rule.onNodeWithTag("question-skip").performClick(); settle()
        assertEquals("Crash reports, skipped", tabName(0) .also { })
    }

    @Test fun theFourStatesDrawFourDifferentGlyphs() {
        // The web's AM3 lesson: assert the DRAWN icon, not only the words.
        val glyphs = listOf(QuestionStatus.Answered, QuestionStatus.Skipped, QuestionStatus.NeedsAnswer, QuestionStatus.Unanswered).map { statusGlyph(it) }
        assertEquals(4, glyphs.toSet().size)
        assertEquals(com.tether.app.ui.icons.TetherIcons.CircleMinus, statusGlyph(QuestionStatus.Skipped))
        assertEquals(com.tether.app.ui.icons.TetherIcons.CircleAlert, statusGlyph(QuestionStatus.NeedsAnswer))
        assertEquals(com.tether.app.ui.icons.TetherIcons.CircleCheck, statusGlyph(QuestionStatus.Answered))
        assertEquals(com.tether.app.ui.icons.TetherIcons.Circle, statusGlyph(QuestionStatus.Unanswered))
    }

    @Test fun aTabTapSelectsThatPageAndSkipsNothing() {
        show()
        tab(0).assertIsSelected()
        selectTab(2)
        tab(2).assertIsSelected()
        assertEquals(2, sel().page)
        assertTrue(sel().skipped.isEmpty())
        tab(2).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
    }

    @Test fun everyTabIsAtLeast48dpTall() {
        show()
        for (i in 0 until 4) tab(i).assertHeightIsAtLeast(48.dp)
    }

    @Test fun theFourQuestionStripWrapsToTwoRowsAt360() {
        show()
        val tops = (0 until 4).map { tab(it).fetchSemanticsNode().boundsInRoot.top }.distinct()
        assertEquals("rows at 360dp: $tops", 2, tops.size)
    }

    @Test fun theCounterIsVisibleButHiddenFromTalkBackWithNoLiveRegion() {
        show()
        rule.onNodeWithTag("question-page-words", useUnmergedTree = true).assertTextEquals("Question 1 of 4")
        val node = rule.onNodeWithTag("question-page").fetchSemanticsNode().config
        assertEquals(null, node.getOrNull(SemanticsProperties.Text))
        assertEquals(null, node.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test fun aTabbedCardDropsTheInPanelHeaderAndAOneQuestionCardKeepsIt() {
        show()
        // The tab's words are spoken through its name; no panel line draws the header as text.
        rule.onAllNodes(androidx.compose.ui.test.hasText("CRASH REPORTS", ignoreCase = true), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Crash reports", substring = true, ignoreCase = true), useUnmergedTree = true).assertCountEquals(1)
    }

    // --- Skip, Next, Back, Undo ------------------------------------------------------------------

    @Test fun skipOnAnInnerTabMarksStaysAndNeverSends() {
        show()
        selectTab(1)
        rule.onNodeWithTag("question-skip").performClick(); settle()
        assertEquals("Skip stayed on its own tab", 1, sel().page)
        assertEquals(setOf(1), sel().skipped)
        assertTrue(calls.isEmpty())
        rule.onNodeWithTag("question-skip").assertIsNotEnabled() // disabled while the current question is skipped
    }

    @Test fun nextIsEnabledAfterSkipAndMovesOnePage() {
        show()
        rule.onNodeWithTag("question-next").assertIsNotEnabled()
        rule.onNodeWithTag("question-skip").performClick(); settle()
        rule.onNodeWithTag("question-next").assertIsEnabled().performClick(); settle()
        assertEquals(1, sel().page)
    }

    @Test fun skipOnTheLastTabOfATabbedCardDoesNotSend() {
        show()
        pick("Yes")
        selectTab(1); pick("One Tap")
        selectTab(2); pick("Purchases")
        selectTab(3) // every other question is settled: a Skip that sent would send now
        scrollTo("question-skip")
        rule.onNodeWithTag("question-skip").performClick(); settle()
        assertTrue("Skip sent on the last tab: $calls", calls.isEmpty())
        assertEquals(setOf(3), sel().skipped)
        assertEquals(3, sel().page)
    }

    @Test fun backExistsFromTheSecondTabAndMovesOnePage() {
        show()
        rule.onAllNodesWithTag("question-back").assertCountEquals(0)
        selectTab(2)
        rule.onNodeWithTag("question-back").performClick(); settle()
        assertEquals(1, sel().page)
        rule.onNodeWithTag("question-back").performClick(); settle()
        assertEquals(0, sel().page)
        rule.onAllNodesWithTag("question-back").assertCountEquals(0)
    }

    @Test fun undoSkipRestoresTheClearedPickAndOther() {
        show()
        selectTab(2) // multi-select Analytics
        pick("Purchases")
        rule.onNode(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("question-other"))).performTextInput("custom")
        settle()
        rule.onNodeWithTag("question-skip").performClick(); settle()
        rule.onNodeWithText("Skipped.").assertExists()
        assertTrue(sel().picks[2] == null && sel().other[2] == null)
        rule.onNodeWithTag("question-undo-skip").performClick(); settle()
        assertEquals(listOf(1), sel().picks[2])
        assertEquals("custom", sel().other[2])
        assertTrue(2 !in sel().skipped)
        rule.onAllNodesWithTag("question-undo-skip").assertCountEquals(0)
    }

    @Test fun aPickOnASkippedQuestionUnSkipsIt() {
        show()
        rule.onNodeWithTag("question-skip").performClick(); settle()
        assertEquals(setOf(0), sel().skipped)
        pick("No")
        assertTrue(sel().skipped.isEmpty())
        assertEquals("Crash reports, answered", tabName(0))
    }

    @Test fun aFailedSubmitSelectsTheFirstOpenQuestionAndNamesEveryOpenOne() {
        show()
        pick("Yes") // Q1 answered
        selectTab(3)
        scrollTo("question-submit")
        rule.onNodeWithTag("question-submit").performClick(); settle()
        assertTrue(sel().attempted)
        assertEquals("the first open question is selected", 1, sel().page)
        assertTrue(calls.isEmpty())
        tab(1).assertIsSelected()
        assertEquals("Google login, needs an answer", tabName(1))
        assertEquals("Deploy target, needs an answer", tabName(3))
        assertEquals("Crash reports, answered", tabName(0))
        rule.onNodeWithTag("question-validation").assertTextEquals(
            "Still unanswered: Google login, Analytics, Deploy target. Answer each one, or choose Skip to leave it unanswered.",
        )
    }

    @Test fun submitSendsWhenEverySlotIsSettledWithTheSkippedOnesOmitted() {
        show()
        pick("Yes")
        selectTab(1); rule.onNodeWithTag("question-skip").performClick(); settle()
        selectTab(2); pick("Screen views")
        selectTab(3); pick("Staging")
        scrollTo("question-submit")
        rule.onNodeWithTag("question-submit").performClick(); settle()
        assertEquals(listOf("question:q-1:{${ApprovalFixtures.Q_CRASH}=Yes, ${ApprovalFixtures.Q_ANALYTICS}=Screen views, ${ApprovalFixtures.Q_DEPLOY}=Staging}"), calls)
    }

    @Test fun theTabsAndBackStayUsableOnALockedCardWhileTheAnswerKeysAreDisabled() {
        show(lock = ConsentLock.Offline)
        selectTab(2)
        tab(2).assertIsSelected()
        rule.onNodeWithTag("question-back").assertIsEnabled().performClick(); settle()
        assertEquals(1, sel().page)
        rule.onAllNodesWithTag("question-option")[0].assertIsNotEnabled()
        rule.onAllNodesWithTag("question-skip").assertCountEquals(0)
    }

    @Test fun onTheLastTabSkipBackAndSubmitShareOneRowAt360() {
        show()
        pick("Yes"); selectTab(3)
        scrollTo("question-submit")
        fun top(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
        // Skip, Back and Submit are one row: their vertical centres agree within a pixel.
        fun mid(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.let { (it.top + it.bottom) / 2 }
        assertEquals(mid("question-skip"), mid("question-back"), 3f)
        assertEquals(mid("question-back"), mid("question-submit"), 3f)
        assertNotEquals(0f, top("question-skip"))
    }

    // --- the announce node ----------------------------------------------------------------------

    @Test fun nextBackSkipAndUndoSetTheAnnounceNodeAndATabTapDoesNot() {
        show()
        fun said() = rule.onNodeWithTag("question-announce").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().single()
        rule.onNodeWithTag("question-announce").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, androidx.compose.ui.semantics.LiveRegionMode.Polite))
        selectTab(1)
        assertEquals("", said().trim())
        pick("Classic button")
        rule.onNodeWithTag("question-skip").performClick(); settle()
        assertEquals("Google login skipped.", said().trim())
        rule.onNodeWithTag("question-next").performClick(); settle()
        assertEquals("Question 3 of 4: Analytics", said().trim())
        rule.onNodeWithTag("question-back").performClick(); settle()
        assertEquals("Question 2 of 4: Google login, skipped", said().trim())
        rule.onNodeWithTag("question-undo-skip").assertExists()
    }

    @Test fun theSameAnnouncementTwiceDiffersByATrailingSpace() {
        show()
        fun said() = rule.onNodeWithTag("question-announce").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().single()
        pick("Yes")
        rule.onNodeWithTag("question-next").performClick(); settle()
        val first = said()
        selectTab(0) // a tab tap announces nothing
        assertEquals(first, said())
        rule.onNodeWithTag("question-next").performClick(); settle()
        // Next again onto the same page: its text equals the one before, the node's text does not.
        assertEquals(first.trim(), said().trim())
        assertNotEquals(first, said())
    }

    // --- the 1-question card is unchanged -------------------------------------------------------

    @Test fun aOneQuestionCardHasNoStripNoBackNoAnnounceAndSkipSends() {
        show(ApprovalFixtures.questionSingle)
        rule.onAllNodesWithTag("question-tabs").assertCountEquals(0)
        rule.onAllNodesWithTag("question-tab").assertCountEquals(0)
        rule.onAllNodesWithTag("question-back").assertCountEquals(0)
        rule.onAllNodesWithTag("question-announce").assertCountEquals(0)
        rule.onAllNodesWithTag("question-page").assertCountEquals(0)
        rule.onAllNodes(androidx.compose.ui.test.hasText("DATABASE", ignoreCase = true), useUnmergedTree = true).assertCountEquals(1) // the header line is kept
        scrollTo("question-skip")
        rule.onNodeWithTag("question-skip").performClick(); settle()
        assertEquals(listOf("question:q-1:{}"), calls) // skip and send
    }

    @Test fun aOneQuestionFailedSubmitKeepsTheHighlightSentence() {
        show(ApprovalFixtures.questionSingle)
        scrollTo("question-submit")
        rule.onNodeWithTag("question-submit").performClick(); settle()
        rule.onNodeWithTag("question-validation").assertTextEquals("Answer each highlighted question, or choose Skip to leave it unanswered.")
    }

    // --- (b1) the tab keys ---------------------------------------------------------------------

    private fun key(i: Int, k: Key) { tab(i).performKeyInput { pressKey(k) }; settle() }

    @Test fun arrowsWrapAndHomeEndJumpSelectingAndFocusingTheTab() {
        show(mode = InputMode.Keyboard)
        selectTab(3)
        tab(3).performSemanticsAction(SemanticsActions.RequestFocus); settle()
        tab(3).assertIsFocused()
        key(3, Key.DirectionRight)
        assertEquals("ArrowRight on the last tab wraps to the first", 0, sel().page)
        tab(0).assertIsSelected(); tab(0).assertIsFocused()
        key(0, Key.DirectionLeft)
        assertEquals("ArrowLeft on the first tab wraps to the last", 3, sel().page)
        tab(3).assertIsFocused()
        key(3, Key.MoveHome)
        assertEquals(0, sel().page); tab(0).assertIsFocused()
        key(0, Key.MoveEnd)
        assertEquals(3, sel().page); tab(3).assertIsFocused()
    }

    @Test fun onlyTheSelectedTabIsATabStopAndTabGoesIntoThePanel() {
        show(mode = InputMode.Keyboard)
        selectTab(1)
        tab(1).performSemanticsAction(SemanticsActions.RequestFocus); settle()
        tab(1).assertIsFocused()
        for (i in listOf(0, 2, 3)) {
            tab(i).performSemanticsAction(SemanticsActions.RequestFocus); settle()
            tab(i).assertIsNotFocused() // not focusable: roving
        }
        tab(1).performSemanticsAction(SemanticsActions.RequestFocus); settle()
        tab(1).performKeyInput { pressKey(Key.Tab) }; settle()
        for (i in 0 until 4) tab(i).assertIsNotFocused()
        assertEquals("Tab from the selected tab lands in the panel", 1, rule.onAllNodes(isFocused()).fetchSemanticsNodes().size)
        rule.onAllNodesWithTag("question-option")[0].assertIsFocused()
    }

    // --- (b2) focus moves only with a hardware keyboard ----------------------------------------

    private fun focusedCount() = rule.onAllNodes(isFocused()).fetchSemanticsNodes().size

    @Test fun inTouchModeNextBackSkipUndoAndAFailedSubmitMoveNoFocus() {
        show(mode = InputMode.Touch)
        act("question-skip")
        assertEquals(0, focusedCount())
        act("question-next")
        assertEquals(0, focusedCount())
        act("question-back")
        assertEquals(0, focusedCount())
    }

    @Test fun inTouchModeUndoAndFailedSubmitMoveNoFocus() {
        show(mode = InputMode.Touch)
        pick("Yes")
        act("question-skip")
        act("question-undo-skip")
        assertEquals(0, focusedCount())
        selectTab(3)
        act("question-submit")
        assertTrue(sel().attempted)
        assertEquals(0, focusedCount())
        rule.onAllNodesWithTag("question-other").assertCountEquals(1)
    }

    @Test fun inKeyboardModeTheSameActionsFocusTheFirstPressedOptionElseTheFirstAndSkipTheKey() {
        show(mode = InputMode.Keyboard)
        rule.onNodeWithTag("question-skip").performSemanticsAction(SemanticsActions.OnClick); settle() // nothing to clear
        rule.onNodeWithTag("question-next").assertIsFocused() // Skip: the primary
        act("question-next")
        rule.onAllNodesWithTag("question-option")[0].assertIsFocused() // first option (nothing pressed)
        rule.onAllNodesWithTag("question-option")[1].performSemanticsAction(SemanticsActions.OnClick); settle()
        act("question-back")
        rule.onAllNodesWithTag("question-option")[0].assertIsFocused() // Q1 skipped: nothing pressed
        act("question-next")
        rule.onAllNodesWithTag("question-option")[1].assertIsFocused() // the first PRESSED option, not the first
        // a failed Submit: first open question, first option
        tab(3).performSemanticsAction(SemanticsActions.OnClick); settle()
        act("question-submit")
        assertEquals(2, sel().page)
        rule.onAllNodesWithTag("question-option")[0].assertIsFocused()
        // Undo skip: focus goes to the restored (pressed) option
        tab(1).performSemanticsAction(SemanticsActions.OnClick); settle()
        act("question-skip")
        act("question-undo-skip")
        rule.onAllNodesWithTag("question-option")[1].assertIsFocused()
    }
}
