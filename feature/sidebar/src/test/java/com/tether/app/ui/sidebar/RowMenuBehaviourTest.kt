package com.tether.app.ui.sidebar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.tether.app.client.Handover
import com.tether.app.protocol.helpers.SidebarRowModel.RowMenuItemId
import com.tether.app.protocol.model.HistoryDigest
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
 * S3a (SL-spec-3 §b.2/§b.3, tests §c.2): the phone row menu. Items, gates and order are the web's (tether ac67c5e4
 * components/session-row-menu.tsx + lib/sidebar-row-model.ts); the menu is a popup under the kebab.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class RowMenuBehaviourTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures
    private val sessions = F.statusSessions + F.live("open", "The open session", ago = 2, status = "active").copy(resumeCommand = "claude --resume abc")
    private val histories = F.statusHistories

    private fun state(activeId: String? = "open", handover: Handover.MenuState = Handover.MenuState.Enabled, connected: Boolean = true) =
        F.state(sessions, histories = histories, activeId = activeId, connected = connected).copy(activeHandover = handover, origin = "https://a.example")

    private var input: androidx.compose.ui.input.InputModeManager? = null

    private fun show(state: SidebarState, actions: SidebarActions = SidebarActions(), seed: SidebarUiSeed = SidebarUiSeed(), layout: TetherLayoutClass = TetherLayoutClass.Phone) {
        rule.setContent {
            input = androidx.compose.ui.platform.LocalInputModeManager.current
            SidebarUnderTest(TetherSkin.StudioDark, state, layout, seed, actions)
        }
        rule.waitForIdle()
    }

    private fun keyboardMode() {
        rule.runOnIdle { input!!.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
        rule.waitForIdle()
    }

    private fun focused(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
    private fun itemFocused(id: RowMenuItemId) = focused(SidebarTags.menuItem(id))

    private fun open(key: String) {
        rule.onNodeWithTag(SidebarTags.kebab(key)).performClick()
        rule.waitForIdle()
    }

    private fun item(id: RowMenuItemId) = rule.onAllNodesWithTag(SidebarTags.menuItem(id), useUnmergedTree = true)
    private fun has(id: RowMenuItemId) = item(id).fetchSemanticsNodes().isNotEmpty()
    private fun menuOpen() = rule.onAllNodesWithTag(SidebarTags.RowMenu, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun aLiveRowsMenuHasTheWebsItemsInTheWebsOrder() {
        show(state())
        open("live:open")
        val order = RowMenuItemId.entries.filter { has(it) }
        assertEquals(listOf(RowMenuItemId.Rename, RowMenuItemId.Pin, RowMenuItemId.Handover, RowMenuItemId.CopyId, RowMenuItemId.CopyPath, RowMenuItemId.CopyResume, RowMenuItemId.End), order)
        // vertical order = the groups' order
        val tops = order.map { rule.onNodeWithTag(SidebarTags.menuItem(it), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
    }

    /** ta-7ox8 B5: EVERY item is a Button for TalkBack; the disabled Hand over also says disabled. */
    @Test fun everyMenuItemIsAButtonAndABlockedHandOverIsDisabled() {
        show(state())
        open("live:open")
        val all = RowMenuItemId.entries.filter { has(it) }
        assertEquals(RowMenuItemId.entries.toList(), all)
        for (id in all) {
            val c = rule.onNodeWithTag(SidebarTags.menuItem(id), useUnmergedTree = true).fetchSemanticsNode().config
            assertEquals("$id", androidx.compose.ui.semantics.Role.Button, c.getOrNull(SemanticsProperties.Role))
            assertFalse("$id enabled", c.contains(SemanticsProperties.Disabled))
        }
    }

    @Test fun aBlockedHandOverIsAButtonThatSaysDisabled() {
        show(state(handover = Handover.MenuState.Blocked("Handover under way.")))
        open("live:open")
        val c = rule.onNodeWithTag(SidebarTags.menuItem(RowMenuItemId.Handover), useUnmergedTree = true).fetchSemanticsNode().config
        assertEquals(androidx.compose.ui.semantics.Role.Button, c.getOrNull(SemanticsProperties.Role))
        assertTrue(c.contains(SemanticsProperties.Disabled))
    }

    @Test fun handOverIsOfferedOnTheOpenRowOnly() {
        show(state())
        open("live:a1")
        assertFalse("Hand over only on the open session's row", has(RowMenuItemId.Handover))
        assertTrue(has(RowMenuItemId.Rename) && has(RowMenuItemId.End))
    }

    @Test fun handOverIsAbsentWhileTheHeadersRowIsHidden() {
        show(state(handover = Handover.MenuState.Hidden))
        open("live:open")
        assertFalse(has(RowMenuItemId.Handover))
    }

    @Test fun aBlockedHandOverIsShownDisabledWithItsReasonAndDoesNothing() {
        var handed = 0
        show(state(handover = Handover.MenuState.Blocked("Handover under way.")), SidebarActions(onHandOver = { handed++ }))
        open("live:open")
        rule.onNodeWithTag(SidebarTags.menuItem(RowMenuItemId.Handover), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(0, handed)
        assertTrue("the menu stays", menuOpen())
        rule.onAllNodesWithText("Handover under way.", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
    }

    @Test fun handOverClosesTheMenuAndCallsTheShell() {
        var handed = 0
        show(state(), SidebarActions(onHandOver = { handed++ }))
        open("live:open")
        rule.onNodeWithTag(SidebarTags.menuItem(RowMenuItemId.Handover), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(1, handed)
        assertFalse(menuOpen())
    }

    @Test fun stopHandoverIsTheSameRowAndStoppingIsDisabled() {
        var stopped = 0
        show(state(handover = Handover.MenuState.Stop("Preparing the summary.")), SidebarActions(onStopHandover = { stopped++ }))
        open("live:open")
        rule.onAllNodesWithText("Stop handover", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        rule.onAllNodesWithText("Preparing the summary. ${Handover.STOP_EFFECT}", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        rule.onNodeWithTag(SidebarTags.menuItem(RowMenuItemId.Handover), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(1, stopped)
    }

    @Test fun aHistoryRowsMenuIsCopyPathOnlyBehindItsDigest() {
        show(state(), seed = SidebarUiSeed())
        open("history:h-old")
        assertEquals(listOf(RowMenuItemId.CopyPath), RowMenuItemId.entries.filter { has(it) })
    }

    @Test fun aDigestRowsMenuOpensWithTheDigestBlock() {
        show(state())
        open("history:h-away")
        val digest = rule.onNodeWithTag(SidebarTags.RowMenuDigest, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(
            "3 new turns since you left. All four tests pass; the backoff is 250ms.",
            digest.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(),
        )
    }

    @Test fun renameAndPinCloseTheMenuAndCarryTheRowsOwnId() {
        val calls = mutableListOf<String>()
        show(state(), SidebarActions(onRenameSession = { id, name -> calls += "rename:$id:$name" }, onTogglePinSession = { id, pinned -> calls += "pin:$id:$pinned" }))
        open("live:a1")
        rule.onNodeWithTag(SidebarTags.menuItem(RowMenuItemId.Rename), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertFalse(menuOpen())
        open("live:a1")
        rule.onNodeWithTag(SidebarTags.menuItem(RowMenuItemId.Pin), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertFalse(menuOpen())
        assertEquals(listOf("rename:a1:Refactor the retry loop", "pin:a1:true"), calls)
    }

    @Test fun copyKeepsTheMenuOpenAndFlipsItsLabel() {
        show(state())
        open("live:a1")
        rule.onNodeWithTag(SidebarTags.menuItem(RowMenuItemId.CopyPath), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue("copy keeps the menu open", menuOpen())
        rule.onAllNodesWithText("Copied path", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        rule.mainClock.advanceTimeBy(1600)
        rule.waitForIdle()
        rule.onAllNodesWithText("Copy path", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
    }

    @Test fun endIsTwoTapsInsideTheMenuAndAClosedMenuDisarmsIt() {
        val ended = mutableListOf<String>()
        show(state(), SidebarActions(onEndSession = { id, origin -> ended += "$id@$origin" }))
        open("live:a1")
        val end = rule.onNodeWithTag(SidebarTags.menuItem(RowMenuItemId.End), useUnmergedTree = true)
        end.performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Tap again to end", useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        assertTrue(ended.isEmpty())
        // closing the menu disarms: reopened, End asks again
        open("live:a1")
        assertFalse(menuOpen())
        open("live:a1")
        rule.onAllNodesWithText("End session", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        rule.onNodeWithTag(SidebarTags.menuItem(RowMenuItemId.End), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SidebarTags.menuItem(RowMenuItemId.End), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("a1@https://a.example"), ended)
        assertFalse("the second tap closes the menu", menuOpen())
    }

    @Test fun anotherItemDisarmsEnd() {
        show(state())
        open("live:a1")
        rule.onNodeWithTag(SidebarTags.menuItem(RowMenuItemId.End), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SidebarTags.menuItem(RowMenuItemId.CopyId), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("End session", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
    }

    /** ta-hmzy: End has no connection term (web parity). The UI half: enabled, no 'Unavailable', two taps end once. */
    @Test fun offlineEndIsEnabledAndEndsWithItsTwoTaps() {
        val ended = mutableListOf<String>()
        show(state(connected = false), SidebarActions(onEndSession = { id, o -> ended += "$id@$o" }))
        open("live:a1")
        item(RowMenuItemId.End).onFirst().assertIsEnabled()
        rule.onNodeWithTag(SidebarTags.menuItem(RowMenuItemId.End), useUnmergedTree = true).performClick()
        rule.onNodeWithTag(SidebarTags.menuItem(RowMenuItemId.End), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("a1@https://a.example"), ended)
        assertTrue(rule.onAllNodesWithText("Unavailable: connect to end it", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun theMenuClosesWhenItsRowGoesAway() {
        var s by mutableStateOf(state())
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, s, TetherLayoutClass.Phone, SidebarUiSeed(), SidebarActions()) }
        rule.waitForIdle()
        open("live:a1")
        assertTrue(menuOpen())
        rule.runOnIdle { s = F.state(sessions.filter { it.id != "a1" }, histories = histories, activeId = "open").copy(activeHandover = Handover.MenuState.Enabled) }
        rule.waitForIdle()
        assertFalse(menuOpen())
    }

    /** The tablet rail keeps its End × and its two-step (wave-check item 1): S3a changes the phone branch only. */
    @Test fun theTabletRailStillHasItsEndXAndItsTwoStep() {
        val ended = mutableListOf<String>()
        show(state(), SidebarActions(onEndSession = { id, _ -> ended += id }), layout = TetherLayoutClass.Expanded)
        rule.onNodeWithTag(SidebarTags.end("live:a1")).assertExists()
        assertTrue("no kebab on the tablet", rule.onAllNodesWithTag(SidebarTags.kebab("live:a1"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag(SidebarTags.end("live:a1")).performClick()
        rule.waitForIdle()
        assertTrue(ended.isEmpty())
        rule.onNodeWithTag(SidebarTags.end("live:a1")).performClick()
        rule.waitForIdle()
        assertEquals(listOf("a1"), ended)
    }

    // ---- ta-7ox8 B4 (session-row-menu.tsx:68-152): keyboard-mode focus ---------------------------------------
    // First-item focus on open and Escape/Tab need focus INSIDE the popup window, which Robolectric never grants (a test-framework
    // requestFocus on a popup node stays unfocused too): those two are verified on the device (receipt), not here.

    @Test fun aTouchOpenFocusesNothing() {
        show(state())
        open("live:open")
        assertTrue(menuOpen())
        assertTrue("no item takes focus on a touch open", RowMenuItemId.entries.none { has(it) && itemFocused(it) })
    }

    @Test fun pinClosesTheMenuAndReturnsFocusToTheKebab() {
        show(state())
        keyboardMode()
        open("live:open")
        rule.onNodeWithTag(SidebarTags.menuItem(RowMenuItemId.Pin), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertFalse(menuOpen())
        assertTrue("the kebab has focus again", focused(SidebarTags.kebab("live:open")))
    }

    @Test fun renameClosesTheMenuAndDoesNotReturnFocus() {
        show(state())
        keyboardMode()
        open("live:open")
        rule.onNodeWithTag(SidebarTags.menuItem(RowMenuItemId.Rename), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertFalse(menuOpen())
        assertFalse("the rename dialog owns focus, not the kebab", focused(SidebarTags.kebab("live:open")))
    }
}
