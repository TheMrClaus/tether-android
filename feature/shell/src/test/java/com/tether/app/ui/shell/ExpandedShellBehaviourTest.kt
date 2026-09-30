package com.tether.app.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Shared helpers for the expanded behaviour tests. */
abstract class ExpandedBehaviourBase {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    protected val events = mutableListOf<String>()

    protected fun show(
        state: PhoneShellState = PhoneShellState(),
        store: PanelStore = PanelStore(),
        session: AgentSession? = ExpandedFixtures.idle,
        skin: TetherSkin = TetherSkin.Machine,
    ) {
        rule.setContent { ExpandedShellUnderTest(skin, state, session, store, onEvent = { events += it }) }
    }

    protected fun back() {
        rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    protected fun widthDp(tag: String): Float =
        with(rule.density) { rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.width.toDp().value }

    protected fun dpPx(dp: Float): Float = with(rule.density) { dp.dp.toPx() }

    protected fun range(tag: String) =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)!!

    protected fun assertTouchTargets() {
        val min = dpPx(44f) - 0.5f
        val root = rule.onNodeWithTag(ShellTags.Shell).fetchSemanticsNode()
        val nodes = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            // The resize handles take a 48dp touch through pointer hit-expansion, which the semantics
            // touch bounds do not report; theHandleTakesATouchBesideItsDrawnStrip* prove it by touch.
            val actionable = n.config.getOrNull(SemanticsActions.OnClick) != null
            if (actionable && n.boundsInRoot.width > 0f) nodes += n
            n.children.forEach(::walk)
        }
        walk(root)
        val targets = nodes.filterNot { it.boundsInRoot.width >= root.boundsInRoot.width - 1f && it.boundsInRoot.height >= root.boundsInRoot.height / 2f }
        assertTrue("found controls", targets.size > 8)
        for (node in targets) {
            val b = node.touchBoundsInRoot
            val name = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "node ${node.id}"
            assertTrue("$name touch ${b.width}x${b.height}px < 44dp", b.width >= min && b.height >= min)
        }
    }
}

