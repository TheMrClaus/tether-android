package com.tether.app.ui.chat

import com.tether.app.protocol.ModelVariantOption
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.helpers.ModelPicker
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One drawn change: "Model" or "Effort", and the two labels. */
data class HandoverChangeItem(val label: String, val from: String, val to: String)

/** What the two handoff banners draw of `AgentSession.handoverChange`: the changed items and the agent's reason (null = none). */
data class HandoverChangeView(val items: List<HandoverChangeItem>, val reason: String?) {
    /** The words a screen reader reads for the change block: "Model A to B. Effort C to D. Reason: ...". */
    val spoken: String
        get() = (items.map { "${it.label} ${it.from} to ${it.to}." } + listOfNotNull(reason?.let { "Reason: $it" })).joinToString(" ")
}

/**
 * `lib/handover-change-view.ts` (web, #301): labels are the client's (the composer chips' strings), a setting whose RESOLVED values
 * are the same is not a change (the server records the raw pinned values; a source on the default and a successor naming that
 * same model arrive as a `model` change), and a cleared setting arrives as `to: "default"`.
 */
internal object HandoverChangeModel {
    private fun isDefault(value: String?) = value.isNullOrEmpty() || value.equals("default", ignoreCase = true)

    private fun lower(value: String) = value.trim().lowercase()

    private fun str(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** `{from, to}` of one setting: `to` must be a string; `from` is a string or null. */
    private fun pair(change: JsonObject, key: String): Pair<String?, String>? {
        val o = change[key] as? JsonObject ?: return null
        val to = str(o, "to") ?: return null
        val from = if (o["from"] is JsonNull || o["from"] == null) null else str(o, "from")
        return from to to
    }

    private fun modelRows(models: List<SessionModelOption>): JsArr = JsArr.of(
        models.map { m ->
            JsObj.of(
                "value" to JsStr(m.value),
                "displayName" to JsStr(m.displayName),
                "resolvedModel" to (m.resolvedModel?.let { JsStr(it) } ?: com.tether.app.protocol.tree.JsNull),
            )
        },
    )

    /** The row a model id points at (by value or by the id it resolves to), so an alias and its full id compare equal. */
    private fun canonicalModel(value: String, models: List<SessionModelOption>): String =
        lower(models.firstOrNull { it.value == value || it.resolvedModel == value }?.value?.takeIf { it.isNotEmpty() } ?: value)

    fun view(
        change: JsonElement?,
        provider: String?,
        models: List<SessionModelOption>,
        defaultModel: String?,
        defaultEffort: String?,
    ): HandoverChangeView? {
        val obj = change as? JsonObject ?: return null
        val items = mutableListOf<HandoverChangeItem>()
        pair(obj, "model")?.let { (from, to) ->
            val def = defaultModel?.takeIf { !isDefault(it) }
            val fromValue = if (isDefault(from)) def else from
            val toValue = if (isDefault(to)) def else to
            val equal = fromValue != null && toValue != null && canonicalModel(fromValue, models) == canonicalModel(toValue, models)
            if (!equal) {
                val rows = modelRows(models)
                val name = { v: String? -> if (v != null) ModelPicker.modelDisplayName(JsObj.of("value" to JsStr(v)), rows) else "Default" }
                items += HandoverChangeItem("Model", name(fromValue), name(toValue))
            }
        }
        pair(obj, "effort")?.let { (from, to) ->
            val def = defaultEffort?.takeIf { !isDefault(it) }
            val fromValue = if (isDefault(from)) def else from
            val toValue = if (isDefault(to)) def else to
            val equal = fromValue != null && toValue != null && lower(fromValue) == lower(toValue)
            if (!equal) {
                val variants: List<ModelVariantOption> = models.flatMap { it.variants.orEmpty() }
                val name = { v: String? ->
                    when {
                        v == null -> "Default"
                        else -> variants.firstOrNull { it.value == v }?.label?.takeIf { it.isNotEmpty() }
                            ?: if (provider == "claude") (ModelPicker.CLAUDE_EFFORT_LABELS[v] ?: v) else v
                    }
                }
                items += HandoverChangeItem("Effort", name(fromValue), name(toValue))
            }
        }
        if (items.isEmpty()) return null
        val reason = str(obj, "reason")?.takeIf { it.isNotBlank() }
        return HandoverChangeView(items, reason)
    }

    fun of(session: com.tether.app.protocol.model.AgentSession?, controls: com.tether.app.protocol.ServerMessage.SessionControls?): HandoverChangeView? =
        session?.let { view(it.handoverChange, it.provider, controls?.models.orEmpty(), controls?.defaultModel, controls?.defaultReasoningEffort) }
}
