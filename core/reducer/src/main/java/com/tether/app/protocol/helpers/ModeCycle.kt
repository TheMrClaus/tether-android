package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.strictEquals
import com.tether.app.protocol.fold.truthy
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue

/** T2.2: faithful port of lib/mode-cycle.mjs. */
object ModeCycle {

    // lib/mode-cycle.mjs:4 — the next selectable option's value, wrapping; null with < 2 options.
    fun nextModeValue(options: JsValue?, currentValue: JsValue?): String? {
        val selectable = (options as? JsArr ?: JsArr.EMPTY).filter { option ->
            truthy(option) &&
                option["value"].let { it is JsStr && it.value.isNotEmpty() } &&
                option["disabled"] != JsBool.TRUE
        }
        if (selectable.size < 2) return null
        val currentIndex = selectable.indexOfFirst { strictEquals(it["value"], currentValue) }
        return (selectable[(currentIndex + 1 + selectable.size) % selectable.size]["value"] as JsStr).value
    }
}
