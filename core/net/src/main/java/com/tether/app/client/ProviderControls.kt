package com.tether.app.client

import com.tether.app.protocol.ModeOption
import com.tether.app.protocol.ModelVariantOption
import com.tether.app.protocol.SessionModelOption
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * T7.2: one session's provider-control state (use-tether.ts:184 `codexControls` / `opencodeControls`):
 * the last catalog snapshot the server sent ON THE CURRENT SOCKET, whether a request or an action is
 * in flight, and the last `*-control-result` message. Cleared when the socket goes: a snapshot's
 * revision names one engine's catalog read, and an action is never sent against an old one.
 */
data class ProviderControlsState<S>(val snapshot: S?, val busy: Boolean, val message: String?)

/** engines/codex-control-service.d.mts CodexCatalog: "ready" | "unavailable" | "unsupported". */
data class CodexCatalog<T>(val status: String, val items: List<T>) {
    val ready: Boolean get() = status == "ready"
}

data class CodexEffort(val id: String, val description: String)

data class CodexModel(
    val id: String,
    val name: String,
    val description: String,
    val defaultReasoningEffort: String,
    val reasoningEfforts: List<CodexEffort>,
)

data class CodexCollaboration(val id: String, val name: String, val mode: String?, val model: String?, val reasoningEffort: String?)

data class CodexSkill(val id: String, val name: String, val description: String, val scope: String, val enabled: Boolean)

data class CodexHook(
    val id: String,
    val name: String,
    val event: String,
    val handler: String,
    val trust: String,
    val enabled: Boolean,
    val managed: Boolean,
)

data class CodexApp(val id: String, val name: String, val description: String, val enabled: Boolean, val accessible: Boolean, val plugins: List<String>)

data class CodexMcpServer(val id: String, val name: String, val status: String, val statusLabel: String, val toolCount: Long)

data class CodexRateWindow(val usedPercent: Double, val windowDurationMins: Long?, val resetsAt: Long?)

data class CodexRateLimit(
    val id: String,
    val name: String,
    val status: String,
    val statusLabel: String,
    val primary: CodexRateWindow?,
    val secondary: CodexRateWindow?,
    val hasCredits: Boolean?,
    val unlimitedCredits: Boolean?,
)

/**
 * CodexControlCatalogSnapshot (engines/codex-control-service.d.mts), read tolerantly from the raw
 * frame: an item missing a required field is dropped (never offered), an unknown status reads as
 * "unavailable", a missing catalog as unavailable and empty. Pure.
 */
