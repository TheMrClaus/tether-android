package com.tether.app.ui.chat

import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T6.2 round 5 (security re-check R4-M1, R4-M2, R4-L1): the parsing that runs BEFORE the card
 * budgets is linear and bounded too. The `diff --git` header match equals the old regex on the
 * corpus and on fuzzed headers without its quadratic backtracking; the unified-diff parse builds at
 * most the card's budget and equals the old whole-split parse + plan on every honest input; Write,
 * Edit and the git hunks never build a row per line of a 10 MB input.
 */
class ToolParsingBoundsTest {
    /** The pre-round-5 matcher, kept only as the reference. */
    private val oldHeader = Regex("^diff --git a/([^\\n\\r\\u2028\\u2029]+) b/([^\\n\\r\\u2028\\u2029]+)\\z")

    private fun old(line: String): Pair<String, String>? = oldHeader.find(line)?.let { it.groupValues[1] to it.groupValues[2] }

    @Test fun theLinearHeaderMatchEqualsTheRegexOnTheCorpusAndOnFuzz() {
        val corpus = ArrayList<String>()
        val doc = JsCodec.parse(checkNotNull(javaClass.getResource("/tool-render/expectations.json")).readText()) as JsObj
        fun collect(text: String?) = text?.split("\n")?.filter { it.startsWith("diff --git ") }?.let { corpus += it }
        (doc["diffs"] as com.tether.app.protocol.tree.JsArr).forEach { collect(((it as JsObj)["input"] as com.tether.app.protocol.tree.JsStr).value) }
        corpus += listOf(
            "diff --git a/x b/y", "diff --git a/x b/", "diff --git a/ b/y", "diff --git a/a b/b b/c", "diff --git a/a b/b\r",
            "diff --git a/a  b/b", "diff --git a/a b/b ", "diff --git a/a\u0085 b/b", "diff --git a/b/ b/", "diff --git a/ b/ b/",
            "diff --git a/x  b/y", "diff --git b/x a/y", "diff --git a/x b/y ", "diff --git a/x b/ y", "diff --git a/", "diff --git ",
            "diff --git a/ b/b/ b/c",
        )
        for (line in corpus) assertEquals(line, old(line), diffGitPaths(line))
        val random = Random(62)
        val alphabet = listOf("a", "b", " ", "/", " b/", "b/", "\r", " ", " ", "\u0085", "x", "\t", "é", "😀")
        repeat(20_000) {
            val tail = (0 until random.nextInt(0, 12)).joinToString("") { alphabet[random.nextInt(alphabet.size)] }
            val line = "diff --git a/$tail"
            assertEquals(line, old(line), diffGitPaths(line))
        }
    }

