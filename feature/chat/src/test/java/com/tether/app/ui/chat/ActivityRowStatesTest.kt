package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-a5jl A6: a row says its state twice, never by colour alone: the glyph AND the words for running, error and
 * interrupted; a done row says neither and its name ends ", done".
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ActivityRowStatesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun inRow(label: String, tag: String) = hasTestTag(tag) and hasAnyAncestor(rowLabel(label))

    private fun show() {
        rule.showTranscript(ActivityFixtures.rows, showThinking = true)
    }

    private fun reach(label: String) {
        rule.onNodeWithTag("chat-transcript").performScrollToNode(rowLabel(label))
    }

    @Test fun aRunningRowShowsTheSpinnerAndTheWordsWithTheElapsedTime() {
        show()
        reach("Shell npm test -- --watch=false, running")
        rule.onNode(rowLabel("Shell npm test -- --watch=false, running, 12s")).assertExists()
        rule.onNode(inRow("Shell npm test", "activity-state-running"), useUnmergedTree = true).assertExists()
        rule.onNode(hasTestTag("activity-row-status") and hasText("running · 12s") and hasAnyAncestor(rowLabel("Shell npm test")), useUnmergedTree = true).assertExists()
    }

    @Test fun anErrorRowShowsTheAlertAndTheWord() {
        show()
        reach("Edit /w/p/src/config.ts, error")
        rule.onNode(inRow("Edit /w/p/src/config.ts, error", "activity-state-error"), useUnmergedTree = true).assertExists()
        rule.onNode(hasTestTag("activity-row-status") and hasText("error") and hasAnyAncestor(rowLabel("Edit /w/p/src/config.ts")), useUnmergedTree = true).assertExists()
    }

    @Test fun anInterruptedRowShowsTheStopAndTheWord() {
        show()
        reach("Search retries  src, interrupted")
        rule.onNode(inRow("Search retries  src, interrupted", "activity-state-interrupted"), useUnmergedTree = true).assertExists()
        rule.onNode(hasTestTag("activity-row-status") and hasText("interrupted") and hasAnyAncestor(rowLabel("Search retries")), useUnmergedTree = true).assertExists()
    }

    @Test fun aDoneRowSaysNothingButItsNameEndsDone() {
        show()
        reach("Shell git status, done")
        // The row is there, whole: its glyph and verb are drawn...
        rule.onNode(inRow("Shell git status, done", "activity-row-glyph"), useUnmergedTree = true).assertExists()
        rule.onNode(inRow("Shell git status, done", "activity-row-verb"), useUnmergedTree = true).assertExists()
        // ...and it draws no state glyph and no status words.
        rule.onAllNodes(hasTestTag("activity-row-status") and hasAnyAncestor(rowLabel("Shell git status, done")), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(hasTestTag("activity-row-cluster") and hasAnyAncestor(rowLabel("Shell git status, done")), useUnmergedTree = true).assertCountEquals(0)
        assertTrue(rule.onNode(rowLabel("Shell git status, done")).fetchSemanticsNode().config.toString().isNotEmpty())
    }
}
