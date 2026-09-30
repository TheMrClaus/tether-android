package com.tether.app.ui.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.tether.app.protocol.model.ApiRetryState
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.model.TurnProjection
import com.tether.app.protocol.model.TurnRun
import com.tether.app.protocol.model.TurnUsage
import com.tether.app.protocol.model.Vocab
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.util.spinnerWordFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T6.4 (turn-activity.tsx, the `turn_activity` run): the RUN is the unit — its clock and token
 * count start at the run's journal-stamped start and its `tokensStart`; the verb is held per run
 * ("Interrupting" while cancelling, "Retrying" between API attempts); a negative elapsed shows no
 * clock; the session total counts only settled turns (max of the two figures, never falling).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class TurnActivityTest {
    @get:Rule val rule = createComposeRule()

    private val session = SubagentFixtures.session

    private fun state(vararg turns: TurnProjection, active: String? = null) = SessionProjection(
        tetherSessionId = "s1",
        provider = "claude",
        cwd = "/w",
        turnOrder = turns.map { it.turnId },
        turnsById = turns.associateBy { it.turnId },
        activeTurnId = active,
    )

    private fun show(projection: SessionProjection, now: Long, part: TurnActivityPart) {
        rule.setContent { ChatHost(TetherSkin.StudioDark) { TurnActivity(projection, session, serverNow = { now }, part = part) } }
        rule.waitForIdle()
    }

    @Test fun settledTokensCountOnlyFinishedTurnsAndTakeTheLargerFigure() {
        val done = TurnProjection("t1", status = Vocab.TURN_DONE)
        assertEquals(0L, settledTurnTokens(done))
        assertEquals(40L, settledTurnTokens(done.copy(liveTokens = 40)))
        assertEquals(900L, settledTurnTokens(done.copy(liveTokens = 40, usage = TurnUsage(perTurnTokens = 900))))
        assertEquals(1200L, settledTurnTokens(done.copy(liveTokens = 1200, usage = TurnUsage(perTurnTokens = 900))))
        // Codex reports usage mid-turn: an open turn is never "settled".
        assertNull(settledTurnTokens(TurnProjection("t2", status = Vocab.TURN_RUNNING, usage = TurnUsage(perTurnTokens = 5))))
    }

    @Test fun theRunRowCountsThisRunOnly() {
        val turn = TurnProjection("t1", run = TurnRun(index = 2, startedAt = 10_000, tokensStart = 300), liveTokens = 1_250, activeMs = 60_000)
        show(state(turn, active = "t1"), now = 55_000, part = TurnActivityPart.Run)
        rule.onNodeWithText("${spinnerWordFor("t1", 2)}…").assertIsDisplayed()
        // 45 s and 950 tokens since the RUN opened (not the turn's 1,250).
        rule.onNodeWithText("45s · 950 tokens").assertIsDisplayed()
    }

    @Test fun cancellingAndRetryingSayWhoIsStuck() {
        val run = TurnRun(index = 1, startedAt = 0, tokensStart = 0)
        show(state(TurnProjection("t1", status = Vocab.TURN_CANCELLING, run = run), active = "t1"), now = 1_000, part = TurnActivityPart.Run)
        rule.onNodeWithText("Interrupting…").assertIsDisplayed()
    }

    @Test fun anApiRetryReadsRetrying() {
        val run = TurnRun(index = 1, startedAt = 0, tokensStart = 0)
        show(state(TurnProjection("t1", run = run, apiRetry = ApiRetryState(attempt = 2)), active = "t1"), now = 1_000, part = TurnActivityPart.Run)
        rule.onNodeWithText("Retrying…").assertIsDisplayed()
    }

    @Test fun aClockBehindTheServerShowsNoElapsed() {
        val run = TurnRun(index = 1, startedAt = 50_000, tokensStart = 0)
        show(state(TurnProjection("t1", run = run, liveTokens = 1), active = "t1"), now = 10_000, part = TurnActivityPart.Run)
        rule.onNodeWithText("1 token").assertIsDisplayed()
        rule.onAllNodesWithText("0s", substring = true).assertCountEquals(0)
    }

    @Test fun idleShowsNoRunRowButKeepsTheSessionTotal() {
        val done = TurnProjection("t1", status = Vocab.TURN_DONE, activeMs = 125_000, liveTokens = 12, usage = TurnUsage(perTurnTokens = 3_400))
        show(state(done), now = 999_999, part = TurnActivityPart.Both)
        rule.onNodeWithText("SESSION TOTAL").assertIsDisplayed()
        rule.onNodeWithText("2m 5s").assertIsDisplayed()
        rule.onNodeWithText("3.4K tokens").assertIsDisplayed()
        rule.onAllNodesWithText("…", substring = true).assertCountEquals(0)
    }
}
