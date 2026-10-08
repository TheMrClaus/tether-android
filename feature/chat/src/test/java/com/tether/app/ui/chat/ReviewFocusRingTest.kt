package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.tether.app.client.ConsentResult
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * ta-xhs4 (W24): the keyboard focus ring on the card that holds the review focus. On the web, focusing Review request and
 * pressing Enter makes `.chat-approval` the active element with `:focus-visible` true: `outline: 2px solid var(--violet);
 * outline-offset: 2px` around the whole card (globals.css:83-86, `[tabindex]:focus-visible`; `.chat-question` has no
 * override either); after a tap `:focus-visible` is false and there is no outline. Chromium decides focus-visible when
 * focus arrives, so the card records the input mode at that moment and keeps it while focus stays.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ReviewFocusRingTest {
    @get:Rule val rule = createComposeRule()

    private var review by mutableStateOf<String?>(null)
    private var input: InputModeManager? = null
    private var focus: FocusManager? = null
    private var violet = Color.Unspecified

    private fun actions() = ConsentActions(
        sessionId = "s1", origin = TEST_ORIGIN, lock = null, decided = emptySet(), questionUnavailable = null,
        onApproval = { _, _, _, _, _ -> ConsentResult.Sent },
        onAnswer = { _, _, _, _ -> ConsentResult.Sent },
        onOpenRun = {},
    )

    private fun show(f: ChatFixtures.Folded) {
        rule.setContent {
            input = LocalInputModeManager.current
            focus = LocalFocusManager.current
            ChatHost(TetherSkin.Studio, wellHeight = 900.dp) {
                violet = LocalTetherTokens.current.violet
                ChatTranscript(
                    projection = f.projection, tree = f.tree, showThinking = false, onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone, consent = actions(), listState = LazyListState(), richCodex = true,
                    reviewFocus = review, onReviewShown = {},
                )
            }
        }
        settle()
    }

    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private fun review(id: String, mode: InputMode) {
        rule.runOnUiThread { input!!.requestInputMode(mode) }
        review = id
        settle()
    }

    /** The pixel 3 dp outside the card's left edge, level with the middle of node [tag] (the ring is 2 dp wide, 2 dp off the card). */
    private fun outsideLeft(tag: String, index: Int = 0): Color {
        val image = rule.onNodeWithTag(WellTag).captureToImage().toPixelMap()
        val node = rule.onAllNodesWithTag(tag)[index].fetchSemanticsNode().boundsInRoot
        // The card's own edge: the head's (a question card is its own head).
        val edge = rule.onAllNodesWithTag("approval-card").fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.left ?: node.left
        val well = rule.onNodeWithTag(WellTag).fetchSemanticsNode().boundsInRoot
        val d = rule.density.density
        return image[Math.round(edge - 3 * d - well.left), Math.round(node.center.y - well.top)]
    }

    private fun isViolet(c: Color) = listOf(c.red - violet.red, c.green - violet.green, c.blue - violet.blue).all { abs(it) * 255f <= 8f }

    private fun assertRing(c: Color, what: String) = assertTrue("$what: want the violet ring, got $c (violet $violet)", isViolet(c))
    private fun assertNoRing(c: Color, what: String) = assertTrue("$what: no ring expected, got $c", !isViolet(c))

    @Test fun aKeyboardReviewDrawsTheRingOnTheHeadTheRowsAndTheTail() {
        show(ApprovalFixtures.grants)
        review("req-g", InputMode.Keyboard)
        rule.onNodeWithTag("approval-card").assertIsFocused()
        assertRing(outsideLeft("approval-card"), "head")
        assertRing(outsideLeft("grant-read", 0), "a read row")
        assertRing(outsideLeft("grant-write"), "the write row")
        assertRing(outsideLeft("grant-confirm"), "the tail")
    }

    @Test fun aTouchReviewDrawsNoRing() {
        show(ApprovalFixtures.grants)
        review("req-g", InputMode.Touch)
        rule.onNodeWithTag("approval-card").assertIsFocused()
        assertNoRing(outsideLeft("approval-card"), "head")
        assertNoRing(outsideLeft("grant-read", 0), "a read row")
        assertNoRing(outsideLeft("grant-confirm"), "the tail")
    }

    @Test fun theRingGoesWhenFocusLeavesTheCard() {
        show(ApprovalFixtures.grants)
        review("req-g", InputMode.Keyboard)
        assertRing(outsideLeft("approval-card"), "head")
        rule.runOnUiThread { focus!!.clearFocus(force = true) }
        settle()
        rule.onNodeWithTag("approval-card").assertIsNotFocused()
        assertNoRing(outsideLeft("approval-card"), "head")
        assertNoRing(outsideLeft("grant-read", 0), "a read row")
    }

    @Test fun aQuestionCardDrawsTheRingByKeyboardAndNoneAfterTouch() {
        show(ApprovalFixtures.question)
        review("q-1", InputMode.Keyboard)
        rule.onNodeWithTag("question-card").assertIsFocused()
        assertRing(outsideLeft("question-card"), "question, keyboard")
    }

    @Test fun aQuestionCardDrawsNoRingAfterTouch() {
        show(ApprovalFixtures.question)
        review("q-1", InputMode.Touch)
        rule.onNodeWithTag("question-card").assertIsFocused()
        assertNoRing(outsideLeft("question-card"), "question, touch")
    }

    @Test fun allowIsNeverFocused() {
        show(ApprovalFixtures.write)
        review("req-w", InputMode.Keyboard)
        rule.onNodeWithTag("approval-card").assertIsFocused()
        rule.onNodeWithTag("approval-allow").assertIsNotFocused()
        rule.onNodeWithTag("approval-deny").assertIsNotFocused()
        assertRing(outsideLeft("approval-card"), "head")
    }

    /** H1: on the web a tap on a control moves focus to it, so the card's ring goes; Compose's toggleable takes no focus on a touch. */
    @Test fun aTapOnAControlInsideTheCardClearsTheRingWhileTheCardStaysFocused() {
        show(ApprovalFixtures.grants)
        review("req-g", InputMode.Keyboard)
        assertRing(outsideLeft("approval-card"), "head, before the tap")
        rule.onAllNodesWithTag("grant-read")[0].performTouchInput { click() }
        settle()
        rule.onNodeWithTag("approval-card").assertIsFocused()
        assertNoRing(outsideLeft("approval-card"), "head, after the tap")
        assertNoRing(outsideLeft("grant-read", 1), "a read row, after the tap")
        assertNoRing(outsideLeft("grant-confirm"), "the tail, after the tap")
    }
}
