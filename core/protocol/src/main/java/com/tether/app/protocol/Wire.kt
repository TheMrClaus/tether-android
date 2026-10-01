package com.tether.app.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The protocol the wire TYPES in this module model: tether lib/protocol.ts
 * PROTOCOL_VERSION v137 (T15.8; ta-ylh 135, ta-koy 129 -> 132). Every
 * ClientMessage/ServerMessage of that union has a Kotlin type; see
 * WireConformanceTest. v130-v137 are all additive and not native-breaking:
 * AgentSession.lastSeq and SessionProjection.removedQueueIds (v130), the opt-in
 * Overview feed frames and pending-request `createdAt` (v131),
 * OverviewActivity.workspace (v132), queued-message `origin` / `noticeKind`
 * (v133), WorktreeScript `proxyUnavailable` and the nullable proxy links (v134),
 * AgentSession.createdVia and ready.hiddenAgentSessionCount (v135),
 * QueuedMessage.queuedAt (v136). v137 only adds `plan` to the HTTP
 * `/api/claude-accounts` rows, which this app does not read yet.
 *
 * Also what the app advertises: [PROTOCOL_VERSION] is defined as this value.
 */
const val TARGET_PROTOCOL_VERSION: Int = 137

/**
 * The protocol version the RUNTIME speaks: the `hello` this app sends. The
 * server serves it anywhere inside its native window
 * `nativeProtocolFloor <= v <= server PROTOCOL_VERSION` (S1.1 / D5; tether
 * lib/hello-compat.mjs); see :core:net Compatibility for the client side of
 * that decision.
 *
 * ta-3uk: defined as [TARGET_PROTOCOL_VERSION], so the advertised and the
 * modelled versions cannot drift apart (ta-ylh had held the hello at 132 until
 * the owner's server was deployed at 137). The cost: a server refuses a native
 * hello NEWER than its own PROTOCOL_VERSION (server_too_old), so advertising
 * this requires a server at >= this version; anything older gets the app's
 * "update the server" copy.
 */
const val PROTOCOL_VERSION: Int = TARGET_PROTOCOL_VERSION

/**
 * v129 (S1.1 / D5): the oldest protocol a NATIVE client may speak and still be
 * served (twin of lib/protocol.ts NATIVE_PROTOCOL_FLOOR, still 129 at v137). The
 * server advertises its own value in `ready.nativeProtocolFloor` and `/healthz`;
 * this constant is documentation only — the runtime always trusts the server's.
 */
const val NATIVE_PROTOCOL_FLOOR: Int = 129

/** v129 `hello.client` value that opts into the native compatibility window. */
const val HELLO_CLIENT_ANDROID: String = "android"

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

/** String value, or null when absent / JsonNull / not a JSON string. Public: :core:net uses it. */
fun JsonObject.str(key: String): String? {
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

/**
 * Integral numeric value, or null when absent / not a JSON number / not finite.
 * A fractional number is truncated (the wire only ever carries integers where
 * this is used: epoch ms, seqs, counters, indices).
 */
internal fun JsonObject.long(key: String): Long? = this[key].asLong()

/** [JsonObject.long] for a bare element. */
internal fun JsonElement?.asLong(): Long? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    p.longOrNull?.let { return it }
    val d = p.doubleOrNull ?: return null
    return if (d.isFinite()) d.toLong() else null
}

/** String array, or null when absent / not an array. Non-string elements are dropped. */
internal fun JsonObject.strList(key: String): List<String>? =
    arr(key)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

/** Object array, or null when absent / not an array. Non-object elements are dropped. */
internal fun JsonObject.objList(key: String): List<JsonObject>? =
    arr(key)?.mapNotNull { it as? JsonObject }

/** True when the key is present with a literal JSON null. */
internal fun JsonObject.isExplicitNull(key: String): Boolean = this[key] is JsonNull

/**
 * A TypeScript `field?: T | null` on an OUTBOUND frame: the Kotlin property is
 * `OrNull<T>?` — Kotlin null = key absent, `OrNull(null)` = explicit JSON null,
 * `OrNull(v)` = the value.
 */
data class OrNull<out T>(val value: T?)

/** Raised inside the decoders when a REQUIRED field is absent or wrongly typed. */
internal class MalformedFrame(val reason: String) : Exception(reason)

/** Required-field reader: absence / wrong type raises [MalformedFrame] (never escapes a decoder). */
internal class Req(val o: JsonObject) {
    private fun missing(key: String, what: String): Nothing =
        throw MalformedFrame("required field `$key` ($what) is missing or wrongly typed")

    fun str(key: String): String = o.str(key) ?: missing(key, "string")
    fun long(key: String): Long = o.long(key) ?: missing(key, "number")
    fun int(key: String): Int = o.long(key)?.toInt() ?: missing(key, "number")
    fun bool(key: String): Boolean = o.boolOrNull(key) ?: missing(key, "boolean")
    fun obj(key: String): JsonObject = o.obj(key) ?: missing(key, "object")
    fun arr(key: String): JsonArray = o.arr(key) ?: missing(key, "array")
    fun strList(key: String): List<String> = o.strList(key) ?: missing(key, "string[]")
    fun objList(key: String): List<JsonObject> = o.objList(key) ?: missing(key, "object[]")

    /** Present, and either null or an object (TS `T | null`); the object or null. */
    fun objOrNull(key: String): JsonObject? {
        if (!o.containsKey(key)) missing(key, "object | null")
        return o.obj(key)
    }
}
