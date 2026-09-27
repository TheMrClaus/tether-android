package com.tether.app.protocol.tree

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList

/**
 * T2.1: the JSON-tree value the v128 reducer folds (docs/parity/T2.1_PLAN.md §1, option b).
 *
 * The tree mirrors a JS value exactly, so a port of `engines/events.mjs` can stay line for line:
 *  - Kotlin `null` is JS `undefined` (an absent key). JS `null` is [JsNull]. The two are never
 *    conflated: `put(key, null)` REMOVES the key, `put(key, JsNull)` stores an explicit null.
 *  - [JsObj] keeps insertion order (a persistent ordered map), like a JS object's string keys.
 *    Updating an existing key keeps its position, deleting and re-adding moves it to the end.
 *  - [JsArr] is a persistent list; [JsNum] is an IEEE-754 double with JS `===` semantics
 *    (`0 == -0`; NaN never occurs in projection data).
 *
 * Everything is immutable and structurally shared, so a delta rebuilds only the path it touches
 * and every untouched subtree keeps its identity (Revision 6/7: `equals` checks identity first).
 */
sealed class JsValue

/** A JS string. */
class JsStr(val value: String) : JsValue() {
    override fun equals(other: Any?): Boolean = this === other || (other is JsStr && other.value == value)
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "JsStr($value)"
}

/** A JS number. Equality is numeric (`0.0 == -0.0`), matching JS `===` and the manifest's compare rule. */
class JsNum(val value: Double) : JsValue() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JsNum) return false
        val a: Double = value
        val b: Double = other.value
        return a == b
    }

    override fun hashCode(): Int = if (value == 0.0) 0 else value.hashCode()
    override fun toString(): String = "JsNum(${JsNumberFormat.toJsString(value)})"
}

/** A JS boolean; use [JsBool.of] or the [TRUE]/[FALSE] singletons. */
class JsBool private constructor(val value: Boolean) : JsValue() {
    override fun toString(): String = "JsBool($value)"

    companion object {
        val TRUE = JsBool(true)
        val FALSE = JsBool(false)
        fun of(value: Boolean): JsBool = if (value) TRUE else FALSE
    }
}

/** JS `null` — distinct from an absent key (Kotlin `null`). */
data object JsNull : JsValue()

/**
 * A JS plain object over an insertion-ordered persistent map. Readable as a [Map]; every
 * "write" returns a new object (or `this` when nothing changed, which preserves identity).
 */
class JsObj private constructor(val map: PersistentMap<String, JsValue>) : JsValue(), Map<String, JsValue> by map {

    /** `Object.hasOwn(obj, key)`. */
    fun has(key: String): Boolean = map.containsKey(key)

    /** `{ ...this, [key]: value }`; a `null` value is JS `undefined`, i.e. the key is removed. */
    fun put(key: String, value: JsValue?): JsObj {
        if (value == null) return remove(key)
        val existing = map[key]
        if (existing === value || (existing != null && isPrimitive(existing) && existing == value)) return this
        return wrap(map.put(key, value))
    }

    /** `delete copy[key]`. */
    fun remove(key: String): JsObj = if (!map.containsKey(key)) this else wrap(map.remove(key))

    /** `{ ...this, ...other }`. */
    fun spread(other: JsObj): JsObj {
        if (other.isEmpty()) return this
        if (isEmpty()) return other
        var next = this
        for ((key, value) in other.map) next = next.put(key, value)
        return next
    }

    /** Batch several writes into one rebuild; `null` values remove, like [put]. */
    fun with(vararg entries: Pair<String, JsValue?>): JsObj {
        var next = this
        for ((key, value) in entries) next = next.put(key, value)
        return next
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JsObj) return false
        return map == other.map
    }

    private var hash = 0
    override fun hashCode(): Int {
        if (hash == 0) hash = map.hashCode()
        return hash
    }

    override fun toString(): String = JsCodec.canonical(this)

    companion object {
        val EMPTY = JsObj(persistentMapOf())

        private fun wrap(map: PersistentMap<String, JsValue>): JsObj = if (map.isEmpty()) EMPTY else JsObj(map)

        /** An object literal; `null` values (JS `undefined`) are skipped, like `JSON.stringify` drops them. */
        fun of(vararg entries: Pair<String, JsValue?>): JsObj = EMPTY.with(*entries)

        fun from(map: Map<String, JsValue>): JsObj {
            val builder = persistentMapOf<String, JsValue>().builder()
            builder.putAll(map)
            return wrap(builder.build())
        }
    }
}

/** A JS array over a persistent list. Readable as a [List]. */
class JsArr private constructor(val list: PersistentList<JsValue>) : JsValue(), List<JsValue> by list {

    /** `[...this, value]`. */
    fun add(value: JsValue): JsArr = JsArr(list.add(value))

    /** `this.map((v, i) => i === index ? value : v)`; returns `this` when the slot already holds it. */
    fun set(index: Int, value: JsValue): JsArr = if (list[index] === value) this else JsArr(list.set(index, value))

    /** `this.slice(from, to)` with JS clamping for negative / out-of-range bounds. */
    fun slice(from: Int = 0, to: Int = size): JsArr {
        val n = size
        val start = if (from < 0) maxOf(n + from, 0) else minOf(from, n)
        val end = if (to < 0) maxOf(n + to, 0) else minOf(to, n)
        if (start == 0 && end == n) return this
        if (start >= end) return EMPTY
        return JsArr(list.subList(start, end).toPersistentList())
    }

    /** `this.filter(predicate)`; returns `this` when nothing is dropped. */
    fun filterKeep(predicate: (JsValue) -> Boolean): JsArr {
        val kept = list.filter(predicate)
        return if (kept.size == size) this else of(kept)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JsArr) return false
        return list == other.list
    }

    private var hash = 0
    override fun hashCode(): Int {
        if (hash == 0) hash = list.hashCode()
        return hash
    }

    override fun toString(): String = JsCodec.canonical(this)

    companion object {
        val EMPTY = JsArr(persistentListOf())
        fun of(vararg values: JsValue): JsArr = if (values.isEmpty()) EMPTY else JsArr(values.asList().toPersistentList())
        fun of(values: Iterable<JsValue>): JsArr {
            val list = values.toPersistentList()
            return if (list.isEmpty()) EMPTY else JsArr(list)
        }
    }
}

private fun isPrimitive(value: JsValue): Boolean = value !is JsObj && value !is JsArr

// ---- construction / read sugar used throughout the fold ----

fun js(value: String): JsStr = JsStr(value)
fun js(value: Double): JsNum = JsNum(value)
fun js(value: Int): JsNum = JsNum(value.toDouble())
fun js(value: Long): JsNum = JsNum(value.toDouble())
fun js(value: Boolean): JsBool = JsBool.of(value)

/** `typeof v === "string" ? v : undefined`. */
val JsValue?.str: String? get() = (this as? JsStr)?.value

/** `typeof v === "number" ? v : undefined`. */
val JsValue?.num: Double? get() = (this as? JsNum)?.value

/** `typeof v === "boolean" ? v : undefined`. */
val JsValue?.bool: Boolean? get() = (this as? JsBool)?.value

/** The value when it is a plain object (not an array, not null). */
val JsValue?.obj: JsObj? get() = this as? JsObj

/** The value when it is an array (`Array.isArray`). */
val JsValue?.arr: JsArr? get() = this as? JsArr
