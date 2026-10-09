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
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    private fun show(state: PhoneShellState, session: com.tether.app.protocol.model.AgentSession? = ShellFixtures.idle, skin: TetherSkin = TetherSkin.StudioDark, warnings: Int = 0) {
        rule.setContent { ShellUnderTest(skin, state, session, unseenWarnings = warnings, onEvent = { events += it }) }
    }

    private fun back() {
        rule.waitForIdle() // BackHandler's enabled flag follows the state on the next composition
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
    }

    /** The drawer host's open flag: the positive signal (a missing node could only say "not drawn"). */
    private fun drawerFlag(): Boolean? = rule.onNodeWithTag(ShellTags.DrawerHost).fetchSemanticsNode().config.getOrNull(DrawerOpenKey)

    @Test fun menuOpensTheDrawerAndCloseHidesIt() {
        val state = PhoneShellState()
        show(state)
        assertEquals("closed at first", false, drawerFlag())
        rule.onNodeWithContentDescription("Open sessions").performClick()
        assertTrue(state.drawerOpen)
        assertEquals(listOf("drawer"), events)
        assertEquals("open", true, drawerFlag())
        rule.onNodeWithTag(DrawerSlotTag).assertExists()
        // ta-1jj7 (owner-directed design): no backdrop is left; the drawer's own close key, Back and a
        // selection close it (the slot here is a placeholder, so the state's close stands for the key).
        rule.runOnUiThread { state.closeDrawer() }
        rule.waitForIdle()
        assertFalse(state.drawerOpen)
        assertEquals("closed again", false, drawerFlag())
        rule.onNodeWithTag(DrawerSlotTag).assertDoesNotExist()
    }

    /** ta-1jj7: the open panel is opaque and full-window, so the topbar and the chat behind it leave the accessibility tree. */
    @Test fun openDrawerHidesTheShellFromTalkBack() {
        val state = PhoneShellState()
        show(state)
        rule.onNodeWithTag(ShellTags.MenuKey).assertExists()
        rule.onNodeWithTag(ShellTags.Stage).assertExists()
        rule.onNodeWithTag(ShellTags.MenuKey).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DrawerSlotTag).assertExists()
        rule.onNodeWithTag(ShellTags.MenuKey).assertDoesNotExist()
        rule.onNodeWithTag(ShellTags.Stage).assertDoesNotExist()
        rule.runOnUiThread { state.closeDrawer() }
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.MenuKey).assertExists()
        rule.onNodeWithTag(ShellTags.Stage).assertExists()
    }

    /** ta-1jj7: the open panel spans the window's width (it was the web's `min(21rem, 92vw)`). */
    @Test fun theOpenPanelFillsTheWindow() {
        val state = PhoneShellState(drawerOpen = true)
        show(state)
        rule.waitForIdle()
        val root = rule.onRoot().getBoundsInRoot()
        val panel = rule.onNodeWithTag(ShellTags.Drawer).getBoundsInRoot()
        assertEquals((root.right - root.left).value, (panel.right - panel.left).value, 0.5f)
        assertEquals(0f, panel.left.value, 0.5f)
    }

    @Test fun closedDrawerIsHiddenFromTalkBack() {
        show(PhoneShellState())
        rule.onNodeWithTag(DrawerSlotTag).assertDoesNotExist()
    }

    @Test fun backClosesTheDrawer() {
        val state = PhoneShellState()
        show(state)
        rule.onNodeWithTag(ShellTags.MenuKey).performClick()
        assertEquals(true, drawerFlag())
        back()
        rule.waitForIdle()
        assertFalse(state.drawerOpen)
        assertEquals("the host says closed", false, drawerFlag())
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

    /** T4.1 verifier follow-up: the popover (top layer) closes before the drawer, through BackHandler. */
    @Test fun backClosesThePopoverBeforeTheDrawer() {
        val state = PhoneShellState(drawerOpen = true, linksOpen = true)
        show(state)
        rule.onNodeWithTag(ShellTags.LinksPopover).assertIsDisplayed()
        back()
        rule.waitForIdle()
        assertFalse(state.linksOpen)
        assertTrue(state.drawerOpen)
        rule.onNodeWithTag(ShellTags.LinksPopover).assertDoesNotExist()
        rule.onNodeWithTag(DrawerSlotTag).assertIsDisplayed()
        back()
        rule.waitForIdle()
        assertFalse(state.drawerOpen)
        rule.onNodeWithTag(DrawerSlotTag).assertDoesNotExist()
        assertFalse(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test fun headerActionsReachTheirHosts() {
        val state = PhoneShellState()
        show(state)
        rule.onNodeWithContentDescription("Rename session").performClick()
        rule.onNodeWithContentDescription("End session").performClick()
        rule.onNodeWithContentDescription("Settings").performClick()
        // T15.4: the phone bar folds the navigation, Files and Accounts into its utility menu; each
        // item closes the menu, then acts.
        for (tag in listOf(ShellTags.LockKey, ShellTags.MenuFiles, ShellTags.MenuAccounts, ShellTags.menuNav(TopBarDestination.Usage), ShellTags.LogKey, ShellTags.menuNav(TopBarDestination.Overview))) {
            rule.onNodeWithTag(ShellTags.ToolsMenuKey).performClick()
            rule.onNodeWithTag(tag).performClick()
            rule.waitForIdle()
            assertFalse(tag, state.menuOpen)
        }
        assertEquals(listOf("rename", "end", "settings", "lock", "files", "usage", "analytics", "log", "nav:overview"), events)
    }

    @Test fun endSessionIsDisabledOnceExited() {
        show(PhoneShellState(), session = ShellFixtures.idle.copy(status = "exited"))
        rule.onNodeWithContentDescription("End session").assertIsNotEnabled()
    }

    @Test fun filesKeyNeedsASession() {
        show(PhoneShellState(menuOpen = true), session = null)
        // aria-disabled: still in the menu, with its reason.
        rule.onNodeWithTag(ShellTags.MenuFiles).assertIsNotEnabled()
        rule.onNodeWithText(TopbarReasons.FILES).assertIsDisplayed()
        rule.onNodeWithTag(ShellTags.MenuAccounts).assertIsEnabled()
        rule.onNodeWithTag(ShellTags.ToolsMenuKey).performClick() // the outside tap closes it
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.WorkspaceHeader).assertDoesNotExist()
        rule.onNodeWithTag(ShellTags.EmptyWorkspace).assertIsDisplayed()
        rule.onNodeWithText("Start where the work lives.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Start first session").performClick()
        assertEquals(listOf("start"), events)
    }

    @Test fun startIsDisabledWhileDisconnected() {
        rule.setContent {
            ShellUnderTest(TetherSkin.Studio, PhoneShellState(), null, emptyStage = EmptyStage.Welcome(false, ShellFixtures.providers))
        }
        rule.onNodeWithContentDescription("Start first session").assertIsNotEnabled()
    }

    @Test fun reopeningStageNamesTheLinkState() {
        rule.setContent { ShellUnderTest(TetherSkin.Studio, PhoneShellState(), null, emptyStage = EmptyStage.Reopening(false)) }
        rule.onNodeWithText("Reconnecting.").assertIsDisplayed()
        rule.onNodeWithText("Start first session").assertDoesNotExist()
    }

    @Test fun talkBackNamesAndStatesNeverRelyOnColour() {
        show(PhoneShellState(), warnings = 3)
        rule.onNodeWithContentDescription("Tether — Overview").assertExists()
        // The warning count is in the trigger's name, never only in its red badge.
        rule.onNodeWithContentDescription("Menu: navigation and tools, 3 unseen warnings").performClick()
        val log = rule.onNodeWithTag(ShellTags.LogKey).fetchSemanticsNode()
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
        // T15.4: and every item of the utility menu.
        rule.onNodeWithTag(ShellTags.ToolsMenuKey).performClick()
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
        // T15.4: the empty phone bar holds five controls (drawer key, brand, Settings, menu, start).
        assertTrue("found clickables", nodes.size > 4)
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
 * Above the 768dp cutoff MainShell hosts the expanded layout (T4.2); the phone shell itself must
 * still lay out and work at tablet width (a window mid-resize, previews).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class PhoneShellExpandedWidthTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun layoutClassIsExpandedAndTheShellStillWorks() {
        assertEquals(com.tether.app.ui.components.TetherLayoutClass.Expanded, shellLayoutFor(1280))
        val state = PhoneShellState()
        rule.setContent { ShellUnderTest(TetherSkin.Studio, state, ShellFixtures.idle) }
        rule.onNodeWithTag(ShellTags.Topbar).assertIsDisplayed()
        rule.onNodeWithTag(ShellTags.MenuKey).performClick()
        assertTrue(state.drawerOpen)
        rule.onNodeWithTag(ShellTags.TelemetryHandle).assertExists()
    }
}

/**
 * T4.1 verifier follow-up: at 1.3× on a short window the Session links popover is capped at
 * `calc(100dvh - 8rem)` and scrolls, so its last control stays reachable (globals.css 11865-11866).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h320dp-420dpi", fontScale = 1.3f)
class LinksPopoverShortScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun popoverIsCappedAndScrollsToItsLastControl() {
        val events = mutableListOf<String>()
        rule.setContent { ShellUnderTest(TetherSkin.StudioDark, PhoneShellState(linksOpen = true), ShellFixtures.idle, onEvent = { events += it }) }
        val card = rule.onNodeWithTag(ShellTags.LinksPopover).fetchSemanticsNode()
        val cap = with(rule.density) { (320.dp - 128.dp).toPx() }
        assertTrue("card ${card.boundsInRoot.height}px > cap ${cap}px", card.boundsInRoot.height <= cap + 1f)
        assertTrue("the card scrolls", card.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)!!.maxValue() > 0f)
        rule.onNodeWithContentDescription("Pin session").performScrollTo().performClick()
        assertEquals(listOf("pin"), events)
    }
}
