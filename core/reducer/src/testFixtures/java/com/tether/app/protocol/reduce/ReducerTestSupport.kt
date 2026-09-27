package com.tether.app.protocol.reduce

import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.fold.initialSessionState
import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import java.util.Collections
import java.util.IdentityHashMap
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

/** T2.1D: `initialSessionState({ tetherSessionId: "s1", provider: "claude", cwd: "/workspace" })` as a tree. */
fun freshTree(): JsObj = initialSessionState("s1", "claude", "/workspace")

/** The AgentEvent as the tree the v128 fold reads. */
fun AgentEvent.tree(): JsObj = JsCodec.fromJson(raw) as JsObj

/** Fold [events] onto [state] with the v128 reducer. */
fun foldTree(state: JsObj, vararg events: AgentEvent): JsObj =
    events.fold(state) { acc, event -> reduce(acc, event.tree()) }

// Typed conveniences for tests that read the legacy SessionProjection: every typed state these
// return remembers the tree it was adapted from, so `fold` continues on the tree (v128 fold) and
// adapts the result (LegacyProjectionAdapter) — the same path RealTetherClient takes.
private val treeOfTyped = Collections.synchronizedMap(IdentityHashMap<SessionProjection, JsObj>())

private fun typed(tree: JsObj): SessionProjection {
    val projection = checkNotNull(LegacyProjectionAdapter.adaptOnce(tree)) { "tree does not fit the typed model" }
    treeOfTyped[projection] = tree
    return projection
}

fun freshState(): SessionProjection = typed(freshTree())

fun fold(state: SessionProjection, vararg events: AgentEvent): SessionProjection {
    val tree = checkNotNull(treeOfTyped[state]) { "fold() needs a state produced by freshState()/fold()" }
    return typed(foldTree(tree, *events))
}
