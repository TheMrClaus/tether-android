package com.tether.app.protocol.conformance

import com.tether.app.protocol.fold.initialSessionState
import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import java.io.File
import org.junit.AfterClass
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * T2.1 H0: the reducer conformance harness — one JUnit test per parity-corpus/reducer case.
 *
 * `state = initialSessionState(initial)` must equal `expectedInitialProjection` (always
 * enforced), then every input event is folded AS STORED and the canonical tree compared
 * (numerically) with each non-null sampled step, stopping at the first failing step. Where the
 * expected projection did not change between two sampled consecutive steps, `reduce` must return
 * the SAME object (Revision 6).
 *
 * Only cases marked `ready` in conformance/units.txt fail the build; `pending` ones (a family
 * unit not landed yet) are reported as expected-red — JUnit "skipped" with the divergence
 * report — and counted in build/parity/conformance-summary.txt, including the normalized
 * failing-path histogram per unit. `-Pparity.only=<glob>[,<glob>…]` (or `unit:<U>`) narrows it.
 */
@RunWith(Parameterized::class)
class ReducerConformanceTest(private val caseName: String, private val unit: String, private val ready: Boolean) {

    companion object {
        private val units by lazy { ConformanceUnits.load() }

        @JvmStatic
        @Parameterized.Parameters(name = "{1}/{0}")
        fun cases(): List<Array<Any>> {
            val only = System.getProperty("parity.only")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            return CorpusCase.names().mapNotNull { name ->
                val entry = units.byName[name] ?: error("corpus case $name is not listed in conformance/units.txt")
                val selected = only.isEmpty() || only.any { pattern ->
                    if (pattern.startsWith("unit:")) pattern.removePrefix("unit:") == entry.unit else globMatches(pattern, name)
                }
                if (selected) arrayOf<Any>(name, entry.unit, entry.ready) else null
            }
        }

        private fun globMatches(glob: String, name: String): Boolean {
            val regex = glob.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } }
            return Regex(regex).matches(name)
        }

        private class Result(val name: String, val unit: String, val ready: Boolean, val failure: Failure?)
        private class Failure(val step: Int, val divergences: List<Divergence>)

        private val results = java.util.concurrent.ConcurrentLinkedQueue<Result>()

        @JvmStatic
        @AfterClass
        fun writeSummary() {
            if (results.isEmpty()) return
            val text = StringBuilder()
            text.appendLine("T2.1 reducer conformance summary (${results.size} cases)")
            for (unitName in ConformanceUnits.UNITS) {
                val mine = results.filter { it.unit == unitName }
                if (mine.isEmpty()) continue
                val red = mine.filter { it.failure != null }
                val readyRed = red.count { it.ready }
                val pendingGreen = mine.filter { !it.ready && it.failure == null }
                text.appendLine(
                    "unit $unitName: ${mine.size - red.size}/${mine.size} green" +
                        (if (readyRed > 0) ", $readyRed READY CASES RED" else "") +
                        ", ${red.size - readyRed} expected-red (pending)" +
                        (if (pendingGreen.isNotEmpty()) ", pending but green (flip to ready): ${pendingGreen.joinToString { it.name }}" else ""),
                )
                for (r in red.sortedBy { it.name }) {
                    val first = r.failure!!.divergences.firstOrNull()
                    text.appendLine("    red ${r.name} @ step ${r.failure.step}: ${first?.let { "${it.path} [${it.label}]" } ?: "identity"}")
                }
                val histogram = red.flatMap { r -> r.failure!!.divergences.map { TreeDiff.normalize(it.path) }.distinct() }
                    .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12)
                if (histogram.isNotEmpty()) {
                    text.appendLine("  failing-path histogram (cases per normalized path):")
                    histogram.forEach { (path, count) -> text.appendLine("    %3d  %s".format(count, path)) }
                }
            }
            val out = File("build/parity/conformance-summary.txt")
            out.parentFile.mkdirs()
            out.writeText(text.toString())
            println(text)
        }
    }

    @Test
    fun conforms() {
        val case = CorpusCase.load(caseName)
        var state = initialSessionState(case.initial)

        // Initial projection: enforced for every case, whatever its unit's status.
        val initialDiff = TreeDiff.diff(case.expectedInitialProjection, state)
        if (initialDiff.isNotEmpty()) {
            fail(report(case, -1, null, initialDiff))
        }

        var failure: Failure? = null
        var previousExpected: Any? = case.expectedInitialProjection
        for ((index, event) in case.inputEvents.withIndex()) {
            val before = state
            state = reduce(state, event)
            val expected = case.expectedAfterEachStep.getOrNull(index)
            if (expected == null) {
                previousExpected = null
                continue
            }
            val divergences = TreeDiff.diff(expected, state, limit = 200)
            if (divergences.isNotEmpty()) {
                failure = Failure(index, divergences)
                break
            }
            // Revision 6: an unchanged projection must be the same object (Compose skips on it).
            if (previousExpected != null && previousExpected == expected && state !== before) {
                failure = Failure(index, emptyList())
                break
            }
            previousExpected = expected
        }
        results.add(Result(caseName, unit, ready, failure))
        if (failure == null) return

        val message = report(case, failure.step, case.inputEvents[failure.step], failure.divergences)
        if (ready) fail(message)
        assumeTrue("expected-red (unit $unit pending)\n$message", false)
    }

    private fun report(case: CorpusCase, step: Int, event: JsObj?, divergences: List<Divergence>): String = buildString {
        appendLine("reducer case ${case.name} (${case.sourceKind}, unit $unit) diverges at " + if (step < 0) "the initial projection" else "step $step")
        if (event != null) {
            val compact = JsCodec.canonical(event)
            appendLine("  event: " + if (compact.length > 400) compact.take(400) + "…" else compact)
        }
        if (divergences.isEmpty()) {
            appendLine("  [identity] the expected projection is unchanged from the previous step, but reduce() returned a new object")
        } else {
            divergences.take(10).forEach { appendLine(it.render()) }
            if (divergences.size > 10) appendLine("  … ${divergences.size - 10} more")
        }
    }
}
