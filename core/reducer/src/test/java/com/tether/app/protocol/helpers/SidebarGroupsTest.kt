package com.tether.app.protocol.helpers

import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.helpers.SidebarOrder.HandoverChainKeys
import com.tether.app.protocol.helpers.SidebarOrder.SequenceName
import com.tether.app.protocol.helpers.SidebarOrder.SidebarGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * ta-ftl7: Kotlin port of tether ac67c5e4 tests/sidebar-groups.test.mjs. The corpus (SidebarChainsCaseTableTest) has no case for
 * bandRecencyOf / dragChainKeysByWorkspace, so this suite is their oracle (agreement, property, F1-F5, D-L34-26/27/38).
 */
class SidebarGroupsTest {
    private val day = 86_400_000.0

    /** A row `key` has live id `L<key>`; `from` = handedOffFrom; `name` is what nameOf reads (top level AND live.name). */
    private fun row(
        key: String, name: String?, from: String? = null, updatedAt: Double = 0.0, lastMessageAt: Double? = null,
        handedOffTo: String? = null, workspace: String? = null,
    ): JsObj {
        val live = JsObj.of(
            "id" to JsStr("L$key"), "handedOffFrom" to from?.let { JsStr("L$it") }, "handedOffTo" to handedOffTo?.let { JsStr(it) },
            "name" to name?.let { JsStr(it) },
        )
        return JsObj.of(
            "key" to JsStr(key), "name" to name?.let { JsStr(it) }, "createdAt" to JsNum(0.0), "updatedAt" to JsNum(updatedAt),
            "lastMessageAt" to lastMessageAt?.let { JsNum(it) }, "workspace" to workspace?.let { JsStr(it) }, "live" to live,
        )
    }

    private fun hist(key: String, name: String): JsObj =
        JsObj.of("key" to JsStr(key), "name" to JsStr(name), "createdAt" to JsNum(0.0), "updatedAt" to JsNum(0.0))

    private fun keys(list: List<JsValue>) = list.map { (it["key"] as JsStr).value }

    private data class Shape(val head: String, val older: List<String>, val multiUnit: Boolean, val anchorKey: String)

    private fun shape(groups: List<SidebarGroup>) = groups.map { Shape(keys(listOf(it.head)).single(), keys(it.older), it.multiUnit, it.anchorKey) }

    private data class Rendered(val rows: List<String>, val older: Map<String, List<String>>)

    private fun fold(rows: List<JsValue>, groups: List<SidebarGroup>): Rendered {
        val f = SidebarOrder.foldHandoverChains(SidebarOrder.placeGroupMembers(rows, groups), groups.map { it.asChain() })
        return Rendered(keys(f.rows), f.olderByHeadKey.mapValues { keys(it.value) })
    }

    private fun render(raw: List<JsValue>, keep: Set<String> = emptySet()): Rendered {
        val rows = SidebarOrder.placeSuccessors(raw, keep)
        return fold(rows, SidebarOrder.sidebarGroups(rows))
    }

    private fun chainKeys(list: List<JsValue>) =
        SidebarOrder.sidebarGroups(list).map { HandoverChainKeys(keys(listOf(it.head)).single(), keys(it.older), it.anchorKey) }

    private fun dragPreview(raw: List<JsValue>): List<JsValue> {
        val ck = chainKeys(raw)
        val expanded = SidebarOrder.expandChainOrder(SidebarOrder.collapseChainOrder(keys(raw), ck), ck)
        val byKey = raw.associateBy { (it["key"] as JsStr).value }
        return expanded.map { byKey.getValue(it) }
    }

