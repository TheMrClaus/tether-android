package com.tether.app.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class PhoneShellBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<String>()

    private fun show(state: PhoneShellState, session: com.tether.app.protocol.model.AgentSession? = ShellFixtures.idle, skin: TetherSkin = TetherSkin.Machine, warnings: Int = 0) {
        rule.setContent { ShellUnderTest(skin, state, session, unseenWarnings = warnings, onEvent = { events += it }) }
    }

    private fun back() {
        rule.waitForIdle() // BackHandler's enabled flag follows the state on the next composition
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
    }

    @Test fun menuOpensTheDrawerAndTheBackdropClosesIt() {
        val state = PhoneShellState()
        show(state)
        rule.onNodeWithContentDescription("Open sessions").performClick()
        assertTrue(state.drawerOpen)
        assertEquals(listOf("drawer"), events)
        rule.onNodeWithTag(DrawerSlotTag).assertExists()
        // The panel covers the backdrop's centre; tap the visible strip at the right edge.
        rule.onNodeWithContentDescription("Close sessions").performTouchInput { click(Offset(width - 10f, centerY)) }
        rule.waitForIdle()
        assertFalse(state.drawerOpen)
        rule.onNodeWithTag(ShellTags.DrawerBackdrop).assertDoesNotExist()
    }

    @Test fun closedDrawerIsHiddenFromTalkBack() {
        show(PhoneShellState())
        rule.onNodeWithTag(DrawerSlotTag).assertDoesNotExist()
    }

    @Test fun backClosesTheDrawer() {
        val state = PhoneShellState()
        show(state)
        rule.onNodeWithTag(ShellTags.MenuKey).performClick()
        back()
        rule.waitForIdle()
        assertFalse(state.drawerOpen)
    }

    @Test fun backWithNothingOpenFallsThrough() {
        val state = PhoneShellState()
        show(state)
        rule.waitForIdle()
        assertFalse(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test fun gaugeTogglesTheTelemetryPanelInPlaceOfTheChat() {
        val state = PhoneShellState()
        show(state)
        rule.onNodeWithTag(ChatSlotTag).assertIsDisplayed()
        rule.onNodeWithTag(ShellTags.TelemetryHandle).performClick()
        assertTrue(state.telemetryOpen)
        rule.onNodeWithTag(ShellTags.TelemetrySheet).assertIsDisplayed()
        rule.onNodeWithText("Session details").assertIsDisplayed()
        rule.onNodeWithTag(InspectorSlotTag).assertExists()
        // The stage stays composed but is not placed: it is neither shown nor announced.
        rule.onNodeWithTag(ChatSlotTag).assertDoesNotExist()
        // The header (and its gauge, the handle) stays reachable: tap again collapses.
        rule.onNodeWithTag(ShellTags.TelemetryHandle).performClick()
        assertFalse(state.telemetryOpen)
        rule.onNodeWithTag(ChatSlotTag).assertIsDisplayed()
    }

    @Test fun panelCloseKeyAndBackCollapseIt() {
        val state = PhoneShellState()
        show(state)
        rule.onNodeWithTag(ShellTags.TelemetryHandle).performClick()
        rule.onNodeWithTag(ShellTags.TelemetryClose).performClick()
        assertFalse(state.telemetryOpen)
        rule.onNodeWithTag(ShellTags.TelemetryHandle).performClick()
        back()
        rule.waitForIdle()
        assertFalse(state.telemetryOpen)
    }

    @Test fun sessionLinksPopoverOpensCopiesAndLightDismisses() {
        val state = PhoneShellState()
        show(state)
        rule.onNodeWithContentDescription("Session links").performClick()
        rule.onNodeWithTag(ShellTags.LinksPopover).assertIsDisplayed()
        rule.onNodeWithContentDescription("Copy this session's Tether id").performClick()
        rule.onNodeWithContentDescription("Copy working directory").performClick()
        rule.onNodeWithContentDescription("Pin session").performClick()
        assertEquals(listOf("copyId", "copyPath", "pin"), events)
        rule.onNodeWithTag(ShellTags.Shell).performTouchInput { click(Offset(20f, centerY)) }
        rule.waitForIdle()
        assertFalse(state.linksOpen)
    }

    @Test fun backClosesThePopoverBeforeThePanel() {
        val state = PhoneShellState(telemetryOpen = true, linksOpen = true)
        show(state)
        back()
        rule.waitForIdle()
        assertFalse(state.linksOpen)
        assertTrue(state.telemetryOpen)
    }

    @Test fun headerActionsReachTheirHosts() {
        show(PhoneShellState())
        rule.onNodeWithContentDescription("Rename session").performClick()
        rule.onNodeWithContentDescription("End session").performClick()
        rule.onNodeWithContentDescription("Lock").performClick()
        rule.onNodeWithContentDescription("Browse workspace files").performClick()
        rule.onNodeWithContentDescription("Account usage").performClick()
        rule.onNodeWithContentDescription("Usage analytics").performClick()
        rule.onNodeWithContentDescription("Health & event log").performClick()
        assertEquals(listOf("rename", "end", "lock", "files", "usage", "analytics", "log"), events)
    }

    @Test fun endSessionIsDisabledOnceExited() {
        show(PhoneShellState(), session = ShellFixtures.idle.copy(status = "exited"))
        rule.onNodeWithContentDescription("End session").assertIsNotEnabled()
    }

    @Test fun filesKeyNeedsASession() {
        show(PhoneShellState(), session = null)
        rule.onNodeWithContentDescription("Browse workspace files").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Account usage").assertIsEnabled()
        rule.onNodeWithTag(ShellTags.WorkspaceHeader).assertDoesNotExist()
        rule.onNodeWithTag(ShellTags.EmptyWorkspace).assertIsDisplayed()
        rule.onNodeWithText("Start where the work lives.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Start first session").performClick()
        assertEquals(listOf("start"), events)
    }

    @Test fun startIsDisabledWhileDisconnected() {
        rule.setContent {
            ShellUnderTest(TetherSkin.Tactile, PhoneShellState(), null, emptyStage = EmptyStage.Welcome(false, ShellFixtures.providers))
        }
        rule.onNodeWithContentDescription("Start first session").assertIsNotEnabled()
    }

    @Test fun reopeningStageNamesTheLinkState() {
        rule.setContent { ShellUnderTest(TetherSkin.Tactile, PhoneShellState(), null, emptyStage = EmptyStage.Reopening(false)) }
        rule.onNodeWithText("Reconnecting.").assertIsDisplayed()
        rule.onNodeWithText("Start first session").assertDoesNotExist()
    }

    @Test fun talkBackNamesAndStatesNeverRelyOnColour() {
        show(PhoneShellState(), warnings = 3)
        rule.onNodeWithContentDescription("Tether").assertExists()
        rule.onNodeWithContentDescription("Console tools").assertExists()
        val log = rule.onNodeWithContentDescription("Health & event log").fetchSemanticsNode()
        assertEquals("3 warnings", log.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription))
        // The status pill prints the word; its name is the word, not the dot.
        rule.onNodeWithContentDescription("Ready").assertExists()
        rule.onNodeWithTag(ShellTags.TelemetryHandle).assertExists()
    }

    @Test fun providerAvailabilityIsSpokenNotJustColoured() {
        show(PhoneShellState(), session = null)
        val acp = rule.onNodeWithContentDescription("ACP").fetchSemanticsNode()
        assertEquals("unavailable", acp.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription))
    }

    /** DESIGN.md 44dp targets: every clickable in the shell chrome takes at least a 44dp touch. */
    @Test fun everyShellControlHasA44dpTouchTarget() {
        show(PhoneShellState())
        assertTouchTargets()
        rule.onNodeWithTag(ShellTags.LinksKey).performClick()
        assertTouchTargets()
    }

    @Test fun emptyStageControlsHave44dpTouchTargets() {
        show(PhoneShellState(), session = null)
        assertTouchTargets()
    }

    @Test fun renamePencilAcceptsATouchOutsideItsDrawnBounds() {
        show(PhoneShellState())
        // The pencil is drawn 24dp (web `.rename-session`); Compose extends its hit area to 48dp.
        rule.onNodeWithTag(ShellTags.RenameKey).performTouchInput {
            click(Offset(centerX, centerY + with(rule.density) { 18.dp.toPx() }))
        }
        assertEquals(listOf("rename"), events)
    }

    private fun assertTouchTargets() {
        val min = with(rule.density) { 44.dp.toPx() } - 0.5f
        val nodes = allClickable(rule.onNodeWithTag(ShellTags.Shell).fetchSemanticsNode())
        assertTrue("found clickables", nodes.size > 5)
        for (node in nodes) {
            val b = node.touchBoundsInRoot
            assertTrue("${describe(node)} touch ${b.width}x${b.height}px < 44dp", b.width >= min && b.height >= min)
        }
    }

    private fun allClickable(root: SemanticsNode): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            if (n.config.getOrNull(SemanticsActions.OnClick) != null && n.boundsInRoot.width > 0f) out += n
            n.children.forEach(::walk)
        }
        walk(root)
        // The full-screen light-dismiss layer and the backdrop are not targets.
        return out.filterNot { it.boundsInRoot.width >= root.boundsInRoot.width - 1f && it.boundsInRoot.height >= root.boundsInRoot.height / 2f }
    }

    private fun describe(n: SemanticsNode): String =
        n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.joinToString() ?: "node ${n.id}"
}

/**
 * Above the 840dp cutoff the expanded layout (T4.2) takes over; until it lands the phone shell
 * must still lay out and work at tablet width.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class PhoneShellExpandedWidthTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun layoutClassIsExpandedAndTheShellStillWorks() {
        assertEquals(com.tether.app.ui.components.TetherLayoutClass.Expanded, shellLayoutFor(1280))
        val state = PhoneShellState()
        rule.setContent { ShellUnderTest(TetherSkin.Precision, state, ShellFixtures.idle) }
        rule.onNodeWithTag(ShellTags.Topbar).assertIsDisplayed()
        rule.onNodeWithTag(ShellTags.MenuKey).performClick()
        assertTrue(state.drawerOpen)
        rule.onNodeWithTag(ShellTags.TelemetryHandle).assertExists()
    }
}
