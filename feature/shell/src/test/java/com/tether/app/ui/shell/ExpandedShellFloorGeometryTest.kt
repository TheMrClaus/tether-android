package com.tether.app.ui.shell

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import com.tether.app.ui.components.TetherLayoutClass
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-09ca (W20): the Expanded shell at the web's 48rem floor, 768 x 1024, against the web at tether 29537e0 (CSS px, 1 dp
 * each; the numbers are the L1 spec section 2 captures of 768x1024): topbar 64, the rail 272 static, its resize handle 16
 * wide centred on the rail edge (x 264-280), the chat column 496, the workspace header 80; collapsed, the column is the
 * whole 768 and the dock is 44x44, 12 from the left and the bottom. The composer (207.58, pill row 36) and the timeline
 * rail on the right are the chat module's, in MidBandComposerRowsTest and MidBandEdgesTest at 768.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w768dp-h1024dp-mdpi")
@OptIn(ExperimentalTestApi::class)
class ExpandedShellFloorGeometryTest : ExpandedBehaviourBase() {
    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test fun at768TheShellIsTheDesktopGridWithTheWebsColumns() {
        assertEquals(TetherLayoutClass.Expanded, shellLayoutFor(768))
        show()
        assertEquals("topbar", 64f, bounds(ShellTags.Topbar).height, 1f)
        // The header's tag sits inside its own padding, so its height is measured by where the stage below it begins.
        assertEquals("workspace header (stage top - topbar)", 80f, bounds(ShellTags.Stage).top - bounds(ShellTags.Topbar).height, 1f)
        assertEquals("rail", 272f, widthDp(ShellTags.Sidebar), 1f)
        assertEquals("chat column (768 - 272)", 496f, widthDp(ShellTags.Workspace), 1f)
        val handle = bounds(ShellTags.RailHandle)
        assertEquals("handle centre is the rail edge", 272f, (handle.left + handle.right) / 2f, 1f)
        assertEquals("handle is 16 wide (x 264-280)", 16f, handle.width, 1f)
    }

    @Test fun theRailsStoredWidthIsClampedToTheFloorAndTheCeilingAt768() {
        assertEquals(272, PanelWidthGeometry.clamp(PanelKind.Rail, 272.0, 768))
        assertEquals("a stored 400 meets min(30rem, 40vw) = 307", 307, PanelWidthGeometry.clamp(PanelKind.Rail, 400.0, 768))
        assertEquals("a stored 100 meets the 14rem floor", 224, PanelWidthGeometry.clamp(PanelKind.Rail, 100.0, 768))
        show(store = PanelStore(PanelPrefs(sidebarWidth = 400)))
        assertEquals(307f, widthDp(ShellTags.Sidebar), 1f)
    }

    @Test fun collapsedTheColumnIsTheWholeWindowAndTheDockIsA44KeyTwelveFromTheCorner() {
        show(store = PanelStore(PanelPrefs(sidebarCollapsed = true)))
        assertEquals("column", 768f, widthDp(ShellTags.Workspace), 1f)
        val dock = bounds(ShellTags.ExpandDock)
        assertEquals("dock width", 44f, dock.width, 1f)
        assertEquals("dock height", 44f, dock.height, 1f)
        assertEquals("12 from the left", 12f, dock.left, 1f)
        assertEquals("12 from the bottom", 12f, 1024f - dock.bottom, 1f)
    }
}
