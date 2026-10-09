package com.tether.app.protocol.model

import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue

/**
 * ta-jtfq: is the newer reading of a session the held one plus more words in an agent message, and nothing else?
 *
 * A streamed `message_delta` appends to one message block's text and changes nothing else in the tree. Whatever reads
 * only the rest of the tree (the composer's run row and queue, the statusline, the inspector) has nothing new to show,
 * so it can keep the reading it has instead of recomposing at every delta. The test is deliberately narrow: any other
 * difference at all (a block added or finished, a tool started, a count, a status, a different session) answers false,
 * so the held reading is replaced at once. It walks only the path a delta changes: the persistent tree shares every
 * unchanged branch, which is a reference check.
 */
object TextOnlyDelta {
    /** [next] is [held] with different `text` in message blocks, and equal in everything else. */
    fun isTextOnly(held: JsObj?, next: JsObj?): Boolean {
        if (held === next) return true
        if (held == null || next == null) return false
        return sameObject(held, next) { key, a, b ->
            if (key == "turnsById") sameEach(a, b, ::sameTurn) else a == b
        }
    }

    /** The typed projection of the same tree: [next] is [held] with different `text` in message blocks, nothing else. */
    fun isTextOnly(held: SessionProjection?, next: SessionProjection?): Boolean {
        if (held === next) return true
        if (held == null || next == null) return false
        if (held.turnOrder != next.turnOrder || held.turnsById.size != next.turnsById.size) return false
        if (held.copy(turnOrder = emptyList(), turnsById = emptyMap()) != next.copy(turnOrder = emptyList(), turnsById = emptyMap())) return false
        for ((id, a) in held.turnsById) {
            val b = next.turnsById[id] ?: return false
            if (a !== b && !sameTurnProjection(a, b)) return false
        }
        return true
    }

    private fun sameTurnProjection(a: TurnProjection, b: TurnProjection): Boolean {
        if (a.blocksById.size != b.blocksById.size) return false
        if (a.copy(blocksById = emptyMap()) != b.copy(blocksById = emptyMap())) return false
        for ((id, x) in a.blocksById) {
            val y = b.blocksById[id] ?: return false
            if (x !== y && x != y && !(x.kind == "message" && y.kind == "message" && x.copy(text = null) == y.copy(text = null))) return false
        }
        return true
    }

    private fun sameTurn(a: JsValue, b: JsValue): Boolean {
        if (a === b) return true
        if (a !is JsObj || b !is JsObj) return a == b
        return sameObject(a, b) { key, x, y -> if (key == "blocksById") sameEach(x, y, ::sameBlock) else x == y }
    }

    private fun sameBlock(a: JsValue, b: JsValue): Boolean {
        if (a === b || a == b) return true
        if (a !is JsObj || b !is JsObj) return false
        if ((a["kind"] as? JsStr)?.value != "message" || (b["kind"] as? JsStr)?.value != "message") return false
        return sameObject(a, b) { key, x, y -> key == "text" || x == y }
    }

    /** Both objects hold the same keys, and every value passes [same] (keyed). */
    private inline fun sameObject(a: JsObj, b: JsObj, same: (String, JsValue, JsValue) -> Boolean): Boolean {
        if (a.size != b.size) return false
        for ((key, x) in a) {
            val y = b[key] ?: return false
            if (x !== y && !same(key, x, y)) return false
        }
        return true
    }

    /** The two values are objects whose entries pass [same] one by one. */
    private fun sameEach(a: JsValue, b: JsValue, same: (JsValue, JsValue) -> Boolean): Boolean {
        if (a === b) return true
        if (a !is JsObj || b !is JsObj) return a == b
        return sameObject(a, b) { _, x, y -> same(x, y) }
    }
}
