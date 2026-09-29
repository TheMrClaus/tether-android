package com.tether.app.ui.sidebar

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** T5.1: the sidebar's gestures and callbacks (session-sidebar.tsx), in the phone drawer. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SidebarBehaviourTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures
    private val events = mutableListOf<String>()

    private val actions = SidebarActions(
        onSelectSession = { events += "select:$it" },
        onReopenHistory = { events += "reopen:${it.historyId}" },
        onEndSession = { id, _ -> events += "end:$id" },
        onReorderSessions = { ws, order -> events += "order:$ws:${order.joinToString(",")}" },
        onToggleActiveOnly = { events += "active" },
        onToggleUnreadOnly = { events += "unread" },
        onToggleWorkspaceCollapsed = { events += "collapse:$it" },
        onTogglePinnedProject = { events += "pin:$it" },
        onNewSessionIn = { events += "new-in:$it" },
        onNewSession = { events += "new" },
        onBrowseWorkspace = { events += "browse" },
        onCloseDrawer = { events += "close" },
        onHarnessFilterChange = { events += "harness:$it" },
        onSortModeChange = { events += "sort:${it.id}" },
    )

    private fun show(state: SidebarState = F.state(F.drawerSessions.take(4)), skin: TetherSkin = TetherSkin.Machine) {
        rule.setContent { SidebarUnderTest(skin, state, actions = actions) }
        rule.waitForIdle()
    }

    /** A row's nav button: its accessible text starts with the session name. */
    private fun row(name: String) = rule.onNode(
        SemanticsMatcher("row $name") { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("$name,") } == true },
    )

    @Test fun tappingARowSelectsItsSession() {
        show()
        row("Add the version file").performClick()
        assertEquals(listOf("select:s02"), events)
    }

    @Test fun aHistoryOnlyRowReopensItsConversation() {
        show(F.state(F.statusSessions, histories = F.statusHistories))
        row("Earlier codex thread").performClick()
        assertEquals(listOf("reopen:h-old"), events)
    }

    @Test fun theEndControlTakesTwoTapsAndDisarmsOnItsOwn() {
        show()
        rule.onNodeWithContentDescription("End Add the version file").performClick()
        assertTrue("one tap only arms", events.isEmpty())
        rule.onNodeWithContentDescription("Tap again to end Add the version file").performClick()
        assertEquals(listOf("end:s02"), events)

        rule.onNodeWithContentDescription("End Long task with notices").performClick()
        rule.onNodeWithContentDescription("Tap again to end Long task with notices").assertExists()
        rule.mainClock.advanceTimeBy(END_SESSION_ARM_MS + 100)
        rule.onNodeWithContentDescription("Tap again to end Long task with notices").assertDoesNotExist()
        rule.onNodeWithContentDescription("End Long task with notices").assertExists()
        assertEquals(listOf("end:s02"), events)
    }

    @Test fun armingASecondRowDisarmsTheFirst() {
        show()
        rule.onNodeWithContentDescription("End Add the version file").performClick()
        rule.onNodeWithContentDescription("End Long task with notices").performClick()
        rule.onNodeWithContentDescription("End Add the version file").assertIsDisplayed()
        rule.onNodeWithContentDescription("Tap again to end Long task with notices").assertIsDisplayed()
    }

    @Test fun pressingElsewhereDisarmsTheEndControl() {
        show()
        rule.onNodeWithContentDescription("End Add the version file").performClick()
        rule.onNodeWithContentDescription("Tap again to end Add the version file").assertExists()
        // session-sidebar.tsx:821 handleEndBlur: interacting anywhere else disarms at once, not after 4s.
        row("Long task with notices").performClick()
        rule.onNodeWithContentDescription("Tap again to end Add the version file").assertDoesNotExist()
        rule.onNodeWithContentDescription("End Add the version file").performClick()
        assertEquals("the next tap only re-arms", listOf("select:s03"), events)
    }

    @Test fun holdingTheCapAndLettingGoWithoutMovingCommitsNothing() {
        show()
        val cap = rule.onNodeWithContentDescription("Hold and drag to move Worktree with a service")
        cap.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(400) // engaged: past the 350ms touch hold
        cap.performTouchInput { up() }
        rule.waitForIdle()
        // session-sidebar.tsx:684-696: an unchanged order is not sent — no set-session-order that
        // would freeze the block's recency order into a manual one.
        assertTrue(events.toString(), events.none { it.startsWith("order:") })
    }

    @Test fun holdingTheProviderCapAndDraggingReordersTheBlock() {
        show()
        val handle = rule.onNodeWithTag(SidebarTags.handle("live:s01"), useUnmergedTree = true)
        val rowHeight = rule.onNodeWithTag(SidebarTags.row("live:s01")).fetchSemanticsNode().size.height.toFloat()
        val cap = rule.onNodeWithContentDescription("Hold and drag to move Worktree with a service")
        cap.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(400) // past the 350ms touch hold
        cap.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, rowHeight * 0.75f)) }
        rule.mainClock.advanceTimeBy(50)
        cap.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, rowHeight * 0.75f)) }
        rule.mainClock.advanceTimeBy(50)
        cap.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(listOf("order:${F.ROOT}:live:s02,live:s01,live:s03,live:s04"), events)
        handle.assertExists()
    }

    @Test fun aQuickMoveOnTheCapIsAScrollNotADrag() {
        show()
        val rowHeight = rule.onNodeWithTag(SidebarTags.row("live:s01")).fetchSemanticsNode().size.height.toFloat()
        val cap = rule.onNodeWithContentDescription("Hold and drag to move Worktree with a service")
        // Past 8px before the 350ms hold: session-sidebar.tsx:736 lets the list have the gesture.
        cap.performTouchInput { down(center) }
        cap.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, 40f)) }
        rule.mainClock.advanceTimeBy(400)
        cap.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, rowHeight)) }
        rule.mainClock.advanceTimeBy(50)
        cap.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, 1f)) }
        cap.performTouchInput { up() }
        rule.waitForIdle()
        assertTrue(events.toString(), events.none { it.startsWith("order:") })
    }

    @Test fun talkBackCanMoveARowWithoutDragging() {
        show()
        val node = rule.onNodeWithContentDescription("Hold and drag to move Worktree with a service").fetchSemanticsNode()
        val move = node.config[SemanticsActions.CustomActions].single { it.label == "Move down" }
        rule.runOnIdle { move.action() }
        assertEquals(listOf("order:${F.ROOT}:live:s02,live:s01,live:s03,live:s04"), events)
    }

    @Test fun swipingLeftRevealsArchiveWhichEndsTheSession() {
        show()
        row("Add the version file").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(-20f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(-200f, 0f))
            up()
        }
        rule.waitForIdle()
        assertTrue("the swipe never navigates", events.isEmpty())
        rule.onNodeWithContentDescription("Archive Add the version file").performClick()
        assertEquals(listOf("end:s02"), events)
    }

    @Test fun theFilterBankTogglesAreSwitchesWithTheirStateInWords() {
        show(F.state(F.drawerSessions.take(4), activeOnly = true))
        rule.onNodeWithContentDescription("Active").assert(
            SemanticsMatcher("switch, on") {
                it.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Switch &&
                    it.config.getOrNull(SemanticsProperties.StateDescription)?.startsWith("On.") == true
            },
        ).performClick()
        rule.onNodeWithContentDescription("Unread").assert(
            SemanticsMatcher("off") { it.config.getOrNull(SemanticsProperties.StateDescription)?.startsWith("Off.") == true },
        ).performClick()
        assertEquals(listOf("active", "unread"), events)
    }

    @Test fun theSortMenuPicksAMode() {
        show()
        rule.onNodeWithContentDescription("Sort sessions by Created Date").performClick()
        rule.onNodeWithText("Last Active").performClick()
        assertEquals(listOf("sort:last-active"), events)
    }

    @Test fun blockHeaderActionsNameTheirWorkspace() {
        show(F.state(F.groupSessions, pinned = listOf(F.DOCS, F.APP), current = F.APP))
        rule.onNodeWithContentDescription("New session in docs").performClick()
        rule.onNodeWithContentDescription("Unpin docs").performClick()
        assertEquals(listOf("new-in:${F.DOCS}", "pin:${F.DOCS}"), events)
    }

    @Test fun everyControlHasA44dpTouchTarget() {
        show(F.state(F.statusSessions, histories = F.statusHistories))
        val minPx = with(rule.density) { 44.dp.toPx() }
        val small = rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().filter { n ->
            val b = n.touchBoundsInRoot
            b.width + 0.5f < minPx || b.height + 0.5f < minPx
        }
        assertTrue(
            "controls under 44dp: " + small.map { it.config.getOrNull(SemanticsProperties.ContentDescription) to it.touchBoundsInRoot },
            small.isEmpty(),
        )
    }

    @Test fun statusIsNeverColourAlone() {
        show(F.state(F.statusSessions, histories = F.statusHistories))
        // Each row's accessible text carries the status words and the unseen cue.
        rule.onNodeWithContentDescription("Refactor the retry loop, chat, Active, 1m", substring = true).assertExists()
        rule.onNodeWithContentDescription("Approve the lint fix, chat, Needs you", substring = true).assertExists()
        rule.onNodeWithContentDescription("Finished while you were away, changed since you last looked", substring = true).assertExists()
        rule.onNodeWithContentDescription("3 new turns since you left", substring = true).assertExists()
        rule.onRoot().assertExists()
    }
}