    // ---- parseSequenceName (D-L34-28) ----
    @Test fun parseSequenceNameTable() {
        val cases: List<Pair<String?, SequenceName?>> = listOf(
            "monsters3" to SequenceName("monsters", 3), "realms 14" to SequenceName("realms", 14),
            "  Realms   14  " to SequenceName("realms", 14), "Monsters-3" to SequenceName("monsters", 3),
            "monsters_#3" to SequenceName("monsters", 3), "x01" to SequenceName("x", 1), "x000" to SequenceName("x", 0),
            "v1.2" to SequenceName("v1", 2), "Report 2025" to null, "PR 1234" to null, "foo 123" to SequenceName("foo", 123),
            "#3" to null, "2 3" to null, "foo 3." to null, "monsters" to null, "" to null, "Ärger 2" to SequenceName("ärger", 2),
            "ärger 3" to SequenceName("ärger", 3), "foo\n3" to null, "\u00a0foo 3" to SequenceName("\u00a0foo", 3), null to null,
            "foo  bar 3" to SequenceName("foo bar", 3), "foo\t\tbar 3" to SequenceName("foo bar", 3), "foo \t bar4" to SequenceName("foo bar", 4),
            // U+0085 is a JS non-terminator for `.` (Java's `.` would refuse it): the base keeps it
            "foo\u0085 3" to SequenceName("foo\u0085", 3),
            "foo 3 " to SequenceName("foo", 3), "foo 3\n" to SequenceName("foo", 3), "foo 3\u2028" to null,
        )
        for ((input, expected) in cases) assertEquals("parse(${input?.let { "\"$it\"" }})", expected, SidebarOrder.parseSequenceName(input))
    }

    // ---- sidebarGroups order (D-L34-27) ----
    @Test fun nDescendingHeadIsHighestNumber() {
        val g = SidebarOrder.sidebarGroups(listOf(row("a", "m 1"), row("b", "m 3"), row("c", "m 2")))
        assertEquals(listOf(Shape("b", listOf("c", "a"), true, "b")), shape(g))
    }

    @Test fun handedOffHeadRanksLastEvenWithHighestN() {
        val g = SidebarOrder.sidebarGroups(listOf(row("a", "m 1"), row("b", "m 3", handedOffTo = "Lz"), row("c", "m 2")))
        assertEquals(Shape("c", listOf("a", "b"), true, "c"), shape(g)[0])
    }

    @Test fun equalNRecencyThenCodeUnitKey() {
        val a = SidebarOrder.sidebarGroups(listOf(row("x", "m 2", updatedAt = 5.0), row("y", "m 2", updatedAt = 9.0)))
        assertEquals("y", keys(listOf(a[0].head)).single())
        val b = SidebarOrder.sidebarGroups(listOf(row("x", "m 2", updatedAt = 5.0, lastMessageAt = 50.0), row("y", "m 2", updatedAt = 9.0)))
        assertEquals("x", keys(listOf(b[0].head)).single())
        val c = SidebarOrder.sidebarGroups(listOf(row("b", "m 2"), row("B", "m 2"), row("a", "m 2")))
        assertEquals(listOf("B", "a", "b"), keys(listOf(c[0].head) + c[0].older)) // "B" < "a" in UTF-16 code units
    }

    // ---- membership ----
    @Test fun headNameOnlyKeysTheUnit() {
        val g = shape(SidebarOrder.sidebarGroups(listOf(row("o", "bar 1"), row("h", "foo 5", "o"), row("f", "foo 2"), row("b", "bar 2"))))
        assertEquals(listOf(Shape("h", listOf("o", "f"), true, "o")), g)
    }

    @Test fun oneUnitBucketEqualsHandoverChainsAndSingletonStaysARow() {
        val entries = listOf(row("p", "plain"), row("c", "alpha 3", "b"), row("b", "alpha 2", "a"), row("a", "alpha 1"), row("q", "other 9"))
        val chains = SidebarOrder.handoverChains(entries)
        assertEquals(chains.map { Shape(keys(listOf(it.head)).single(), keys(it.older), false, keys(listOf(it.older.last())).single()) }, shape(SidebarOrder.sidebarGroups(entries)))
        assertTrue(SidebarOrder.sidebarGroups(listOf(row("only", "solo 1"))).isEmpty())
    }

    @Test fun historyOnlyMembersJoinANameGroup() {
        val g = SidebarOrder.sidebarGroups(listOf(hist("h1", "realms 13"), row("l", "realms 14"), hist("h2", "Realms 12")))
        assertEquals(listOf(Shape("l", listOf("h1", "h2"), true, "l")), shape(g))
    }

    @Test fun twoChainsAndAManualSingletonInOneBucket() {
        val g = shape(SidebarOrder.sidebarGroups(listOf(row("a1", "w 1"), row("a2", "w 4", "a1"), row("b1", "w 2"), row("b2", "w 3", "b1"), row("s", "w 5"))))
        assertEquals(listOf(Shape("s", listOf("a2", "a1", "b2", "b1"), true, "s")), g)
    }

    @Test fun chainHeadedGroupAnchorsAtTheChainRoot() {
        val g = shape(SidebarOrder.sidebarGroups(listOf(row("r", "w 1"), row("h", "w 9", "r"), row("s", "w 2"))))
        assertEquals(listOf(Shape("h", listOf("r", "s"), true, "r")), g)
    }

    @Test fun cyclesStayFlatAndDroppedSecondSuccessorIsASingletonUnit() {
        assertTrue(SidebarOrder.sidebarGroups(listOf(row("a", null, "b"), row("b", null, "a"), row("z", null))).isEmpty())
        assertTrue(SidebarOrder.sidebarGroups(listOf(row("a", "n 1", "b"), row("b", "n 2", "a"), row("z", "n 3"))).isEmpty())
        val cycPlus = listOf(row("a", "n 1", "b"), row("b", "n 2", "a"), row("y", "n 3"), row("z", "n 4"))
        assertEquals(listOf(Shape("z", listOf("y"), true, "z")), shape(SidebarOrder.sidebarGroups(cycPlus)))
        val drop = listOf(row("c", null, "b"), row("d", null, "b"), row("b", null))
        assertEquals(SidebarOrder.handoverChains(drop).map { Shape(keys(listOf(it.head)).single(), keys(it.older), false, "b") }, shape(SidebarOrder.sidebarGroups(drop)))
        val dropN = listOf(row("c", "q 3", "b"), row("d", "q 4", "b"), row("b", "q 1"))
        assertEquals(listOf(Shape("d", listOf("c", "b"), true, "d")), shape(SidebarOrder.sidebarGroups(dropN)))
    }

    @Test fun noRowsOrUnnamedRowsFormNoGroup() {
        assertTrue(SidebarOrder.sidebarGroups(emptyList()).isEmpty())
        assertTrue(SidebarOrder.sidebarGroups(listOf(row("a", null), row("b", null))).isEmpty())
        assertEquals(1, SidebarOrder.sidebarGroups(listOf(row("p", "m 1"), row("q", "m 2")))[0].older.size)
    }

    // ---- band recency (D-L34-26) ----
    @Test fun bandRecencyIsTheMaxOverHeadAndOlder() {
        val now = 100 * day
        val head = row("h", "m 9", updatedAt = now - 30 * day)
        val older = listOf(row("o", "m 2", updatedAt = now - 1 * day))
        assertEquals(now - 1 * day, SidebarOrder.groupBandRecency(head, older), 0.0)
        assertTrue(now - SidebarOrder.groupBandRecency(head, older) < 7 * day)
        assertEquals(7.0, SidebarOrder.groupBandRecency(row("x", "m", lastMessageAt = 7.0, updatedAt = 99.0), emptyList()), 0.0)
    }

    // ---- agreement: render fold == drag consumer (D-L34-12 i, D-L34-24) ----
    @Test fun scatteredGroupRendersAndDragPreviewsAtFoo2sSlot() {
        for (raw in listOf(listOf(row("foo1", "foo 1"), row("X", "other"), row("foo2", "foo 2")), listOf(row("foo2", "foo 2"), row("X", "other"), row("foo1", "foo 1")))) {
            val r = render(raw)
            assertEquals(mapOf("foo2" to listOf("foo1")), r.older)
            val firstIsFoo1 = keys(raw)[0] == "foo1"
            assertEquals(if (firstIsFoo1) listOf("X", "foo2") else listOf("foo2", "X"), r.rows)
            val preview = dragPreview(raw)
            assertEquals(if (firstIsFoo1) listOf("X", "foo2", "foo1") else listOf("foo2", "foo1", "X"), keys(preview))
            assertEquals(r, fold(preview, SidebarOrder.sidebarGroups(preview)))
        }
    }

    @Test fun anUnrelatedDragKeepsTheScatteredGroupContiguous() {
        val raw = listOf(row("foo1", "foo 1"), row("X", "other"), row("foo2", "foo 2"), row("Y", "else"))
        val ck = chainKeys(raw)
        val collapsed = SidebarOrder.collapseChainOrder(keys(raw), ck)
        assertEquals(listOf("X", "foo2", "Y"), collapsed)
        val dragged = SidebarOrder.moveSidebarEntry(
            com.tether.app.protocol.tree.JsArr.of(collapsed.map { JsObj.of("key" to JsStr(it)) }), JsStr("Y"), JsStr("X"),
        ).map { (it as JsStr).value }
        assertEquals(listOf("Y", "X", "foo2"), dragged)
        val saved = SidebarOrder.expandChainOrder(dragged, ck)
        assertEquals(listOf("Y", "X", "foo2", "foo1"), saved)
        val byKey = raw.associateBy { (it["key"] as JsStr).value }
        assertEquals(Rendered(listOf("Y", "X", "foo2"), mapOf("foo2" to listOf("foo1"))), render(saved.map { byKey.getValue(it) }, saved.toSet()))
    }

    @Test fun propertyRenderEqualsFoldOfExpandCollapse() {
        val seeds = listOf(0x5eed1234, 0x42, 0xdeadbeef.toInt(), 0x1111, 0xabcdef, 0x7777777) + (1..18)
        var multi = 0; var chains = 0; var cycles = 0
        for (seed in seeds) {
            val rnd = Random(seed)
            repeat(500) { iter ->
                val n = 3 + rnd.nextInt(10)
                val ids = List(n) { "k$it" }
                val used = HashSet<String>()
                val rows = ids.map { key ->
                    val roll = rnd.nextDouble()
                    val name = when {
                        roll < 0.55 -> "${listOf("foo", "Bar", "baz")[rnd.nextInt(3)]} ${1 + rnd.nextInt(5)}"
                        roll < 0.7 -> "Report 2025"
                        else -> "solo${rnd.nextInt(3)}x"
                    }
                    val free = ids.filter { it !in used }
                    val from = if (rnd.nextDouble() < 0.35 && free.isNotEmpty()) free[rnd.nextInt(free.size)] else null
                    if (from != null) used += from
                    val historyOnly = rnd.nextDouble() < 0.15
                    val outTo = if (rnd.nextDouble() < 0.1) "gone" else null
                    val updated = rnd.nextInt(5).toDouble()
                    val last = if (rnd.nextDouble() < 0.3) rnd.nextInt(5).toDouble() else null
                    if (historyOnly) hist(key, name).let { JsObj.of("key" to it["key"], "name" to it["name"], "createdAt" to JsNum(0.0), "updatedAt" to JsNum(updated)) }
                    else row(key, name, from, updated, last, outTo)
                }
                val raw = rows.shuffled(rnd)
                val g = SidebarOrder.sidebarGroups(raw)
                if (g.any { it.multiUnit }) multi++
                if (g.any { !it.multiUnit }) chains++
                val live = raw.filter { it["live"] is JsObj }
                if (live.any { r -> val f = r["live"]["handedOffFrom"]; f is JsStr && live.any { o -> o["live"]["id"] == f && o["live"]["handedOffFrom"] == r["live"]["id"] } }) cycles++
                assertEquals("seed $seed iteration $iter: ${raw.map { keys(listOf(it)) }}", render(raw), render(dragPreview(raw)))
            }
        }
        assertTrue("generator coverage: multi=$multi chains=$chains cycles=$cycles", multi > 100 && chains > 100 && cycles > 0)
    }

    // ---- SB-2 fix round 1 pins ----
    @Test fun f1LoneChainKeepsHeadOnlyBandRecencyOnlyMultiUnitTakesMax() {
        val head = row("h", "alpha", "o", updatedAt = 1 * day)
        val older = row("o", "beta", updatedAt = 90 * day)
        val rows = listOf(head, older)
        val groups = SidebarOrder.sidebarGroups(rows)
        assertEquals(1, groups.size)
        assertEquals(false, groups[0].multiUnit)
        val folded = SidebarOrder.foldHandoverChains(rows, groups.map { it.asChain() })
        val multi = groups.filter { it.multiUnit }.map { keys(listOf(it.head)).single() }.toSet()
        assertEquals(1 * day, SidebarOrder.bandRecencyOf(head, multi, folded.olderByHeadKey)!!, 0.0) // head-only, NOT 90 * day
        val a = row("a", "m 9", updatedAt = 1 * day)
        val b = row("b", "m 2", updatedAt = 90 * day)
        val g2 = SidebarOrder.sidebarGroups(listOf(a, b))
        val f2 = SidebarOrder.foldHandoverChains(listOf(a, b), g2.map { it.asChain() })
        assertEquals(90 * day, SidebarOrder.bandRecencyOf(a, g2.filter { it.multiUnit }.map { keys(listOf(it.head)).single() }.toSet(), f2.olderByHeadKey)!!, 0.0)
    }

    @Test fun f3DragConsumerLaysNothingOutWhileFilteringAndADelegateChildNeverJoins() {
        val rows = listOf(row("p", "m 1", workspace = "w"), row("q", "m 2", workspace = "w"), row("d", "m 3", workspace = "w"))
        val plain = SessionSidebar.chainKeysByWorkspace(rows, emptySet(), false)
        assertEquals(mapOf("w" to listOf(HandoverChainKeys("d", listOf("q", "p"), "d"))), plain)
        assertTrue(SessionSidebar.chainKeysByWorkspace(rows, emptySet(), true).isEmpty())
        assertEquals(mapOf("w" to listOf(HandoverChainKeys("q", listOf("p"), "q"))), SessionSidebar.chainKeysByWorkspace(rows, setOf("d"), false))
    }

    @Test fun f5PlaceGroupMembersEmitsHeadFirstThenOlderInGroupOrder() {
        val raw = listOf(row("a", "g 1"), row("x", "other"), row("b", "g 2"), row("c", "g 3"))
        val groups = SidebarOrder.sidebarGroups(raw)
        assertEquals(listOf(Shape("c", listOf("b", "a"), true, "c")), shape(groups))
        assertEquals(listOf("x", "c", "b", "a"), keys(SidebarOrder.placeGroupMembers(raw, groups)))
    }

    // ---- wiring: the render pipeline reports the multi-unit heads (GroupedRows.multiUnitHeadKeys) ----
    @Test fun groupedByWorkspaceReportsMultiUnitHeadsAndNotLoneChains() {
        val rows = listOf(row("a", "m 1", workspace = "w"), row("b", "m 2", workspace = "w"), row("h", "alpha", "o", workspace = "w"), row("o", "beta", workspace = "w"))
        val grouped = SessionSidebar.groupedByWorkspace(mapOf("w" to rows), filtering = false).getValue("w")
        assertEquals(setOf("b"), grouped.multiUnitHeadKeys)
        assertEquals(setOf("b", "h"), grouped.olderByHeadKey.keys)
        assertTrue(SessionSidebar.groupedByWorkspace(mapOf("w" to rows), filtering = true).getValue("w").multiUnitHeadKeys.isEmpty())
        assertTrue(SessionSidebar.GroupedRows(rows, emptyMap()).multiUnitHeadKeys.isEmpty())
    }
}
