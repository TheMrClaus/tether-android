package com.tether.app.ui.sidebar

import com.tether.app.protocol.helpers.SidebarOrder
import com.tether.app.protocol.model.AgentSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W35 (ta-b6m9): the handover-chain fold as the sidebar view model feeds it (web components/session-sidebar.tsx @7cc0f38c). */
class SidebarChainsModelTest {
    private val F = SidebarFixtures
    private val WEEK_MIN = 7L * 24 * 60

    /** m0 (oldest) .. m(n-1) (head), each handed off from the previous; [ago] of the head, one minute apart. */
    private fun chain(n: Int, prefix: String = "m", ago: Long = 10, status: (Int) -> String = { "ready" }): List<AgentSession> =
        (0 until n).map { i ->
            F.live("$prefix$i", "Member $prefix$i", ago = ago + (n - 1 - i), status = status(i), handedOffFrom = if (i > 0) "$prefix${i - 1}" else null)
        }

    private fun keys(list: List<SidebarEntry>) = list.map { it.key }

    @Test fun aChainFoldsIntoItsHeadAtTheRootsSlotAndCountsOnce() {
        val solo = F.live("solo", "Solo", ago = 5)
        val view = SidebarViewModel.view(F.state(chain(4) + solo, current = F.APP))
        val block = view.blocks.single()
        assertEquals(setOf("live:solo", "live:m3"), keys(block.rows).toSet())
        assertEquals(2, block.rows.size)
        assertEquals(2, block.totalRows)
        assertEquals(listOf("live:m2", "live:m1", "live:m0"), keys(block.olderByHeadKey.getValue("live:m3")))
        assertEquals("3 earlier sessions", block.chainSummaries.getValue("live:m3").label)
        assertEquals("live:m0", block.chainSummaries.getValue("live:m3").rootKey)
        assertEquals(5, view.openCount)
    }

    @Test fun aFilterFlattensEveryMemberAndEmptiesTheChainKeys() {
        val view = SidebarViewModel.view(F.state(chain(3), query = "Member", current = F.APP))
        assertEquals(3, view.blocks.single().rows.size)
        assertTrue(view.blocks.single().olderByHeadKey.isEmpty())
        assertTrue(view.chainKeysByWorkspace.isEmpty())
        assertTrue(!view.dragEnabled)
    }

    @Test fun chainKeysComeFromTheVisibleRowsOfTheBlock() {
        val view = SidebarViewModel.view(F.state(chain(3), current = F.APP))
        assertEquals(listOf(SidebarOrder.HandoverChainKeys("live:m2", listOf("live:m1", "live:m0"), "live:m0")), view.chainKeysByWorkspace.getValue(F.APP))
    }

    @Test fun anArchivedMiddleSplitsTheChainAndALoneSideKeepsAPlainRow() {
        // SB-2: the lone side is named "Origin" so it does not share a name base with "Member m3" (see the regroup test below).
        val sessions = chain(4).map {
            when (it.id) {
                "m1" -> F.live("m1", "Member m1", ago = 12, archived = true, handedOffFrom = "m0")
                "m0" -> F.live("m0", "Origin", ago = 15)
                else -> it
            }
        }
        val view = SidebarViewModel.view(F.state(sessions, current = F.APP))
        val block = view.blocks.single()
        assertEquals(setOf("live:m3", "live:m0"), keys(block.rows).toSet())
        assertEquals(listOf("live:m2"), keys(block.olderByHeadKey.getValue("live:m3")))
        assertEquals(listOf("live:m1"), keys(view.archived))
    }

    @Test fun anArchivedMiddleWhoseLoneSideSharesTheNameBaseRegroupsByName() {
        val sessions = chain(4).map { if (it.id == "m1") F.live("m1", "Member m1", ago = 12, archived = true, handedOffFrom = "m0") else it }
        val block = SidebarViewModel.view(F.state(sessions, current = F.APP)).blocks.single()
        // "Member m3" (head of m3<-m2) and "Member m0" are one name group (web SB-2): one row, m0 folded under it, anchored at m0.
        assertEquals(setOf("live:m3"), keys(block.rows).toSet())
        assertEquals(listOf("live:m2", "live:m0"), keys(block.olderByHeadKey.getValue("live:m3")))
    }

