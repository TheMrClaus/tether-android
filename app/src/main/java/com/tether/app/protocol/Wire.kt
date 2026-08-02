package com.tether.app.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Must equal the server's PROTOCOL_VERSION (aidash lib/protocol.ts). */
const val PROTOCOL_VERSION: Int = 40

/**
 * The single Json configuration for the whole protocol layer.
 * - ignoreUnknownKeys: a newer server may add fields; never fail on them.
 * - explicitNulls=false: absent keys stay at their Kotlin defaults, and nulls
 *   are omitted on encode (matching JS "undefined key is absent" semantics).
 * - isLenient=false: the wire is strict JSON; do not accept quirks.
 */
val TetherJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = false
}

// ---------------------------------------------------------------------------
// Defensive JsonObject accessors. These mirror the JS reducer's dynamic-typing
// checks (`typeof x === "string"`, `Number.isFinite(x)`, `x === true`) so that
// a malformed field degrades to "absent" instead of throwing.
// ---------------------------------------------------------------------------

/** The raw element, or null when the key is absent (JsonNull is returned as-is). */
internal fun JsonObject.el(key: String): JsonElement? = this[key]

/** String value, or null when absent / JsonNull / not a JSON string. */
internal fun JsonObject.str(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    return if (p.isString) p.content else null
}

/** Numeric value, or null when absent / not a JSON number. */
internal fun JsonObject.num(key: String): Double? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    return p.doubleOrNull
}

/** Boolean value, or null when absent / not a JSON boolean. */
internal fun JsonObject.boolOrNull(key: String): Boolean? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    return p.booleanOrNull
}

/** True only for a literal JSON `true` (mirrors `x === true`). */
internal fun JsonObject.boolTrue(key: String): Boolean = boolOrNull(key) == true

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** Port of events.mjs nonNegativeFiniteNumber: finite number >= 0, else null. */
internal fun nonNegativeFinite(value: Double?): Double? =
    if (value != null && value.isFinite() && value >= 0) value else null

internal fun JsonObject.nonNegLong(key: String): Long? = nonNegativeFinite(num(key))?.toLong()

internal fun JsonObject.intOrNull(key: String): Int? = num(key)?.toInt()
