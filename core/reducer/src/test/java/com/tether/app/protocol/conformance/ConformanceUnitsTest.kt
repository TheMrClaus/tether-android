package com.tether.app.protocol.conformance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2.1 Revisions 1–3: units.txt partitions the corpus cases and events.mjs 215–1607 exactly. */
class ConformanceUnitsTest {

    private val units = ConformanceUnits.load()

    @Test
    fun everyCorpusCaseIsInExactlyOneUnit() {
        val corpus = CorpusCase.names().toSet()
        val listed = units.cases.groupingBy { it.name }.eachCount()
        val duplicated = listed.filterValues { it > 1 }.keys
        assertTrue("listed more than once: $duplicated", duplicated.isEmpty())
        assertEquals("corpus cases missing from units.txt", emptySet<String>(), corpus - listed.keys)
        assertEquals("units.txt names cases the corpus does not have", emptySet<String>(), listed.keys - corpus)
        assertEquals(70, corpus.size)
    }

    @Test
    fun revision1UnitIHoldsTheFixturesAndThreeNamedCases() {
        val unitI = units.cases.filter { it.unit == "I" }.map { it.name }.toSet()
        val fixtures = CorpusCase.names().filter { it.startsWith("fixture-") }.toSet()
        assertEquals(16, fixtures.size)
        assertEquals(fixtures + setOf("provider-projection-limits", "codex-app-server-representative", "opencode-provider-session"), unitI)
    }

    @Test
    fun revision3SharedCasesHaveOneOwner() {
        assertEquals("A", units.byName.getValue("cancel-interrupted").unit)
        assertEquals("C", units.byName.getValue("plan-diff-reroute-review-compaction").unit)
    }

    @Test
    fun eventsMjsLines215To1607AreCoveredExactlyOnce() {
        val owners = HashMap<Int, MutableList<String>>()
        for (range in units.ranges) {
            assertTrue("bad range $range", range.from <= range.to)
            for (line in range.from..range.to) owners.getOrPut(line) { ArrayList() }.add(range.unit)
        }
        val uncovered = (215..1607).filter { it !in owners }
        val doubled = owners.filterValues { it.size > 1 }.keys.sorted()
        val outside = owners.keys.filter { it !in 215..1607 }.sorted()
        assertTrue("uncovered events.mjs lines: ${compress(uncovered)}", uncovered.isEmpty())
        assertTrue("lines owned by more than one unit: ${compress(doubled)}", doubled.isEmpty())
        assertTrue("ranges outside 215–1607: ${compress(outside)}", outside.isEmpty())
    }

    @Test
    fun revision2BoundariesHold() {
        fun ownerOf(line: Int) = units.ranges.single { line in it.from..it.to }.unit
        assertEquals("H1", ownerOf(557)) // sameStringList
        assertEquals("H1", ownerOf(634)) // shared normalizers end
        assertEquals("B", ownerOf(635)) // B starts at normalizeGrantedPermissions
        assertEquals("C", ownerOf(562)) // CLI helpers
        assertEquals("C", ownerOf(586))
    }

    private fun compress(lines: List<Int>): String {
        if (lines.isEmpty()) return "[]"
        val out = ArrayList<String>()
        var start = lines[0]
        var prev = lines[0]
        for (l in lines.drop(1) + Int.MIN_VALUE) {
            if (l == prev + 1) {
                prev = l
                continue
            }
            out.add(if (start == prev) "$start" else "$start-$prev")
            start = l
            prev = l
        }
        return out.joinToString(",")
    }
}
