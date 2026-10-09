package com.tether.app.ui.sidebar

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-1jj7 (owner-directed design): the phone drawer's one touch floor is 48 dp. Every control's LAYOUT
 * bounds (not the larger touch-slop area Compose adds) are at least 48 x 48, and consecutive rows are at
 * least 48 dp apart, so no two rows' targets overlap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class PhoneDrawerTouchFloorTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures

    private fun show(state: SidebarState) {
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, state) }
        rule.waitForIdle()
    }

    private fun assertEveryControlIs48(state: SidebarState) {
        show(state)
        val floor = with(rule.density) { PhoneDrawer.Floor.toPx() }
        val nodes = rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("the drawer has controls", nodes.size > 20)
        val small = nodes.filter { n -> n.boundsInRoot.width + 0.5f < floor || n.boundsInRoot.height + 0.5f < floor }
        assertTrue(
            "controls under 48dp: " + small.map { it.config.getOrNull(SemanticsProperties.ContentDescription) to it.boundsInRoot },
            small.isEmpty(),
        )
    }

    @Test fun everyControlOnTheDrawerListIs48dp() = assertEveryControlIs48(F.state(F.drawerSessions))

    @Test fun everyControlOnTheStatusBoardIs48dp() = assertEveryControlIs48(F.state(F.statusSessions, histories = F.statusHistories, activeId = "a1"))

    @Test fun everyControlOfSeveralBlocksIs48dp() =
        assertEveryControlIs48(F.state(F.groupSessions, pinned = listOf(F.DOCS, F.APP), current = F.APP, activeId = "g3", collapsed = listOf(F.DOCS)))

    @Test fun consecutiveRowsAreAtLeast48dpApart() {
        show(F.state(F.drawerSessions))
        val floor = with(rule.density) { PhoneDrawer.Floor.toPx() }
        val tops = rule.rowNodes().map { it.boundsInRoot.top }.sorted()
        assertTrue("several rows are drawn: $tops", tops.size >= 6)
        tops.zipWithNext().forEach { (a, b) -> assertTrue("rows $a and $b are under 48dp apart", b - a + 0.5f >= floor) }
        assertTrue(48.dp.value == PhoneDrawer.Floor.value)
    }
}
