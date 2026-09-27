package com.tether.app.ui.statusline

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.tether.app.ui.statusline.screenshots.BoardEnv
import com.tether.app.ui.statusline.screenshots.BoardNow
import com.tether.app.ui.statusline.screenshots.boardState
import com.tether.app.ui.theme.TetherTheme
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TalkBack labels, 44dp targets, progress semantics, and the self-expiring wrap-up. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class StatuslineAccessibilityTest {
    @get:Rule val rule = createComposeRule()

    private val metrics = TelemetryMetrics(
        contextPercent = 82.0,
        contextTokens = 164_000.0,
        contextWindow = 200_000.0,
        fiveHour = TelemetryWindow(41.0, BoardNow + 125 * 60_000.0),
    )

    @Test fun statuslineSegmentsReadTheirFullReading() {
        rule.setContent { TetherTheme { SessionStatusline(metrics, boardState(grace = "wrap_up"), env = BoardEnv) } }
        rule.onNodeWithContentDescription("Session telemetry").assertExists()
        rule.onNodeWithContentDescription(
            "Usage limit reached · wrapping up — a capped allowance — it may stop after the current step · limit resets in 1h 35m",
        ).assertExists()
        rule.onNodeWithContentDescription("Context 82% full · 164K of 200K tokens")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(82f, 0f..100f)))
        rule.onNodeWithContentDescription("Five hour usage 41% · resets in 2h 5m").assertExists()
    }

    @Test fun gaugeIsAToggleHandleWithItsReadingAsLabel() {
        var open = false
        rule.setContent {
            TetherTheme { ContextGauge(metrics, pressed = open, onClick = { open = !open }, env = BoardEnv) }
        }
        rule.onNodeWithContentDescription("Context 82% full · 164K of 200K tokens")
            .assertIsToggleable()
            .assertWidthIsAtLeast(44.dp)
            .assertHeightIsAtLeast(44.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not pressed"))
            .performClick()
        assertEquals(true, open)
    }

    @Test fun gaugeWithoutAReadingFallsBack() {
        rule.setContent { TetherTheme { ContextGauge(null, onClick = {}, env = BoardEnv) } }
        rule.onNodeWithContentDescription("Session telemetry").assertWidthIsAtLeast(44.dp).assertHeightIsAtLeast(44.dp)
    }

    @Test fun dialAnnouncesElapsedTimeAndTicks() {
        var now = BoardNow
        rule.mainClock.autoAdvance = false
        rule.setContent { TetherTheme { SessionDial(BoardNow - 540_000, null, active = true, clock = { now }) } }
        rule.onNodeWithContentDescription("Session elapsed time 00:09:00").assertExists()
        now += 2_000
        rule.mainClock.advanceTimeBy(1_100) // the 1s interval, plus the frame that recomposes
        rule.onNodeWithContentDescription("Session elapsed time 00:09:02").assertExists()
    }

    @Test fun wrapUpExpiresAtItsResetWithoutAnotherEvent() {
        var now = BoardNow.toDouble()
        val env = { ReadingEnv(now, Locale.US, ZoneOffset.UTC) }
        val state = boardState(grace = "wrap_up") // resetsAt = BoardNow + 95 min
        rule.mainClock.autoAdvance = false
        rule.setContent { TetherTheme { WrapUpNotice(state, env = env) } }
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("Wrapping up", substring = true).assertCountEquals(0) // text, not a description
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
        now += 95 * 60_000
        rule.mainClock.advanceTimeBy(95 * 60_000L + 16)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0)
    }
}
