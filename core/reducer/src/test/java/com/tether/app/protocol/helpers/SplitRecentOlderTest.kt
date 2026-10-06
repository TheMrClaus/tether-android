package com.tether.app.protocol.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

/** lib/sidebar-workspaces.mjs splitRecentOlder (tether #244): the sidebar's "Older" band, pure. */
class SplitRecentOlderTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L
    private val horizon = SidebarWorkspaces.SIDEBAR_RECENT_MS

    private data class Row(val id: String, val at: Double?, val keep: Boolean = false)

    private fun split(vararg rows: Row) = SidebarWorkspaces.splitRecentOlder(rows.toList(), now, horizon, { it.at }) { it.keep }

    @Test fun theHorizonIsSevenDays() {
        assertEquals(7 * day, horizon)
    }

    @Test fun aRowIdleForTheWholeHorizonIsOlderAndOneJustInsideIsNot() {
        val out = split(
            Row("fresh", (now - 1_000).toDouble()),
            Row("just-inside", (now - 7 * day + 1).toDouble()),
            Row("exactly", (now - 7 * day).toDouble()),
            Row("old", (now - 30 * day).toDouble()),
        )
        assertEquals(listOf("fresh", "just-inside"), out.recent.map { it.id })
        assertEquals(listOf("exactly", "old"), out.older.map { it.id })
    }

    @Test fun anUnknownActivityTimeIsNeverFolded() {
        val out = split(Row("none", null), Row("nan", Double.NaN), Row("zero", 0.0), Row("negative", -5.0), Row("inf", Double.POSITIVE_INFINITY))
        assertEquals(listOf("none", "nan", "zero", "negative", "inf"), out.recent.map { it.id })
        assertEquals(emptyList<Row>(), out.older)
    }

    @Test fun aKeptRowStaysEvenWhenOld() {
        val out = split(Row("pinned", (now - 40 * day).toDouble(), keep = true), Row("old", (now - 40 * day).toDouble()))
        assertEquals(listOf("pinned"), out.recent.map { it.id })
        assertEquals(listOf("old"), out.older.map { it.id })
    }

    @Test fun eachBandKeepsTheRowsOriginalOrder() {
        val out = split(Row("o1", 1.0e9), Row("r1", (now).toDouble()), Row("o2", 2.0e9), Row("r2", (now - day).toDouble()))
        assertEquals(listOf("r1", "r2"), out.recent.map { it.id })
        assertEquals(listOf("o1", "o2"), out.older.map { it.id })
    }

    @Test fun withoutAKeepPredicateNothingIsKept() {
        val out = SidebarWorkspaces.splitRecentOlder(listOf(1.0e9, now.toDouble()), now, horizon, { it })
        assertEquals(listOf(now.toDouble()), out.recent)
        assertEquals(listOf(1.0e9), out.older)
    }
}
