package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.tether.app.client.ConsentResult
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-xxda (W24): the portrait card's right edge. The web's `.chat-scroll` pads 16 / 16 (studio.css:461) and the right-docked
 * conversation-timeline rail is an absolute overlay (conversation-timeline.module.css:5-7, :241-249: `position: absolute`,
 * z 18, the page reserves no width for it), so the card, like every row, runs 16..W-16 and the rail lies over its right part:
 * at 412 the card is 16..396 and the rail 358.41..412; a hit on a grant row at x >= 366 lands on the rail, at x <= 356 on
 * the card (the served 29537e0 hit-scan). The desktop's left gutter (3.4rem) is the one column added beside the rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class PhoneRailOverlayTest {
    @get:Rule val rule = createComposeRule()

    private fun actions() = ConsentActions(
        sessionId = "s1", origin = TEST_ORIGIN, lock = null, decided = emptySet(), questionUnavailable = null,
        onApproval = { _, _, _, _, _ -> ConsentResult.Sent },
        onAnswer = { _, _, _, _ -> ConsentResult.Sent },
        onOpenRun = {},
    )

    private fun show() {
        val fixture = ApprovalFixtures.grants
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 900.dp) {
                ChatTranscript(
                    projection = fixture.projection, tree = fixture.tree, showThinking = false, onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone, consent = actions(), listState = LazyListState(),
                )
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private fun dp(px: Float) = px / rule.density.density

    private fun well() = rule.onNodeWithTag(WellTag).fetchSemanticsNode().boundsInRoot
    private fun card() = rule.onNodeWithTag("approval-card").fetchSemanticsNode().boundsInRoot
    private fun rail() = rule.onNodeWithTag(TIMELINE_TAG).fetchSemanticsNode().boundsInRoot

    /** Tap at [x] (root px) on the first read row's centre line. */
    private fun tapRow(x: Float) {
        val row = rule.onAllNodesWithTag("grant-read")[0].fetchSemanticsNode().boundsInRoot
        val origin = well()
        rule.onNodeWithTag(WellTag).performTouchInput { click(Offset(x - origin.left, row.center.y - origin.top)) }
        rule.waitForIdle()
    }

    @Test fun theCardRunsToTheRightPaddingAndTheRailOverlaysIt() {
        show()
        val w = well()
        val c = card()
        assertEquals(16f, dp(c.left - w.left), 0.5f)
        assertEquals(dp(w.width) - 16f, dp(c.right - w.left), 0.5f)
        val r = rail()
        assertEquals(dp(w.width), dp(r.right - w.left), 0.5f)
        assertEquals(53.6f, dp(r.width), 0.5f)
    }

    @Test fun aTapOverTheRailReachesTheRailAndAnyOtherTapOnTheRowToggles() {
        show()
        val one = rule.density.density
        val r = rail()
        val row = rule.onAllNodesWithTag("grant-read")[0].fetchSemanticsNode().boundsInRoot
        // The row (inside the card's 20 dp padding) ends inside the rail's 53.6 dp: the part of the row under the rail.
        assert(row.right > r.left + 2 * one) { "the row ${row.right} must reach under the rail ${r.left}" }
        tapRow(row.right - 2 * one)
        rule.onAllNodesWithTag("grant-read")[0].assertIsOn()
        // Left of the rail it is the row's own.
        tapRow(r.left - 20 * one)
        rule.onAllNodesWithTag("grant-read")[0].assertIsOff()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-420dpi")
    fun theDesktopKeepsItsLeftGutterBesideTheRows() {
        show()
        // 32 start padding + the rail's 54.4 gutter on the left (Left side at 1024 dp and up): unchanged.
        assertEquals(32f + 54.4f, dp(card().left - well().left), 0.5f)
    }
}
