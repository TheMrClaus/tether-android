package com.tether.app.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.tether.app.ui.chat.BrowserPaneTags
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
 * The open browser pane on wide windows (globals.css `.is-browser-open`, from 80rem): a column of the
 * shell under the top bar, 520dp, giving back to 480dp so the chat column stays at 480dp or more; from
 * 100rem right of the inspector. Below 80rem the overlay is unchanged. The bar measures the workspace
 * (chat) column and the pane.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class BrowserDockGeometryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val harness = DockHarness()

    private fun show(store: PanelStore = PanelStore(), open: Boolean = true) {
        harness.open = open
        rule.setContent {
            ExpandedShellUnderTest(TetherSkin.StudioDark, PhoneShellState(), ExpandedFixtures.idle, store, slots = dockSlots(harness))
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun dp(px: Float) = with(rule.density) { px.toDp().value }
    private fun docked() = rule.onAllNodesWithTagCount(ShellTags.BrowserDock) > 0

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    @Config(qualifiers = "w1279dp-h800dp-mdpi")
    fun at1279ThePaneStaysTheOverlayAndTheChatKeepsItsColumn() {
        show()
        assertFalse(docked())
        assertEquals("workspace (1279 - rail 272 - nothing docked)", 1007f, dp(bounds(ShellTags.Workspace).width), 1f)
        assertEquals("overlay pane is the 520 panel less its 1dp line", 519f, dp(bounds(BrowserPaneTags.Pane).width), 1f)
        assertEquals("overlay reaches the window's right edge", 1279f, dp(bounds(BrowserPaneTags.Pane).right), 1f)
    }

    @Test
    fun at1280ThePaneIsAColumnUnderTheTopBarAndTheChatIs488() {
        show()
        assertTrue(docked())
        val dock = bounds(ShellTags.BrowserDock)
        assertEquals("pane x", 760f, dp(dock.left), 1f)
        assertEquals("pane width", 520f, dp(dock.width), 1f)
        assertEquals("pane under the top bar", dp(bounds(ShellTags.Topbar).bottom), dp(dock.top), 1f)
        assertEquals("pane to the window's bottom", 800f, dp(dock.bottom), 1f)
        assertEquals("chat column", 488f, dp(bounds(ShellTags.Workspace).width), 1f)
        assertEquals("the pane touches the chat column", dp(bounds(ShellTags.Workspace).right), dp(dock.left), 1f)
    }

    @Test
    @Config(qualifiers = "w1599dp-h800dp-mdpi")
    fun at1599ThereIsNoInspectorAndTheChatIs807() {
        show()
        assertEquals("rail 320 + chat + pane 520", 759f, dp(bounds(ShellTags.Workspace).width), 1f)
        assertEquals(520f, dp(bounds(ShellTags.BrowserDock).width), 1f)
        assertEquals(0, rule.onAllNodesWithTagCount(ShellTags.InspectorColumn))
    }

    @Test
    @Config(qualifiers = "w1600dp-h800dp-mdpi")
    fun at1600ThePaneIsRightOfTheInspectorAndGivesBackToKeepTheChatAt480() {
        show()
        val inspector = bounds(ShellTags.InspectorColumn)
        val dock = bounds(ShellTags.BrowserDock)
        assertEquals("chat column", 480f, dp(bounds(ShellTags.Workspace).width), 1f)
        assertEquals("pane gives back from 520", 512f, dp(dock.width), 1f)
        assertEquals("order: workspace | inspector | pane", dp(inspector.right), dp(dock.left), 1f)
        assertTrue(dp(bounds(ShellTags.Workspace).right) <= dp(inspector.left) + 1f)
        assertEquals("pane to the window's right edge", 1600f, dp(dock.right), 1f)
        assertEquals("under the top bar", dp(bounds(ShellTags.Topbar).bottom), dp(dock.top), 1f)
    }

    @Test
    @Config(qualifiers = "w1920dp-h1080dp-mdpi")
    fun at1920TheInspectorIsLeftOfThePane() {
        show()
        assertEquals(520f, dp(bounds(ShellTags.BrowserDock).width), 1f)
        assertEquals("chat column", 792f, dp(bounds(ShellTags.Workspace).width), 1f)
        assertEquals(dp(bounds(ShellTags.InspectorColumn).right), dp(bounds(ShellTags.BrowserDock).left), 1f)
    }

    @Test
    fun theCollapsedRailGivesTheChatItsWidthBack() {
        show(PanelStore(PanelPrefs(sidebarCollapsed = true)))
        assertEquals("1280 - pane 520", 760f, dp(bounds(ShellTags.Workspace).width), 1f)
    }

    @Test
    fun theGeometryIsOneFunction() {
        assertEquals(520, BrowserDockGeometry.width(1280, 272, 0))
        assertEquals(512, BrowserDockGeometry.width(1600, 320, 288))
        assertEquals(480, BrowserDockGeometry.width(1280, 400, 0))
        assertFalse(BrowserDockGeometry.docks(1279))
        assertTrue(BrowserDockGeometry.docks(1280))
    }

    @Test
    fun openingAndClosingKeepsTheReadingPositionAndTheFocusAndMovesNothingElse() {
        show(open = false)
        rule.onNodeWithTag(DockChatField).performClick()
        rule.onNodeWithTag(DockChatField).assertIsFocused()
        rule.runOnUiThread { kotlinx.coroutines.runBlocking { harness.list!!.scrollToItem(60, 17) } }
        rule.waitForIdle()
        val before = harness.list!!.firstVisibleItemIndex to harness.list!!.firstVisibleItemScrollOffset

        rule.runOnIdle { harness.open = true }
        rule.waitForIdle()
        assertTrue(docked())
        assertEquals("reading position while open", before, harness.list!!.firstVisibleItemIndex to harness.list!!.firstVisibleItemScrollOffset)
        rule.onNodeWithTag(DockChatField).assertIsFocused()

        rule.runOnIdle { harness.open = false }
        rule.waitForIdle()
        assertFalse(docked())
        assertEquals("reading position after close", before, harness.list!!.firstVisibleItemIndex to harness.list!!.firstVisibleItemScrollOffset)
        rule.onNodeWithTag(DockChatField).assertIsFocused()
        assertEquals("chat back to 488+520 = 1008", 1008f, dp(bounds(ShellTags.Workspace).width), 1f)
    }
}
