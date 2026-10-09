package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-a5jl A12: only a tool call and a thinking block become rows. The cards the agent needs an answer to or the operator
 * needs to read (approval, question, answered, denial, plan / diff / review, the `!` command panel, the background-command
 * chip) are still those cards: each is on screen under its own tag, and no row stands for any of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h2400dp-420dpi")
class ScopeKeepsCardsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun scope(fixture: ChatFixtures.Folded, richCodex: Boolean = false, cards: List<SemanticsMatcher>) {
        rule.showTranscript(fixture, showThinking = true, richCodex = richCodex, wellHeight = 2300.dp, groupsOpen = true)
        for (card in cards) {
            rule.onNodeWithTag("chat-transcript").performScrollToNode(card)
            rule.onAllNodes(card).onFirst().assertExists()
        }
        val expected = buildChatItems(fixture.projection, fixture.tree, showThinking = true, zone = ChatFixtures.zone, richCodex = richCodex, groupOpen = { _, _ -> true })
            .count { it.isActivityRow }
        assertEquals("one row per tool call / thinking block, none for a card", expected, rule.onAllNodesWithTag("activity-row").fetchSemanticsNodes().size)
    }

    @Test fun anApprovalStaysACard() = scope(ApprovalFixtures.write, cards = listOf(hasTestTag("approval-card")))

    @Test fun aQuestionStaysACard() = scope(ApprovalFixtures.question, cards = listOf(hasTestTag("question-card")))

    @Test fun anAnsweredQuestionStaysACard() = scope(ApprovalFixtures.answered, cards = listOf(hasTestTag("answered-card")))

    @Test fun denialsStayCardsNestedAndTopLevel() {
        scope(ApprovalFixtures.denials, cards = listOf(hasTestTag("denial-card")))
        // Every denial is a card, one per refused call (nested ones follow their row).
        assertEquals(4, rule.onAllNodesWithTag("denial-card").fetchSemanticsNodes().size)
    }

    @Test fun aCodexTurnsPlanDiffAndReviewStayCards() =
        scope(
            ToolFixtures.codexDetails, richCodex = true,
            cards = listOf(hasContentDescription("Codex plan"), hasContentDescription("Turn changes"), hasContentDescription("Review failed")),
        )

    @Test fun theBangCommandPanelStaysAPanel() = scope(CommandFixtures.failed, cards = listOf(hasTestTag(COMMAND_PANEL_TAG)))

    @Test fun aBackgroundCommandsChipStaysAChip() = scope(SubagentFixtures.activity, cards = listOf(hasTestTag("bg-command-chip")))
}
