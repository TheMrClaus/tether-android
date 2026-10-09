package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ta-a5jl A11: the group header is as it was; opened, its children are rows (one per call), not cards. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h2400dp-420dpi")
class GroupRowsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val summary = "1 file read, 2 searches, 2 shell commands, 2 file edits, 1 file write, 1 web request, 1 tool call"

    @Test fun theHeaderIsUnchangedAndOpenedItHoldsOneRowPerCall() {
        rule.showTranscript(ToolFixtures.tools, wellHeight = androidx.compose.ui.unit.Dp(2300f))
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("tool-activity-group"))
        rule.onNodeWithTag("tool-activity-group").assert(hasContentDescription(summary)).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        // Closed: only the picture-bearing call (never grouped) is a row.
        assertEquals(1, rule.onAllNodesWithTag("activity-row").fetchSemanticsNodes().size)
        rule.onNodeWithTag("tool-activity-group").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tool-activity-group").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        // Open: the ten grouped calls are ten rows (plus the picture's own), and none of them is a card.
        assertEquals(11, rule.onAllNodesWithTag("activity-row").fetchSemanticsNodes().size)
        rule.onAllNodesWithTag("tool-card").assertCountEquals(0)
    }
}
