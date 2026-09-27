package com.tether.app.protocol.model

import com.tether.app.protocol.TetherJson
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * T2.1D: the legacy adapter, JsValue tree (the v128 fold's source of truth) → the typed
 * [SessionProjection] the pre-T5/T6 screens still read (docs/parity/T2.1_PLAN.md §1 "UI blast
 * radius", Revision 9).
 *
 * Decoding goes through the typed model's own serializers ([TetherJson]: unknown keys ignored,
 * absent = default), so the adapter shows exactly the v40 subset of the projection, nothing
 * invented. It is tolerant: an object that does not fit its typed class keeps every top-level
 * key that does fit (a misfit key falls back to its default); an object whose REQUIRED keys do
 * not fit is left out (a block from `blocksById`, a turn from `turnsById`; a whole session → null).
 *
 * MEMOIZED BY IDENTITY. The fold rebuilds only the path it touches and every untouched subtree
 * keeps its identity, so the adapter keeps, per turn and per block, the source object it last
 * decoded and the typed value it produced: an identical source (`===`) reuses the typed value
 * (same instance, so the UI can skip it). A `message_delta` therefore re-decodes only the changed
 * block and the non-block fields of its turn; the per-turn `blocksById` map is re-assembled
 * (O(blocks in that turn) identity checks, no decoding). The same tree → the same projection.
 *
 * Not thread-safe: one adapter per session stream, driven by one thread (the fold's caller).
 * Memory: it holds the last-seen typed value per live turn/block (stale slots are dropped as the
 * tree changes).
 */
class LegacyProjectionAdapter {

    /**
     * How many objects did not fit their typed class wholly (a key fell back to its default or
     * the object was left out) — diagnostics; 0 on every corpus case.
     */
    var misfits: Int = 0
        private set

    private var lastState: JsObj? = null
    private var last: SessionProjection? = null
    private val sessionRest = Memo(SESSION_SHAPE)
    private var turnOrderSource: JsValue? = null
    private var turnOrder: List<String> = emptyList()
    private var turnsByIdSource: JsValue? = null
    private var turnsById: Map<String, TurnProjection> = emptyMap()
    private var turnSlots: HashMap<String, TurnSlot> = HashMap()

    /** The typed view of [state]; null when the session's required fields do not fit the model. */
    fun adapt(state: JsObj): SessionProjection? {
        if (state === lastState) return last
        lastState = state
        val rest = sessionRest.get(state)
        if (rest == null) {
            last = null
            return null
        }
        val order = state["turnOrder"]
        if (order !== turnOrderSource) {
            turnOrderSource = order
            turnOrder = stringList(order)
        }
        val turns = state["turnsById"]
        if (turns !== turnsByIdSource) {
            turnsByIdSource = turns
            turnsById = adaptTurns(turns)
        }
        val previous = last
        last = if (previous != null && sessionRest.unchanged && previous.turnOrder === turnOrder && previous.turnsById === turnsById) {
            previous
        } else {
            rest.copy(turnOrder = turnOrder, turnsById = turnsById)
        }
        return last
    }

    private fun adaptTurns(turns: JsValue?): Map<String, TurnProjection> {
        val obj = turns as? JsObj ?: return emptyMap<String, TurnProjection>().also { turnSlots = HashMap() }
        val slots = HashMap<String, TurnSlot>(obj.size * 2)
        val out = LinkedHashMap<String, TurnProjection>(obj.size * 2)
        for ((turnId, value) in obj) {
            val turn = value as? JsObj ?: continue
            val slot = turnSlots[turnId] ?: TurnSlot()
            slots[turnId] = slot
            slot.adapt(turnId, turn)?.let { out[turnId] = it }
        }
        turnSlots = slots
        return out
    }

    private inner class TurnSlot {
        private var source: JsObj? = null
        private var value: TurnProjection? = null
        private val rest = Memo(TURN_SHAPE)
        private var blocksSource: JsValue? = null
        private var blocks: List<String> = emptyList()
        private var blocksByIdSource: JsValue? = null
        private var blocksById: Map<String, TurnBlock> = emptyMap()
        private var blockSlots: HashMap<String, BlockSlot> = HashMap()

        fun adapt(turnId: String, turn: JsObj): TurnProjection? {
            if (turn === source) return value
            source = turn
            val decoded = rest.get(turn, "turnId", turnId)
            if (decoded == null) {
                value = null
                return null
            }
            val blockIds = turn["blocks"]
            if (blockIds !== blocksSource) {
                blocksSource = blockIds
                blocks = stringList(blockIds)
            }
            val byId = turn["blocksById"]
            if (byId !== blocksByIdSource) {
                blocksByIdSource = byId
                blocksById = adaptBlocks(byId)
            }
            val previous = value
            value = if (previous != null && rest.unchanged && previous.blocks === blocks && previous.blocksById === blocksById) {
                previous
            } else {
                decoded.copy(blocks = blocks, blocksById = blocksById)
            }
            return value
        }

        private fun adaptBlocks(byId: JsValue?): Map<String, TurnBlock> {
            val obj = byId as? JsObj ?: return emptyMap<String, TurnBlock>().also { blockSlots = HashMap() }
            val slots = HashMap<String, BlockSlot>(obj.size * 2)
            val out = LinkedHashMap<String, TurnBlock>(obj.size * 2)
            for ((blockId, value) in obj) {
                val block = value as? JsObj ?: continue
                val prior = blockSlots[blockId]
                val slot = if (prior != null && prior.source === block) {
                    prior
                } else {
                    BlockSlot(block, decode(BLOCK_SHAPE, block, "blockId", blockId))
                }
                slots[blockId] = slot
                slot.value?.let { out[blockId] = it }
            }
            blockSlots = slots
            return out
        }
    }

    private class BlockSlot(val source: JsObj, val value: TurnBlock?)

    /**
     * Decode [obj]'s modeled keys; when the whole does not fit, keep the required keys and every
     * optional key that fits on its own (the rest fall back to their defaults). [idKey] = [id]
     * stands in for a required id the tree lacks (a block's `blockId`; the map key is the id).
     */
    private fun <T : Any> decode(shape: Shape<T>, obj: JsObj, idKey: String?, id: String?): T? {
        val fields = LinkedHashMap<String, JsonElement>(shape.keys.size * 2)
        for (key in shape.keys) obj[key]?.let { fields[key] = JsCodec.toJson(it) }
        if (idKey != null && id != null && obj[idKey] !is JsStr) fields[idKey] = JsCodec.toJson(JsStr(id))
        tryDecode(shape.serializer, fields)?.let { return it }
        misfits++
        val kept = LinkedHashMap<String, JsonElement>()
        for (key in shape.required) fields[key]?.let { kept[key] = it }
        if (tryDecode(shape.serializer, kept) == null) return null
        for ((key, element) in fields) {
            if (key in shape.required) continue
            kept[key] = element
            if (tryDecode(shape.serializer, kept) == null) kept.remove(key)
        }
        return tryDecode(shape.serializer, kept)
    }

    /**
     * The typed decode of one object's modeled keys, memoized on the identity of each of those
     * keys' values (so a change to an unmodeled v128 key, or to an excluded child such as
     * `blocksById`, costs identity checks only).
     */
    private inner class Memo<T : Any>(private val shape: Shape<T>) {
        private var sources: Array<JsValue?>? = null
        private var value: T? = null

        /** True when the last [get] reused the previous value. */
        var unchanged: Boolean = false
            private set

        fun get(obj: JsObj, idKey: String? = null, id: String? = null): T? {
            val keys = shape.keys
            val prev = sources
            var same = prev != null
            val next = arrayOfNulls<JsValue>(keys.size)
            for (i in keys.indices) {
                val v = obj[keys[i]]
                next[i] = v
                if (same && prev!![i] !== v) same = false
            }
            unchanged = same
            if (same) return value
            sources = next
            value = decode(shape, obj, idKey, id)
            return value
        }
    }

    /** A typed class's serializer plus the tree keys it models (minus the ones assembled by hand). */
    private class Shape<T : Any>(val serializer: KSerializer<T>, excluded: Set<String> = emptySet()) {
        val keys: Array<String>
        val required: Set<String>

        init {
            val d = serializer.descriptor
            keys = (0 until d.elementsCount).map { d.getElementName(it) }.filterNot { it in excluded }.toTypedArray()
            required = (0 until d.elementsCount).filterNot { d.isElementOptional(it) }.map { d.getElementName(it) }.toSet()
        }
    }

    companion object {
        private val SESSION_SHAPE = Shape(SessionProjection.serializer(), setOf("turnOrder", "turnsById"))
        private val TURN_SHAPE = Shape(TurnProjection.serializer(), setOf("blocks", "blocksById"))
        private val BLOCK_SHAPE = Shape(TurnBlock.serializer())

        /** One-shot adapt (no memo carried over): previews, tests, one-off snapshot reads. */
        fun adaptOnce(state: JsObj): SessionProjection? = LegacyProjectionAdapter().adapt(state)

        private fun stringList(value: JsValue?): List<String> {
            val arr = value as? JsArr ?: return emptyList()
            return arr.mapNotNull { (it as? JsStr)?.value }
        }

        private fun <T> tryDecode(serializer: KSerializer<T>, fields: Map<String, JsonElement>): T? =
            try {
                TetherJson.decodeFromJsonElement(serializer, JsonObject(fields))
            } catch (_: IllegalArgumentException) {
                // SerializationException (and its MissingFieldException) extends IllegalArgumentException.
                null
            }
    }
}
