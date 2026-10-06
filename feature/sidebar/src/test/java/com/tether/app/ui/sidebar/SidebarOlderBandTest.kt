package com.tether.app.ui.sidebar

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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

private const val DAY_MIN = 24L * 60

/**
 * tether #244 (session-sidebar.tsx:1028-1042, :1193-1205): sessions idle for more than 7 days fold into a
 * collapsed "Older" row per workspace block. The view rules first, then the row on a phone and on the
 * expanded layout.
 */
class SidebarOlderBandRulesTest {
    private val F = SidebarFixtures

    private fun keys(view: SidebarView, workspace: String = F.ROOT) = view.blocks.first { it.workspace == workspace }.rows.map { it.key }
    private fun block(view: SidebarView, workspace: String = F.ROOT) = view.blocks.first { it.workspace == workspace }

    private val sessions = listOf(
        F.live("fresh", "Fresh", ago = 5),
        F.live("six", "Six days", ago = 6 * DAY_MIN),
        F.live("ten", "Ten days", ago = 10 * DAY_MIN),
        F.live("month", "A month", ago = 30 * DAY_MIN),
    )

    @Test fun rowsIdleForSevenDaysFoldIntoTheCollapsedBand() {
        val view = SidebarViewModel.view(F.state(sessions))
        assertEquals(listOf("live:fresh", "live:six"), keys(view))
        assertEquals(2, block(view).olderCount)
        assertFalse(block(view).olderOpen)
    }

    @Test fun theBoundaryIsSevenWholeDays() {
        val exactly = F.live("edge", "Edge", ago = 7 * DAY_MIN)
        val inside = F.live("inside", "Inside", ago = 7 * DAY_MIN - 1)
        val view = SidebarViewModel.view(F.state(listOf(exactly, inside)))
        assertEquals(listOf("live:inside"), keys(view))
        assertEquals(1, block(view).olderCount)
    }

    @Test fun expandingListsTheRecentRowsThenTheOlderOnesInTheirOrder() {
        val view = SidebarViewModel.view(F.state(sessions), olderOpen = setOf(F.ROOT))
        assertEquals(listOf("live:fresh", "live:six", "live:ten", "live:month"), keys(view))
        assertTrue(block(view).olderOpen)
        assertEquals("the band's row stays, to hide it again", 2, block(view).olderCount)
    }

    @Test fun anotherBlocksBandStaysClosed() {
        val docs = F.live("docs-old", "Old docs", cwd = F.DOCS, ago = 12 * DAY_MIN)
        val state = F.state(sessions + docs, pinned = listOf(F.DOCS))
        val view = SidebarViewModel.view(state, olderOpen = setOf(F.ROOT))
        assertTrue(block(view, F.ROOT).olderOpen)
        assertFalse(block(view, F.DOCS).olderOpen)
        assertEquals(1, block(view, F.DOCS).olderCount)
        assertEquals(emptyList<String>(), keys(view, F.DOCS))
    }

    @Test fun aPinnedWorkingSelectedOrUnreadRowIsNeverFolded() {
        val pinned = F.live("pinned", "Pinned", ago = 20 * DAY_MIN).copy(pinned = true)
        val working = F.live("working", "Working", status = "active", ago = 20 * DAY_MIN)
        val waiting = F.live("waiting", "Waiting", status = "waiting", ago = 20 * DAY_MIN)
        val selected = F.live("selected", "Selected", ago = 20 * DAY_MIN)
        val plain = F.live("plain", "Plain", ago = 20 * DAY_MIN)
        val unread = F.history("h-unread", "Unread report", ago = 20 * DAY_MIN, seenAgo = 30 * DAY_MIN)
        val state = F.state(
            listOf(pinned, working, waiting, selected, plain),
            histories = mapOf(F.ROOT to listOf(unread)),
            activeId = "selected",
        )
        val view = SidebarViewModel.view(state)
        assertEquals(
            setOf("live:pinned", "live:working", "live:waiting", "live:selected", "history:h-unread"),
            keys(view).toSet(),
        )
        assertEquals(1, block(view).olderCount)
    }

    @Test fun aParentWithAWorkingDelegateChildIsKept() {
        val parent = F.live("parent", "Parent", ago = 20 * DAY_MIN)
        val child = F.live("child", "Child", status = "active", ago = 20 * DAY_MIN, parent = "parent")
        val view = SidebarViewModel.view(F.state(listOf(parent, child)))
        assertEquals(listOf("live:parent"), keys(view))
        assertEquals(0, block(view).olderCount)
    }

