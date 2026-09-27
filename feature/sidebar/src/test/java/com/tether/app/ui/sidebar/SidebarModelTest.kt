package com.tether.app.ui.sidebar

import com.tether.app.protocol.model.HistoryDigest
import com.tether.app.protocol.helpers.SessionSidebar as Web
import com.tether.app.ui.prefs.SidebarSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T5.1: the dashboard.tsx:564-910 derivations over the verified helpers. */
class SidebarModelTest {
    private val F = SidebarFixtures

    private fun rows(
        sessions: List<com.tether.app.protocol.model.AgentSession>,
        histories: Map<String, List<com.tether.app.protocol.model.HistorySession>> = emptyMap(),
        workspaces: List<String> = listOf(F.ROOT),
        lastSeen: Map<String, Long> = emptyMap(),
        orders: Map<String, List<String>> = emptyMap(),
        sort: SidebarSort = SidebarSort.Created,
        activeId: String? = null,
        opening: String? = null,
    ) = SidebarModel.sidebarSessions(sessions, histories, workspaces, lastSeen, orders, sort, activeId, opening, F.collator)

    @Test fun liveRowsSortNewestFirstAndCarryTheWebShape() {
        val out = rows(F.drawerSessions)
        assertEquals(F.drawerSessions.map { "live:${it.id}" }, out.map { it.key })
        val js = out.first().js
        assertEquals(F.ROOT, (js["workspace"] as com.tether.app.protocol.tree.JsStr).value)
        // encodeDefaults: the mode reaches the helpers, so the row wears its Chat tag.
        assertEquals("headless", (Web.sidebarSessionMode(js) as com.tether.app.protocol.tree.JsStr).value)
        assertEquals("Worktree with a service", Web.sidebarSessionName(js))
    }

    @Test fun theServersExplicitOrderWinsThenRecency() {
        val out = rows(F.drawerSessions.take(4), orders = mapOf(F.ROOT to listOf("live:s03", "live:s01")))
        assertEquals(listOf("live:s03", "live:s01", "live:s02", "live:s04"), out.map { it.key })
    }

    @Test fun lastActiveSortKeysOnLastMessageAt() {
        val a = F.live("a", "a", ago = 10).copy(startedAt = F.NOW - 1_000, lastMessageAt = F.NOW - 900_000)
        val b = F.live("b", "b", ago = 20).copy(startedAt = F.NOW - 2_000, lastMessageAt = F.NOW - 100_000)
        assertEquals(listOf("live:a", "live:b"), rows(listOf(a, b)).map { it.key })
        assertEquals(listOf("live:b", "live:a"), rows(listOf(a, b), sort = SidebarSort.LastActive).map { it.key })
    }

    @Test fun eachSessionBelongsToTheDeepestListedWorkspace() {
        val out = rows(F.groupSessions.filter { !it.runtimeArchived }, workspaces = listOf(F.ROOT, F.APP, F.DOCS))
        val byWs = out.groupBy { it.workspace }
        assertEquals(listOf("live:g6"), byWs[F.ROOT]!!.map { it.key })
        assertEquals(setOf("live:g3", "live:g4", "live:g5"), byWs[F.APP]!!.map { it.key }.toSet())
        assertEquals(2, byWs[F.DOCS]!!.size)
    }

    @Test fun liveSessionsOfOneNativeChatCollapseToTheNewest() {
        val older = F.live("x1", "chat", ago = 30).copy(resumeTargetNativeId = "native-1")
        val newer = F.live("x2", "chat", ago = 5).copy(resumeTargetNativeId = "native-1")
        assertEquals(listOf("live:x2"), rows(listOf(older, newer)).map { it.key })
    }

    @Test fun aLinkedHistoryRowIsUnreadUntilSeen() {
        val live = F.live("l", "done", ago = 12, historyId = "h")
        val history = F.history("h", "done", cwd = F.ROOT, ago = 12)
        val unseen = rows(listOf(live), mapOf(F.ROOT to listOf(history))).single()
        assertEquals("history:h", unseen.key)
        assertTrue(unseen.unread)
        assertTrue(Web.hasUnseenWork(unseen.js))
        // Seen locally after the update (lastSeenSessions) → read.
        assertFalse(rows(listOf(live), mapOf(F.ROOT to listOf(history)), lastSeen = mapOf("h" to F.NOW)).single().unread)
        // Seen on another device (the server's authoritative lastSeenAt, v68) → read.
        val seenElsewhere = history.copy(lastSeenAt = F.NOW)
        assertFalse(rows(listOf(live), mapOf(F.ROOT to listOf(seenElsewhere))).single().unread)
        // The open row is never unread.
        assertFalse(rows(listOf(live), mapOf(F.ROOT to listOf(history)), activeId = "l").single().unread)
    }

    @Test fun aRunningOrWaitingRowIsNeverUnread() {
        for (status in listOf("active", "waiting")) {
            val live = F.live("l", "busy", status = status, ago = 1, historyId = "h")
            assertFalse(status, rows(listOf(live), mapOf(F.ROOT to listOf(F.history("h", "busy", cwd = F.ROOT, ago = 1)))).single().unread)
        }
    }

