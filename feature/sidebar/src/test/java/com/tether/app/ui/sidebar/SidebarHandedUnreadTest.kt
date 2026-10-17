package com.tether.app.ui.sidebar

import com.tether.app.protocol.helpers.SessionSidebar as Web
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.HistoryDigest
import com.tether.app.protocol.model.HistorySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ta-hkk7.2 (W43): a handed-over session (live `handedOffTo`) shows no unread dot, digest or chain "N new" — only standalone / latest do. */
class SidebarHandedUnreadTest {
    private val F = SidebarFixtures
    private val digest = HistoryDigest(3, "tests pass")

    private fun hist(id: String, ago: Long = 10) = F.history(id, "n$id", cwd = F.APP, ago = ago, digest = digest)

    // live.updatedAt is AHEAD of history.updatedAt: the case the server's lastSeenAt clamp cannot reach.
    private fun live(id: String, h: String, status: String = "ready", ago: Long = 2, to: String? = null, from: String? = null) =
        F.live(id, "Chat $id", status = status, ago = ago, historyId = h, handedOffTo = to, handedOffFrom = from)

    private fun rows(sessions: List<AgentSession>, histories: List<HistorySession>, lastSeen: Map<String, Long> = emptyMap()) =
        SidebarModel.sidebarSessions(sessions, mapOf(F.APP to histories), listOf(F.APP), lastSeen, emptyMap(),
            com.tether.app.ui.prefs.SidebarSort.Created, null, null, F.collator)

    @Test fun aHandedOverReadyOrExitedMemberHasNoDotAndNoDigest() {
        for (status in listOf("ready", "exited")) {
            val r = rows(listOf(live("a", "h", status = status, to = "b")), listOf(hist("h"))).single()
            assertFalse(status, r.unread)
            assertNull(status, r.js["digest"])
            assertFalse(status, Web.hasUnseenWork(r.js))
        }
        // The same row without handedOffTo is unread with its digest (the red/green control).
        val plain = rows(listOf(live("a", "h")), listOf(hist("h"))).single()
        assertTrue(plain.unread)
        assertTrue(plain.js["digest"] != null)
    }

    @Test fun aHandedOverActiveOrWaitingMemberLosesItsDigestToo() {
        for (status in listOf("active", "waiting")) {
            val r = rows(listOf(live("a", "h", status = status, to = "b")), listOf(hist("h"))).single()
            assertFalse(status, r.unread)
            assertNull(status, r.js["digest"])
        }
    }

    private fun chainView(headSeen: Boolean): BlockView {
        val sessions = listOf(live("a", "ha", ago = 6, to = "b"), live("b", "hb", ago = 3, from = "a"))
        val histories = listOf(hist("ha", ago = 8), hist("hb", ago = 4))
        val seen = if (headSeen) mapOf("hb" to F.NOW) else emptyMap()
        return SidebarViewModel.view(F.state(sessions, current = F.APP, histories = mapOf(F.APP to histories), lastSeen = seen)).blocks.single()
    }

    @Test fun theChainHeadShowsNoNewSuffixForAHandedOverOlderMemberAndKeepsItsOwnBadge() {
        val seen = chainView(headSeen = true)
        assertNull(seen.chainSummaries.getValue("history:hb").suffix)
        val unseen = chainView(headSeen = false)
        assertNull(unseen.chainSummaries.getValue("history:hb").suffix)
        val head = unseen.rows.single { it.key == "history:hb" }
        assertTrue(head.unread)
        assertTrue(head.js["digest"] != null)
    }

    @Test fun theUnreadLensDropsTheHandedOverMemberButWorkspaceCountsStillCountIt() {
        val sessions = listOf(live("a", "ha", ago = 6, to = "b"), live("b", "hb", ago = 3, from = "a"), live("c", "hc", ago = 1))
        val histories = listOf(hist("ha", ago = 8), hist("hb", ago = 4), hist("hc", ago = 2))
        val base = F.state(sessions, current = F.APP, histories = mapOf(F.APP to histories), lastSeen = mapOf("hb" to F.NOW))
        val lens = F.state(sessions, current = F.APP, histories = mapOf(F.APP to histories), lastSeen = mapOf("hb" to F.NOW), unreadOnly = true)
        val keys = SidebarViewModel.view(lens).blocks.flatMap { it.rows }.map { it.key }
        assertEquals(listOf("history:hc"), keys)
        assertEquals(3, SidebarViewModel.view(base).blocks.single().openCount)
    }

    @Test fun nameGroupsAndHandoverPendingWithoutHandedOffToAreUnchanged() {
        val out = rows(
            listOf(live("g1", "hg1", ago = 3).copy(name = "Monsters 1"), live("g2", "hg2", ago = 2).copy(name = "Monsters 2"), live("p", "hp", ago = 1).copy(handoverPending = true)),
            listOf(hist("hg1"), hist("hg2"), hist("hp")),
        )
        assertTrue(out.all { it.unread })
        assertTrue(out.all { it.js["digest"] != null })
    }

    @Test fun onlyTheNewestMemberOfALongerChainCanBeUnread() {
        val sessions = listOf(live("a", "ha", ago = 6, to = "b"), live("b", "hb", ago = 4, to = "c", from = "a"), live("c", "hc", ago = 2, from = "b"))
        val out = rows(sessions, listOf(hist("ha"), hist("hb"), hist("hc"))).associateBy { it.key }
        assertFalse(out.getValue("history:ha").unread)
        assertFalse(out.getValue("history:hb").unread)
        assertTrue(out.getValue("history:hc").unread)
    }

    @Test fun aHistoryOnlyRowAndARowWhoseLiveIsGoneKeepTodaysBehaviour() {
        // No live: HistorySession carries no handedOffTo, so the row stays unread (the server clamp's job).
        val r = rows(emptyList(), listOf(hist("h"))).single()
        assertTrue(r.unread)
        assertTrue(r.js["digest"] != null)
    }

    @Test fun aHandedOverRowStillOpens() {
        val r = rows(listOf(live("a", "h", to = "b")), listOf(hist("h"))).single()
        assertEquals("a", r.live?.id)
        assertEquals("history:h", r.key)
        assertEquals("b", r.live?.handedOffTo)
    }
}
