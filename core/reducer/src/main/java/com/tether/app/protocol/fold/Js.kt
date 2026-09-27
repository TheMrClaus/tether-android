package com.tether.app.protocol.fold

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsNumberFormat
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.str
import kotlin.math.floor

// T2.1 H1: JavaScript semantics the events.mjs port leans on. Kotlin `null` is JS `undefined`
// throughout (see JsValue). Keep these exact — the corpus is the judge, and the classic
// mismatches (`??` vs `||`, truthiness, UTF-16 length vs code points, trim) all live here.

// ---- values and operators ----

/** JS truthiness (`if (v)`, `!v`, `Boolean(v)`). */
fun truthy(v: JsValue?): Boolean = when (v) {
    null, JsNull -> false
    is JsBool -> v.value
    is JsNum -> !(v.value == 0.0 || v.value.isNaN())
    is JsStr -> v.value.isNotEmpty()
    else -> true // objects and arrays, even empty ones
}

/** `v == null` (loose): undefined or null. */
fun isNullish(v: JsValue?): Boolean = v == null || v === JsNull

/** `a ?? b`. */
fun coalesce(a: JsValue?, b: JsValue?): JsValue? = if (isNullish(a)) b else a

/** `v ?? null` — the idiom that turns an absent key into an explicit null. */
fun JsValue?.orJsNull(): JsValue = if (this == null) JsNull else this

/** `a || b`. */
fun jsOr(a: JsValue?, b: JsValue?): JsValue? = if (truthy(a)) a else b

/** `a === b`: primitives by value (NaN !== NaN, 0 === -0), objects and arrays by identity. */
fun strictEquals(a: JsValue?, b: JsValue?): Boolean = when {
    a == null || b == null -> a == null && b == null
    a is JsObj || a is JsArr || b is JsObj || b is JsArr -> a === b
    a is JsNum && b is JsNum -> a.value == b.value
    else -> a == b
}

/** `typeof v`, for the values a projection can hold. */
fun typeOf(v: JsValue?): String = when (v) {
    null -> "undefined"
    is JsStr -> "string"
    is JsNum -> "number"
    is JsBool -> "boolean"
    else -> "object" // null, objects and arrays
}

/** `v && typeof v === "object" && !Array.isArray(v)` — a plain object. */
fun isPlainObject(v: JsValue?): Boolean = v is JsObj

/** `Number.isFinite(v)` (no coercion: only a number qualifies). */
fun isFiniteNumber(v: JsValue?): Boolean = v is JsNum && v.value.isFinite()

/** `Number.isInteger(v)`. */
fun isInteger(v: JsValue?): Boolean = v is JsNum && v.value.isFinite() && floor(v.value) == v.value

/** ECMAScript Number::toString (shortest round-trip digits, JS exponent layout). */
fun numberToString(d: Double): String = JsNumberFormat.toJsString(d)

/**
 * `String(v)` / template-literal `${v}` interpolation. Objects print as JS does
 * (`[object Object]`, arrays joined by commas) — the fold never relies on those.
 */
fun jsToString(v: JsValue?): String = when (v) {
    null -> "undefined"
    JsNull -> "null"
    is JsStr -> v.value
    is JsNum -> numberToString(v.value)
    is JsBool -> if (v.value) "true" else "false"
    is JsArr -> v.joinToString(",") { if (isNullish(it)) "" else jsToString(it) }
    is JsObj -> "[object Object]"
}

/** `obj[key]` for an arbitrary key value: JS property access coerces the key with ToString. */
fun JsObj.prop(key: JsValue?): JsValue? = this[jsToString(key)]

/** `Object.keys(obj).length` for a value that may be absent. */
fun keyCount(v: JsValue?): Int = (v as? JsObj)?.size ?: 0

/** `JSON.stringify(v)` with JS property order (integer-like keys ascending first — Revision 5). */
fun jsonStringify(v: JsValue): String = JsCodec.stringify(v)

