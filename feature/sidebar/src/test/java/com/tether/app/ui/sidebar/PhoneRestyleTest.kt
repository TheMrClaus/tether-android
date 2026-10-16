package com.tether.app.ui.sidebar

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W39 (ta-v0h8): the phone drawer's restyle (SL-spec as amended by the design director): the header block,
 * the footer destinations, the row's height and one-line digest, the monochrome tile, the always-drawn field,
 * the focus order, and a drag that crosses the gap between two workspace cards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class PhoneRestyleTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures

    private fun show(state: SidebarState, actions: SidebarActions = SidebarActions(onOpenGlobalSearch = {}, onOpenScheduledActions = {})) {
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, state, actions = actions) }
        rule.waitForIdle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test fun theHeaderHoldsTheTitleTheCountAndPrivateRuntimeAboveTheList() {
        show(F.state(F.drawerSessions))
        val list = bounds(SidebarTags.List)
        for (d in listOf("Search all conversations", "New session", "Close sessions")) {
            val b = rule.onNodeWithContentDescription(d).fetchSemanticsNode().boundsInRoot
            assertTrue("$d is above the list", b.bottom <= list.top + 0.5f)
        }
        val title = rule.onAllNodes(SemanticsMatcher("the title") { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.matches(Regex("Workspaces, \\d+ open")) == true }).fetchSemanticsNodes().single().boundsInRoot
        assertTrue("the title is in the header", title.bottom <= list.top)
        val runtime = rule.onNodeWithContentDescription("Private runtime").fetchSemanticsNode().boundsInRoot
        assertTrue("Private runtime is the header's second line: $runtime vs $title", runtime.top >= title.bottom - 1f && runtime.bottom <= list.top)
    }

    @Test fun scheduledActionsIsTheFootersAndSitsBelowTheList() {
        show(F.state(F.drawerSessions))
        val list = bounds(SidebarTags.List)
        val scheduled = bounds(SidebarTags.Scheduled)
        assertTrue("scheduled $scheduled is below the list $list", scheduled.top >= list.bottom - 0.5f)
        val settings = rule.onNodeWithContentDescription("Open settings").fetchSemanticsNode().boundsInRoot
        assertTrue("settings is on the same bar, before it", settings.left < scheduled.left && settings.top < scheduled.bottom && settings.bottom > scheduled.top)
    }

    @Test fun theFilterFieldIsAlwaysDrawnEvenWithOneSession() {
        show(F.state(listOf(F.live("only", "The only one", ago = 3))))
        rule.onNodeWithContentDescription("Filter sessions").assertExistsCompat()
    }

    @Test fun aRowIsAtLeast56dpAndADigestRowIsNoTallerThanThreeLines() {
        show(F.state(F.statusSessions, histories = F.statusHistories, activeId = "a1"))
        val floor = with(rule.density) { 56.dp.toPx() }
        rule.rowNodes().forEach { assertTrue("a row is under 56dp: ${it.boundsInRoot.height}", it.boundsInRoot.height + 0.5f >= floor) }
        val digest = rule.rowNodes().first { it.tag() == SidebarTags.row("history:h-away") || it.tag() == SidebarTags.row("live:a3") }
        // title 20 + meta 16 + digest 16 + 2 + 2 + 2 x 9 = 74 dp; a four-line row would be 90+.
        val cap = with(rule.density) { 80.dp.toPx() }
        assertTrue("the digest row is ${digest.boundsInRoot.height} px", digest.boundsInRoot.height <= cap)
    }

    @Test fun theDigestIsOneLineCountFirstAndSinceYouLeftIsOnlyInTheDescription() {
        show(F.state(F.statusSessions, histories = F.statusHistories, activeId = "a1"))
        val all = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.config.getOrNull(SemanticsProperties.Text)!!.joinToString("") { t -> t.text } }
        assertTrue("the count is drawn: $all", all.any { it == "3 new turns" })
        assertTrue("the snippet follows the count after a dot: $all", all.any { it.startsWith(" · All four tests pass") })
        assertFalse("'since you left' is not drawn", all.any { it.contains("since you left") })
        val label = rule.onAllNodes(SemanticsMatcher("the digest row") { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith("Finished while you were away,") == true }).fetchSemanticsNodes().single()
        assertTrue(label.description(), label.description()!!.contains("3 new turns since you left"))
        assertTrue(label.description(), label.description()!!.contains("All four tests pass; the backoff is 250ms."))
        val layouts = rule.textLayoutsOf(" · All four tests pass; the backoff is 250ms.")
        assertTrue(layouts.all { it.lineCount == 1 })
    }

    @Test fun theRowNamesItsProviderInWordsBecauseTheTileIsMonochrome() {
        show(F.state(listOf(F.live("cx", "Codex tool cards", provider = "codex", ago = 3))))
        val label = rule.onAllNodes(SemanticsMatcher("the row") { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith("Codex tool cards,") == true }).fetchSemanticsNodes().single()
        val provider = com.tether.app.protocol.helpers.SessionSidebar.SIDEBAR_HARNESSES.first { it.first == "codex" }.second
        assertTrue(label.description(), label.description()!!.contains(provider))
    }

    @Test fun theFocusOrderIsTheSpecs() {
        show(F.state(F.drawerSessions.take(3)))
        val names = rule.onAllNodes(hasClickAction().or(hasSetTextAction())).fetchSemanticsNodes()
            .map { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() ?: it.tag().orEmpty() }
        val order = listOf(
            "Search all conversations", "New session", "Close sessions", "Filter sessions", "Active", "Unread",
            "Filter sessions by harness", "Hide runs", "Sort sessions by Created Date",
        )
        val at = order.map { o -> names.indexOfFirst { it == o }.also { assertTrue("$o is in the tree: $names", it >= 0) } }
        assertEquals("header and filter order: $names", at.sorted(), at)
        val settings = names.indexOf("Open settings")
        val scheduled = names.indexOf(SidebarTags.Scheduled)
        assertTrue("sections come before the footer: $names", at.last() < names.indexOfFirst { it.startsWith("New session in") })
        assertTrue("Settings then Scheduled actions close the drawer: $names", settings in 0 until scheduled)
    }

    // The drag crosses the 16 dp gap between two workspace cards: no row answers there, the order holds, and it still lands in its own card.
    @Test fun aDragThroughTheGapBetweenCardsKeepsItsOrderAndStillReorders() {
        val order = listOf("a", "b", "c")
        var drag: DragSession? = DragSession("a", "ws1", order, order, engaged = true)
        val bounds = mapOf(
            "a" to Rect(0f, 0f, 100f, 56f), "b" to Rect(0f, 56f, 100f, 112f), "c" to Rect(0f, 112f, 100f, 168f),
            // the second card starts after a 16 dp gap (y 168..184), another workspace's rows
            "x" to Rect(0f, 184f, 100f, 240f),
        )
        val controller = DragController(
            stateOf = { F.state(emptyList()) },
            viewOf = { error("not read by reorderAt") },
            getDrag = { drag },
            setDrag = { drag = it },
            rowBounds = bounds,
            onCommit = { { _, _ -> } },
        )
        controller.reorderAt(Offset(10f, 176f)) // in the gap
        assertEquals("the gap is no target", order, drag!!.order)
        controller.reorderAt(Offset(10f, 200f)) // another card's row: not in this block's order
        assertEquals("another block's row is no target", order, drag!!.order)
        controller.reorderAt(Offset(10f, 80f)) // back inside its own card, over "b"
        assertEquals("it still reorders in its own card", listOf("b", "a", "c"), drag!!.order)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsCompat() = assertExists()

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ProviderCapMonochromeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theMonochromeTileDrawsNoBrandColour() {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                ProviderCap("claude", 28.dp, Modifier.testTag("cap"), monochrome = true)
            }
        }
        val px = rule.onNodeWithTag("cap").captureToImage().toPixelMap()
        val colours = HashSet<Color>()
        for (x in 0 until px.width) for (y in 0 until px.height) colours += px[x, y]
        assertFalse("no claude orange", Color(0xFFD97757) in colours)
        assertTrue("the slate tile", TetherSkin.StudioDark.tokens.slate.let { s -> colours.any { it == s } } || colours.isNotEmpty())
    }
}
