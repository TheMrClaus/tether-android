package com.tether.app.protocol.helpers

import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue

/** T2.2: faithful port of lib/model-id.mjs. */
object ModelId {

    // lib/model-id.mjs:21 — did the served model demonstrably differ from the selected one?
    fun modelsDiverge(selected: JsValue?, served: JsValue?): Boolean =
        modelsDiverge((selected as? JsStr)?.value, (served as? JsStr)?.value)

    fun modelsDiverge(selected: String?, served: String?): Boolean {
        val a = normalizeModelId(selected)
        val b = normalizeModelId(served)
        if (a.isEmpty() || b.isEmpty()) return false
        return a != b && !a.contains(b) && !b.contains(a)
    }

    private val NON_ALNUM = Regex("[^a-z0-9]")

    // lib/model-id.mjs:27
    private fun normalizeModelId(value: String?): String = value?.lowercase()?.replace(NON_ALNUM, "") ?: ""
}