    @Test fun theDigestShowsOnlyOnANotOpenStillUnseenRow() {
        val digest = HistoryDigest(2, "tests pass")
        val history = F.history("h", "n", cwd = F.ROOT, ago = 5, digest = digest)
        val shown = rows(emptyList(), mapOf(F.ROOT to listOf(history))).single()
        assertTrue(shown.js["digest"] != null)
        assertNull(rows(emptyList(), mapOf(F.ROOT to listOf(history)), lastSeen = mapOf("h" to F.NOW)).single().js["digest"])
        assertNull(rows(emptyList(), mapOf(F.ROOT to listOf(history)), opening = "h").single().js["digest"])
    }

    @Test fun titleFilterAndHarnessNarrowWithoutResorting() {
        val all = rows(F.drawerSessions)
        assertEquals(listOf("live:s05"), SidebarModel.filteredSessions(all, "", "codex").map { it.key })
        assertEquals(listOf("live:s05", "live:s07"), SidebarModel.filteredSessions(all, " TOOL ", null).map { it.key })
        assertEquals(all, SidebarModel.filteredSessions(all, "  ", null))
    }

    @Test fun projectActivityCountsWaitingAndActivePerBlock() {
        val activity = SidebarModel.projectActivity(F.groupSessions, listOf(F.DOCS, F.APP))
        assertEquals(WorkspaceActivity(1, 0), activity[F.DOCS])
        assertEquals(WorkspaceActivity(0, 1), activity[F.APP])
    }

    @Test fun pinnedServerListWinsOverTheDevicesAndTheCurrentOneJoinsLast() {
        assertEquals(listOf(F.APP), SidebarModel.pinnedWorkspaces(listOf(F.APP), listOf(F.DOCS)))
        assertEquals(listOf(F.DOCS), SidebarModel.pinnedWorkspaces(null, listOf(F.DOCS)))
        assertEquals(listOf(F.DOCS, F.ROOT), SidebarModel.sidebarWorkspaces(listOf(F.DOCS), F.ROOT))
        assertEquals(listOf(F.DOCS), SidebarModel.sidebarWorkspaces(listOf(F.DOCS), F.DOCS))
    }

    // ---- the view (session-sidebar.tsx:549-652) ----------------------------------------------

    @Test fun archivedRowsLeaveTheListForTheArchivedGroup() {
        val state = F.state(F.groupSessions, pinned = listOf(F.DOCS, F.APP), current = F.APP)
        val view = SidebarViewModel.view(state)
        assertEquals(setOf("live:g7", "live:g8"), view.archived.map { it.key }.toSet())
        assertTrue(view.blocks.flatMap { it.rows }.none { it.live?.runtimeArchived == true })
        assertEquals(5, view.openCount)
    }

    @Test fun delegateChildrenGroupUnderTheirParentAndNeverJoinTheDragOrder() {
        val state = F.state(F.groupSessions, pinned = listOf(F.DOCS, F.APP), current = F.APP)
        val view = SidebarViewModel.view(state)
        val app = view.blocks.single { it.workspace == F.APP }
        assertEquals(listOf("live:g3"), app.rows.map { it.key })
        assertEquals(listOf("live:g4", "live:g5"), app.childrenByKey["live:g3"]!!.map { it.key })
        // session-sidebar.tsx:705-707 filters sidebarSessions (archived rows included), not the page.
        assertEquals(listOf("live:g3", "live:g7"), SidebarViewModel.dragBaseOrder(state, F.APP, view.delegateChildKeys))
    }

    @Test fun aLensForcesBlocksOpenDropsEmptyOnesAndDisablesDrag() {
        val state = F.state(F.groupSessions, pinned = listOf(F.DOCS, F.APP), current = F.APP, collapsed = listOf(F.DOCS, F.APP), activeOnly = true)
        val view = SidebarViewModel.view(state)
        assertTrue(view.filtering)
        assertFalse(view.dragEnabled)
        assertTrue(view.blocks.none { it.collapsed })
        // Active Only keeps work in progress, flat (no delegate grouping under a lens).
        assertEquals(setOf("live:g1", "live:g3"), view.blocks.flatMap { it.rows }.map { it.key }.toSet())
    }

    @Test fun theUnreadLensKeepsOnlyUnseenWork() {
        val state = F.state(F.statusSessions, histories = F.statusHistories, unreadOnly = true)
        val keys = SidebarViewModel.view(state).blocks.flatMap { it.rows }.map { it.key }
        assertEquals(setOf("history:h-away", "history:h-old"), keys.toSet())
    }

    @Test fun blocksPageTenRowsAndNeverHideTheSelectedOne() {
        val many = (1..14).map { F.live("p$it", "row $it", ago = it.toLong()) }
        val state = F.state(many, activeId = "p13")
        val block = SidebarViewModel.view(state).blocks.single()
        assertEquals(11, block.rows.size)
        assertEquals("live:p13", block.rows.last().key)
        assertEquals(3, block.remaining)
        val more = SidebarViewModel.view(state, mapOf(F.ROOT to 20)).blocks.single()
        assertEquals(14, more.rows.size)
        assertTrue(more.expanded)
    }

    @Test fun aDragPreviewReordersItsBlock() {
        val state = F.state(F.drawerSessions.take(3))
        val preview = DragPreview("live:s03", listOf("live:s03", "live:s01", "live:s02"))
        assertEquals(preview.order, SidebarViewModel.view(state, drag = preview).blocks.single().rows.map { it.key })
        assertEquals(listOf("live:s02", "live:s01", "live:s03"), SidebarViewModel.move(listOf("live:s01", "live:s02", "live:s03"), "live:s02", "live:s01"))
    }
}
