package com.tether.app.ui.overview

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.TetherTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P8D / m03 behaviour: the one Filters control, the one-line chip row, the two banner states, the
 * activity header's Event log link and the compact tile's View usage link.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class OverviewM03BehaviourTest {
    @get:Rule val rule = createComposeRule()

    private fun hasDescription(d: String) = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(d))

    @Test fun bothFiltersLiveBehindTheOneFiltersControlAndStillWork() {
        var choice by mutableStateOf(OverviewChoice())
        rule.setContent { OverviewUnderTest(TetherSkin.Studio, OverviewFixtures.populated, choice = choice, onChoice = { choice = it }) }
        assertEquals("selects are hidden until Filters opens", 0, rule.onAllNodes(hasDescription("Workspace")).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasDescription("Provider")).fetchSemanticsNodes().size)
        rule.onNodeWithTag(OverviewTags.Filters).performClick()
        rule.onAllNodes(hasDescription("Workspace")).assertCountEquals1()
        rule.onAllNodes(hasDescription("Provider")).assertCountEquals1()
        // Workspace: pick one.
        rule.onNode(hasDescription("Workspace")).performClick()
        rule.waitForIdle()
        rule.onNode(hasDescription("cario, /srv/cario")).performClick()
        rule.waitForIdle()
        assertEquals("/srv/cario", choice.workspace)
        // Provider: pick one; both now in force, shown on the control.
        rule.onNode(hasDescription("Provider")).performClick()
        rule.waitForIdle()
        rule.onNode(hasDescription("Codex")).performClick()
        rule.waitForIdle()
        assertEquals("codex", choice.provider)
        assertEquals("Filters, 2 in force", rule.onNodeWithTag(OverviewTags.Filters).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single())
    }

    @Test fun bannerIsAmberWithTheWaitingItemsOrOneEmeraldLineAtZero() {
        // waiting > 0: the attention panel lists the item, no all-clear strip.
        rule.setContent { OverviewUnderTest(TetherSkin.Studio, OverviewFixtures.populated) }
        rule.onNodeWithTag(OverviewTags.Pending).assertExists()
        rule.onNodeWithText("Run integration tests?", substring = true, useUnmergedTree = true).assertExists()
        assertEquals(0, rule.onAllNodesWithTag(OverviewTags.AllClear).fetchSemanticsNodes().size)
    }

    @Test fun zeroWaitingIsOneEmeraldStripNotAPanel() {
        rule.setContent { OverviewUnderTest(TetherSkin.Studio, OverviewFixtures.clear) }
        val strip = rule.onNodeWithTag(OverviewTags.AllClear)
        strip.assertExists()
        rule.onNodeWithText("Nothing is waiting for you.", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("All clear", useUnmergedTree = true).assertExists()
        assertEquals("the panel is gone", 0, rule.onAllNodesWithTag(OverviewTags.Pending).fetchSemanticsNodes().size)
        val h = strip.fetchSemanticsNode().boundsInRoot.height
        assertTrue("one line: $h px", h < 80 * 2.625f)
    }

    @Test fun theChipRowIsOneChipTallAtNormalScale() = chipRowIsOneLine()

    @Test @Config(fontScale = 1.3f) fun theChipRowIsOneChipTallAt1_3x() = chipRowIsOneLine()

    private fun chipRowIsOneLine() {
        rule.setContent { OverviewUnderTest(TetherSkin.Studio, OverviewFixtures.populated) }
        // Laid-out geometry (a chip scrolled out of view is clipped from boundsInRoot, never from its layout).
        val row = rule.onNodeWithTag(OverviewTags.StatusChips).fetchSemanticsNode()
        val chips = StatusTab.entries.map { rule.onNodeWithTag(OverviewTags.tab(it)).fetchSemanticsNode() }
        val chip = chips.first()
        println("CHIPROW row=${row.size.height}px chip=${chip.size.height}px tops=${chips.map { it.positionInRoot.y }} widths=${chips.map { it.size.width }}")
        assertEquals("the row is exactly one chip tall", chip.size.height, row.size.height)
        assertTrue("every chip on the same line", chips.all { it.positionInRoot.y == chip.positionInRoot.y && it.size.height == chip.size.height })
    }

    @Test fun eventLogLinkIsInTheActivityHeaderAndHandsOff() {
        val calls = mutableListOf<String>()
        rule.setContent {
            OverviewUnderTest(TetherSkin.Studio, OverviewFixtures.populated, actions = OverviewActions(onOpenEventLog = { calls += "log" }))
        }
        val link = rule.onNodeWithTag(OverviewTags.EventLog)
        link.performScrollTo().performClick()
        assertEquals(listOf("log"), calls)
        val head = rule.onNodeWithTag(OverviewTags.Activity).fetchSemanticsNode().boundsInRoot
        val at = link.fetchSemanticsNode().boundsInRoot
        assertTrue("in the panel's header (top 80dp): ${at.top - head.top}", at.top - head.top < 80 * 2.625f)
        assertEquals("the old footer link is gone", 0, rule.onAllNodes(hasDescription("Open event log")).fetchSemanticsNodes().size)
    }

    @Test fun viewUsageStillCallsOnViewUsage() {
        var called = 0
        rule.setContent {
            TetherTheme(com.tether.app.ui.theme.ThemeMode.Light) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    HostUsagePanel(
                        tile = HostUsagePresentation.tile(
                            HostUsageFixtures.fresh(HostUsageFixtures.host),
                            HostUsageFixtures.fresh(HostUsageFixtures.usageExact),
                            HostUsageFixtures.NOW,
                            connected = true,
                        ),
                        onViewUsage = { called++ },
                    )
                }
            }
        }
        rule.onNodeWithTag(HostUsageTags.ViewUsage).performClick()
        assertEquals(1, called)
    }

    @Test fun anAllClearFilteredNodeSaysSoInTheStrip() {
        val state = OverviewFixtures.clear
        rule.setContent { OverviewUnderTest(TetherSkin.Studio, state, choice = OverviewChoice(workspace = "/srv/tether")) }
        rule.onNodeWithText("Nothing is waiting for you in these filters.", useUnmergedTree = true).assertExists()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals1() {
    assertEquals(1, fetchSemanticsNodes().size)
}

/**
 * P8D / m03 contrast, measured on the pixels the widgets really drew (the pixel farthest in luminance from the face, as the
 * sidebar's Fix3ContrastTest): banner text (amber and emerald) and chip text, >= 4.5:1, Light and Dark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class OverviewM03ContrastTest {
    @get:Rule val rule = createComposeRule()

    private fun lum(c: Color): Double {
        fun ch(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun ratio(a: Color, b: Color): Double = lum(a).let { x -> lum(b).let { y -> (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) } }

    /** [node]'s inner pixels (3 px in, clear of the border): the face is the commonest colour, the glyph the farthest from it. */
    private fun measure(node: SemanticsNodeInteraction, expectedFace: Color?): Triple<Color, Color, Double> {
        val b = node.fetchSemanticsNode().boundsInRoot
        val m = rule.onRoot().captureToImage().toPixelMap()
        val inset = 4
        val counts = HashMap<Int, Int>()
        val x0 = b.left.toInt() + inset; val x1 = b.right.toInt() - inset
        val y0 = b.top.toInt() + inset; val y1 = b.bottom.toInt() - inset
        for (y in y0 until y1) for (x in x0 until x1) counts.merge(m[x, y].toArgb(), 1, Int::plus)
        val face = Color(counts.maxByOrNull { it.value }!!.key)
        if (expectedFace != null) assertEquals("face is the token's colour", expectedFace.toArgb(), face.toArgb())
        var best = face
        var bestRatio = 1.0
        for (y in y0 until y1) for (x in x0 until x1) ratio(m[x, y], face).let { if (it > bestRatio) { bestRatio = it; best = m[x, y] } }
        return Triple(face, best, bestRatio)
    }

    private fun report(name: String, skin: TetherSkin, r: Triple<Color, Color, Double>) {
        println("CONTRAST $name ${skin.id} face=#%06X glyph=#%06X ratio=%.2f".format(r.first.toArgb() and 0xFFFFFF, r.second.toArgb() and 0xFFFFFF, r.third))
        assertTrue("$name ($skin) glyph vs face ${r.third}", r.third >= 4.5)
    }

    private fun tokens(skin: TetherSkin, state: com.tether.app.protocol.overview.OverviewClientState): TetherTokens {
        var t: TetherTokens? = null
        rule.setContent {
            TetherTheme(choiceFor(skin)) { t = LocalTetherTokens.current }
            OverviewUnderTest(skin, state)
        }
        rule.waitForIdle()
        return t!!
    }

    private fun emerald(skin: TetherSkin) {
        val t = tokens(skin, OverviewFixtures.clear)
        // the strip's TEXT nodes only (not the whole strip, whose icon would satisfy the check on its own).
        report("all-clear strip sentence", skin, measure(rule.onNodeWithText("Nothing is waiting for you.", useUnmergedTree = true), t.css.runBg))
        report("all-clear strip label", skin, measure(rule.onNodeWithText("All clear", useUnmergedTree = true), t.css.runBg))
    }

    private fun amber(skin: TetherSkin) {
        val t = tokens(skin, OverviewFixtures.populated)
        // the attention banner's own text: the "Approval" kind label on its face.
        val kind = rule.onNodeWithText("Approval", useUnmergedTree = true)
        report("amber banner kind label", skin, measure(kind, t.attentionBg))
    }

    private fun chipLabel(tab: StatusTab): SemanticsNodeInteraction =
        rule.onNode(hasText(tab.label, substring = true) and hasAnyAncestor(hasTestTag(OverviewTags.tab(tab))), useUnmergedTree = true)

    private fun chips(skin: TetherSkin) {
        // populatedCapped: E8 hides a waiting card the banner lists in full, so the kept (capped) waiting card keeps its chip in view.
        val t = tokens(skin, OverviewFixtures.populatedCapped)
        // the chip's label TEXT node (not the whole chip, whose 1dp outline would satisfy the check on its own).
        report("selected status chip", skin, measure(chipLabel(StatusTab.Active), t.violetWash))
        report("idle status chip", skin, measure(chipLabel(StatusTab.Waiting), t.graphite))
        // every card's status chip (running, waiting, attention tones) against its own wash.
        val all = rule.onAllNodesWithTag(OverviewTags.StatusChip, useUnmergedTree = true)
        val n = all.fetchSemanticsNodes().size
        assertTrue("card status chips drawn: $n", n >= 4)
        for (i in 0 until n) {
            rule.onAllNodesWithTag(OverviewTags.StatusChip, useUnmergedTree = true)[i].performScrollTo()
            report("card status chip #$i", skin, measure(rule.onAllNodesWithTag(OverviewTags.StatusChip, useUnmergedTree = true)[i], null))
        }
    }

    @Test fun lightEmeraldStripText() = emerald(TetherSkin.Studio)
    @Test fun darkEmeraldStripText() = emerald(TetherSkin.StudioDark)
    @Test fun lightAmberBannerText() = amber(TetherSkin.Studio)
    @Test fun darkAmberBannerText() = amber(TetherSkin.StudioDark)
    @Test fun lightChipText() = chips(TetherSkin.Studio)
    @Test fun darkChipText() = chips(TetherSkin.StudioDark)
}
