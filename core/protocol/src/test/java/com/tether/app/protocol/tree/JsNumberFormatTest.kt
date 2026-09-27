package com.tether.app.protocol.tree

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2.1 Revision 4: the shortest-repr ECMAScript Number::toString port. */
class JsNumberFormatTest {

    private fun js(d: Double) = JsNumberFormat.toJsString(d)

    @Test
    fun revision4Values() {
        assertEquals("5e-324", js(Double.MIN_VALUE))
        assertEquals("1e+21", js(1e21))
        assertEquals("1e-7", js(1e-7))
        assertEquals("0.30000000000000004", js(0.1 + 0.2))
        assertEquals("9007199254740994", js(Math.pow(2.0, 53.0) + 2))
        assertEquals("0", js(-0.0))
        assertEquals("1.23e-18", js(123e-20))
        assertEquals("1.7976931348623157e+308", js(Double.MAX_VALUE))
        assertEquals("999999999999999900000", js(999999999999999900000.0))
        assertEquals("0.000001", js(1e-6))
        assertEquals("1790000001000", js(1790000001000.0))
        assertEquals("0.028414199999999997", js(0.028414199999999997))
        assertEquals("NaN", js(Double.NaN))
        assertEquals("-Infinity", js(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun integersUpTo2pow53PrintPlain() {
        val rnd = Random(53)
        for (i in 0 until 20_000) {
            val v = if (i < 1000) i.toLong() else (rnd.nextLong() ushr 11) // < 2^53
            assertEquals(v.toString(), js(v.toDouble()))
            if (v != 0L) assertEquals("-$v", js(-v.toDouble()))
        }
        assertEquals("9007199254740992", js(9007199254740992.0))
    }

    /** Vectors printed by node 22 (`String(x)`), generated once by a fixed-seed script. */
    @Test
    fun matchesNodeVectors() {
        val lines = javaClass.getResourceAsStream("/js-number-vectors.txt")!!.bufferedReader().readLines()
        assertTrue(lines.size > 5000)
        val failures = lines.mapNotNull { line ->
            val (hex, expected) = line.split(' ', limit = 2)
            val d = java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(hex, 16))
            val actual = js(d)
            if (actual == expected) null else "$hex: expected $expected, got $actual"
        }
        assertEquals(failures.take(10).joinToString("\n"), 0, failures.size)
    }

    /** Independent oracle: the definition itself (fewest digits that parse back, nearest, ties even). */
    @Test
    fun matchesBruteForceOracleOnRandomDoubles() {
        val rnd = Random(20260927)
        repeat(30_000) {
            val d = java.lang.Double.longBitsToDouble(rnd.nextLong())
            if (d.isNaN() || d.isInfinite() || d == 0.0) return@repeat
            assertEquals("bits ${java.lang.Long.toHexString(d.toRawBits())}", oracle(d), js(d))
        }
    }

    private fun oracle(d: Double): String {
        val exact = BigDecimal(d).abs()
        for (p in 1..17) {
            val candidates = listOf(RoundingMode.HALF_EVEN, RoundingMode.FLOOR, RoundingMode.CEILING)
                .map { exact.round(MathContext(p, it)) }
                .distinct()
                .filter { it.toDouble() == Math.abs(d) }
            if (candidates.isEmpty()) continue
            val best = candidates.minWith(compareBy<BigDecimal> { (it - exact).abs() }.thenBy { it.unscaledValue().toLong() % 2 })
            val stripped = best.stripTrailingZeros()
            val digits = stripped.unscaledValue().toString()
            val exponent = -stripped.scale()
            val body = layout(digits, exponent)
            return if (d < 0) "-$body" else body
        }
        error("no representation for $d")
    }

    private fun layout(s: String, exponent: Int): String {
        val k = s.length
        val n = exponent + k
        return when {
            n in k..21 -> s + "0".repeat(n - k)
            n in 1..21 -> s.substring(0, n) + "." + s.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + s
            else -> {
                val e = n - 1
                val exp = if (e >= 0) "e+$e" else "e-${-e}"
                if (k == 1) s + exp else s.substring(0, 1) + "." + s.substring(1) + exp
            }
        }
    }
}
