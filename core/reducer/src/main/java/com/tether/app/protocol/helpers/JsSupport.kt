package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.isJsWhitespace
import com.tether.app.protocol.fold.jsTrim
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor

// T2.2: JavaScript semantics the pure-helper ports lean on, on top of fold/Js.kt (truthy, jsTrim,
// jsToString, …). As in the reducer, Kotlin `null` is JS `undefined` wherever a parameter or
// return type is `JsValue?`; JS `null` is [JsNull]. Each helper module is an `object` named after
// its JS module so identically named exports (pending-input vs pending-workspace `markSent`) stay
// apart, the way ES module imports keep them apart.

/**
 * A JS `throw new <name>(message)` surfaced by a helper (`Error`, `TypeError`). The conformance
 * corpus records `{ name, message }` for throwing cases; callers catch this where the web does.
 */
class JsError(val name: String, message: String) : RuntimeException(message)

/** `Math.round(x)`: nearest integer, ties toward +Infinity; keeps -0 for x in [-0.5, -0]. */
fun jsRound(x: Double): Double {
    if (x.isNaN() || x.isInfinite() || x == 0.0) return x
    if (x < 0 && x >= -0.5) return -0.0
    val f = floor(x)
    return if (x - f >= 0.5) f + 1 else f
}

/** `Number.prototype.toFixed(digits)` for a finite value below 1e21 (exact binary value, ties up). */
fun jsToFixed(x: Double, digits: Int): String {
    if (x.isNaN()) return "NaN"
    if (x.isInfinite() || kotlin.math.abs(x) >= 1e21) return com.tether.app.protocol.fold.numberToString(x)
    val negative = x < 0
    val text = BigDecimal(kotlin.math.abs(x)).setScale(digits, RoundingMode.HALF_UP).toPlainString()
    return if (negative) "-$text" else text
}

/** `String(value).padStart(2, "0")` for the clock helpers. */
fun pad2(value: Int): String = value.toString().padStart(2, '0')

/**
 * `Number(string)` (ECMAScript StringToNumber): JS-trimmed; "" → 0; `Infinity` with an optional
 * sign; `0x`/`0o`/`0b` integers (unsigned); otherwise a StrDecimalLiteral or NaN.
 */
fun jsNumberFromString(input: String): Double {
    val s = jsTrim(input)
    if (s.isEmpty()) return 0.0
    when (s) {
        "Infinity", "+Infinity" -> return Double.POSITIVE_INFINITY
        "-Infinity" -> return Double.NEGATIVE_INFINITY
    }
    if (s.length > 2 && s[0] == '0') {
        val radix = when (s[1]) {
            'x', 'X' -> 16
            'o', 'O' -> 8
            'b', 'B' -> 2
            else -> 0
        }
        if (radix != 0) {
            val digits = s.substring(2)
            if (digits.all { Character.digit(it, radix) >= 0 }) return java.math.BigInteger(digits, radix).toDouble()
            return Double.NaN
        }
    }
    return if (DECIMAL_LITERAL.matches(s)) s.toDouble() else Double.NaN
}

private val DECIMAL_LITERAL = Regex("""^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$""")

/** `Number.parseInt(s, 10)`: leading JS whitespace, optional sign, the longest digit prefix; NaN when none. */
fun jsParseInt10(input: String): Double {
    var i = 0
    while (i < input.length && isJsWhitespace(input[i])) i++
    var sign = 1.0
    if (i < input.length && (input[i] == '+' || input[i] == '-')) {
        if (input[i] == '-') sign = -1.0
        i++
    }
    val start = i
    while (i < input.length && input[i] in '0'..'9') i++
    if (i == start) return Double.NaN
    return sign * BigDecimal(input.substring(start, i)).toDouble()
}

/** `Number.isInteger(v)` for a Double. */
fun isIntegral(d: Double): Boolean = d.isFinite() && floor(d) == d

