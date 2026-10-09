package com.tether.app.client

import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.SessionDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** ta-jtfq: the ranks use the stamps SidebarModel puts on a row (SidebarOrder.linkedStamp / lastActive), live rows among history rows. */
class SessionRanksTest {
    private val base = 1_000_000_000_000L

    private fun live(id: String, updatedAt: Long, lastMessageAt: Long?, historyId: String? = null) =
        AgentSession(id = id, provider = "claude", name = id, cwd = "/w", status = "active", startedAt = 1, updatedAt = updatedAt, lastMessageAt = lastMessageAt, historyId = historyId)

    @Test fun aLiveRowWithoutAMessageStampRanksByItsUpdatedAt() {
        val list = listOf(live("a", base + 9_400, null), live("b", base + 9_000, base + 9_500))
        val same = listOf(live("a", base + 9_450, null), live("b", base + 9_000, base + 9_500))
        val crossed = listOf(live("a", base + 9_600, null), live("b", base + 9_000, base + 9_500))
        assertEquals(SessionDisplay.ranksOf(list, emptyMap()), SessionDisplay.ranksOf(same, emptyMap()))
        assertNotEquals(SessionDisplay.ranksOf(list, emptyMap()), SessionDisplay.ranksOf(crossed, emptyMap()))
    }

    @Test fun aLinkedRowWithAMissingOrZeroStampFallsBackToItsHistoryRow() {
        val history = mapOf("h1" to base + 9_500)
        // Linked: no message stamp (or 0) and no live updatedAt (0) take the history row's stamp, tying with it.
        val missing = SessionDisplay.ranksOf(listOf(live("a", 0, null, "h1")), history)
        val zero = SessionDisplay.ranksOf(listOf(live("a", 0, 0, "h1")), history)
        assertEquals(missing, zero)
        // Not linked (the history row is not in the discovered rows): the same live row sorts by its own stamps.
        val unlinked = SessionDisplay.ranksOf(listOf(live("a", 0, null, "h2")), history)
        assertNotEquals(missing, unlinked)
        // A linked row with a real stamp keeps it.
        val real = SessionDisplay.ranksOf(listOf(live("a", base + 9_800, base + 9_900, "h1")), history)
        assertNotEquals(missing, real)
    }
}
