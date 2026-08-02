package com.tether.app.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A tolerant tagged representation of one normalized AgentEvent
 * (specs/reducer-spec.md §1). The event keeps its raw JsonObject so the
 * reducer can read per-type payload fields defensively, exactly like the
 * JS reducer (aidash engines/events.mjs) reads dynamic properties.
 *
 * Unknown event types parse successfully and fold as no-ops. `seq`/`ts` are
 * journal-stamped and OPTIONAL — absence degrades gracefully, never throws.
 */
class AgentEvent(val raw: JsonObject) {
    /** Event type literal; "" if absent (folds as an inert no-op). */
    val type: String = raw.str("type") ?: ""

    /** Turn scope, or null for session-level events (explicit null or absent). */
    val turnId: String? = raw.str("turnId")

    /** Journal sequence number, monotonic per session; null when unstamped. */
    val seq: Long? = raw.nonNegLong("seq")

    /** Journal wall-clock epoch ms; null when unstamped. The ONLY time source. */
    val ts: Long? = raw.nonNegLong("ts")

    override fun toString(): String = "AgentEvent($type, turnId=$turnId, seq=$seq)"

    companion object {
        fun parse(json: JsonObject): AgentEvent = AgentEvent(json)

        /** Test/utility builder. */
        fun of(
            type: String,
            turnId: String? = null,
            seq: Long? = null,
            ts: Long? = null,
            fields: JsonObject = buildJsonObject { },
        ): AgentEvent = AgentEvent(
            buildJsonObject {
                put("type", JsonPrimitive(type))
                if (turnId != null) put("turnId", JsonPrimitive(turnId))
                if (seq != null) put("seq", JsonPrimitive(seq))
                if (ts != null) put("ts", JsonPrimitive(ts))
                for ((k, v) in fields) if (k !in setOf("type", "turnId", "seq", "ts")) put(k, v)
            },
        )
    }
}
