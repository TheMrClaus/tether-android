package com.tether.app.ui.sidebar

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W53 (ta-hkk7.8): the tablet rail is the phone's list inside the web's SB rail chrome. The chrome's thresholds are pure
 * ([RailGeometry]); the host tests render the rail at the widths the shell gives it (272 below 1440 dp, 320 from there).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h900dp-320dpi")
class TabletRailTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures

    private fun show(
        state: SidebarState = F.state(F.statusSessions, histories = F.statusHistories, activeId = "a1"),
        railWidth: Dp = 272.dp,
        actions: SidebarActions = SidebarActions(),
        seed: SidebarUiSeed = SidebarUiSeed(),
    ) {
        // Unspecified turns Material's 48 dp inflation off, so a control reaches the floor only by its own size.
        rule.setContent {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                SidebarUnderTest(TetherSkin.StudioDark, state, TetherLayoutClass.Expanded, seed, actions, railWidth)
            }
        }
        rule.waitForIdle()
    }

    // ---- pure thresholds ---------------------------------------------------------------------------------------

    @Test fun theLongScheduledLabelIsMeasuredOnTheContentBox() {
        assertEquals("Scheduled", RailGeometry.scheduledLabel(291f))
        assertEquals("Scheduled actions", RailGeometry.scheduledLabel(292f))
        // The rail pads 14 + 14: the long label from a rail of 320.
        assertEquals("Scheduled", RailGeometry.scheduledLabelForRail(319f))
        assertEquals("Scheduled actions", RailGeometry.scheduledLabelForRail(320f))
    }

    // ---- the rail host: label, chip, legend -------------------------------------------------------------------------

    private fun scheduledLabelShown(): String =
        rule.onNodeWithTag(RailTags.ScheduledLabel, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)!!.joinToString("") { it.text }

    @Test fun theScheduledLabelIsShortAt319AndLongAt320() {
        show(railWidth = 319.dp)
        assertEquals("Scheduled", scheduledLabelShown())
    }

    @Test fun theScheduledLabelIsLongAt320() {
        show(railWidth = 320.dp)
        assertEquals("Scheduled actions", scheduledLabelShown())
    }

    @Test fun theScheduledLabelIsShortAtTheDefault272() {
        show(railWidth = 272.dp)
        assertEquals("Scheduled", scheduledLabelShown())
    }

    private fun assertNoChip(w: Dp) {
        show(F.state(F.statusSessions), railWidth = w)
        val key = rule.onNodeWithTag(SidebarTags.NewSession, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val chips = rule.onAllNodesWithText("N", useUnmergedTree = true).fetchSemanticsNodes().filter { key.contains(it.boundsInRoot.center) }
        assertTrue("a chip \"N\" inside New session at $w", chips.isEmpty())
        rule.onNodeWithText("New session", useUnmergedTree = true).assertExists()
    }

    @Test fun noKbdChipOnNewSessionAt272() = assertNoChip(272.dp)
    @Test fun noKbdChipOnNewSessionAt320() = assertNoChip(320.dp)
    @Test fun noKbdChipOnNewSessionAt400() = assertNoChip(400.dp)

    private val legend = SemanticsMatcher("the Workspaces legend") {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith("Workspaces,") } == true
    }
    private fun legendBounds() = rule.onAllNodes(legend, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
    private fun togglesBounds() = rule.onNodeWithTag(RailTags.Toggles, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test fun theFiveTogglesWrapUnderTheCountAt272() {
        show(F.state(F.statusSessions), railWidth = 272.dp)
        val count = legendBounds()
        assertTrue("toggles ${togglesBounds()} sit under the count $count", togglesBounds().top + 0.5f >= count.bottom)
        assertEquals("five 48 dp toggles", with(rule.density) { 240.dp.toPx() }, togglesBounds().width, 1f)
    }

    @Test fun theFiveTogglesShareTheCountsLineAt320() {
        show(F.state(F.statusSessions), railWidth = 320.dp)
        val count = legendBounds()
        assertTrue("toggles ${togglesBounds()} share the line of $count", togglesBounds().top < count.bottom)
    }

    // ---- the touch floor (48) on the TABLET layout -----------------------------------------------------------------

    private fun assertEveryControlIs48(label: String) {
        val floor = with(rule.density) { 48.dp.toPx() } // the owner-directed 48 dp literal, not the constant under test
        val nodes = rule.onAllNodes(hasClickAction().or(hasSetTextAction()), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$label: the rail has controls (${nodes.size})", nodes.size > 20)
        val small = nodes.filter { n -> n.size.width + 0.5f < floor || n.size.height + 0.5f < floor }
        assertTrue(
            "$label: controls under 48dp: " + small.map { it.config.getOrNull(SemanticsProperties.ContentDescription) to it.size },
            small.isEmpty(),
        )
    }

    @Test fun everyControlOnTheTabletRailIs48dp() {
        show(F.state(F.statusSessions, histories = F.statusHistories, activeId = "a1"))
        assertEveryControlIs48("status board")
    }

    @Test fun everyControlOfTheTabletChainAndGroupsIs48dp() {
        show(F.state(F.chainSessions, current = F.APP, activeId = "c3"), seed = SidebarUiSeed(openChains = setOf("live:c0")))
        assertEveryControlIs48("open chain")
    }

    @Test fun theKebabAndItsMenuItemsOnTheTabletAre48dp() {
        show()
        rule.onNodeWithTag(SidebarTags.kebab("live:a1")).performClick()
        rule.waitForIdle()
        val floor = with(rule.density) { 48.dp.toPx() }
        val items = com.tether.app.protocol.helpers.SidebarRowModel.RowMenuItemId.entries
            .flatMap { rule.onAllNodesWithTag(SidebarTags.menuItem(it), useUnmergedTree = true).fetchSemanticsNodes() }
        assertTrue("the menu has items", items.size >= 3)
        assertTrue("menu items under 48dp: " + items.map { it.size }, items.none { it.size.height + 0.5f < floor })
        assertTrue("the kebab is 48", rule.onNodeWithTag(SidebarTags.kebab("live:a1")).fetchSemanticsNode().size.let { it.width + 0.5f >= floor && it.height + 0.5f >= floor })
    }

    // ---- no swipe on the tablet (the web's isMobile is off at 48rem) ------------------------------------------------

    @Test fun aHorizontalSwipeOnATabletRowDoesNotArchiveIt() {
        val ended = mutableListOf<String>()
        show(actions = SidebarActions(onEndSession = { id, _ -> ended += id }))
        rule.onNodeWithTag(SidebarTags.row("live:a1")).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertTrue("nothing ended", ended.isEmpty())
        assertTrue("no ARCHIVE action behind the row", rule.onAllNodesWithTag(SidebarTags.archive("live:a1"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun theTabletRowHasNoEndXAndOneKebab() {
        show()
        assertTrue(rule.onAllNodesWithTag(SidebarTags.end("live:a1"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag(SidebarTags.kebab("live:a1")).assertExists()
    }
}
