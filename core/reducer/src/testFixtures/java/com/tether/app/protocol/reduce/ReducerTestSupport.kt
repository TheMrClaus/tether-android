package com.tether.app.protocol.reduce

import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.model.SessionProjection
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Build an AgentEvent from literal payload fields. */
fun ev(
    type: String,
    turnId: String? = null,
    seq: Long? = null,
    ts: Long? = null,
    build: JsonObjectBuilder.() -> Unit = {},
): AgentEvent = AgentEvent.of(type, turnId, seq, ts, buildJsonObject(build))

/** Same, but with an EXPLICIT JSON-null turnId (session-level events on the wire). */
fun evNullTurn(
    type: String,
    seq: Long? = null,
    ts: Long? = null,
    build: JsonObjectBuilder.() -> Unit = {},
): AgentEvent = AgentEvent(
    buildJsonObject {
        put("type", type)
        put("turnId", JsonNull as JsonElement)
        if (seq != null) put("seq", seq)
        if (ts != null) put("ts", ts)
        buildJsonObject(build).forEach { (k, v) -> put(k, v) }
    },
)

fun freshState(): SessionProjection = initialSessionState("s1", "claude", "/workspace")

fun fold(state: SessionProjection, vararg events: AgentEvent): SessionProjection =
    events.fold(state) { acc, event -> reduce(acc, event) }