data class CodexSnapshot(
    val revision: String,
    val models: CodexCatalog<CodexModel>,
    val collaborationModes: CodexCatalog<CodexCollaboration>,
    val skills: CodexCatalog<CodexSkill>,
    val hooks: CodexCatalog<CodexHook>,
    val apps: CodexCatalog<CodexApp>,
    val mcpServers: CodexCatalog<CodexMcpServer>,
    val rateLimits: CodexCatalog<CodexRateLimit>,
    val reviewStatus: String,
    val compactionStatus: String,
) {
    companion object {
        /** Null when [raw] has no non-empty `revision` (an action could never be bound to it). */
        fun parse(raw: JsonObject?): CodexSnapshot? {
            val o = raw ?: return null
            val revision = o.s("revision")?.takeIf { it.isNotEmpty() } ?: return null
            val actions = o["actions"] as? JsonObject
            return CodexSnapshot(
                revision = revision,
                models = o.catalog("models") { m ->
                    val id = m.s("id") ?: return@catalog null
                    CodexModel(
                        id = id,
                        name = LabelText.label(m.s("name")).ifEmpty { LabelText.label(id) },
                        description = LabelText.hint(m.s("description")),
                        defaultReasoningEffort = m.s("defaultReasoningEffort").orEmpty(),
                        reasoningEfforts = (m["reasoningEfforts"] as? JsonArray).orEmpty().mapNotNull { e ->
                            val eo = e as? JsonObject ?: return@mapNotNull null
                            CodexEffort(eo.s("id") ?: return@mapNotNull null, LabelText.hint(eo.s("description")))
                        }.take(LabelText.MAX_ITEMS),
                    )
                },
                collaborationModes = o.catalog("collaborationModes") { m ->
                    CodexCollaboration(m.s("id") ?: return@catalog null, LabelText.label(m.s("name")), m.s("mode"), m.s("model"), m.s("reasoningEffort"))
                },
                skills = o.catalog("skills") { m ->
                    CodexSkill(m.s("id") ?: return@catalog null, LabelText.label(m.s("name")), LabelText.hint(m.s("description")), LabelText.label(m.s("scope")), m.b("enabled") ?: return@catalog null)
                },
                hooks = o.catalog("hooks") { m ->
                    CodexHook(
                        m.s("id") ?: return@catalog null, LabelText.label(m.s("name")), LabelText.label(m.s("event")), LabelText.label(m.s("handler")),
                        LabelText.label(m.s("trust")), m.b("enabled") == true, m.b("managed") == true,
                    )
                },
                apps = o.catalog("apps") { m ->
                    CodexApp(
                        m.s("id") ?: return@catalog null, LabelText.label(m.s("name")), LabelText.hint(m.s("description")), m.b("enabled") == true, m.b("accessible") == true,
                        (m["plugins"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(LabelText::label) }.take(LabelText.MAX_ITEMS),
                    )
                },
                mcpServers = o.catalog("mcpServers") { m ->
                    CodexMcpServer(m.s("id") ?: return@catalog null, LabelText.label(m.s("name")), m.s("status").orEmpty(), LabelText.label(m.s("statusLabel")), m.n("toolCount")?.toLong() ?: 0)
                },
                rateLimits = o.catalog("rateLimits") { m ->
                    val credits = m["credits"] as? JsonObject
                    CodexRateLimit(
                        m.s("id") ?: return@catalog null, LabelText.label(m.s("name")), m.s("status").orEmpty(), LabelText.label(m.s("statusLabel")),
                        window(m["primary"]), window(m["secondary"]), credits?.b("hasCredits"), credits?.b("unlimited"),
                    )
                },
                reviewStatus = status(actions?.s("review")),
                compactionStatus = status(actions?.s("compaction")),
            )
        }

        private fun window(value: JsonElement?): CodexRateWindow? {
            val o = value as? JsonObject ?: return null
            val used = o.n("usedPercent")?.takeIf { it.isFinite() } ?: return null
            return CodexRateWindow(used, o.n("windowDurationMins")?.toLong(), o.n("resetsAt")?.takeIf { it.isFinite() }?.toLong())
        }
    }
}

/** engines/opencode-serve-control-service.d.mts: "ready" | "error" | "unavailable" | "unsupported". */
data class OpencodeCatalog<T>(val status: String, val items: List<T>, val error: String?) {
    val ready: Boolean get() = status == "ready"
}

/** OpencodeServeControlCatalogSnapshot: `models` are ModelOption rows, `modes` ModeOption rows. */
data class OpencodeSnapshot(
    val revision: String,
    val models: OpencodeCatalog<SessionModelOption>,
    val modes: OpencodeCatalog<ModeOption>,
) {
    companion object {
        fun parse(raw: JsonObject?): OpencodeSnapshot? {
            val o = raw ?: return null
            val revision = o.s("revision")?.takeIf { it.isNotEmpty() } ?: return null
            return OpencodeSnapshot(
                revision = revision,
                models = o.opencodeCatalog("models") { m ->
                    val value = m.s("value") ?: return@opencodeCatalog null
                    SessionModelOption(
                        value = value,
                        displayName = LabelText.label(m.s("displayName")).ifEmpty { LabelText.label(value) },
                        description = LabelText.hint(m.s("description")).ifEmpty { null },
                        providerLabel = LabelText.label(m.s("providerLabel")).ifEmpty { null },
                        variants = (m["variants"] as? JsonArray)?.mapNotNull { v ->
                            val vo = v as? JsonObject ?: return@mapNotNull null
                            val vv = vo.s("value") ?: return@mapNotNull null
                            ModelVariantOption(vv, LabelText.label(vo.s("label")).ifEmpty { LabelText.label(vv) })
                        }?.take(LabelText.MAX_ITEMS),
                    )
                },
                modes = o.opencodeCatalog("modes") { m ->
                    ModeOption(m.s("value") ?: return@opencodeCatalog null, LabelText.label(m.s("label")), LabelText.hint(m.s("hint")), m.b("danger"))
                },
            )
        }
    }
}

private fun status(value: String?): String = when (value) {
    "ready", "unavailable", "unsupported" -> value
    else -> "unavailable"
}

private fun <T> JsonObject.catalog(key: String, item: (JsonObject) -> T?): CodexCatalog<T> {
    val c = this[key] as? JsonObject ?: return CodexCatalog("unavailable", emptyList())
    val items = (c["items"] as? JsonArray).orEmpty().asSequence().mapNotNull { (it as? JsonObject)?.let(item) }.take(LabelText.MAX_ITEMS).toList()
    return CodexCatalog(status(c.s("status")), items)
}

private fun <T> JsonObject.opencodeCatalog(key: String, item: (JsonObject) -> T?): OpencodeCatalog<T> {
    val c = this[key] as? JsonObject ?: return OpencodeCatalog("unavailable", emptyList(), null)
    val items = (c["items"] as? JsonArray).orEmpty().asSequence().mapNotNull { (it as? JsonObject)?.let(item) }.take(LabelText.MAX_ITEMS).toList()
    val st = when (val s = c.s("status")) {
        "ready", "error", "unavailable", "unsupported" -> s
        else -> "unavailable"
    }
    return OpencodeCatalog(st, items, c.s("error")?.let(LabelText::error)?.ifEmpty { null })
}

private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.b(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.booleanOrNull

private fun JsonObject.n(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
