package com.tether.app.protocol.tree

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/**
 * T2.1: JsonElement ↔ [JsValue], and the JSON writers.
 *
 * [canonical] is the parity corpus's canonical form (parity-corpus/corpus-manifest.json
 * `canonicalJson`): object keys sorted by UTF-16 code unit, numbers in ECMAScript
 * `Number::toString` form (-0 as 0), `JSON.stringify` string escaping, no whitespace.
 * [stringify] is `JSON.stringify(value)` itself: the same escaping and numbers, but object keys
 * in JS property order (integer-like keys ascending first, then insertion order — Revision 5).
 */
object JsCodec {

    fun parse(text: String): JsValue = fromJson(Json.parseToJsonElement(text))

    fun fromJson(element: JsonElement): JsValue = when (element) {
        is JsonNull -> JsNull
        is JsonPrimitive -> when {
            element.isString -> JsStr(element.content)
            element.content == "true" -> JsBool.TRUE
            element.content == "false" -> JsBool.FALSE
            else -> JsNum(element.content.toDouble())
        }
        is JsonObject -> JsObj.from(LinkedHashMap<String, JsValue>(element.size).also { out ->
            for ((key, value) in element) out[key] = fromJson(value)
        })
        is JsonArray -> JsArr.of(element.map { fromJson(it) })
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun toJson(value: JsValue): JsonElement = when (value) {
        is JsNull -> JsonNull
        is JsStr -> JsonPrimitive(value.value)
        is JsBool -> JsonPrimitive(value.value)
        // Written as the JS text so a Kotlin re-serialization is byte-identical to the server's.
        is JsNum -> JsonUnquotedLiteral(JsNumberFormat.toJsString(value.value))
        is JsObj -> JsonObject(LinkedHashMap<String, JsonElement>(value.size).also { out ->
            for ((key, v) in value) out[key] = toJson(v)
        })
        is JsArr -> JsonArray(value.map { toJson(it) })
    }

    /** The corpus's canonical JSON text (no trailing newline). */
    fun canonical(value: JsValue): String = StringBuilder().also { write(it, value, sortKeys = true) }.toString()

    /** `JSON.stringify(value)`: JS key order, no whitespace. */
    fun stringify(value: JsValue): String = StringBuilder().also { write(it, value, sortKeys = false) }.toString()

    private fun write(out: StringBuilder, value: JsValue, sortKeys: Boolean) {
        when (value) {
            is JsNull -> out.append("null")
            is JsBool -> out.append(if (value.value) "true" else "false")
            is JsNum -> {
                // JSON.stringify writes non-finite numbers as null; the corpus never holds them.
                val d = value.value
                out.append(if (d.isNaN() || d.isInfinite()) "null" else JsNumberFormat.toJsString(d))
            }
            is JsStr -> quote(out, value.value)
            is JsArr -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(out, item, sortKeys)
                }
                out.append(']')
            }
            is JsObj -> {
                out.append('{')
                val keys = if (sortKeys) value.keys.sorted() else jsPropertyOrder(value.keys)
                keys.forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    quote(out, key)
                    out.append(':')
                    write(out, value.getValue(key), sortKeys)
                }
                out.append('}')
            }
        }
    }

    /**
     * JS `OrdinaryOwnPropertyKeys` order for string keys: array-index keys (canonical decimal
     * integers 0..2^32-2) ascending, then every other key in insertion order.
     */
    fun jsPropertyOrder(keys: Collection<String>): List<String> {
        val (indices, others) = keys.partition { isArrayIndex(it) }
        if (indices.isEmpty()) return others
        return indices.sortedBy { it.toLong() } + others
    }

    private fun isArrayIndex(key: String): Boolean {
        if (key.isEmpty() || key.length > 10) return false
        if (key.length > 1 && key[0] == '0') return false
        if (!key.all { it in '0'..'9' }) return false
        return key.toLong() < 4294967295L
    }

    /** `JSON.stringify` string quoting (ECMAScript QuoteJSONString, well-formed variant). */
    fun quote(out: StringBuilder, s: String) {
        out.append('"')
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> hexEscape(out, c)
                Character.isHighSurrogate(c) -> {
                    if (i + 1 < n && Character.isLowSurrogate(s[i + 1])) {
                        out.append(c).append(s[i + 1])
                        i++
                    } else {
                        hexEscape(out, c)
                    }
                }
                Character.isLowSurrogate(c) -> hexEscape(out, c)
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    private fun hexEscape(out: StringBuilder, c: Char) {
        val hex = Integer.toHexString(c.code)
        out.append("\\u")
        repeat(4 - hex.length) { out.append('0') }
        out.append(hex)
    }
}
