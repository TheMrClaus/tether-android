package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.isNullish
import com.tether.app.protocol.fold.jsToString
import com.tether.app.protocol.fold.jsonStringify
import com.tether.app.protocol.tree.JsValue

/** T2.2: faithful port of lib/version-compare.mjs. */
object VersionCompare {

    private val DIGITS = Regex("^\\d+$")

    // lib/version-compare.mjs:29 — strict `x.y.z` comparison; throws on anything else.
    fun compareVersionTriples(a: JsValue?, b: JsValue?): Double {
        fun parse(value: JsValue?): List<Double> =
            (if (isNullish(value)) "" else jsToString(value)).split(".").map { part ->
                if (DIGITS.matches(part)) part.toBigDecimal().toDouble() else Double.NaN
            }
        val left = parse(a)
        val right = parse(b)
        if (left.size != 3 || right.size != 3 || left.any { it.isNaN() } || right.any { it.isNaN() }) {
            throw JsError("Error", "Unparseable version comparison: ${stringify(a)} vs ${stringify(b)}")
        }
        for (index in 0 until 3) {
            val diff = left[index] - right[index]
            if (diff != 0.0) return diff
        }
        return 0.0
    }

    /** `JSON.stringify(v)` — `undefined` for an absent value (template-literal "undefined"). */
    private fun stringify(value: JsValue?): String = if (value == null) "undefined" else jsonStringify(value)
}
