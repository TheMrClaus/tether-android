package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.ServerMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * ta-q6p: the custom-providers registry (providers.json) as Settings > Engines > Custom providers
 * reads and writes it (settings-dialog.tsx 887c222 :339-1000 `ProfilesEditor`, lib/protocol.ts
 * :1567-1599 `ProfileEntry`, lib/providers-registry.mjs `normalizeProfileEntry`).
 *
 * The server REPLACES the whole registry on `set-providers` (server.mjs :9241-9258) and
 * broadcasts the canonical list to every socket. So:
 * - every write is the newest list the client holds with ONE edit applied ([ProvidersPatch]),
 *   built at the moment of the write, never from a list the editor captured earlier;
 * - each profile is kept as the JSON object the server sent ([Profile.raw]) and an edit changes
 *   only its own key, so a key this app does not model goes back exactly as it came;
 * - a list the app cannot read faithfully (the frame was not the whole registry, an entry the
 *   server's validator would refuse, a part past the server's limits) is shown but never written
 *   back ([ProvidersList.writable]): a whole-list write from it would rewrite what it lost.
 *
 * Each profile's `env` VALUES are secrets the server sends in plaintext: they are [SecretText]
 * here, and no `toString` in this file prints one.
 */
object ProfileLimits {
    // lib/providers-registry.mjs 887c222 :28-45.
    const val PROFILES = 64
    const val ID = 64
    const val LABEL = 80
    const val COMMAND_ENTRY = 256
    const val COMMAND = 32
    const val HOME = 4096
    const val ENV_KEYS = 64
    const val ENV_VALUE = 4096
    const val DROP_ENV = 32
    const val DROP_ENV_PREFIX = 64
    const val VERSION = 64
    const val MODELS = 64
    const val MODEL_ID = 200
    const val MODEL_LABEL = 200
    const val TOOLS = 64
    const val TOOL_NAME = 200
    const val ORDER = 1_000_000L

    /** :49-57 `EXTENDS_PROVIDERS`, the closed engine set (the web's `EXTENDS_OPTIONS` order). */
    val EXTENDS = listOf("claude", "codex", "opencode", "reasonix", "pi", "dsh", "acp")

    /** :64 `ID_PATTERN`. */
    val ID_PATTERN = Regex("^[a-z][a-z0-9-]*$")

    /** :116 issue #107: the editor's auto-assigned placeholder model ids ("model", "model-2", …). */
    val MODEL_PLACEHOLDER = Regex("^model(?:-\\d+)?$")

    /** :103 a drop-env entry is a variable-name prefix. */
    val DROP_ENV_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
}

/** One `{ id, label?, isDefault? }` model row (lib/protocol.ts :1578 `ProfileModelEntry`). */
data class ProfileModel(val id: String, val label: String?, val isDefault: Boolean)

/** Which of a profile's two model lists (settings-dialog.tsx :936-959). */
enum class ModelList(val key: String) { Models("models"), Additional("additionalModels") }

/**
 * One profile as the server sent it ([raw], kept whole) and as the editor reads it. A part of
 * another type, or past the server's limit, reads as absent here and makes [valid] false.
 * [toString] prints the id and the env KEYS (never a value).
 */
class Profile internal constructor(internal val raw: JsonObject) {
    val id: String = string(raw["id"], ProfileLimits.ID).orEmpty()
    val extends: String = string(raw["extends"], Int.MAX_VALUE)?.takeIf { it in ProfileLimits.EXTENDS }.orEmpty()
    val label: String = string(raw["label"], ProfileLimits.LABEL).orEmpty()
    val command: List<String>? = (raw["command"] as? JsonArray)?.take(ProfileLimits.COMMAND)?.mapNotNull { string(it, ProfileLimits.COMMAND_ENTRY) }
    val homeDir: String? = string(raw["homeDir"], ProfileLimits.HOME)
    private val envObject: JsonObject? = raw["env"] as? JsonObject

