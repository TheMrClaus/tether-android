package com.tether.app.protocol.conformance

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsValue

/** One difference between the expected (JS) projection and the Kotlin one. */
data class Divergence(val path: String, val label: String, val expected: JsValue?, val actual: JsValue?) {
    fun render(): String = "  $path  [$label]  expected=${show(expected)}  actual=${show(actual)}"

    private fun show(v: JsValue?): String {
        if (v == null) return "<absent>"
        val text = JsCodec.canonical(v)
        return if (text.length <= MAX_VALUE_CHARS) text else text.take(MAX_VALUE_CHARS) + "…(${text.length} chars)"
    }

    companion object {
        const val MAX_VALUE_CHARS = 160
    }
}

/**
 * Structural diff of two canonical trees. Numbers compare numerically (JsNum equality), object
 * keys as sets (canonical order is sorted anyway). Labels: `missing` (expected has it, Kotlin
 * does not), `extra` (the reverse), `null-vs-absent` (one side is explicit null, the other
 * absent), `type`, `value`.
 */
object TreeDiff {

    /** Record-shaped maps whose keys are ids; printed as `["id"]` and wildcarded in histograms. */
    private val RECORD_KEYS = setOf("turnsById", "blocksById", "pendingApprovals", "pendingQuestions", "mcpHealth", "entries")

    fun diff(expected: JsValue?, actual: JsValue?, limit: Int = Int.MAX_VALUE): List<Divergence> {
        val out = ArrayList<Divergence>()
        walk("$", null, expected, actual, out, limit)
        return out
    }

    private fun walk(path: String, parentKey: String?, e: JsValue?, a: JsValue?, out: MutableList<Divergence>, limit: Int) {
        if (out.size >= limit) return
        if (e === a) return
        when {
            e == null && a == null -> return
            e == null -> out.add(Divergence(path, if (a === JsNull) "null-vs-absent" else "extra", e, a))
            a == null -> out.add(Divergence(path, if (e === JsNull) "null-vs-absent" else "missing", e, a))
            e is JsObj && a is JsObj -> {
                for (key in (e.keys + a.keys).sorted()) {
                    walk(child(path, parentKey, key), key, e[key], a[key], out, limit)
                    if (out.size >= limit) return
                }
            }
            e is JsArr && a is JsArr -> {
                for (i in 0 until maxOf(e.size, a.size)) {
                    walk("$path[$i]", parentKey, e.getOrNull(i), a.getOrNull(i), out, limit)
                    if (out.size >= limit) return
                }
            }
            e::class != a::class -> out.add(Divergence(path, "type", e, a))
            e != a -> out.add(Divergence(path, "value", e, a))
        }
    }

    private val IDENTIFIER = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")

    private fun child(path: String, parentKey: String?, key: String): String {
        val quoted = JsCodec.stringify(com.tether.app.protocol.tree.JsStr(key))
        return when {
            // A record map's children: the id in brackets. `parentKey` is the key that led to the
            // current object, so `$.turnsById` + "t1" becomes `$.turnsById["t1"]`.
            path.endsWith(".$parentKey") && parentKey in RECORD_KEYS -> "$path[$quoted]"
            IDENTIFIER.matches(key) -> "$path.$key"
            else -> "$path[$quoted]"
        }
    }

    /** `$.turnsById["t1"].blocksById["b2"].ts` → `$.turnsById.*.blocksById.*.ts`; `[3]` → `[*]`. */
    fun normalize(path: String): String = path
        .replace(Regex("""\["(?:[^"\\]|\\.)*"]"""), ".*")
        .replace(Regex("""\[\d+]"""), "[*]")
}
