package com.tether.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tether.app.ui.resolvedWorkspaceLabel
import com.tether.app.ui.prefs.LastOpenedSession
import com.tether.app.ui.prefs.TetherPreferences
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class WorkspaceLabelSelectionTest {
    @Test fun operatorThenRememberedThenDefaultThenRootAndUnresolvedFollowTheSidebarRule() {
        val preferences = TetherPreferences(
            defaultWorkspace = "/srv/default-project",
            lastOpenedSession = LastOpenedSession("/srv/remembered-project", "s1", "h1"),
        )
        assertEquals("operator-project", resolvedWorkspaceLabel("/srv/operator-project", preferences, "/srv/root-project"))
        assertEquals("remembered-project", resolvedWorkspaceLabel(null, preferences, "/srv/root-project"))
        assertEquals("default-project", resolvedWorkspaceLabel(null, preferences.copy(lastOpenedSession = null), "/srv/root-project"))
        assertEquals("root-project", resolvedWorkspaceLabel(null, TetherPreferences.Default, "/srv/root-project"))
        assertEquals("Root", resolvedWorkspaceLabel("/", TetherPreferences.Default, null))
        assertEquals("Workspace", resolvedWorkspaceLabel(null, TetherPreferences.Default, null))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1400dp-h900dp-mdpi")
class TopbarWorkspaceTest {
    @get:Rule val rule = createComposeRule()

    private data class Case(val label: String, val width: Int, val wide: Boolean, val fontScale: Float)
    private var case by mutableStateOf(Case("tether-android", 412, false, 1f))
    private val navigated = mutableListOf<DashboardView>()

    private fun show() {
        rule.setContent {
            val base = LocalDensity.current
            val shown = case
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                CompositionLocalProvider(LocalDensity provides Density(base.density, shown.fontScale)) {
                    Box(Modifier.width(shown.width.dp).fillMaxHeight(), contentAlignment = Alignment.TopStart) {
                        TetherTopbar(
                            actions = TopbarActions(
                                onOpenDrawer = {},
                                onLogout = {},
                                onOpenSettings = {},
                                onNavigate = { navigated += it },
                            ),
                            state = TopbarState(
                                current = TopBarDestination.Sessions,
                                link = LinkReadout.Connected,
                                wide = shown.wide,
                                workspaceLabel = shown.label,
                                drawerKey = !shown.wide,
                                viewportWidth = shown.width,
                            ),
                            onToggleMenu = {},
                        )
                    }
                }
            }
        }
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test fun everyRequiredViewportKeepsA44DpBarAndTheFullWorkspaceOverviewName() {
        show()
        val cases = listOf(
            Case("tether-android", 412, false, 1f),
            Case("an-extremely-long-workspace-name", 412, false, 1.3f),
            Case("Root", 780, true, 1f),
            Case("Workspace", 1280, true, 1f),
        )
        cases.forEach { next ->
            rule.runOnIdle { case = next }
            rule.waitForIdle()
            val bar = bounds(ShellTags.Topbar)
            val brand = bounds(ShellTags.Brand)
            val cap = if (next.wide) 180f else 136f
            assertEquals("${next.width}dp @${next.fontScale} topbar", 44f, bar.height, 0.5f)
            assertTrue("workspace control ${brand.width} in [44, $cap]", brand.width >= 43.5f && brand.width <= cap + 0.5f)
            assertTrue("workspace control is inside the bar", brand.left >= bar.left && brand.right <= bar.right)
            val actionable = buildList {
                add(ShellTags.Brand)
                if (!next.wide) add(ShellTags.MenuKey)
                add(ShellTags.SettingsKey)
                add(ShellTags.ToolsMenuKey)
            }
            actionable.forEach { tag ->
                val touch = rule.onNodeWithTag(tag).fetchSemanticsNode().touchBoundsInRoot
                assertTrue("$tag touch target >= 44dp: ${touch.width} x ${touch.height}", touch.width >= 43.5f && touch.height >= 43.5f)
            }
            rule.onNodeWithTag(ShellTags.Brand)
                .assertContentDescriptionEquals("${next.label} workspace — Overview")
            rule.onNodeWithText(next.label, useUnmergedTree = true).assertExists()
        }
        rule.onNodeWithTag(ShellTags.Brand).performClick()
        assertEquals(listOf(DashboardView.Overview), navigated)
    }
}