    @Test fun aQueryOrALensShowsEverything() {
        val view = SidebarViewModel.view(F.state(sessions, query = "days"))
        assertEquals(listOf("live:six", "live:ten"), keys(view))
        assertEquals(0, block(view).olderCount)
        val lens = SidebarViewModel.view(F.state(sessions, harness = "claude"))
        assertEquals(sessions.map { "live:${it.id}" }, keys(lens))
        assertEquals(0, block(lens).olderCount)
    }

    @Test fun aRowWithoutAnActivityTimeIsNeverFolded() {
        val undated = F.live("undated", "Undated", ago = 0).copy(startedAt = 0, updatedAt = 0)
        val view = SidebarViewModel.view(F.state(listOf(undated)))
        assertEquals(listOf("live:undated"), keys(view))
        assertEquals(0, block(view).olderCount)
    }

    @Test fun theBandRowWaitsForShowMoreToFinish() {
        val many = (1..12).map { F.live("r$it", "Recent $it", ago = it.toLong()) } + F.live("old", "Old", ago = 20 * DAY_MIN)
        val first = SidebarViewModel.view(F.state(many))
        assertEquals(10, block(first).rows.size)
        assertEquals(2, block(first).remaining)
        assertEquals("not offered while recent rows are still behind Show more", 0, block(first).olderCount)
        val all = SidebarViewModel.view(F.state(many), visibleCounts = mapOf(F.ROOT to 20))
        assertEquals(0, block(all).remaining)
        assertEquals(1, block(all).olderCount)
    }

    @Test fun lastMessageAtDecidesWhenItIsKnown() {
        val old = F.live("chat", "Chat", ago = 30 * DAY_MIN).copy(lastMessageAt = F.NOW - 60_000)
        val view = SidebarViewModel.view(F.state(listOf(old)))
        assertEquals(listOf("live:chat"), keys(view))
    }
}

/** The "Older" row as drawn and driven, on the phone drawer and the expanded rail. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SidebarOlderBandBehaviourTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures

    private val sessions = listOf(
        F.live("fresh", "Fresh chat", ago = 5),
        F.live("ten", "Ten days chat", ago = 10 * DAY_MIN),
        F.live("month", "Month old chat", ago = 30 * DAY_MIN),
    )

    private fun show(layout: TetherLayoutClass) {
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, F.state(sessions), layout, SidebarUiSeed(), SidebarActions()) }
        rule.waitForIdle()
    }

    private fun label(node: androidx.compose.ui.test.SemanticsNodeInteraction): List<String> =
        node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()

    private fun checkBand(layout: TetherLayoutClass) {
        show(layout)
        rule.onNodeWithTag(SidebarTags.row("live:fresh")).assertIsDisplayed()
        rule.onNodeWithTag(SidebarTags.row("live:ten")).assertDoesNotExist()
        rule.onNodeWithTag(SidebarTags.row("live:month")).assertDoesNotExist()
        val band = rule.onNodeWithTag(SidebarTags.older(F.ROOT))
        band.assertIsDisplayed()
        assertEquals(listOf("Older", "2"), label(band).filter { it.isNotEmpty() })

        band.performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SidebarTags.row("live:ten")).assertIsDisplayed()
        rule.onNodeWithTag(SidebarTags.row("live:month")).assertExists()
        assertEquals(listOf("Hide older", "2"), label(rule.onNodeWithTag(SidebarTags.older(F.ROOT))).filter { it.isNotEmpty() })

        rule.onNodeWithTag(SidebarTags.older(F.ROOT)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SidebarTags.row("live:ten")).assertDoesNotExist()
        assertEquals(listOf("Older", "2"), label(rule.onNodeWithTag(SidebarTags.older(F.ROOT))).filter { it.isNotEmpty() })
    }

    @Test fun onThePhoneDrawerTheBandIsCollapsedAndToggles() = checkBand(TetherLayoutClass.Phone)

    @Test fun onTheExpandedRailTheBandIsCollapsedAndToggles() = checkBand(TetherLayoutClass.Expanded)

    @Test fun aSeededOpenBandListsItsRows() {
        rule.setContent {
            SidebarUnderTest(TetherSkin.StudioDark, F.state(sessions), TetherLayoutClass.Phone, SidebarUiSeed(olderOpen = setOf(F.ROOT)), SidebarActions())
        }
        rule.waitForIdle()
        rule.onNodeWithTag(SidebarTags.row("live:month")).assertExists()
    }
}