/** The web's tablet viewport: two columns, the floating telemetry sheet, one handle. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
@OptIn(ExperimentalTestApi::class)
class ExpandedShellBehaviourTest : ExpandedBehaviourBase() {

    @Test fun expandedWidthsGetTheDesktopShell() {
        assertEquals(TetherLayoutClass.Expanded, shellLayoutFor(840))
        show()
        rule.onNodeWithTag(ShellTags.Sidebar).assertIsDisplayed()
        rule.onNodeWithTag(DrawerSlotTag).assertIsDisplayed() // the drawer's list IS the rail here
        rule.onNodeWithTag(ShellTags.MenuKey).assertDoesNotExist() // `.mobile-menu { display: none }`
        rule.onNodeWithTag(ShellTags.InspectorColumn).assertDoesNotExist() // < 100rem
        rule.onNodeWithTag(ShellTags.InspectorHandle).assertDoesNotExist()
        // T15.4: the four destinations, then Files and Accounts on the bar (≥ 64rem); Lock is in the menu.
        rule.onNodeWithTag(ShellTags.nav(TopBarDestination.Overview)).assertIsDisplayed()
        rule.onNodeWithText("Files").assertIsDisplayed()
        rule.onNodeWithText("Accounts").assertIsDisplayed()
        rule.onNodeWithText("Lock").assertDoesNotExist()
        rule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        rule.onNodeWithContentDescription("More tools").assertIsDisplayed()
        rule.onNodeWithContentDescription("Secure link").assertIsDisplayed()
        rule.onNodeWithTag(ShellTags.Dial).assertIsDisplayed()
        rule.onNodeWithText("Telemetry").assertIsDisplayed()
        rule.onNodeWithText("Pin").assertIsDisplayed()
    }

    @Test fun railTakesTheThemeDefaultWithNothingStored() {
        show()
        assertEquals(264f, widthDp(ShellTags.Sidebar), 0.5f)
        val r = range(ShellTags.RailHandle)
        assertEquals(264f, r.current)
        assertEquals(224f..480f, r.range)
    }

    @Test fun studioDefaultIsSeventeenRem() {
        show(skin = TetherSkin.StudioDark)
        assertEquals(272f, widthDp(ShellTags.Sidebar), 0.5f)
    }

    @Test fun storedWidthIsRestoredAndReclamped() {
        show(store = PanelStore(PanelPrefs(sidebarWidth = 360)))
        assertEquals(360f, widthDp(ShellTags.Sidebar), 0.5f)
    }

    @Test fun anOutOfRangeStoredWidthIsClampedNotDropped() {
        show(store = PanelStore(PanelPrefs(sidebarWidth = 999)))
        assertEquals(480f, widthDp(ShellTags.Sidebar), 0.5f) // min(30rem, 40vw)
    }

    @Test fun draggingTheHandleResizesLiveAndCommitsOnceOnRelease() {
        val store = PanelStore()
        show(store = store)
        rule.onNodeWithTag(ShellTags.RailHandle).performTouchInput {
            down(center)
            moveBy(Offset(dpPx(30f), 0f))
            moveBy(Offset(dpPx(30f), 0f))
        }
        rule.waitForIdle()
        assertEquals("live width during the drag", 324f, widthDp(ShellTags.Sidebar), 0.5f)
        assertTrue("nothing committed mid-drag", store.commits.isEmpty())
        rule.onNodeWithTag(ShellTags.RailHandle).performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(listOf(PanelPrefs(sidebarWidth = 324)), store.commits)
        assertEquals(324f, widthDp(ShellTags.Sidebar), 0.5f)
    }

    @Test fun aDragIsClampedToTheViewportCeiling() {
        val store = PanelStore()
        show(store = store)
        rule.onNodeWithTag(ShellTags.RailHandle).performTouchInput {
            down(center)
            moveBy(Offset(dpPx(700f), 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(480, store.panels.sidebarWidth)
    }

    @Test fun aMoveUnderTwoPixelsIsATapNotADrag() {
        val store = PanelStore()
        show(store = store)
        rule.onNodeWithTag(ShellTags.RailHandle).performTouchInput {
            down(center)
            moveBy(Offset(dpPx(1f), 0f))
            up()
        }
        rule.waitForIdle()
        assertTrue(store.commits.isEmpty())
    }

    @Test fun theHandleTakesATouchBesideItsDrawnStripOnTheStageSide() {
        val store = PanelStore()
        show(store = store)
        // 23.5dp right of the 16dp strip's centre (inside the 48dp minimum touch target): over the stage's gutter.
        rule.onNodeWithTag(ShellTags.RailHandle).performTouchInput {
            down(Offset(centerX + dpPx(23.5f), centerY))
            moveBy(Offset(dpPx(40f), 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(304, store.panels.sidebarWidth)
    }

    @Test fun theHandleTakesATouchBesideItsDrawnStripOnTheRailSide() {
        val store = PanelStore()
        show(store = store)
        rule.onNodeWithTag(ShellTags.RailHandle).performTouchInput {
            down(Offset(centerX - dpPx(23.5f), centerY))
            moveBy(Offset(-dpPx(24f), 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(240, store.panels.sidebarWidth)
    }

    @Test fun doubleTapResetsToTheThemeDefault() {
        val store = PanelStore(PanelPrefs(sidebarWidth = 360))
        show(store = store)
        rule.onNodeWithTag(ShellTags.RailHandle).performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertEquals(listOf(PanelPrefs(sidebarWidth = null)), store.commits)
        assertEquals(264f, widthDp(ShellTags.Sidebar), 0.5f)
    }

    @Test fun arrowKeysMoveTheEdgeAndHomeResets() {
        val store = PanelStore()
        show(store = store)
        val handle = rule.onNodeWithTag(ShellTags.RailHandle)
        handle.performSemanticsAction(SemanticsActions.RequestFocus)
        handle.performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        assertEquals(280, store.panels.sidebarWidth)
        handle.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) } }
        rule.waitForIdle()
        assertEquals(344, store.panels.sidebarWidth)
        handle.performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        assertEquals(328, store.panels.sidebarWidth)
        handle.performKeyInput { pressKey(Key.MoveHome) }
        rule.waitForIdle()
        assertEquals(null, store.panels.sidebarWidth)
        assertEquals(264f, widthDp(ShellTags.Sidebar), 0.5f)
    }

    @Test fun talkBackAdjustsTheWidthAsARangeAndCanReset() {
        val store = PanelStore()
        show(store = store)
        val handle = rule.onNodeWithTag(ShellTags.RailHandle)
        val node = handle.fetchSemanticsNode()
        assertEquals("Resize session sidebar", node.config[SemanticsProperties.ContentDescription].single())
        handle.performSemanticsAction(SemanticsActions.SetProgress) { it(300f) }
        rule.waitForIdle()
        assertEquals(300, store.panels.sidebarWidth)
        handle.performSemanticsAction(SemanticsActions.SetProgress) { it(9_000f) }
        rule.waitForIdle()
        assertEquals(480, store.panels.sidebarWidth)
        val reset = rule.onNodeWithTag(ShellTags.RailHandle).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == "Reset to default width" }
        rule.runOnUiThread { reset.action() }
        rule.waitForIdle()
        assertEquals(null, store.panels.sidebarWidth)
    }

    @Test fun collapsedRailShowsTheDockWhichExpandsIt() {
        val store = PanelStore(PanelPrefs(sidebarWidth = 300, sidebarCollapsed = true))
        show(store = store)
        rule.onNodeWithTag(ShellTags.Sidebar).assertDoesNotExist()
        rule.onNodeWithTag(ShellTags.RailHandle).assertDoesNotExist()
        rule.onNodeWithContentDescription("Expand sidebar").performClick()
        rule.waitForIdle()
        // The width stored while collapsed is kept for the next expand.
        assertEquals(PanelPrefs(sidebarWidth = 300, sidebarCollapsed = false), store.panels)
        assertEquals(300f, widthDp(ShellTags.Sidebar), 0.5f)
        rule.onNodeWithTag(ShellTags.ExpandDock).assertDoesNotExist()
    }

    @Test fun gaugeFloatsTheSheetBesideTheConversation() {
        val state = PhoneShellState()
        show(state)
        rule.onNodeWithContentDescription("Session telemetry").performClick()
        assertTrue(state.telemetryOpen)
        rule.onNodeWithTag(ShellTags.TelemetrySheet).assertIsDisplayed()
        rule.onNodeWithTag(InspectorSlotTag).assertExists()
        // Unlike the phone, the conversation stays on screen (globals.css 11909).
        rule.onNodeWithTag(ChatSlotTag).assertIsDisplayed()
        rule.onNodeWithTag(ShellTags.TelemetryClose).performClick()
        assertFalse(state.telemetryOpen)
        rule.onNodeWithContentDescription("Session telemetry").performClick()
        back()
        assertFalse(state.telemetryOpen)
    }

    @Test fun backClosesThePopoverBeforeTheSheetThenFallsThrough() {
        val state = PhoneShellState(telemetryOpen = true, linksOpen = true)
        show(state)
        rule.onNodeWithTag(ShellTags.LinksPopover).assertIsDisplayed()
        back()
        assertFalse(state.linksOpen)
        assertTrue(state.telemetryOpen)
        rule.onNodeWithTag(ShellTags.LinksPopover).assertDoesNotExist()
        rule.onNodeWithTag(ShellTags.TelemetrySheet).assertIsDisplayed()
        back()
        assertFalse(state.telemetryOpen)
        assertFalse(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test fun theDrawerIsAPhoneSurfaceAndClosesOnEntry() {
        val state = PhoneShellState(drawerOpen = true)
        show(state)
        rule.waitForIdle()
        assertFalse(state.drawerOpen)
        rule.onNodeWithTag(ShellTags.DrawerBackdrop).assertDoesNotExist()
        assertFalse(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test fun desktopPopoverHasNoPinButTheHeaderDoes() {
        show(PhoneShellState())
        rule.onNodeWithContentDescription("Session links").performClick()
        rule.onNodeWithContentDescription("Copy this session's Tether id").assertIsDisplayed()
        rule.onNodeWithContentDescription("Pin session").assertDoesNotExist() // the phone-only popover pin
        rule.onNodeWithTag(ShellTags.Shell).performTouchInput { click(Offset(width / 2f, height - 20f)) }
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.LinksPopover).assertDoesNotExist()
        rule.onNodeWithText("Pin").performClick()
        rule.onNodeWithText("End session").performClick()
        assertEquals(listOf("pin", "end"), events)
    }

    @Test fun pinKeyReportsItsPressedState() {
        show(session = ExpandedFixtures.idle.copy(pinned = true))
        val node = rule.onNodeWithTag(ShellTags.PinKey).fetchSemanticsNode()
        assertEquals(androidx.compose.ui.state.ToggleableState.On, node.config[SemanticsProperties.ToggleableState])
        rule.onNodeWithText("Pinned").assertIsDisplayed()
    }

    @Test fun topbarKeysReachTheirHosts() {
        show()
        rule.onNodeWithTag(ShellTags.FilesKey).performClick()
        rule.onNodeWithTag(ShellTags.AccountsKey).performClick()
        rule.onNodeWithTag(ShellTags.nav(TopBarDestination.Usage)).performClick()
        rule.onNodeWithTag(ShellTags.nav(TopBarDestination.Overview)).performClick()
        rule.onNodeWithTag(ShellTags.Brand).performClick()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithTag(ShellTags.ToolsMenuKey).performClick()
        rule.onNodeWithTag(ShellTags.LogKey).performClick()
        rule.onNodeWithTag(ShellTags.ToolsMenuKey).performClick()
        rule.onNodeWithTag(ShellTags.LockKey).performClick()
        assertEquals(listOf("files", "usage", "analytics", "nav:overview", "nav:overview", "settings", "log", "lock"), events)
    }

    @Test fun filesNeedsASessionAndTheEmptyStageShows() {
        show(session = null, skin = TetherSkin.Tactile)
        rule.onNodeWithTag(ShellTags.FilesKey).assertIsNotEnabled()
        rule.onNodeWithTag(ShellTags.EmptyWorkspace).assertIsDisplayed()
        rule.onNodeWithContentDescription("Start first session").assertIsDisplayed()
    }

    /** DESIGN.md 44dp targets across the expanded chrome, including the resize handle. */
    @Test fun everyControlHasA44dpTouchTarget() {
        show()
        assertTouchTargets()
        rule.onNodeWithTag(ShellTags.LinksKey).performClick()
        assertTouchTargets()
    }

    @Test fun collapsedDockHasA44dpTarget() {
        show(store = PanelStore(PanelPrefs(sidebarCollapsed = true)))
        assertTouchTargets()
    }
}

