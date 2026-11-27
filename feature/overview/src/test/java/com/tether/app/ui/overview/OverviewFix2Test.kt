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

    // P8D-FIX3: at 1.3x the meters stack (the side-by-side cell cannot hold the memory value), so the tracks go down the column, each its own row.
    @Test @Config(fontScale = 1.3f) fun theMetersStackAt412dpFont1_3() {
        val tops = tracksAndValues().map { it.first }
        assertTrue("tracks go down the column: $tops", tops[0] < tops[1] && tops[1] < tops[2])
        rule.onNode(hasText("35.4/62.6G"), useUnmergedTree = true).assertExists()
    }

    private fun assertAligned() {
        val tops = tracksAndValues().map { it.first }
        println("TRACKTOPS $tops")
        assertEquals("CPU/Memory track tops", tops[0], tops[1], 0.5f)
        assertEquals("Memory/Disk track tops", tops[1], tops[2], 0.5f)
        rule.onNode(hasText("35.4/62.6G"), useUnmergedTree = true).assertExists()
    }

    // ── P8D-FIX3 ──
    /** The memory value's layout: one line, nothing cut off at the end (no ellipsis, no clip, wider than its cell never). */
    private fun memoryLayout(total: Double = 62.6, used: Double = 35.4): TextLayoutResult {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.fillMaxWidth().padding(14.dp).testTag("shot")) { HostUsagePanel(tile(used, total)) }
                }
            }
        }
        val out = mutableListOf<TextLayoutResult>()
        rule.onNode(hasTextExactlyAny(), useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(out)
        return out.single()
    }

    private fun hasTextExactlyAny() = hasAnyAncestor(hasTestTag(HostUsageTags.meter("Memory"))) and
        SemanticsMatcher("memory value") { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains("62.6") } == true }

    private fun assertNotClipped() {
        val r = memoryLayout()
        println("MEMLAYOUT text=${r.layoutInput.text.text} overflow=${r.hasVisualOverflow} w=${r.size.width} h=${r.size.height} lineRight=${r.getLineRight(0)} mpWidth=${r.multiParagraph.width} intrinsicMax=${r.multiParagraph.maxIntrinsicWidth} lastCharRight=${r.getBoundingBox(r.layoutInput.text.length - 1).right}")
        assertEquals(1, r.lineCount)
        assertTrue("memory value is cut off: ${r.layoutInput.text.text} (wants ${r.multiParagraph.maxIntrinsicWidth}, has ${r.multiParagraph.width})", r.multiParagraph.maxIntrinsicWidth <= r.multiParagraph.width)
        assertTrue("trailing G laid out", r.layoutInput.text.text.endsWith("G"))
    }

    @Test @Config(qualifiers = "w412dp-h915dp-420dpi") fun theMemoryValueIsNotClippedAt412dpFont1_0() = assertNotClipped()
    @Test @Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f) fun theMemoryValueIsNotClippedAt412dpFont1_3() = assertNotClipped()
    @Test @Config(qualifiers = "w320dp-h915dp-420dpi") fun theMemoryValueIsNotClippedAt320dpFont1_0() = assertNotClipped()
    @Test @Config(qualifiers = "w320dp-h915dp-420dpi", fontScale = 1.3f) fun theMemoryValueIsNotClippedAt320dpFont1_3() = assertNotClipped()

    @Test @Config(qualifiers = "w320dp-h915dp-420dpi") fun atTheStackedWidthAllThreeMetersShow() {
        val tops = tracksAndValues()
        println("STACKED tracks/meters $tops")
        // CPU, Memory, Disk each take a row of their own, in order, every one with a track and a non-zero height.
        assertTrue("tracks go down the column: $tops", tops[0].first < tops[1].first && tops[1].first < tops[2].first)
        tops.forEach { assertTrue("meter has a height: $tops", it.second > 20f) }
    }
}
