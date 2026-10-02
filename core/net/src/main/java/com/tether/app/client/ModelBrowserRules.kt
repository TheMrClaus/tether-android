package com.tether.app.client

import com.tether.app.protocol.fold.jsTrim
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue

/**
 * ta-2uq (T8.1 slice 3): the operator's hand-added model ids (issue #45, lib/draft-form.ts
 * addCustomModelPref). They are client-only: kept in the draft preferences of ONE server origin
 * ([com.tether.app.ui.prefs.DraftStore] keys them per origin), merged into that server's catalog and
 * sent as the create's `model` like a discovered pick.
 *
 * ta-coik.4: the web's rule (lib/draft-form.ts:250-268 addCustomModelPref, model-browser.tsx:530-532):
 * any id that is not empty after ECMAScript `trim`. The server validates what a create carries.
 */
object CustomModelId {
    /** Why an id cannot be added (null from [problem]: it can). */
    enum class Problem(val copy: String) {
        Empty(""),
    }

    /** What the web stores for [raw] (its trim). */
    fun normalize(raw: String): String = jsTrim(raw)

    /** Null when [raw] (trimmed) may be stored and sent; otherwise why not. */
    fun problem(raw: String): Problem? = if (normalize(raw).isEmpty()) Problem.Empty else null

    fun valid(raw: String): Boolean = problem(raw) == null

    /**
     * The draft preferences' `customModels` as the browser's Custom section reads them
     * (lib/draft-form.ts customModelIdsFor and mergeCustomModelsIntoEntries): row key → its list's
     * string ids, trimmed, empty and repeated ones skipped. Anything that is not an object of lists
     * reads as none.
     */
    fun asMap(customModels: JsValue?): Map<String, List<String>> {
        val map = customModels as? JsObj ?: return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        for ((key, value) in map.entries) {
            val ids = (value as? JsArr)?.asSequence().orEmpty()
                .mapNotNull { (it as? JsStr)?.value?.let(::normalize) }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
            if (ids.isNotEmpty()) out[key] = ids
        }
        return out
    }
}

/** ta-2uq: what became of a model browser's Retry or Refresh. */
enum class ProviderRefreshResult {
    /** `refresh-providers` went out on the live socket for that row. */
    Sent,

    /** No live, handshaken socket, or not the one the browser was drawn for: nothing sent. */
    NotConnected,

    /** The row is not in the catalog the current socket delivered: nothing sent. */
    NotOffered,
}