// ---- strings ----

/** ECMAScript WhiteSpace + LineTerminator — what `String.prototype.trim` strips. */
fun isJsWhitespace(c: Char): Boolean = when (c) {
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', ' ', ' ', ' ',
    ' ', ' ', ' ', ' ', '　', '﻿' -> true
    else -> c in ' '..' '
}

/** `s.trim()` — unlike Kotlin `trim()`, strips U+FEFF and U+2028/2029 but not U+001C..U+001F. */
fun jsTrim(s: String): String {
    var start = 0
    var end = s.length
    while (start < end && isJsWhitespace(s[start])) start++
    while (end > start && isJsWhitespace(s[end - 1])) end--
    return if (start == 0 && end == s.length) s else s.substring(start, end)
}

/** `Array.from(s).length`: code points, a lone surrogate counting as one. */
fun codePointLength(s: String): Int = s.codePointCount(0, s.length)

/** `Array.from(s).slice(0, n).join("")`. */
fun codePointPrefix(s: String, n: Int): String {
    if (n <= 0) return ""
    if (codePointLength(s) <= n) return s
    return s.substring(0, s.offsetByCodePoints(0, n))
}

// ---- shared normalizers (events.mjs 557–634, 762) ----

// events.mjs:557
fun sameStringList(a: JsValue?, b: JsArr): Boolean {
    if (a !is JsArr || a.size != b.size) return false
    return a.indices.all { strictEquals(a[it], b[it]) }
}

// events.mjs:587
fun identifier(value: JsValue?, stripLeadingSlash: Boolean = false): String? {
    if (value !is JsStr) return null
    val trimmed = jsTrim(value.value)
    val normalized = if (stripLeadingSlash) trimmed.removePrefix("/") else trimmed
    if (normalized.isEmpty() || codePointLength(normalized) > Limits.CLI_INVENTORY_LIMITS.nameChars) return null
    return normalized
}

// events.mjs:594 — `undefined` (Kotlin null) for a non-string.
fun boundedDisplayText(value: JsValue?, maxChars: Int): String? {
    if (value !is JsStr) return null
    return codePointPrefix(value.value, maxChars)
}

// events.mjs:607
fun boundedIdentifier(value: JsValue?): String? {
    if (value !is JsStr || value.value.isEmpty()) return null
    return boundedDisplayText(value, Limits.PROVIDER_PROJECTION_LIMITS.idChars)
}

// events.mjs:615 — UTF-16 length on purpose (matches inbound validation).
fun boundedApprovalChoiceId(value: JsValue?): String? {
    if (value !is JsStr || value.value.isEmpty() || value.value.length > Limits.PROVIDER_PROJECTION_LIMITS.approvalChoiceIdChars) {
        return null
    }
    return value.value
}

// events.mjs:624 — `undefined` (Kotlin null) for a non-array.
fun normalizeStringList(values: JsValue?, limit: Int, maxChars: Int): JsArr? {
    if (values !is JsArr) return null
    val result = ArrayList<JsValue>()
    for (value in values) {
        if (result.size >= limit) break
        if (value !is JsStr || value.value.isEmpty()) continue
        result.add(JsStr(boundedDisplayText(value, maxChars)!!))
    }
    return JsArr.of(result)
}

// events.mjs:762
fun isBlank(value: JsValue?): Boolean = value !is JsStr || jsTrim(value.value).isEmpty()

/** [isBlank] for an already-unwrapped `string | undefined`. */
fun isBlank(value: String?): Boolean = value == null || jsTrim(value).isEmpty()

// ---- small builders ----

/** A JsStr for a `string | undefined` (Kotlin null stays absent). */
fun jsOrUndefined(value: String?): JsStr? = value?.let { JsStr(it) }

/** `event.type` etc.: a string field or null. */
fun JsObj.string(key: String): String? = this[key].str
