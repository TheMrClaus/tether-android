package com.tether.app.protocol.conformance

import com.tether.app.protocol.fold.strictEquals
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import java.io.File
import java.security.MessageDigest

/**
 * T2.1 H0: the parity corpus's canonical JSON (corpus-manifest.json `canonicalJson`), shared by
 * the reducer conformance harness and T2.2's helper harness, which layers the typed values
 * (`$undefined`, `$number`, `$date`, `$map`, `$set`, `$fn`) on top of these plain trees via
 * [decodeTagged] / [encodeTagged].
 */
object CanonicalJson {

    /** The vendored corpus root, from the `parity.corpus` system property the build sets. */
    val corpusDir: File by lazy {
        val path = System.getProperty("parity.corpus")
            ?: error("system property parity.corpus is not set (core/reducer/build.gradle.kts sets it for Test tasks)")
        File(path).also { require(it.isDirectory) { "parity corpus not found at $it" } }
    }

    /** The repo root (parent of parity-corpus/), for tests that read sources. */
    val repoRoot: File get() = corpusDir.parentFile

    fun parse(text: String): JsValue = JsCodec.parse(text)

    fun read(file: File): JsValue = parse(file.readText(Charsets.UTF_8))

    /** Canonical text without the corpus files' trailing newline. */
    fun write(value: JsValue): String = JsCodec.canonical(value)

    /** Exactly what a corpus file holds: one canonical value and a single "\n". */
    fun fileText(value: JsValue): String = write(value) + "\n"

    val manifest: JsObj by lazy { read(File(corpusDir, "corpus-manifest.json")) as JsObj }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---- T2.2: the helper corpus's typed layer (manifest canonicalJson.helperTypedValues) ----

    /** JS `undefined` as an argument, a top-level result, or an array element (`{"$undefined":true}`). */
    object Undefined {
        override fun toString() = "undefined"
    }

    /** A Date instance (`{"$date":<ms>}`); [ms] is its getTime(), NaN for an Invalid Date. */
    data class TaggedDate(val ms: Double)

    /** A Map, entries in insertion order (`{"$map":[[k,v],…]}`). */
    data class TaggedMap(val entries: List<Pair<Any?, Any?>>)

    /** A Set, values in insertion order (`{"$set":[…]}`). */
    data class TaggedSet(val values: List<Any?>)

    /**
     * A callback argument: `{"$fn":"constant","value":v}` always returns v;
     * `{"$fn":"propertyEquals","path":["a","b"],"value":v}` is `(x) => x?.a?.b === v`.
     */
    class TaggedFn(val kind: String, val path: List<String>, val value: JsValue) {
        operator fun invoke(x: JsValue?): JsValue = when (kind) {
            "constant" -> value
            "propertyEquals" -> {
                var cursor: JsValue? = x
                for (key in path) cursor = (cursor as? JsObj)?.get(key)
                JsBool.of(strictEquals(cursor, value))
            }
            else -> error("unknown \$fn kind $kind")
        }

        override fun toString() = "\$fn:$kind${if (path.isEmpty()) "" else path.joinToString(".", "(", ")")}=${write(value)}"
    }

    /** A plain object with a member the JSON tree cannot hold (a tagged Map/Set/Date/fn/undefined). */
    data class TaggedObject(val props: Map<String, Any?>)

    /** An array with an element the JSON tree cannot hold. */
    data class TaggedArray(val items: List<Any?>)

    /**
     * Decode the typed layer: a plain JSON subtree stays a [JsValue] (`$number` becomes a JsNum
     * holding NaN/±Infinity/-0), `$undefined` is [Undefined], and the other tags become the
     * wrapper types above — an object/array containing one becomes [TaggedObject]/[TaggedArray].
     */
    fun decodeTagged(value: JsValue): Any? = when (value) {
        is JsObj -> decodeObject(value)
        is JsArr -> {
            val items = value.map { decodeTagged(it) }
            if (items.all { it is JsValue }) JsArr.of(items.map { it as JsValue }) else TaggedArray(items)
        }
        else -> value
    }

