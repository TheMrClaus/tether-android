package com.tether.app.protocol.tree

import java.math.BigInteger

/**
 * ECMAScript `Number::toString(x)` (radix 10) — what `String(x)` and `JSON.stringify` print.
 *
 * T2.1 Revision 4: neither JDK 17's `Double.toString` nor ART guarantees the SHORTEST
 * round-tripping digits (JDK 17 prints `4.9E-324` where JS prints `5e-324`), and the reducer
 * needs the exact JS text at runtime (`JSON.stringify` in `coerceTaskResultText`,
 * events.mjs:887) as well as in the canonical corpus writer. So this is a pure-Kotlin port of
 * Ryu (Ulf Adams, PLDI 2018, reference `d2s.c`): shortest digits, nearest on ties-to-even, then
 * laid out with the ECMAScript 2024 §6.1.6.1.20 rules. The 128-bit power-of-5 tables are
 * computed once at class init (the reference ships them precomputed with identical values).
 */
object JsNumberFormat {

    fun toJsString(value: Double): String {
        if (value.isNaN()) return "NaN"
        if (value == Double.POSITIVE_INFINITY) return "Infinity"
        if (value == Double.NEGATIVE_INFINITY) return "-Infinity"
        if (value == 0.0) return "0" // +0 and -0 both print "0"
        val bits = value.toRawBits()
        val negative = bits < 0
        val ieeeMantissa = bits and ((1L shl MANTISSA_BITS) - 1)
        val ieeeExponent = ((bits ushr MANTISSA_BITS) and ((1L shl EXPONENT_BITS) - 1)).toInt()
        val (digits, exponent) = shortest(ieeeMantissa, ieeeExponent)
        return layout(negative, digits.toString(), exponent)
    }

    /** Number::toString step 5+: `digits` = s (k digits), value = s × 10^exponent, n = exponent + k. */
    private fun layout(negative: Boolean, s: String, exponent: Int): String {
        val k = s.length
        val n = exponent + k
        val body = when {
            n in k..21 -> s + "0".repeat(n - k)
            n in 1..21 -> s.substring(0, n) + "." + s.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + s
            else -> {
                val e = n - 1
                val exp = if (e >= 0) "e+$e" else "e-${-e}"
                if (k == 1) s + exp else s.substring(0, 1) + "." + s.substring(1) + exp
            }
        }
        return if (negative) "-$body" else body
    }

    private const val MANTISSA_BITS = 52
    private const val EXPONENT_BITS = 11
    private const val BIAS = 1023
    private const val POW5_INV_BITCOUNT = 125
    private const val POW5_BITCOUNT = 125
    private const val POW5_INV_TABLE_SIZE = 342
    private const val POW5_TABLE_SIZE = 326

    // [lo, hi] 64-bit halves of each 125-bit table entry.
    private val POW5_INV_SPLIT: LongArray
    private val POW5_SPLIT: LongArray

    init {
        val mask64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        POW5_INV_SPLIT = LongArray(POW5_INV_TABLE_SIZE * 2)
        POW5_SPLIT = LongArray(POW5_TABLE_SIZE * 2)
        val five = BigInteger.valueOf(5)
        var pow = BigInteger.ONE
        for (i in 0 until maxOf(POW5_INV_TABLE_SIZE, POW5_TABLE_SIZE)) {
            val pow5len = pow.bitLength()
            if (i < POW5_TABLE_SIZE) {
                val shifted = pow.shiftRight(pow5len - POW5_BITCOUNT) // negative distance shifts left
                POW5_SPLIT[2 * i] = shifted.and(mask64).toLong()
                POW5_SPLIT[2 * i + 1] = shifted.shiftRight(64).toLong()
            }
            if (i < POW5_INV_TABLE_SIZE) {
                val j = pow5len - 1 + POW5_INV_BITCOUNT
                val inv = BigInteger.ONE.shiftLeft(j).divide(pow).add(BigInteger.ONE)
                POW5_INV_SPLIT[2 * i] = inv.and(mask64).toLong()
                POW5_INV_SPLIT[2 * i + 1] = inv.shiftRight(64).toLong()
            }
            pow = pow.multiply(five)
        }
    }

    private fun pow5bits(e: Int): Int = ((e.toLong() * 1217359L) ushr 19).toInt() + 1
    private fun log10Pow2(e: Int): Int = ((e.toLong() * 78913L) ushr 18).toInt()
    private fun log10Pow5(e: Int): Int = ((e.toLong() * 732923L) ushr 20).toInt()

    private fun pow5Factor(value: Long): Int {
        var v = value
        var count = 0
        while (v > 0 && v % 5 == 0L) {
            v /= 5
            count++
        }
        return count
    }

    private fun multipleOfPowerOf5(value: Long, p: Int): Boolean = pow5Factor(value) >= p
    private fun multipleOfPowerOf2(value: Long, p: Int): Boolean = (value and ((1L shl p) - 1)) == 0L

    private fun unsignedMultiplyHigh(a: Long, b: Long): Long =
        Math.multiplyHigh(a, b) + ((a shr 63) and b) + ((b shr 63) and a)