    @Test fun anAdversarialHeaderIsLinear() {
        // ~85K repeats fit under the 256K turn-diff cap: the old regex backtracks ~10^10 steps here.
        val hostile = "diff --git a/" + " b/".repeat(85_000) + "\r"
        val started = System.nanoTime()
        repeat(20) { assertEquals(null, diffGitPaths(hostile)) }
        val clean = "diff --git a/" + " b/".repeat(85_000) + "x"
        assertEquals(" b/".repeat(84_999).drop(0), diffGitPaths(clean)!!.first)
        assertTrue("linear: ${(System.nanoTime() - started) / 1_000_000} ms", (System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    // --- the bounded parse ----------------------------------------------------------------------

    /** The pre-round-5 whole-split parse + plan, as the reference for honest inputs. */
    private class OldPlan(val shown: List<List<Pair<DiffFileView, Int>>>, val groupsDrawn: Int, val hiddenRows: Int, val hiddenFiles: Int)

    private fun oldPlan(diffs: List<String>, budget: Int, headerCost: Int): OldPlan {
        val groups = diffs.map { if (it.isEmpty()) emptyList() else parseUnifiedDiff(it) }
        var left = budget
        var hiddenRows = 0
        var hiddenFiles = 0
        var drawn = 0
        val out = groups.map { files ->
            if (left <= 0) {
                hiddenFiles += maxOf(1, files.size)
                hiddenRows += files.sumOf { it.rows.size }
                return@map emptyList()
            }
            drawn++
            left -= headerCost
            files.mapNotNull { file ->
                if (left <= 0) {
                    hiddenFiles++
                    hiddenRows += file.rows.size
                    null
                } else {
                    val take = minOf(left, file.rows.size)
                    left -= take
                    file to take
                }
            }
        }
        return OldPlan(out, drawn, hiddenRows, hiddenFiles)
    }

    private fun randomDiff(random: Random): String {
        val pieces = listOf("diff --git a/f b/f", "--- a/f", "+++ b/f", "@@ -1 +1 @@", "+add", "-del", " ctx", "\\ No newline", "plain", "")
        return (0 until random.nextInt(0, 40)).joinToString("\n") { pieces[random.nextInt(pieces.size)] }
    }

    @Test fun theBoundedParseEqualsTheWholeSplitParseAndPlanOnHonestInput() {
        val random = Random(5)
        repeat(3_000) {
            val diffs = List(random.nextInt(1, 5)) { randomDiff(random) }
            val budget = random.nextInt(0, 60)
            val headerCost = random.nextInt(0, 2)
            val expected = oldPlan(diffs, budget, headerCost)
            val actual = planDiffCard(diffs, budget, headerCost)
            assertEquals("groups", expected.groupsDrawn, actual.groupsDrawn)
            assertEquals("hidden rows $diffs/$budget", expected.hiddenRows, actual.hiddenRows)
            assertEquals("hidden files", expected.hiddenFiles, actual.hiddenFiles)
            assertEquals(expected.shown.map { g -> g.size }, actual.files.map { g -> g.size })
            expected.shown.zip(actual.files).forEach { (e, a) ->
                e.zip(a).forEach { (ef, af) ->
                    assertEquals(ef.second, af.rows)
                    assertEquals(ef.first.rows.take(ef.second), af.file.rows)
                    assertEquals(ef.first.rows.size, af.file.totalRows)
                    assertEquals(ef.first.key, af.file.key)
                    assertEquals(ef.first.oldPath, af.file.oldPath)
                    assertEquals(ef.first.newPath, af.file.newPath)
                }
            }
        }
    }

    @Test fun tenMegabytesOfLinesBuildAtMostTheBudget() {
        val huge = "a\n".repeat(5_000_000)
        val runtime = Runtime.getRuntime()
        System.gc()
        val before = runtime.totalMemory() - runtime.freeMemory()
        val started = System.nanoTime()
        val plan = planDiffCard(listOf(huge, huge), headerCost = 1)
        val ms = (System.nanoTime() - started) / 1_000_000
        val after = runtime.totalMemory() - runtime.freeMemory()
        assertEquals(DIFF_CARD_MAX_ROWS - 1, plan.files[0].single().rows)
        assertEquals(5_000_001, plan.files[0].single().file.totalRows)
        assertEquals(1, plan.groupsDrawn)
        assertEquals(1, plan.hiddenFiles)
        assertEquals(5_000_001, plan.hiddenRows)
        assertTrue("bounded in time: $ms ms", ms < 3_000)
        // Two 10 MB strings are ~40 MB themselves; the parse keeps only the budget's rows.
        assertTrue("bounded in memory: ${(after - before) / 1_000_000} MB retained", after - before < 64L * 1024 * 1024)
        assertEquals(2, plan.totalFiles)
    }

    @Test fun writeAndEditNeverBuildARowPerLineOfAHugeInput() {
        val huge = "a\n".repeat(5_000_000)
        val started = System.nanoTime()
        val write = toolInputModel("Write", JsObj.of("file_path" to com.tether.app.protocol.tree.JsStr("big.txt"), "content" to com.tether.app.protocol.tree.JsStr(huge))) as ToolInputModel.Edit
        assertEquals(MAX_DIFF_ROWS, write.diffs.single().size)
        assertEquals(5_000_001, write.totals.single())
        val capped = capEdits(write.diffs, write.totals).single()
        assertEquals(200 to 4_999_801, capped.shown.size to capped.hidden)
        // An Edit over EDIT_DIFF_MAX_CHARS is shown raw (a 600-character summary), never diffed.
        val edit = toolInputModel("Edit", JsObj.of("file_path" to com.tether.app.protocol.tree.JsStr("a"), "old_string" to com.tether.app.protocol.tree.JsStr(huge), "new_string" to com.tether.app.protocol.tree.JsStr("x")))
        assertTrue(edit is ToolInputModel.Raw && edit.text.length <= 601)
        val multi = toolInputModel(
            "MultiEdit",
            JsCodec.parse("""{"file_path":"a","edits":[{"old_string":"${"y".repeat(200_000)}","new_string":"x"},{"old_string":"${"y".repeat(100_000)}","new_string":"x"}]}"""),
        )
        assertTrue("the MultiEdit budget spans every edit's strings", multi is ToolInputModel.Raw)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        // Under the size cap an Edit still diffs as before, with the row total counted.
        val small = toolInputModel("Edit", JsObj.of("file_path" to com.tether.app.protocol.tree.JsStr("a"), "old_string" to com.tether.app.protocol.tree.JsStr("1\n2\n3"), "new_string" to com.tether.app.protocol.tree.JsStr("1\nX\n3"))) as ToolInputModel.Edit
        assertEquals(lineDiff("1\n2\n3", "1\nX\n3"), small.diffs.single())
        assertEquals(listOf(4), small.totals)
        val (rows, total) = lineDiffBounded((1..1_000).joinToString("\n"), "", 10)
        assertEquals(10 to 1_001, rows.size to total)
    }

    @Test fun boundedLinesIsASplitPrefix() {
        for (text in listOf("", "a", "a\n", "\n\n", "a\nb\nc", "x\r\ny")) {
            assertEquals(text.split("\n"), boundedLines(text, 100).first)
            assertEquals(text.split("\n").size, boundedLines(text, 100).second)
        }
        val (lines, total) = boundedLines("a\n".repeat(5_000_000), DIFF_CARD_MAX_ROWS)
        assertEquals(DIFF_CARD_MAX_ROWS to 5_000_001, lines.size to total)
        assertEquals(listOf(EditDiffRow("add", "p"), EditDiffRow("add", "")), addedLines("p\n", 5).first)
    }

    @Test fun pathsAreCutBeforeTheyAreDrawn() {
        val long = "d/".repeat(1_000_000)
        assertEquals(PATH_MAX + 1, cutLine(long, PATH_MAX).length)
        assertTrue(cutLine(long, PATH_MAX).breakAnywhere().length < 2 * PATH_MAX + 2)
    }
}
