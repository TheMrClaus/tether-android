package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-a5jl A9: a thinking block is a "Thinking" row where it shows today (the web's showThinking gate, default off); its
 * sheet holds the WHOLE text (no 13rem clamp, no "Show more"); with the gate off there is no row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ThinkingRowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val fixture: ChatFixtures.Folded by lazy {
        val s = ActivityFixtures.Script()
        s.start("Why does it loop?")
        s.thinking("th1", (1..60).joinToString("\n\n") { "Paragraph $it of the reasoning." })
        s.message("m1", "Found it.")
        s.fold()
    }

    @Test fun theRowOpensTheWholeTextAndTheGateRemovesIt() {
        var showThinking by mutableStateOf(true)
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                ChatTranscript(
                    projection = fixture.projection, tree = fixture.tree, showThinking = showThinking, onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone, showTimeline = false,
                )
            }
        }
        rule.waitForIdle()
        rule.onNode(rowLabel("Thinking, done")).assertExists()
        rule.onNode(rowLabel("Thinking, done")).performClick()
        rule.waitForIdle()
        // First and last paragraph are both in the sheet; nothing is clamped behind a toggle.
        rule.onNode(hasText("Paragraph 1 of the reasoning.") and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("Paragraph 60 of the reasoning.") and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists()
        rule.onAllNodes(hasContentDescription("Show more", substring = true) and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertCountEquals(0)
        rule.onNode(hasContentDescription("Close") and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
        // The gate off: no row (and nothing else stands in for it).
        rule.runOnIdle { showThinking = false }
        rule.waitForIdle()
        rule.onAllNodes(rowLabel("Thinking")).assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Thinking").assertCountEquals(0)
    }

    /** ta-rzgv: a sheet that is OPEN when the gate turns off closes with its row (the sheet's block is gated like the row). */
    @Test fun anOpenThinkingSheetClosesWhenTheGateTurnsOff() {
        var showThinking by mutableStateOf(true)
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                ChatTranscript(
                    projection = fixture.projection, tree = fixture.tree, showThinking = showThinking, onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone, showTimeline = false,
                )
            }
        }
        rule.waitForIdle()
        rule.onNode(rowLabel("Thinking, done")).performClick()
        rule.waitForIdle()
        rule.onNode(hasText("Paragraph 1 of the reasoning.") and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists()
        rule.runOnIdle { showThinking = false }
        rule.waitUntil(5_000) { rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        rule.onAllNodes(rowLabel("Thinking")).assertCountEquals(0)
        // The gate back on: the row is back and the sheet stays shut (the open key was cleared, not parked).
        rule.runOnIdle { showThinking = true }
        rule.waitForIdle()
        rule.onNode(rowLabel("Thinking, done")).assertExists()
        assertEquals(0, rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
    }
}
