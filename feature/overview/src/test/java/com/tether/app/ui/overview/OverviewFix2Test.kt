package com.tether.app.ui.overview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.tether.app.client.HostMemory
import com.tether.app.client.HostMetrics
import com.tether.app.client.HostReading
import com.tether.app.protocol.model.OverviewPending
import com.tether.app.protocol.model.OverviewPendingPanel
import com.tether.app.ui.overview.HostUsageFixtures.GIB
import com.tether.app.ui.overview.HostUsageFixtures.NOW
import com.tether.app.ui.overview.HostUsageFixtures.fresh
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P8D-FIX2: B1 (E8 banner de-duplication), B2 (card rhythm), B3 (card title), B4 (Host & usage meters). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class OverviewFix2Test {
    @get:Rule val rule = createComposeRule()

    private fun cardCount() = rule.onAllNodesWithTag(OverviewTags.card("s-sync")).fetchSemanticsNodes().size

    // ── B1 ──
    @Test fun aWaitingSessionTheBannerListsHasNoCard() {
        rule.setContent { OverviewUnderTest(TetherSkin.Studio, OverviewFixtures.populated) }
        rule.onNodeWithTag(OverviewTags.Pending).assertExists()
        assertEquals("1 waiting session + 1 banner item = 0 waiting cards", 0, cardCount())
        // the other sessions keep their cards
        rule.onNodeWithTag(OverviewTags.card("s-proto")).assertExists()
    }

    @Test fun aWaitingSessionTheBannerCapCutKeepsItsCard() {
        val extra = OverviewFixtures.pending.copy(requestId = "r-2")
        val waiting = OverviewFixtures.cards.first().copy(pending = listOf(OverviewFixtures.pending, extra))
        val data = OverviewFixtures.populatedData.copy(
            cards = listOf(waiting) + OverviewFixtures.cards.drop(1),
            pending = OverviewPendingPanel(listOf(OverviewFixtures.pending), 2, 0), // the banner's cap cut r-2
        )
        rule.setContent { OverviewUnderTest(TetherSkin.Studio, OverviewFixtures.populated.copy(data = data)) }
        assertEquals("the banner does not list every request: the card stays", 1, cardCount())
    }

    @Test fun whenTheFilterEmptiesTheListAPointerSaysWhereTheyWent() {
        val data = OverviewFixtures.populatedData.copy(cards = OverviewFixtures.cards.take(1), totalCards = 1)
        rule.setContent { OverviewUnderTest(TetherSkin.Studio, OverviewFixtures.populated.copy(data = data)) }
        assertEquals(0, cardCount())
        rule.onNodeWithTag(OverviewTags.BannerPointer).assertExists()
        rule.onNode(hasText("1 waiting session is in the banner above, ready to review.", substring = false), useUnmergedTree = true).assertExists()
    }

    @Test fun noPointerWhileAnyCardShows() {
        rule.setContent { OverviewUnderTest(TetherSkin.Studio, OverviewFixtures.populated) }
        assertEquals(0, rule.onAllNodesWithTag(OverviewTags.BannerPointer).fetchSemanticsNodes().size)
    }

    // ── B2 / B3 ──
    private fun center(tagCard: String, matcher: SemanticsMatcher) =
        rule.onNode(matcher and hasAnyAncestor(hasTestTag(tagCard)), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.y

    @Test fun theCardTitleToMetaGapIsTheWaitingCardsRhythm() {
        rule.setContent { OverviewUnderTest(TetherSkin.Studio, OverviewFixtures.clear) }
        val card = OverviewTags.card("s-proto")
        val title = center(card, hasText("Protocol compatibility"))
        val meta = center(card, SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("branch fix/protocol")))
        val dp = (meta - title) / 2.625f
        println("TITLEMETA dp=$dp")
        assertTrue("title to meta centre gap $dp dp must be under 30 (was ~38 with the 44dp link)", dp < 30f)
    }

    @Test fun theWholeCardIsTheOpenTarget() {
        val calls = mutableListOf<String>()
        rule.setContent {
            OverviewUnderTest(TetherSkin.Studio, OverviewFixtures.clear, actions = OverviewActions(onOpenSession = { calls += it }))
        }
        rule.onNodeWithTag(OverviewTags.card("s-game")).performClick()
        assertEquals(listOf("s-game"), calls)
    }

    @Test fun theCardTitleIsTheWebsOneAndAQuarterTimesTheProviderName() {
        rule.setContent { OverviewUnderTest(TetherSkin.Studio, OverviewFixtures.clear) }
        fun style(text: String): androidx.compose.ui.text.TextStyle {
            val out = mutableListOf<TextLayoutResult>()
            rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(OverviewTags.card("s-proto"))), useUnmergedTree = true)
                .fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(out)
            return out.single().layoutInput.style
        }
        val title = style("Protocol compatibility")
        val provider = style("Codex")
        assertEquals(700, title.fontWeight!!.weight)
        assertEquals("1.05rem / 0.84rem", 1.05f / 0.84f, title.fontSize.value / provider.fontSize.value, 0.01f)
    }

    // ── B4 ──
    private fun tile(memUsed: Double, memTotal: Double) = HostUsagePresentation.tile(
        fresh(HostMetrics("host", HostReading.Value(com.tether.app.client.HostCpu(23.0, 8)), HostReading.Value(HostMemory(memUsed * GIB, memTotal * GIB)),
            HostReading.Value(com.tether.app.client.HostDisk(435.0 * GIB, 500.0 * GIB, 65.0 * GIB)), false)),
        fresh(HostUsageFixtures.usageExact), NOW, true,
    )

    private fun tracksAndValues(): List<Pair<Float, Float>> {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.fillMaxWidth().padding(14.dp).testTag("shot")) { HostUsagePanel(tile(35.4, 62.6)) }
                }
            }
        }
        return listOf("CPU", "Memory", "Disk").map { l ->
            rule.onNodeWithTag(HostUsageTags.track(l), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y to
                rule.onNodeWithTag(HostUsageTags.meter(l), useUnmergedTree = true).fetchSemanticsNode().size.height.toFloat()
        }
    }

    @Test fun theThreeTracksShareABaselineAt412dp() = assertAligned()

    @Test @Config(fontScale = 1.3f) fun theThreeTracksShareABaselineAt412dpFont1_3() = assertAligned()

    private fun assertAligned() {
        val tops = tracksAndValues().map { it.first }
        println("TRACKTOPS $tops")
        assertEquals("CPU/Memory track tops", tops[0], tops[1], 0.5f)
        assertEquals("Memory/Disk track tops", tops[1], tops[2], 0.5f)
        rule.onNode(hasText("35.4/62.6G"), useUnmergedTree = true).assertExists()
    }
}