/** ≥ 100rem: the inspector is a column with its own handle; the gauge is only an indicator. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1680dp-h1050dp-mdpi")
@OptIn(ExperimentalTestApi::class)
class ExpandedShellColumnBehaviourTest : ExpandedBehaviourBase() {

    @Test fun inspectorColumnTakesItsDefaultAndTheGaugeIsNotAHandle() {
        val state = PhoneShellState()
        show(state)
        rule.onNodeWithTag(ShellTags.InspectorColumn).assertIsDisplayed()
        rule.onNodeWithTag(InspectorSlotTag).assertExists()
        assertEquals(272f, widthDp(ShellTags.InspectorColumn), 0.5f)
        assertEquals(320f, widthDp(ShellTags.Sidebar), 0.5f)
        // Tooltip-only: no toggle state and no action (dashboard.tsx:80).
        val gauge = rule.onNodeWithContentDescription("Session telemetry").fetchSemanticsNode()
        assertEquals(null, gauge.config.getOrNull(SemanticsProperties.ToggleableState))
        assertEquals(null, gauge.config.getOrNull(SemanticsActions.OnClick))
        rule.onNodeWithTag(ShellTags.TelemetrySheet).assertDoesNotExist()
    }

    @Test fun draggingTheInspectorEdgeLeftWidensIt() {
        val store = PanelStore()
        show(store = store)
        rule.onNodeWithTag(ShellTags.InspectorHandle).performTouchInput {
            down(center)
            moveBy(Offset(-dpPx(48f), 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(PanelPrefs(inspectorWidth = 320), store.panels)
        assertEquals(320f, widthDp(ShellTags.InspectorColumn), 0.5f)
    }

    @Test fun arrowRightNarrowsTheInspector() {
        val store = PanelStore()
        show(store = store)
        val handle = rule.onNodeWithTag(ShellTags.InspectorHandle)
        handle.performSemanticsAction(SemanticsActions.RequestFocus)
        handle.performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        assertEquals(256, store.panels.inspectorWidth)
    }

    @Test fun storedWidthsForBothColumnsAreRestored() {
        show(store = PanelStore(PanelPrefs(sidebarWidth = 300, inspectorWidth = 360)))
        assertEquals(300f, widthDp(ShellTags.Sidebar), 0.5f)
        assertEquals(360f, widthDp(ShellTags.InspectorColumn), 0.5f)
    }

    @Test fun popoverClearsTheInspectorColumn() {
        show(PhoneShellState(linksOpen = true), store = PanelStore(PanelPrefs(inspectorWidth = 300)))
        val card = rule.onNodeWithTag(ShellTags.LinksPopover).fetchSemanticsNode().boundsInRoot
        val shell = rule.onNodeWithTag(ShellTags.Shell).fetchSemanticsNode().boundsInRoot
        assertEquals(shell.right - dpPx(300f + 16f), card.right, 1f)
    }

    @Test fun noSessionMeansNoInspectorColumn() {
        show(session = null)
        rule.onNodeWithTag(ShellTags.InspectorColumn).assertDoesNotExist()
        rule.onNodeWithTag(ShellTags.InspectorHandle).assertDoesNotExist()
        assertNotNull(rule.onNodeWithTag(ShellTags.RailHandle).fetchSemanticsNode())
    }
}

/** Just above the cutoff (900dp): no tool words, the narrow stage gutter, the 40vw rail ceiling. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h700dp-mdpi")
class ExpandedShellFoldableBehaviourTest : ExpandedBehaviourBase() {

    @Test fun foldableKeepsTheDesktopGridWithoutToolWords() {
        show(store = PanelStore(PanelPrefs(sidebarWidth = 470)))
        assertEquals(360f, widthDp(ShellTags.Sidebar), 0.5f) // stored on a wider screen, re-clamped
        // T15.4 r2: the bar keeps what fits whole (measured) and the menu lists the rest.
        rule.onNodeWithTag(ShellTags.nav(TopBarDestination.Usage)).assertIsDisplayed()
        rule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        rule.onNodeWithTag(ShellTags.ToolsMenuKey).performClick()
        rule.waitForIdle()
        for ((onBar, inMenu) in listOf(ShellTags.FilesKey to ShellTags.MenuFiles, ShellTags.AccountsKey to ShellTags.MenuAccounts)) {
            val bar = rule.onAllNodesWithTag(onBar).fetchSemanticsNodes().size
            val menu = rule.onAllNodesWithTag(inMenu).fetchSemanticsNodes().size
            assertEquals("$onBar on the bar XOR in the menu", 1, bar + menu)
        }
        rule.onNodeWithTag(ShellTags.ToolsMenuKey).performClick()
        rule.waitForIdle()
        val stage = rule.onNodeWithTag(ShellTags.Stage).fetchSemanticsNode().boundsInRoot
        val workspace = rule.onNodeWithTag(ShellTags.Workspace).fetchSemanticsNode().boundsInRoot
        assertEquals(dpPx(23f), stage.left - workspace.left, 1f) // calc(space-lg + 7px) below 64rem
    }

    @Test fun everyControlHasA44dpTouchTarget() {
        show()
        assertTouchTargets()
    }
}

/**
 * Just below the 100rem switch (1560dp): still two columns, the telemetry sheet floats and the
 * gauge is its handle (globals.css 11896: `max-width: 99.999rem`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1560dp-h1000dp-mdpi")
class ExpandedShellBelowColumnBehaviourTest : ExpandedBehaviourBase() {

    @Test fun belowOneHundredRemTheSheetFloatsAndThereIsNoColumn() {
        val state = PhoneShellState()
        show(state)
        rule.onNodeWithTag(ShellTags.InspectorColumn).assertDoesNotExist()
        rule.onNodeWithTag(ShellTags.InspectorHandle).assertDoesNotExist()
        assertEquals(264f, widthDp(ShellTags.Sidebar), 0.5f) // 16.5rem at 48-100rem (11856)
        val gauge = rule.onNodeWithContentDescription("Session telemetry").fetchSemanticsNode()
        assertEquals(androidx.compose.ui.state.ToggleableState.Off, gauge.config[SemanticsProperties.ToggleableState])
        rule.onNodeWithContentDescription("Session telemetry").performClick()
        assertTrue(state.telemetryOpen)
        rule.onNodeWithTag(ShellTags.TelemetrySheet).assertIsDisplayed()
        rule.onNodeWithTag(ChatSlotTag).assertIsDisplayed()
    }
}

/** Exactly 100rem (1600dp): the inspector becomes the third column (globals.css 4139). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1600dp-h1000dp-mdpi")
class ExpandedShellAtColumnBehaviourTest : ExpandedBehaviourBase() {

    @Test fun atOneHundredRemTheInspectorIsAColumn() {
        val state = PhoneShellState()
        show(state)
        rule.onNodeWithTag(ShellTags.InspectorColumn).assertIsDisplayed()
        rule.onNodeWithTag(ShellTags.InspectorHandle).assertExists()
        assertEquals(320f, widthDp(ShellTags.Sidebar), 0.5f) // 20rem from 100rem (11727)
        assertEquals(272f, widthDp(ShellTags.InspectorColumn), 0.5f)
        val gauge = rule.onNodeWithContentDescription("Session telemetry").fetchSemanticsNode()
        assertEquals(null, gauge.config.getOrNull(SemanticsActions.OnClick))
        rule.onNodeWithTag(ShellTags.TelemetrySheet).assertDoesNotExist()
    }
}
