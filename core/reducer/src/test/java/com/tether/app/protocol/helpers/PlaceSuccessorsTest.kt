package com.tether.app.protocol.helpers

import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** ta-ozn2.3: lib/sidebar-order.mjs placeSuccessors (GH #273) at fe4aa8c0. */
class PlaceSuccessorsTest {
    private fun row(key: String, id: String, from: String? = null, workspace: String = "/w"): JsObj = JsObj.of(
        "key" to JsStr(key),
        "workspace" to JsStr(workspace),
        "live" to JsObj.of("id" to JsStr(id), "handedOffFrom" to from?.let { JsStr(it) }),
    )

    private fun keys(rows: List<JsValue>) = rows.map { (it["key"] as JsStr).value }

    @Test fun aSuccessorSitsDirectlyAboveItsSource() {
        val rows = listOf(row("X", "x"), row("A", "a"), row("B", "b", from = "a"), row("Y", "y"))
        assertEquals(listOf("X", "B", "A", "Y"), keys(SidebarOrder.placeSuccessors(rows)))
    }

    @Test fun aChainStacksNewestFirstRecursively() {
        val rows = listOf(row("A", "a"), row("B", "b", from = "a"), row("C", "c", from = "b"))
        assertEquals(listOf("C", "B", "A"), keys(SidebarOrder.placeSuccessors(rows)))
    }

    @Test fun aDraggedSuccessorKeepsItsManualRank() {
        val rows = listOf(row("B", "b", from = "a"), row("X", "x"), row("A", "a"))
        assertEquals(listOf("B", "X", "A"), keys(SidebarOrder.placeSuccessors(rows, setOf("B"))))
    }

    @Test fun noListedSourceAndCyclesKeepTheirPlaceAndNothingIsLost() {
        val rows = listOf(row("B", "b", from = "gone"), row("P", "p", from = "q"), row("Q", "q", from = "p"), row("S", "s", from = "s"))
        assertEquals(setOf("B", "P", "Q", "S"), keys(SidebarOrder.placeSuccessors(rows)).toSet())
        assertEquals(4, SidebarOrder.placeSuccessors(rows).size)
        val plain = listOf(row("A", "a"), row("B", "b"))
        assertSame(plain, SidebarOrder.placeSuccessors(plain))
    }

    @Test fun theBrowsingViewPlacesThemAndAFilterLensStaysFlat() {
        val rows = listOf(row("A", "a"), row("B", "b", from = "a"))
        val byWorkspace = mapOf("/w" to rows)
        assertEquals(listOf("B", "A"), keys(SessionSidebar.groupedByWorkspace(byWorkspace, filtering = false).getValue("/w").rows))
        assertEquals(listOf("A", "B"), keys(SessionSidebar.groupedByWorkspace(byWorkspace, filtering = true).getValue("/w").rows))
        assertEquals(listOf("B", "A"), keys(SessionSidebar.groupedByWorkspace(byWorkspace, false, mapOf("/w" to listOf("B"))).getValue("/w").rows).reversed())
    }
}
