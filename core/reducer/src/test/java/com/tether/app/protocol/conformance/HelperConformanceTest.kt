package com.tether.app.protocol.conformance

import com.tether.app.protocol.helpers.JsError
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.str
import java.io.File
import org.junit.AfterClass
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * T2.2: the pure-helper conformance harness — one JUnit test per parity-corpus/helpers table ×
 * case. Each recorded `fn(...args)` is decoded through the typed layer
 * ([CanonicalJson.decodeTagged]), called on its Kotlin port ([HelperAdapters]) with Date.now
 * pinned to the case's `nowMs` and the corpus zone (UTC), and the result is encoded back
 * ([CanonicalJson.encodeTagged]) and compared structurally with the recorded `result` (numbers
 * numerically); a `throws` case must raise the same `{ name, message }`. No case is skipped: a
 * table's own `skipped` list names the host-locale branches the exporter never recorded (see
 * HelperCorpusIntegrityTest), and those are unit-tested in HelperLocaleBranchesTest.
 *
 * `-Pparity.only=<glob>[,<glob>…]` narrows to matching `table/fn` names. A per-table summary is
 * written to build/parity/helper-conformance-summary.txt.
 */
@RunWith(Parameterized::class)
class HelperConformanceTest(private val table: String, private val index: Int, private val fn: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}/{1} {2}")
        fun cases(): List<Array<Any>> {
            val only = System.getProperty("parity.only")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            return HelperTable.names().flatMap { name ->
                HelperTable.load(name).cases.mapNotNull { case ->
                    val id = "$name/${case.fn}"
                    if (only.isEmpty() || only.any { globMatches(it, id) || globMatches(it, name) }) arrayOf<Any>(name, case.index, case.fn) else null
                }
            }
        }

        private fun globMatches(glob: String, name: String): Boolean {
            val regex = glob.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } }
            return Regex(regex).matches(name)
        }

        private val results = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Boolean>>()

        @JvmStatic
        @AfterClass
        fun writeSummary() {
            if (results.isEmpty()) return
            val byTable = results.groupBy({ it.first }, { it.second })
            val text = StringBuilder()
            val green = byTable.count { (_, r) -> r.all { it } }
            text.appendLine("T2.2 helper conformance: ${results.count { it.second }}/${results.size} cases, $green/${byTable.size} tables green")
            for ((name, r) in byTable.toSortedMap()) {
                text.appendLine("  %-28s %3d/%3d".format(name, r.count { it }, r.size))
            }
            val out = File("build/parity/helper-conformance-summary.txt")
            out.parentFile.mkdirs()
            out.writeText(text.toString())
            println(text)
        }
    }

    @Test
    fun conforms() {
        val helperTable = HelperTable.load(table)
        val case = helperTable.cases[index]
        var ok = false
        try {
            val args = HelperAdapters.Args(case.args, case.nowMs, helperTable.constants)
            val outcome = runCatching { HelperAdapters.call(table, fn, args) }
            val thrown = outcome.exceptionOrNull()
            val expectedThrow = case.throws
            if (expectedThrow != null) {
                val name = expectedThrow["name"].str
                val message = expectedThrow["message"].str
                when {
                    thrown == null -> fail(report(case, "expected a throw $name: $message, got result ${show(CanonicalJson.encodeTagged(outcome.getOrNull()))}"))
                    thrown !is JsError -> throw thrown
                    thrown.name != name || thrown.message != message ->
                        fail(report(case, "expected throw $name: $message\n  actual throw ${thrown.name}: ${thrown.message}"))
                }
            } else {
                if (thrown != null) throw AssertionError(report(case, "threw ${thrown::class.simpleName}: ${thrown.message}"), thrown)
                val actual = CanonicalJson.encodeTagged(outcome.getOrNull())
                val divergences = TreeDiff.diff(case.result, actual, limit = 50)
                if (divergences.isNotEmpty()) {
                    fail(
                        report(case, buildString {
                            appendLine("expected ${show(case.result)}")
                            appendLine("  actual   ${show(actual)}")
                            divergences.take(10).forEach { appendLine(it.render()) }
                        }),
                    )
                }
            }
            ok = true
        } finally {
            results.add(table to ok)
        }
    }

    private fun show(v: JsValue?): String {
        if (v == null) return "<absent>"
        val text = JsCodec.canonical(v)
        return if (text.length <= 600) text else text.take(600) + "…(${text.length} chars)"
    }

    private fun report(case: HelperCase, detail: String): String = buildString {
        appendLine("helper $table (${HelperTable.load(table).module}) case #$index: ${case.fn}(${show(case.raw["args"])})" +
            (case.nowMs?.let { " @nowMs=${com.tether.app.protocol.fold.numberToString(it)}" } ?: ""))
        append("  ").append(detail)
    }
}