/**
 * `a.localeCompare(b)` — the default ICU collator (`new Intl.Collator()`: the host locale, usage
 * "sort", sensitivity "variant" = tertiary strength, punctuation and spaces NOT ignored, no numeric
 * collation, caseFirst off). Collation is injected so this module stays pure JVM: Android passes an
 * `android.icu.text.Collator` (feature/chat IcuJsCollator), JVM tests ICU4J. The JDK's
 * java.text.Collator is NOT a substitute — it ignores '-' and ' ', so "GPT-5" and "GPT5" tie.
 */
fun interface JsCollator {
    /** Negative, zero or positive, like `a.localeCompare(b)`. */
    fun compare(a: String, b: String): Int
}

/**
 * A destructured / defaulted options parameter (`{ a, b } = {}`): `undefined` takes the default,
 * but JS `null` throws the TypeError the web would.
 */
fun requireNotJsNull(value: JsValue?, firstProperty: String): JsValue? {
    if (value === JsNull) throw JsError("TypeError", "Cannot destructure property '$firstProperty' of 'object null' as it is null.")
    return value
}

/** The JS `===` of two values when one side is a known string. */
fun JsValue?.isStr(value: String): Boolean = this is JsStr && this.value == value

/** A non-empty string value, or null (`typeof v === "string" && v`). */
val JsValue?.nonEmptyStr: String? get() = (this as? JsStr)?.value?.takeIf { it.isNotEmpty() }

/** Property access `v?.[key]` on any value (only objects carry properties the helpers read). */
operator fun JsValue?.get(key: String): JsValue? = (this as? JsObj)?.get(key)

/** `Array.isArray(v) ? v : []`. */
fun JsValue?.arrOrEmpty(): JsArr = this as? JsArr ?: JsArr.EMPTY

/** A JS string/number/boolean literal as a tree value; `null` stays JS undefined. */
fun jsStrOrNull(value: String?): JsValue = if (value == null) JsNull else JsStr(value)

/** `Number.isFinite(v) ? v : null` for a JsValue. */
val JsValue?.finite: Double? get() = (this as? JsNum)?.value?.takeIf { it.isFinite() }

/** Build a JS array of strings. */
fun jsStrings(values: Iterable<String>): JsArr = JsArr.of(values.map { JsStr(it) })


/** `ToNumber(v)` for the values a helper can receive (undefined → NaN, null → 0, strings parsed). */
fun jsToNumber(v: JsValue?): Double = when (v) {
    null -> Double.NaN
    JsNull -> 0.0
    is JsNum -> v.value
    is JsBool -> if (v.value) 1.0 else 0.0
    is JsStr -> jsNumberFromString(v.value)
    // ToPrimitive: an array joins to a string first ([] → "" → 0, [7] → "7" → 7); objects are NaN.
    is JsArr -> jsNumberFromString(com.tether.app.protocol.fold.jsToString(v))
    is JsObj -> Double.NaN
}

/** A comparator result (`a - b || …`, possibly NaN) as the sign Array.prototype.sort reads: NaN is +0. */
fun sortSign(d: Double): Int = if (d.isNaN() || d == 0.0) 0 else if (d < 0) -1 else 1

/** `a || b` over comparator numbers: NaN and ±0 are falsy. */
fun orNumber(a: Double, b: () -> Double): Double = if (a.isNaN() || a == 0.0) b() else a

/**
 * `for (const x of v)`: arrays yield their elements, strings their code points; anything else
 * throws the TypeError the web would (callers apply `?? []` themselves).
 */
fun jsIterate(v: JsValue?): List<JsValue?> = when (v) {
    is JsArr -> v
    is JsStr -> {
        val out = ArrayList<JsValue?>()
        var i = 0
        while (i < v.value.length) {
            val next = v.value.offsetByCodePoints(i, 1)
            out.add(JsStr(v.value.substring(i, next)))
            i = next
        }
        out
    }
    else -> throw JsError("TypeError", "${com.tether.app.protocol.fold.typeOf(v)} is not iterable")
}

/**
 * The ECMAScript `\s` class (WhiteSpace + LineTerminator) for Kotlin regexes: Java's `\s` is ASCII
 * only. Note also that a JS `$` (no `m` flag) is Java's `\z` — Java's `$` also matches before a
 * final line terminator.
 */
const val JS_WS = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"
