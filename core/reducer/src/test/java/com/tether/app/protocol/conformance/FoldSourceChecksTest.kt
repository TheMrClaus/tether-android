package com.tether.app.protocol.conformance

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2.1 source-level guards over core/reducer/.../fold/:
 *  - purity: the fold never reads a clock or a random source (events.mjs line 4 is pure);
 *  - Revision 10: every `case "…"` label in the vendored events.mjs `reduceEvent` is dispatched
 *    explicitly by Reduce.kt, and (reported now, fail-hard once unit I flips [STRICT_DEFAULT] or
 *    under `-Pparity.strict=true`) actually ported in the family file it is dispatched to.
 */
class FoldSourceChecksTest {

    companion object {
        /** Unit I flips this to true: from then on a dispatched-but-unported label fails the build. */
        const val STRICT_DEFAULT = false

        private val strict: Boolean
            get() = System.getProperty("parity.strict")?.toBooleanStrictOrNull() ?: STRICT_DEFAULT
    }

    private val foldDir = File(CanonicalJson.repoRoot, "core/reducer/src/main/java/com/tether/app/protocol/fold")
    private val eventsMjs = File(CanonicalJson.corpusDir, "reference/events.mjs")

    private fun code(file: File): String =
        file.readLines().joinToString("\n") { line -> line.replace(Regex("//.*$"), "") }

    @Test
    fun foldIsPure() {
        val forbidden = listOf(
            Regex("""\bSystemClock\b"""),
            Regex("""\bRandom\b"""),
            Regex("""\bInstant\s*\.\s*now\b"""),
            Regex("""\bClock\b"""),
            Regex("""\bSystem\s*\.\s*currentTimeMillis\b"""),
            Regex("""\bSystem\s*\.\s*nanoTime\b"""),
            Regex("""\bLocalDateTime\b|\bjava\.util\.Date\b"""),
        )
        val files = foldDir.listFiles { f -> f.name.endsWith(".kt") }!!.toList()
        assertTrue("fold/ not found at $foldDir", files.isNotEmpty())
        val hits = files.flatMap { file ->
            code(file).lines().mapIndexedNotNull { index, line ->
                forbidden.firstOrNull { it.containsMatchIn(line) }?.let { "${file.name}:${index + 1}: ${line.trim()}" }
            }
        }
        assertTrue("fold/ must not read a clock or randomness:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    /** `case "x":` labels inside `function reduceEvent(state, event) { … }` of the reference. */
    private fun jsLabels(): List<String> {
        val lines = eventsMjs.readLines()
        val start = lines.indexOfFirst { it.startsWith("function reduceEvent(") }
        assertTrue("reduceEvent not found in reference events.mjs", start >= 0)
        val end = (start + 1 until lines.size).first { lines[it] == "}" }
        val label = Regex("""^\s*case "([^"]+)":""")
        return (start..end).mapNotNull { label.find(lines[it])?.groupValues?.get(1) }
    }

    /** Reduce.kt's dispatch: label → family function name. */
    private fun kotlinDispatch(): Map<String, String> {
        val arm = Regex("""^\s*"([^"]+)"\s*->\s*(\w+)\(""")
        return code(File(foldDir, "Reduce.kt")).lines().mapNotNull { arm.find(it) }
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun reduceKtDispatchesEveryReduceEventLabelExplicitly() {
        val js = jsLabels()
        assertEquals("reference reduceEvent should have 74 case labels", 74, js.size)
        val dispatch = kotlinDispatch()
        assertEquals("labels missing from Reduce.kt's when", emptyList<String>(), js.filter { it !in dispatch })
        assertEquals("Reduce.kt dispatches labels events.mjs does not have", emptyList<String>(), dispatch.keys.filter { it !in js })
    }

    @Test
    fun everyDispatchedLabelIsPortedInItsFamilyFile() {
        val familyFiles = mapOf(
            "foldTurn" to "FoldTurn.kt",
            "foldInteraction" to "FoldInteraction.kt",
            "foldNotices" to "FoldNotices.kt",
            "foldSessionLists" to "FoldSessionLists.kt",
            "foldBackground" to "FoldBackground.kt",
        )
        val armLabels = Regex("""^\s*((?:"[^"]+"\s*,\s*)*"[^"]+")\s*->""")
        val quoted = Regex(""""([^"]+)"""")
        val ported: Map<String, Set<String>> = familyFiles.mapValues { (_, file) ->
            code(File(foldDir, file)).lines().mapNotNull { armLabels.find(it) }
                .flatMap { m -> quoted.findAll(m.groupValues[1]).map { it.groupValues[1] } }.toSet()
        }
        val unported = kotlinDispatch().filter { (label, fn) -> fn in familyFiles && label !in ported.getValue(fn) }
        val byFamily = unported.entries.groupBy({ familyFiles.getValue(it.value) }, { it.key })
        val report = "event-type labels dispatched but not yet ported (${unported.size}/74):\n" +
            byFamily.entries.joinToString("\n") { (file, labels) -> "  $file (${labels.size}): ${labels.joinToString()}" }
        println(report)
        File("build/parity/label-coverage.txt").apply { parentFile.mkdirs() }.writeText(report + "\n")
        if (strict) assertTrue(report, unported.isEmpty())
    }
}
