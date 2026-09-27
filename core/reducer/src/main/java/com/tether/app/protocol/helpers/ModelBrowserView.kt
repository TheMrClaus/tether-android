package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.coalesce
import com.tether.app.protocol.fold.isNullish
import com.tether.app.protocol.fold.jsToString
import com.tether.app.protocol.fold.strictEquals
import com.tether.app.protocol.fold.truthy
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.js

/**
 * T2.2: faithful port of lib/model-browser-view.mjs — the model browser's view / search / ranking
 * over ProviderCatalogEntry rows. A row is
 * `{ key, entryKey, provider, providerLabel, modelId, modelLabel, description, isDefault }`.
 */
object ModelBrowserView {

    // lib/model-browser-view.mjs:18
    private fun rowKey(entryKey: JsValue?, modelId: JsValue?): String = "${jsToString(entryKey)}:${jsToString(modelId)}"

    // lib/model-browser-view.mjs:24 — the synthetic "Default" row for an EMPTY ready catalog.
    fun buildSyntheticDefaultRow(entry: JsValue?): JsObj = JsObj.of(
        "key" to JsStr(rowKey(entry["key"], JsStr(""))),
        "entryKey" to entry["key"],
        "provider" to entry["provider"],
        "providerLabel" to coalesce(entry["label"], entry["provider"]),
        "modelId" to JsStr(""),
        "modelLabel" to JsStr("Default"),
        "description" to null,
        "isDefault" to JsBool.TRUE,
    )

    private fun isDefaultValue(value: JsValue?) = value.isStr("") || value.isStr("default")

    // lib/model-browser-view.mjs:37
    private fun buildModelRows(entry: JsValue?): List<JsObj> {
        val models = entry["models"].arrOrEmpty()
        return models.map { model ->
            val value = model["value"]
            JsObj.of(
                "key" to JsStr(rowKey(entry["key"], value)),
                "entryKey" to entry["key"],
                "provider" to entry["provider"],
                "providerLabel" to coalesce(entry["label"], entry["provider"]),
                "modelId" to JsStr((value as? JsStr)?.value ?: ""),
                "modelLabel" to JsStr(ModelPicker.modelDisplayName(model, models)),
                "description" to coalesce(model["description"], if (isDefaultValue(value)) null else value),
                "isDefault" to js(isDefaultValue(value)),
            )
        }
    }

    // lib/model-browser-view.mjs:55 — a ready entry's rows; a non-ready entry yields nothing.
    fun getProviderModelRows(entry: JsValue?): JsArr {
        if (!truthy(entry) || !entry["status"].isStr("ready")) return JsArr.EMPTY
        val models = entry["models"].arrOrEmpty()
        if (models.isEmpty()) return JsArr.of(buildSyntheticDefaultRow(entry))
        return JsArr.of(buildModelRows(entry))
    }

    // lib/model-browser-view.mjs:62
    fun getAllProviderModelRows(entries: JsValue?): JsArr = JsArr.of(entries.arrOrEmpty().flatMap { getProviderModelRows(it) })

    private fun lowerString(v: JsValue?): String = (if (isNullish(v)) "" else jsToString(v)).lowercase()

    // lib/model-browser-view.mjs:69 — prefix-on-label > prefix-on-id > substring-on-label > the rest.
    private fun scoreModelRow(row: JsValue, q: String): Int? {
        val label = lowerString(row["modelLabel"])
        val id = lowerString(row["modelId"])
        val providerLabel = lowerString(row["providerLabel"])
        val description = lowerString(row["description"])
        if (q.isEmpty()) return null
        if (label.startsWith(q)) return 0
        if (id.startsWith(q)) return 1
        if (label.contains(q)) return 2
        if (id.contains(q) || providerLabel.contains(q) || description.contains(q)) return 3
        return null
    }

    // lib/model-browser-view.mjs:84 — ranked matches; ties break by label (localeCompare).
    fun filterAndRankModelRows(rows: JsValue?, normalizedQuery: JsValue?): JsArr {
        val list = rows.arrOrEmpty()
        if (!truthy(normalizedQuery)) return list
        val q = jsToString(normalizedQuery)
        val scored = list.mapNotNull { row -> scoreModelRow(row, q)?.let { row to it } }
        return JsArr.of(
            scored.sortedWith { a, b ->
                if (a.second != b.second) a.second - b.second else jsLocaleCompare(jsToString(a.first["modelLabel"]), jsToString(b.first["modelLabel"]))
            }.map { it.first },
        )
    }

    // lib/model-browser-view.mjs:101 — "Claude · Most capable", or just the provider label.
    fun buildProviderQualifiedDescription(row: JsValue?): JsValue? =
        if (truthy(row["description"])) JsStr("${jsToString(row["providerLabel"])} · ${jsToString(row["description"])}") else row["providerLabel"]

    // lib/model-browser-view.mjs:105
    fun resolveModelBrowserAllView(entries: JsValue?, normalizedQuery: JsValue?): JsObj {
        if (!truthy(normalizedQuery)) return JsObj.of("kind" to JsStr("browse"))
        val rows = filterAndRankModelRows(getAllProviderModelRows(entries), normalizedQuery)
        if (rows.isEmpty()) return JsObj.of("kind" to JsStr("noSearchMatches"))
        return JsObj.of("kind" to JsStr("searchResults"), "rows" to rows)
    }

    // lib/model-browser-view.mjs:115 — sole provider → its view; else the selected one; else root.
    fun resolveInitialModelBrowserView(entries: JsValue?, selectedKey: JsValue?): JsObj {
        val selectable = entries.arrOrEmpty().filter { !it["status"].isStr("unavailable") }
        if (selectable.size == 1) {
            val only = selectable[0]
            return JsObj.of("kind" to JsStr("provider"), "entryKey" to only["key"], "providerLabel" to coalesce(only["label"], only["provider"]))
        }
        if (truthy(selectedKey)) {
            val selected = selectable.firstOrNull { strictEquals(it["key"], selectedKey) }
            if (selected != null) {
                return JsObj.of("kind" to JsStr("provider"), "entryKey" to selected["key"], "providerLabel" to coalesce(selected["label"], selected["provider"]))
            }
        }
        return JsObj.of("kind" to JsStr("all"))
    }

    // lib/model-browser-view.mjs:131 — the label a model chip shows while nothing is pinned.
    fun resolveSelectedModelLabel(entries: JsValue?, selectedKey: JsValue?, selectedModel: JsValue?): JsValue? {
        val entry = entries.arrOrEmpty().firstOrNull { strictEquals(it["key"], selectedKey) } ?: return JsStr("")
        val rows = getProviderModelRows(entry)
        rows.firstOrNull { strictEquals(it["modelId"], selectedModel) }?.let { return it["modelLabel"] }
        if (truthy(selectedModel)) {
            val name = ModelPicker.modelDisplayName(JsObj.of("value" to selectedModel), entry["models"])
            return if (name.isNotEmpty() && !selectedModel.isStr(name)) JsStr(name) else selectedModel
        }
        return rows.firstOrNull { it["isDefault"] == JsBool.TRUE }?.get("modelLabel")
            ?: rows.firstOrNull()?.get("modelLabel")
            ?: JsStr("Default")
    }
}
