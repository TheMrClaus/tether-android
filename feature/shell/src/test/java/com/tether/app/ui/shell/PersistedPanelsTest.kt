package com.tether.app.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherSkin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The save path MainShell uses ([rememberPersistedPanels] over the real [UiPrefs] DataStore): a
 * settled resize or an expand is written to the store, and a NEW composition reads it back.
 */
abstract class PersistedPanelsBase {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    protected lateinit var prefs: UiPrefs
    private var generation by mutableIntStateOf(0)

    @Before fun resetStore() {
        prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        runBlocking { prefs.updatePreferences { PanelPrefs().applyTo(it) } }
    }

    protected fun seed(panels: PanelPrefs) = runBlocking { prefs.updatePreferences { panels.applyTo(it) } }

    protected fun stored(): PanelPrefs = runBlocking { PanelPrefs.from(prefs.preferences.first()) }

    protected fun awaitStored(expected: PanelPrefs) {
        rule.waitUntil(5_000) { stored() == expected }
        assertEquals(expected, stored())
    }

    protected fun show() {
        rule.setContent {
            key(generation) {
                ExpandedShellUnderTest(TetherSkin.StudioDark, PhoneShellState(), ExpandedFixtures.idle, persisted = rememberPersistedPanels(prefs))
            }
        }
    }

    /** Throws the shell's composition away and builds a fresh one (every remember reset). */
    protected fun recompose() {
        rule.runOnUiThread { generation++ }
        rule.waitForIdle()
    }

    protected fun awaitWidth(tag: String, dp: Float) {
        rule.waitUntil(5_000) { widthDp(tag) == dp }
    }

    protected fun widthDp(tag: String): Float = with(rule.density) {
        rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.width.toDp().value
    }

    protected fun dpPx(dp: Float): Float = with(rule.density) { dp.dp.toPx() }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class PersistedPanelsTest : PersistedPanelsBase() {

    @Test fun aRailResizeIsSavedAndRestoredInANewComposition() {
        show()
        awaitWidth(ShellTags.Sidebar, 272f)
        rule.onNodeWithTag(ShellTags.RailHandle).performTouchInput {
            down(center)
            moveBy(Offset(dpPx(60f), 0f))
            up()
        }
        awaitStored(PanelPrefs(sidebarWidth = 332))
        recompose()
        awaitWidth(ShellTags.Sidebar, 332f)
    }

    @Test fun aResetRemovesTheSavedWidth() {
        seed(PanelPrefs(sidebarWidth = 360))
        show()
        awaitWidth(ShellTags.Sidebar, 360f)
        rule.onNodeWithTag(ShellTags.RailHandle).performTouchInput { doubleClick(center) }
        awaitStored(PanelPrefs())
        recompose()
        awaitWidth(ShellTags.Sidebar, 272f)
    }

    @Test fun expandingTheCollapsedRailIsSaved() {
        seed(PanelPrefs(sidebarWidth = 300, sidebarCollapsed = true))
        show()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists(ShellTags.ExpandDock) }
        rule.onNodeWithContentDescription("Expand sidebar").performClick()
        awaitStored(PanelPrefs(sidebarWidth = 300, sidebarCollapsed = false))
        recompose()
        awaitWidth(ShellTags.Sidebar, 300f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1680dp-h1050dp-mdpi")
class PersistedPanelsColumnTest : PersistedPanelsBase() {

    @Test fun anInspectorResizeIsSavedAndRestoredInANewComposition() {
        show()
        awaitWidth(ShellTags.InspectorColumn, 288f)
        rule.onNodeWithTag(ShellTags.InspectorHandle).performTouchInput {
            down(center)
            moveBy(Offset(-dpPx(48f), 0f))
            up()
        }
        awaitStored(PanelPrefs(inspectorWidth = 336))
        recompose()
        awaitWidth(ShellTags.InspectorColumn, 336f)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagExists(tag: String): Boolean =
    onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