    /** The env keys in the server's order (not secret), at most [ProfileLimits.ENV_KEYS]. */
    val envKeys: List<String> = envObject?.keys?.take(ProfileLimits.ENV_KEYS).orEmpty()

    /** [key]'s value, a secret; null when the key is absent or its value not a string. */
    fun envValue(key: String): SecretText? = (envObject?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.let { SecretText(it.content) }

    val dropEnv: List<String> = strings(raw["dropEnv"], ProfileLimits.DROP_ENV, ProfileLimits.DROP_ENV_PREFIX)
    val models: List<ProfileModel> = modelRows(raw[ModelList.Models.key])
    val additionalModels: List<ProfileModel> = modelRows(raw[ModelList.Additional.key])
    val disallowedTools: List<String> = strings(raw["disallowedTools"], ProfileLimits.TOOLS, ProfileLimits.TOOL_NAME)

    /** `profile.enabled` (a boolean on a valid entry). */
    val enabled: Boolean = (raw["enabled"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
    val order: Long? = (raw["order"] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.longOrNull
    val verifiedThrough: String? = string(raw["verifiedThrough"], ProfileLimits.VERSION)

    fun models(list: ModelList): List<ProfileModel> = if (list == ModelList.Models) models else additionalModels

    /** Whether the server's validator would accept this entry as it is (lib/providers-registry.mjs :171-237). */
    val valid: Boolean by lazy { ProfileShape.valid(raw) }

    override fun toString(): String = "Profile(id=$id, keys=${raw.keys.sorted()}, env=$envKeys)"

    private companion object {
        fun string(e: JsonElement?, max: Int): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= max }

        fun strings(e: JsonElement?, count: Int, max: Int): List<String> =
            (e as? JsonArray)?.take(count)?.mapNotNull { string(it, max) }.orEmpty()

        fun modelRows(e: JsonElement?): List<ProfileModel> = (e as? JsonArray)?.take(ProfileLimits.MODELS)?.mapNotNull { row ->
            val o = row as? JsonObject ?: return@mapNotNull null
            val id = string(o["id"], ProfileLimits.MODEL_ID) ?: return@mapNotNull null
            ProfileModel(id, string(o["label"], ProfileLimits.MODEL_LABEL), (o["isDefault"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true)
        }.orEmpty()
    }
}

/**
 * One `providers` frame, as the editor draws it. [generation] counts the frames the client has
 * had (a write built from an older one is refused). [writable]: the frame was the server's whole
 * registry, within its limits, every entry one its validator accepts and every id distinct.
 */
class ProvidersList private constructor(
    val profiles: List<Profile>,
    val writable: Boolean,
    val generation: Long,
) {
    fun profile(id: String): Profile? = profiles.firstOrNull { it.id == id }

    internal val raws: List<JsonObject> get() = profiles.map { it.raw }

    override fun toString(): String = "ProvidersList(ids=${profiles.map { it.id }}, writable=$writable, generation=$generation)"

    companion object {
        fun of(frame: ServerMessage.Providers, generation: Long): ProvidersList {
            val profiles = frame.profiles.take(ProfileLimits.PROFILES).map(::Profile)
            val writable = frame.intact && frame.profiles.size <= ProfileLimits.PROFILES &&
                profiles.all { it.valid } && profiles.map { it.id }.toSet().size == profiles.size
            return ProvidersList(profiles, writable, generation)
        }
    }
}

/** A Kotlin mirror of lib/providers-registry.mjs 887c222 `normalizeProfileEntry` (shape only). */
internal object ProfileShape {
    private fun bounded(e: JsonElement?, max: Int): Boolean =
        e is JsonPrimitive && e.isString && e.content.isNotEmpty() && e.content.length <= max

    private fun absent(e: JsonElement?) = e == null || e is JsonNull

    private fun bool(e: JsonElement?) = e is JsonPrimitive && !e.isString && e.booleanOrNull != null

    fun valid(o: JsonObject): Boolean {
        if (!bounded(o["id"], ProfileLimits.ID) || !ProfileLimits.ID_PATTERN.matches((o["id"] as JsonPrimitive).content)) return false
        if (!bounded(o["label"], ProfileLimits.LABEL)) return false
        val ext = o["extends"]
        if (!(ext is JsonPrimitive && ext.isString && ext.content in ProfileLimits.EXTENDS)) return false
        if (!bool(o["enabled"])) return false
        o["command"].let { c ->
            if (!absent(c)) {
                if (c !is JsonArray || c.isEmpty() || c.size > ProfileLimits.COMMAND) return false
                if (!c.all { bounded(it, ProfileLimits.COMMAND_ENTRY) }) return false
            }
        }
        if (!absent(o["homeDir"]) && !bounded(o["homeDir"], ProfileLimits.HOME)) return false
        o["env"].let { env ->
            if (!absent(env)) {
                if (env !is JsonObject || env.size > ProfileLimits.ENV_KEYS) return false
                for ((k, v) in env) {
                    if (k.isEmpty() || k.length > ProfileLimits.COMMAND_ENTRY) return false
                    if (!(v is JsonPrimitive && v.isString && v.content.length <= ProfileLimits.ENV_VALUE)) return false
                }
            }
        }
        o["dropEnv"].let { d ->
            if (!absent(d)) {
                if (d !is JsonArray || d.size > ProfileLimits.DROP_ENV) return false
                if (!d.all { bounded(it, ProfileLimits.DROP_ENV_PREFIX) && ProfileLimits.DROP_ENV_PATTERN.matches((it as JsonPrimitive).content) }) return false
            }
        }
        for (list in ModelList.entries) if (!modelsValid(o[list.key])) return false
        o["disallowedTools"].let { d ->
            if (!absent(d)) {
                if (d !is JsonArray || d.size > ProfileLimits.TOOLS) return false
                if (!d.all { bounded(it, ProfileLimits.TOOL_NAME) }) return false
            }
        }
        o["order"].let { n ->
            if (!absent(n)) {
                if (n !is JsonPrimitive || n.isString) return false
                val v = n.longOrNull ?: n.doubleOrNull?.takeIf { it == Math.floor(it) && it.isFinite() }?.toLong() ?: return false
                if (v < 0 || v > ProfileLimits.ORDER) return false
            }
        }
        o["verifiedThrough"].let { v ->
            if (!absent(v) && (!bounded(v, ProfileLimits.VERSION) || jsTrim((v as JsonPrimitive).content).isEmpty())) return false
        }
        return true
    }

    private fun modelsValid(e: JsonElement?): Boolean {
        if (absent(e)) return true
        if (e !is JsonArray || e.size > ProfileLimits.MODELS) return false
        var defaults = 0
        for (row in e) {
            if (row !is JsonObject) return false
            if (!bounded(row["id"], ProfileLimits.MODEL_ID)) return false
            val labelOk = bounded(row["label"], ProfileLimits.MODEL_LABEL)
            if (ProfileLimits.MODEL_PLACEHOLDER.matches((row["id"] as JsonPrimitive).content) && !labelOk) return false
            val d = row["isDefault"]
            if (d != null && !bool(d)) return false
            if ((d as? JsonPrimitive)?.booleanOrNull == true) defaults++
        }
        return defaults <= 1
    }
}

/**
 * One edit of the registry that is NOT what the server runs, applied to whatever list is newest
 * when it is sent (each is the web's handler, settings-dialog.tsx 887c222 :621-1000, re-read
 * against that list). An edit whose profile (or model row) is gone from the newest list, or
 * that would change nothing, writes nothing. A profile's command and home are not here: they
 * are [ProfileRunsEdit]s, sent only after their confirmation.
 */
sealed interface ProfileEdit {
    /** The switch (:647): `{ enabled: !profile.enabled }`. */
    data class Enabled(val id: String) : ProfileEdit

    /** Label (:660): the trimmed value, when non-empty and new. */
    data class Label(val id: String, val typed: String) : ProfileEdit

    /** ID (:670): the profile renamed in place (the server propagates the rename to its sessions). */
    data class Rename(val id: String, val typed: String) : ProfileEdit

    /** Extends (:681). */
    data class Extends(val id: String, val value: String) : ProfileEdit

    /** EnvEditor's key blur (:385-393): the trimmed new name, with the key's value. */
    data class EnvKey(val id: String, val key: String, val typed: String) : ProfileEdit

    /** EnvEditor's value (:400-404), not trimmed. [value] is a secret. */
    data class EnvValue(val id: String, val key: String, val value: SecretText) : ProfileEdit

    /** EnvEditor's remove (:410). */
    data class EnvRemove(val id: String, val key: String) : ProfileEdit

    /** EnvEditor's Add (:368-374): the trimmed name with [value] (a secret, not trimmed). */
    data class EnvAdd(val id: String, val typedKey: String, val value: SecretText) : ProfileEdit

    /** Drop env prefixes (:766-770): split at commas. */
    data class DropEnv(val id: String, val typed: String) : ProfileEdit

    /** Disallowed tools (:790-794): split at commas. */
    data class DisallowedTools(val id: String, val typed: String) : ProfileEdit

    /** Order (:808-813): a whole number, or empty to clear it. */
    data class Order(val id: String, val typed: String) : ProfileEdit

    /** Verified through (:826, acp): the trimmed value, or empty to clear it. */
    data class VerifiedThrough(val id: String, val typed: String) : ProfileEdit

    /** A model list's edit (ModelListEditor :467-616). [index] and [rowId] name the row together. */
    data class Model(val id: String, val list: ModelList, val op: ModelOp) : ProfileEdit

    /** "Add profile" (:625-633): `{ id, extends: "claude", label: id, enabled: true }`, id unused. */
    data object Add : ProfileEdit

    /** Remove (:636). */
    data class Remove(val id: String) : ProfileEdit
}

/** One ModelListEditor change. A row is named by its index AND its id, so a list changed meanwhile never takes another row's edit. */
sealed interface ModelOp {
    /** The id field's blur (:519-522): the trimmed value, when non-empty and new. */
    data class SetId(val index: Int, val rowId: String, val typed: String) : ModelOp

    /** The label field's blur (:530-533): the trimmed value, or none when emptied. */
    data class SetLabel(val index: Int, val rowId: String, val typed: String) : ModelOp

    /** The Default radio (:541-545): this row the default, every other row's flag dropped. */
    data class SetDefault(val index: Int, val rowId: String) : ModelOp

    data class Remove(val index: Int, val rowId: String) : ModelOp

    /**
     * The draft row's commit (:491-497, issue #107): only when the typed id is a REAL one (non-empty,
     * not the placeholder the draft started at). r1 (the server's rule, :138): a placeholder-shaped
     * id without a label is never committed either, as the server would refuse the whole list.
     */
    data class Add(val initialId: String, val typedId: String, val typedLabel: String) : ModelOp
}

/** What sets what the server RUNS for a profile: written only after a confirmation that shows the new value. */
sealed interface ProfileRunsEdit {
    val id: String

    /** Command (:705-711): [parts] are the binary and its arguments; empty clears it (`undefined`). */
    data class Command(override val id: String, val parts: List<String>) : ProfileRunsEdit

    /** Home (:735): the trimmed value; empty clears it (`value || undefined`). */
    data class Home(override val id: String, val value: String) : ProfileRunsEdit
}

/**
 * A `set-providers` write: the WHOLE list as it will be sent, the [generation] of the list it was
 * built from, and (only when it came from a confirmation) the one command or home it changes.
 * Made only by [ProvidersPatch] (the constructor is internal to this module), and the client
 * sends nothing else as `set-providers` ([TetherClient.setProviders]). [toString] prints ids only.
 */
class ProvidersWrite internal constructor(
    val profiles: List<JsonObject>,
    val generation: Long,
    internal val confirmed: ProfileRunsEdit?,
    /** new id -> old id, for a rename (the renamed profile keeps what it runs). */
    internal val renames: Map<String, String> = emptyMap(),
) {
    val message: ClientMessage.SetProviders get() = ClientMessage.SetProviders(profiles)

    /** Whether this write came from a confirmation of what the server runs. */
    val isConfirmed: Boolean get() = confirmed != null

    override fun toString(): String =
        "ProvidersWrite(ids=${profiles.map { (it["id"] as? JsonPrimitive)?.content }}, generation=$generation, confirmed=${confirmed?.let { "${it::class.simpleName}:${it.id}" }})"
}

/**
 * The builders of [ProvidersWrite] and the one rule every send path applies ([refusal]).
 * Each builder takes the newest list ([ProvidersList]) and returns null when nothing should be
 * sent: the list is not writable, the edit's profile is gone, or the edit changes nothing.
 */
object ProvidersPatch {
    /** The two keys that set what a profile RUNS (owner decision 2026-10-01; a home holds the engine's config). */
    val RUNS_KEYS = listOf("command", "homeDir")

    /** settings-dialog.tsx :707 `raw.split(/\s+/)` after `.trim()`: JavaScript's whitespace, as [jsTrim]. */
    fun commandParts(typed: String): List<String> {
        val trimmed = jsTrim(typed)
        if (trimmed.isEmpty()) return emptyList()
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (c in trimmed) {
            if (jsSpace(c)) {
                if (current.isNotEmpty()) {
                    parts += current.toString()
                    current.clear()
                }
            } else {
                current.append(c)
            }
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    /** Whether [parts] fit the server's command limits (:181-186): at most 32 parts of at most 256 units. */
    fun commandFits(parts: List<String>): Boolean = parts.size <= ProfileLimits.COMMAND && parts.all { it.length <= ProfileLimits.COMMAND_ENTRY }

    /** A plain edit applied to [list]; null when nothing is to be sent. */
    fun write(list: ProvidersList?, edit: ProfileEdit): ProvidersWrite? {
        if (list == null || !list.writable) return null
        val base = list.raws
        var renames = emptyMap<String, String>()
        val next: List<JsonObject> = when (edit) {
            is ProfileEdit.Add -> {
                var id = "profile"
                var n = 1
                while (base.any { idOf(it) == id }) {
                    n += 1
                    id = "profile-$n"
                }
                base + JsonObject(linkedMapOf("id" to JsonPrimitive(id), "extends" to JsonPrimitive("claude"), "label" to JsonPrimitive(id), "enabled" to JsonPrimitive(true)))
            }
            is ProfileEdit.Remove -> if (base.none { idOf(it) == edit.id }) return null else base.filter { idOf(it) != edit.id }
            is ProfileEdit.Rename -> {
                val value = jsTrim(edit.typed)
                if (value.isEmpty() || value == edit.id || base.none { idOf(it) == edit.id }) return null
                renames = mapOf(value to edit.id)
                base.map { if (idOf(it) == edit.id) with(it, "id", JsonPrimitive(value)) else it }
            }
            else -> {
                val target = base.firstOrNull { idOf(it) == profileId(edit) } ?: return null
                val profile = list.profile(profileId(edit)) ?: return null
                val updated = applyTo(profile, target, edit) ?: return null
                if (updated == target) return null
                base.map { if (it === target) updated else it }
            }
        }
        if (next == base) return null
        return ProvidersWrite(next, list.generation, confirmed = null, renames = renames)
    }

    /**
     * A command or home the user CONFIRMED, applied to the newest [list] (built when they confirm).
     * Null when the list is not writable, the profile is gone, the value is the server's already,
     * or it passes the server's limits. The ONLY producer of a write that changes what runs.
     */
    fun confirmed(list: ProvidersList?, edit: ProfileRunsEdit): ProvidersWrite? {
        if (list == null || !list.writable) return null
        val base = list.raws
        val target = base.firstOrNull { idOf(it) == edit.id } ?: return null
        val updated = when (edit) {
            is ProfileRunsEdit.Command -> {
                if (!commandFits(edit.parts)) return null
                // :709 compares the joined text: the same words with other spacing are no change.
                val now = (list.profile(edit.id)?.command ?: emptyList()).joinToString(" ")
                if (edit.parts.joinToString(" ") == now) return null
                if (edit.parts.isEmpty()) without(target, "command") else with(target, "command", JsonArray(edit.parts.map(::JsonPrimitive)))
            }
            is ProfileRunsEdit.Home -> {
                if (edit.value.length > ProfileLimits.HOME) return null
                if (edit.value == (list.profile(edit.id)?.homeDir ?: "")) return null
                if (edit.value.isEmpty()) without(target, "homeDir") else with(target, "homeDir", JsonPrimitive(edit.value))
            }
        }
        if (updated == target) return null
        return ProvidersWrite(base.map { if (it === target) updated else it }, list.generation, confirmed = edit)
    }

    /**
     * Why [write] must NOT be sent against [newest] (the client's newest list), or null when it may.
     * Every send path applies it, the client's under the same lock as its frames:
     * - no newest list, or one that cannot be written back;
     * - [write] was built from an older list than [newest] (a broadcast landed since: sending it
     *   would undo that concurrent edit);
     * - any profile's command or home differs from the newest list's (a renamed profile is
     *   compared with its old self), unless it is exactly the value its confirmation names.
     */
    fun refusal(write: ProvidersWrite, newest: ProvidersList?): String? {
        if (newest == null || !newest.writable) return "no writable list"
        if (write.generation != newest.generation) return "built from an older list"
        val confirmed = write.confirmed
        for (p in write.profiles) {
            val id = idOf(p)
            val before = newest.raws.firstOrNull { idOf(it) == (write.renames[id] ?: id) }
            for (key in RUNS_KEYS) {
                val sent = present(p[key])
                val expected = when {
                    confirmed is ProfileRunsEdit.Command && key == "command" && confirmed.id == id ->
                        confirmed.parts.takeIf { it.isNotEmpty() }?.let { parts -> JsonArray(parts.map(::JsonPrimitive)) }
                    confirmed is ProfileRunsEdit.Home && key == "homeDir" && confirmed.id == id ->
                        confirmed.value.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive)
                    else -> present(before?.get(key))
                }
                if (sent != expected) return "an unconfirmed change to what profile $id runs"
            }
        }
        return null
    }

    // ---- the web's handlers --------------------------------------------------------------------

    private fun profileId(edit: ProfileEdit): String = when (edit) {
        is ProfileEdit.Enabled -> edit.id
        is ProfileEdit.Label -> edit.id
        is ProfileEdit.Rename -> edit.id
        is ProfileEdit.Extends -> edit.id
        is ProfileEdit.EnvKey -> edit.id
        is ProfileEdit.EnvValue -> edit.id
        is ProfileEdit.EnvRemove -> edit.id
        is ProfileEdit.EnvAdd -> edit.id
        is ProfileEdit.DropEnv -> edit.id
        is ProfileEdit.DisallowedTools -> edit.id
        is ProfileEdit.Order -> edit.id
        is ProfileEdit.VerifiedThrough -> edit.id
        is ProfileEdit.Model -> edit.id
        is ProfileEdit.Remove -> edit.id
        ProfileEdit.Add -> ""
    }

    private fun applyTo(profile: Profile, raw: JsonObject, edit: ProfileEdit): JsonObject? = when (edit) {
        is ProfileEdit.Enabled -> with(raw, "enabled", JsonPrimitive(!profile.enabled))
        is ProfileEdit.Label -> jsTrim(edit.typed).takeIf { it.isNotEmpty() && it != profile.label }?.let { with(raw, "label", JsonPrimitive(it)) }
        is ProfileEdit.Extends -> edit.value.takeIf { it in ProfileLimits.EXTENDS && it != profile.extends }?.let { with(raw, "extends", JsonPrimitive(it)) }
        is ProfileEdit.EnvKey -> {
            val env = raw["env"] as? JsonObject
            val nextKey = jsTrim(edit.typed)
            val value = env?.get(edit.key)
            if (env == null || value == null || nextKey.isEmpty() || nextKey == edit.key) {
                null
            } else {
                val next = LinkedHashMap(env)
                next.remove(edit.key)
                next[nextKey] = value
                withEnv(raw, next)
            }
        }
        is ProfileEdit.EnvValue -> {
            val env = raw["env"] as? JsonObject
            val now = env?.get(edit.key)
            if (env == null || now == null || (now as? JsonPrimitive)?.content == edit.value.reveal()) {
                null
            } else {
                val next = LinkedHashMap(env)
                next[edit.key] = JsonPrimitive(edit.value.reveal())
                withEnv(raw, next)
            }
        }
        is ProfileEdit.EnvRemove -> {
            val env = raw["env"] as? JsonObject
            if (env == null || edit.key !in env) {
                null
            } else {
                val next = LinkedHashMap(env)
                next.remove(edit.key)
                withEnv(raw, next)
            }
        }
        is ProfileEdit.EnvAdd -> {
            val key = jsTrim(edit.typedKey)
            if (key.isEmpty()) {
                null
            } else {
                val next = LinkedHashMap((raw["env"] as? JsonObject).orEmpty())
                next[key] = JsonPrimitive(edit.value.reveal())
                withEnv(raw, next)
            }
        }
        is ProfileEdit.DropEnv -> splitList(raw, "dropEnv", edit.typed, profile.dropEnv)
        is ProfileEdit.DisallowedTools -> splitList(raw, "disallowedTools", edit.typed, profile.disallowedTools)
        is ProfileEdit.Order -> {
            val trimmed = jsTrim(edit.typed)
            when {
                trimmed.isEmpty() -> if (profile.order != null) without(raw, "order") else null
                trimmed.all { it in '0'..'9' } && trimmed.length <= 7 -> {
                    val n = trimmed.toLong()
                    if (n > ProfileLimits.ORDER || n == profile.order) null else with(raw, "order", JsonPrimitive(n))
                }
                // Not a whole number: nothing (the browser's number input never yields one).
                else -> null
            }
        }
        is ProfileEdit.VerifiedThrough -> {
            val value = jsTrim(edit.typed)
            when {
                value == (profile.verifiedThrough ?: "") -> null
                value.isEmpty() -> without(raw, "verifiedThrough")
                value.length > ProfileLimits.VERSION -> null
                else -> with(raw, "verifiedThrough", JsonPrimitive(value))
            }
        }
        is ProfileEdit.Model -> modelEdit(raw, edit.list, edit.op)
        is ProfileEdit.Add, is ProfileEdit.Remove, is ProfileEdit.Rename -> null
    }

    /** :766-770: the trimmed text split at each comma (and the spaces around it), empty parts dropped; written when the comma join differs. */
    private fun splitList(raw: JsonObject, key: String, typed: String, now: List<String>): JsonObject? {
        val trimmed = jsTrim(typed)
        val parts = if (trimmed.isEmpty()) emptyList() else trimmed.split(',').map(::jsTrim).filter { it.isNotEmpty() }
        if (parts.joinToString(",") == now.joinToString(",")) return null
        return if (parts.isEmpty()) without(raw, key) else with(raw, key, JsonArray(parts.map(::JsonPrimitive)))
    }

    private fun modelEdit(raw: JsonObject, list: ModelList, op: ModelOp): JsonObject? {
        val rows: List<JsonObject> = (raw[list.key] as? JsonArray)?.map { it as? JsonObject ?: return null }.orEmpty()
        fun row(index: Int, rowId: String): JsonObject? = rows.getOrNull(index)?.takeIf { (it["id"] as? JsonPrimitive)?.content == rowId }
        fun label(o: JsonObject) = (o["label"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val next: List<JsonObject> = when (op) {
            is ModelOp.SetId -> {
                val r = row(op.index, op.rowId) ?: return null
                val value = jsTrim(op.typed)
                if (value.isEmpty() || value == op.rowId || value.length > ProfileLimits.MODEL_ID) return null
                if (ProfileLimits.MODEL_PLACEHOLDER.matches(value) && label(r).isEmpty()) return null
                rows.mapIndexed { i, o -> if (i == op.index) with(o, "id", JsonPrimitive(value)) else o }
            }
            is ModelOp.SetLabel -> {
                val r = row(op.index, op.rowId) ?: return null
                val value = jsTrim(op.typed)
                if (value == label(r) || value.length > ProfileLimits.MODEL_LABEL) return null
                if (value.isEmpty() && ProfileLimits.MODEL_PLACEHOLDER.matches(op.rowId)) return null
                rows.mapIndexed { i, o -> if (i == op.index) (if (value.isEmpty()) without(o, "label") else with(o, "label", JsonPrimitive(value))) else o }
            }
            is ModelOp.SetDefault -> {
                row(op.index, op.rowId) ?: return null
                rows.mapIndexed { i, o -> if (i == op.index) with(o, "isDefault", JsonPrimitive(true)) else without(o, "isDefault") }
            }
            is ModelOp.Remove -> {
                row(op.index, op.rowId) ?: return null
                rows.filterIndexed { i, _ -> i != op.index }
            }
            is ModelOp.Add -> {
                val id = jsTrim(op.typedId)
                val label = jsTrim(op.typedLabel)
                if (id.isEmpty() || id == op.initialId || id.length > ProfileLimits.MODEL_ID || label.length > ProfileLimits.MODEL_LABEL) return null
                if (ProfileLimits.MODEL_PLACEHOLDER.matches(id) && label.isEmpty()) return null
                if (rows.size >= ProfileLimits.MODELS) return null
                rows + JsonObject(if (label.isEmpty()) linkedMapOf("id" to JsonPrimitive(id)) else linkedMapOf("id" to JsonPrimitive(id), "label" to JsonPrimitive(label)))
            }
        }
        return if (next.isEmpty()) without(raw, list.key) else with(raw, list.key, JsonArray(next))
    }

    /** The draft row's placeholder id (:477-485): "model", then "model-2", … avoiding the committed ids. */
    fun draftModelId(rows: List<ProfileModel>): String {
        var id = "model"
        var n = 1
        while (rows.any { it.id == id }) {
            n += 1
            id = "model-$n"
        }
        return id
    }

    private fun withEnv(raw: JsonObject, env: Map<String, JsonElement>): JsonObject =
        if (env.isEmpty()) without(raw, "env") else with(raw, "env", JsonObject(env))

    /** `{ ...o, [key]: value }`: an existing key keeps its place, a new one goes last. */
    private fun with(o: JsonObject, key: String, value: JsonElement): JsonObject = JsonObject(LinkedHashMap(o).apply { put(key, value) })

    /** `{ ...o, [key]: undefined }` as JSON.stringify writes it: the key gone. */
    private fun without(o: JsonObject, key: String): JsonObject = if (key !in o) o else JsonObject(LinkedHashMap(o).apply { remove(key) })

    private fun idOf(o: JsonObject): String? = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A key's value with JSON null read as absent (JSON.stringify drops `undefined`; the validator reads null as absent). */
    private fun present(e: JsonElement?): JsonElement? = if (e == null || e is JsonNull) null else e

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
}
