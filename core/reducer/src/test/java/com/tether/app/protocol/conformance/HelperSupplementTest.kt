package com.tether.app.protocol.conformance

import com.tether.app.protocol.helpers.JsError
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * T2.2 (verifier round 1): cases the S0.2 corpus never recorded, generated ONCE from the real web
 * modules by tools/parity/gen-helper-supplement.mjs (Node 22, ICU 77.1) and checked in as
 * src/test/resources/helpers/supplement.json:
 *  - `localeCompare` order and the full pairwise sign matrix over adversarial strings (hyphens,
 *    spaces, digits, case, accents, punctuation, the empty string) — the ICU collator the ports use;
 *  - collation-sensitive helper calls (label/key tie-breaks), compactNumber past the T unit, and
 *    calls where an `undefined` and a `null` argument diverge.
 * A V8 TypeError's message is engine wording ("(intermediate value)…"), so for TypeError only the
 * error name is compared; every other throw compares name and message.
 */
@RunWith(Parameterized::class)
class HelperSupplementTest(private val index: Int, private val label: String) {

    companion object {
        val fixture: JsObj by lazy {
            CanonicalJson.parse(HelperSupplementTest::class.java.getResource("/helpers/supplement.json")!!.readText()) as JsObj
        }

        private val cases: List<JsObj> get() = (fixture["cases"] as JsArr).map { it as JsObj }

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun params(): List<Array<Any>> = cases.mapIndexed { i, c -> arrayOf<Any>(i, "${c["table"].str}.${c["fn"].str}") }
    }

    @Test
    fun matchesTheWeb() {
        val case = cases[index]
        val table = case["table"].str!!
        val fn = case["fn"].str!!
        val args = (case["args"] as JsArr).map { CanonicalJson.decodeTagged(it) }
        val outcome = runCatching { HelperAdapters.call(table, fn, HelperAdapters.Args(args, null, HelperTable.load(table).constants)) }
        val shown = "$table.$fn(${JsCodec.canonical(case["args"]!!)})"
        val throws = case["throws"] as? JsObj
        if (throws != null) {
            val error = outcome.exceptionOrNull() as? JsError
                ?: fail("$shown: expected ${throws["name"].str}: ${throws["message"].str}, got ${outcome.exceptionOrNull() ?: outcome.getOrNull()}").let { return }
            assertEquals("$shown error name", throws["name"].str, error.name)
            if (error.name != "TypeError") assertEquals("$shown error message", throws["message"].str, error.message)
            return
        }
        outcome.exceptionOrNull()?.let { throw AssertionError("$shown threw $it", it) }
        val actual = CanonicalJson.encodeTagged(outcome.getOrNull())
        val diff = TreeDiff.diff(case["result"], actual, limit = 20)
        assertTrue(
            "$shown\n  expected ${JsCodec.canonical(case["result"]!!)}\n  actual   ${JsCodec.canonical(actual)}\n" +
                diff.joinToString("\n") { it.render() },
            diff.isEmpty(),
        )
    }
}

/** The ICU collator the helpers are given reproduces Node's `localeCompare` exactly. */
class LocaleCompareDifferentialTest {

    private val collation = HelperSupplementTest.fixture["collation"] as JsObj
    private val strings = (collation["strings"] as JsArr).map { it.str!! }
    private val collator = IcuTestCollator.EN_US

    @Test
    fun fixtureIsAdversarialEnough() {
        assertTrue(strings.size >= 40)
        assertTrue(strings.contains("") && strings.any { '-' in it } && strings.any { ' ' in it } && strings.any { it.any(Char::isDigit) })
        assertEquals("en-US", (HelperSupplementTest.fixture["collator"] as JsObj)["locale"].str)
        assertEquals("variant", (HelperSupplementTest.fixture["collator"] as JsObj)["sensitivity"].str)
    }

    @Test
    fun sortOrderMatchesNode() {
        assertEquals((collation["sorted"] as JsArr).map { it.str!! }, strings.sortedWith { a, b -> collator.compare(a, b) })
    }

    @Test
    fun everyPairwiseSignMatchesNode() {
        val signs = collation["signs"] as JsArr
        val mismatches = ArrayList<String>()
        for ((i, a) in strings.withIndex()) {
            val row = signs[i] as JsArr
            for ((j, b) in strings.withIndex()) {
                val expected = (row[j] as JsNum).value.toInt()
                val actual = Integer.signum(collator.compare(a, b))
                if (expected != actual) mismatches.add("compare(${JsCodec.stringify(com.tether.app.protocol.tree.JsStr(a))}, ${JsCodec.stringify(com.tether.app.protocol.tree.JsStr(b))}) = $actual, node $expected")
            }
        }
        assertTrue(mismatches.take(20).joinToString("\n"), mismatches.isEmpty())
    }

    /** The JDK collator the first port used fails this fixture — the reason collation is injected. */
    @Test
    fun jdkCollatorWouldNotMatch() {
        val jdk = java.text.Collator.getInstance(java.util.Locale.ENGLISH)
        assertTrue(strings.sortedWith { a, b -> jdk.compare(a, b) } != (collation["sorted"] as JsArr).map { it.str!! })
    }
}