    /** ((m × mul) >> j) for a 64-bit m and a 128-bit mul; 64 < j < 128 (d2s.c mulShift64). */
    private fun mulShift64(m: Long, table: LongArray, index: Int, j: Int): Long {
        val mulLo = table[2 * index]
        val mulHi = table[2 * index + 1]
        val b0Hi = unsignedMultiplyHigh(m, mulLo)
        val b2Lo = m * mulHi
        val b2Hi = unsignedMultiplyHigh(m, mulHi)
        val sumLo = b2Lo + b0Hi
        val carry = if (java.lang.Long.compareUnsigned(sumLo, b2Lo) < 0) 1L else 0L
        val sumHi = b2Hi + carry
        val shift = j - 64
        return (sumLo ushr shift) or (sumHi shl (64 - shift))
    }

    /** d2s.c `d2d`: the shortest decimal (digits, exponent) that round-trips to the double. */
    private fun shortest(ieeeMantissa: Long, ieeeExponent: Int): Pair<Long, Int> {
        val e2: Int
        val m2: Long
        if (ieeeExponent == 0) {
            e2 = 1 - BIAS - MANTISSA_BITS - 2
            m2 = ieeeMantissa
        } else {
            e2 = ieeeExponent - BIAS - MANTISSA_BITS - 2
            m2 = (1L shl MANTISSA_BITS) or ieeeMantissa
        }
        val acceptBounds = (m2 and 1L) == 0L
        val mv = 4 * m2
        val mmShift = if (ieeeMantissa != 0L || ieeeExponent <= 1) 1L else 0L

        var vr: Long
        var vp: Long
        var vm: Long
        val e10: Int
        var vmIsTrailingZeros = false
        var vrIsTrailingZeros = false
        if (e2 >= 0) {
            val q = log10Pow2(e2) - (if (e2 > 3) 1 else 0)
            e10 = q
            val k = POW5_INV_BITCOUNT + pow5bits(q) - 1
            val i = -e2 + q + k
            vr = mulShift64(4 * m2, POW5_INV_SPLIT, q, i)
            vp = mulShift64(4 * m2 + 2, POW5_INV_SPLIT, q, i)
            vm = mulShift64(4 * m2 - 1 - mmShift, POW5_INV_SPLIT, q, i)
            if (q <= 21) {
                val mvMod5 = (mv % 5).toInt()
                if (mvMod5 == 0) {
                    vrIsTrailingZeros = multipleOfPowerOf5(mv, q)
                } else if (acceptBounds) {
                    vmIsTrailingZeros = multipleOfPowerOf5(mv - 1 - mmShift, q)
                } else if (multipleOfPowerOf5(mv + 2, q)) {
                    vp -= 1
                }
            }
        } else {
            val q = log10Pow5(-e2) - (if (-e2 > 1) 1 else 0)
            e10 = q + e2
            val i = -e2 - q
            val k = pow5bits(i) - POW5_BITCOUNT
            val j = q - k
            vr = mulShift64(4 * m2, POW5_SPLIT, i, j)
            vp = mulShift64(4 * m2 + 2, POW5_SPLIT, i, j)
            vm = mulShift64(4 * m2 - 1 - mmShift, POW5_SPLIT, i, j)
            if (q <= 1) {
                vrIsTrailingZeros = true
                if (acceptBounds) vmIsTrailingZeros = mmShift == 1L else vp -= 1
            } else if (q < 63) {
                vrIsTrailingZeros = multipleOfPowerOf2(mv, q)
            }
        }

        var removed = 0
        var lastRemovedDigit = 0
        val output: Long
        if (vmIsTrailingZeros || vrIsTrailingZeros) {
            while (vp / 10 > vm / 10) {
                vmIsTrailingZeros = vmIsTrailingZeros && vm % 10 == 0L
                vrIsTrailingZeros = vrIsTrailingZeros && lastRemovedDigit == 0
                lastRemovedDigit = (vr % 10).toInt()
                vr /= 10
                vp /= 10
                vm /= 10
                removed++
            }
            if (vmIsTrailingZeros) {
                while (vm % 10 == 0L) {
                    vrIsTrailingZeros = vrIsTrailingZeros && lastRemovedDigit == 0
                    lastRemovedDigit = (vr % 10).toInt()
                    vr /= 10
                    vp /= 10
                    vm /= 10
                    removed++
                }
            }
            if (vrIsTrailingZeros && lastRemovedDigit == 5 && vr % 2 == 0L) lastRemovedDigit = 4
            val roundUp = (vr == vm && (!acceptBounds || !vmIsTrailingZeros)) || lastRemovedDigit >= 5
            output = vr + (if (roundUp) 1 else 0)
        } else {
            var roundUp = false
            if (vp / 100 > vm / 100) {
                roundUp = vr % 100 >= 50
                vr /= 100
                vp /= 100
                vm /= 100
                removed += 2
            }
            while (vp / 10 > vm / 10) {
                roundUp = vr % 10 >= 5
                vr /= 10
                vp /= 10
                vm /= 10
                removed++
            }
            output = vr + (if (vr == vm || roundUp) 1 else 0)
        }
        return output to (e10 + removed)
    }
}