    @Test fun theOlderBandKeepsAGroupWhoseOlderMemberIsPinnedAndFoldsAnIdleOne() {
        val idle = chain(3, ago = WEEK_MIN * 2)
        val plain = SidebarViewModel.view(F.state(idle + F.live("fresh", "Fresh", ago = 1), current = F.APP), olderOpen = emptySet()).blocks.single()
        assertEquals(1, plain.olderCount)
        val pinned = idle.map { if (it.id == "m0") F.live("m0", "Member m0", ago = WEEK_MIN * 2 + 2, pinned = true) else it }
        val kept = SidebarViewModel.view(F.state(pinned + F.live("fresh", "Fresh", ago = 1), current = F.APP)).blocks.single()
        assertEquals(0, kept.olderCount)
        assertTrue("live:m2" in keys(kept.rows))
    }

    @Test fun aWaitingDelegateOfAnOlderMemberKeepsTheGroupAndFeedsTheFacts() {
        val sessions = chain(3, ago = WEEK_MIN * 2) +
            F.live("d", "Delegate", ago = WEEK_MIN * 2, status = "waiting", parent = "m0")
        val block = SidebarViewModel.view(F.state(sessions + F.live("fresh", "Fresh", ago = 1), current = F.APP)).blocks.single()
        assertEquals(0, block.olderCount)
        val view = block.chainSummaries.getValue("live:m2")
        assertEquals(SidebarOrder.ChainSuffix(SidebarOrder.ChainSuffixKind.Waiting, 1), view.suffix)
        assertEquals("1 needs you", view.suffixText)
        assertEquals(listOf("live:m0"), view.reasonKeys)
    }

    @Test fun aSelectedOlderMemberHoldsTheGroupOnTheFirstPage() {
        val solos = (1..12).map { F.live("s$it", "Solo ${'a' + it - 1}", ago = it.toLong()) }
        val tail = chain(2, ago = 100)
        val without = SidebarViewModel.view(F.state(solos + tail, current = F.APP)).blocks.single()
        assertEquals(10, without.rows.size)
        val withSelected = SidebarViewModel.view(F.state(solos + tail, current = F.APP, activeId = "m0")).blocks.single()
        assertTrue("live:m1" in keys(withSelected.rows))
        assertEquals(listOf("live:m0"), withSelected.chainSummaries.getValue("live:m1").reasonKeys)
    }

    @Test fun theDragOrderHoldsOneKeyPerChainAndThePreviewKeepsTheMembers() {
        val state = F.state(chain(3) + F.live("solo", "Solo", ago = 1), current = F.APP)
        val view = SidebarViewModel.view(state)
        val chains = view.chainKeysByWorkspace.getValue(F.APP)
        val base = SidebarViewModel.dragBaseOrder(state, F.APP, view.delegateChildKeys)
        val collapsed = SidebarViewModel.collapsedDragOrder(base, chains)
        assertEquals(2, collapsed.size)
        assertTrue("live:m1" !in collapsed && "live:m0" !in collapsed)
        val dropped = SidebarViewModel.move(collapsed, "live:m2", "live:solo")
        val preview = SidebarViewModel.view(state, drag = DragPreview("live:m2", dropped)).blocks.single()
        assertEquals(listOf("live:m2", "live:solo"), keys(preview.rows))
        assertEquals(listOf("live:m1", "live:m0"), keys(preview.olderByHeadKey.getValue("live:m2")))
        assertEquals(listOf("live:m2", "live:m1", "live:m0", "live:solo"), SidebarViewModel.committedOrder(dropped, chains))
    }
}