    private fun decodeObject(value: JsObj): Any? {
        val tag = value.keys.firstOrNull { it.startsWith("$") } // no real key ever starts with "$" (manifest)
        if (tag != null) {
            return when (tag) {
                "\$undefined" -> Undefined
                "\$number" -> JsNum(
                    when (val text = (value[tag] as JsStr).value) {
                        "NaN" -> Double.NaN
                        "Infinity" -> Double.POSITIVE_INFINITY
                        "-Infinity" -> Double.NEGATIVE_INFINITY
                        "-0" -> -0.0
                        else -> error("unknown \$number $text")
                    },
                )
                "\$date" -> TaggedDate((decodeTagged(value.getValue(tag)) as JsNum).value)
                "\$map" -> TaggedMap((value.getValue(tag) as JsArr).map { pair ->
                    val entry = pair as JsArr
                    decodeTagged(entry[0]) to decodeTagged(entry[1])
                })
                "\$set" -> TaggedSet((value.getValue(tag) as JsArr).map { decodeTagged(it) })
                "\$fn" -> TaggedFn(
                    kind = (value.getValue(tag) as JsStr).value,
                    path = (value["path"] as? JsArr)?.map { (it as JsStr).value }.orEmpty(),
                    value = value.getValue("value"),
                )
                else -> error("unknown helper tag $tag in ${write(value)}")
            }
        }
        val props = LinkedHashMap<String, Any?>()
        for ((key, v) in value) props[key] = decodeTagged(v)
        return if (props.values.all { it is JsValue }) value else TaggedObject(props)
    }

    /**
     * Encode a Kotlin helper result into the typed layer, for comparison with a corpus `result`:
     * JsValue trees (with non-finite / negative-zero numbers tagged), Kotlin strings, numbers and
     * booleans, [Undefined], Kotlin `null` as JS `null`, Maps as `$map`, Sets as `$set`, other
     * Lists as arrays, and the wrapper types.
     */
    fun encodeTagged(value: Any?): JsValue = when (value) {
        null -> JsNull
        Undefined -> JsObj.of("\$undefined" to JsBool.TRUE)
        is JsNum -> encodeNumber(value.value)
        is JsObj -> JsObj.from(LinkedHashMap<String, JsValue>().also { out -> for ((k, v) in value) out[k] = encodeTagged(v) })
        is JsArr -> JsArr.of(value.map { encodeTagged(it) })
        is JsValue -> value
        is String -> JsStr(value)
        is Boolean -> JsBool.of(value)
        is Int -> encodeNumber(value.toDouble())
        is Long -> encodeNumber(value.toDouble())
        is Double -> encodeNumber(value)
        is TaggedDate -> JsObj.of("\$date" to encodeNumber(value.ms))
        is TaggedMap -> JsObj.of("\$map" to arr(value.entries.map { (k, v) -> arr(listOf(encodeTagged(k), encodeTagged(v))) }))
        is TaggedSet -> JsObj.of("\$set" to arr(value.values.map { encodeTagged(it) }))
        is TaggedObject -> JsObj.from(LinkedHashMap<String, JsValue>().also { out ->
            for ((k, v) in value.props) if (v !== Undefined) out[k] = encodeTagged(v)
        })
        is TaggedArray -> arr(value.items.map { encodeTagged(it) })
        is Map<*, *> -> JsObj.of("\$map" to arr(value.entries.map { (k, v) -> arr(listOf(encodeTagged(k), encodeTagged(v))) }))
        is Set<*> -> JsObj.of("\$set" to arr(value.map { encodeTagged(it) }))
        is List<*> -> arr(value.map { encodeTagged(it) })
        else -> error("cannot encode ${value::class.qualifiedName} into the helper typed layer")
    }

    private fun arr(values: List<JsValue>) = JsArr.of(values)

    private fun encodeNumber(d: Double): JsValue = when {
        d.isNaN() -> JsObj.of("\$number" to JsStr("NaN"))
        d == Double.POSITIVE_INFINITY -> JsObj.of("\$number" to JsStr("Infinity"))
        d == Double.NEGATIVE_INFINITY -> JsObj.of("\$number" to JsStr("-Infinity"))
        d == 0.0 && 1 / d < 0 -> JsObj.of("\$number" to JsStr("-0"))
        else -> JsNum(d)
    }
}
