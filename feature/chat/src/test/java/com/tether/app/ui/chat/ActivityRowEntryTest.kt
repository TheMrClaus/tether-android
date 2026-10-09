package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-a5jl A1: a tool call is ONE row in the transcript, a button that says "Show details"; the card it used to draw is no
 * longer in the transcript (it lives in the sheet).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ActivityRowEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun aToolCallIsOneButtonRowAndNotACard() {
        rule.showTranscript(ToolFixtures.tools)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("tool-activity-group"))
        rule.onNodeWithTag("tool-activity-group").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("chat-transcript").performScrollToNode(rowLabel("Shell npm test, done"))
        val row = rule.onNode(rowLabel("Shell npm test, done"))
        row.assertHeightIsAtLeast(44.dp)
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        row.assert(SemanticsMatcher("onClick label is Show details") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Show details" })
        // The row stands for the card: with the group open, none of its calls draws one.
        assertEquals(0, rule.onAllNodesWithTag("tool-card").fetchSemanticsNodes().size)
    }

    @Test fun theMediaToolIsARowToo() {
        rule.showTranscript(ToolFixtures.tools)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(rowLabel("Tool mcp__parity__render_chart"))
        rule.onNode(rowLabel("Tool mcp__parity__render_chart")).assertHeightIsAtLeast(44.dp)
        assertEquals(0, rule.onAllNodesWithTag("tool-card").fetchSemanticsNodes().size)
    }
}
