package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #301: both handoff banners draw the change row and the reason line from the shown session's own record, and nothing when there is none. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class HandoverChangeBannerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val peer: AgentSession = ComposerFixtures.session.copy(id = "sess-0002", name = "Parser rewrite")
    private val both = HandoverChangeView(
        listOf(HandoverChangeItem("Model", "Opus 5.5", "Fable 5.2"), HandoverChangeItem("Effort", "High", "Extra high")),
        "Stuck on the tokenizer's edge cases",
    )

    private fun show(variant: HandoffVariant, change: HandoverChangeView?) {
        rule.setContent { ChatHost(TetherSkin.Studio) { HandoffNotice(variant, peer, onOpenSession = {}, change = change) } }
        rule.waitForIdle()
    }

    private fun spoken() = rule.onNodeWithTag(HANDOFF_CHANGE_TAG).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

    private val bothSpoken = "Model Opus 5.5 to Fable 5.2. Effort High to Extra high. Reason: Stuck on the tokenizer's edge cases"

    @Test fun theSourcesBannerDrawsModelEffortAndReason() {
        show(HandoffVariant.To, both)
        assertEquals(bothSpoken, spoken())
    }

    @Test fun theSuccessorsBannerDrawsModelEffortAndReason() {
        show(HandoffVariant.From, both)
        assertEquals(bothSpoken, spoken())
    }

    @Test fun aModelOnlyChangeWithoutAReasonDrawsOneItem() {
        show(HandoffVariant.To, HandoverChangeView(listOf(HandoverChangeItem("Model", "Opus 5.5", "Fable 5.2")), null))
        assertEquals("Model Opus 5.5 to Fable 5.2.", spoken())
    }

    @Test fun anEffortOnlyChangeWithAReasonDrawsBoth() {
        show(HandoffVariant.From, HandoverChangeView(listOf(HandoverChangeItem("Effort", "High", "Extra high")), "short"))
        assertEquals("Effort High to Extra high. Reason: short", spoken())
    }

    @Test fun noChangeDrawsNoRowOnTheSource() {
        show(HandoffVariant.To, null)
        rule.onAllNodesWithTag(HANDOFF_CHANGE_TAG).assertCountEquals(0)
    }

    @Test fun noChangeDrawsNoRowOnTheSuccessor() {
        show(HandoffVariant.From, null)
        rule.onAllNodesWithTag(HANDOFF_CHANGE_TAG).assertCountEquals(0)
    }

    @Test fun theNoticeTextBreaksTheSentenceBeforeTheChange() {
        assertEquals("Handed off to Parser rewrite", handoffNoticeText(HandoffVariant.To, peer))
        assertEquals(
            "Continued from Parser rewrite. Model Opus 5.5 to Fable 5.2. Effort High to Extra high. Reason: Stuck on the tokenizer's edge cases",
            handoffNoticeText(HandoffVariant.From, peer, both),
        )
    }
}
