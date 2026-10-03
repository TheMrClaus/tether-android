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
 *
 * ta-coik.5 (owner rule 2026-10-03): every edit is sent as the web's handler sends it, with no
 * confirmation: the command, the home, the engine and any env key included, and an env add or
 * rename onto a name the profile already has overwrites it, as the web's object spread does.
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
 * had (a write built from an older one is refused); [epoch] is the socket that delivered it
 * (r2, security F1: a list from before a reconnect is never written back, since the server may
 * have changed while the link was down). [writable]: the frame was the server's whole registry,
 * within its limits, every entry one its validator accepts and every id distinct.
 */
class ProvidersList private constructor(
    val profiles: List<Profile>,
    val writable: Boolean,
    val generation: Long,
    val epoch: Long,
) {
    fun profile(id: String): Profile? = profiles.firstOrNull { it.id == id }

    internal val raws: List<JsonObject> get() = profiles.map { it.raw }

    override fun toString(): String = "ProvidersList(ids=${profiles.map { it.id }}, writable=$writable, generation=$generation, epoch=$epoch)"

    companion object {
        fun of(frame: ServerMessage.Providers, generation: Long, epoch: Long = 0L): ProvidersList {
            val profiles = frame.profiles.take(ProfileLimits.PROFILES).map(::Profile)
            val writable = frame.intact && frame.profiles.size <= ProfileLimits.PROFILES &&
                profiles.all { it.valid } && profiles.map { it.id }.toSet().size == profiles.size
            return ProvidersList(profiles, writable, generation, epoch)
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
 * One edit of the registry, applied to whatever list is newest when it is sent (each is the web's
 * handler, settings-dialog.tsx 90fbb9f :621-864, re-read against that list).
 */
sealed interface ProfileEdit {
    /** The switch (:647). r2 (security F8): the value the user asked for, not a toggle of whatever is newest. */
    data class Enabled(val id: String, val enabled: Boolean) : ProfileEdit

    /** Label (:660): the trimmed value, when non-empty and new. */
    data class Label(val id: String, val typed: String) : ProfileEdit

    /** ID (:670): the profile renamed in place (the server propagates the rename to its sessions). */
    data class Rename(val id: String, val typed: String) : ProfileEdit

    /** Extends (:694-699): the engine the profile runs on. */
    data class Extends(val id: String, val value: String) : ProfileEdit

    /** Command (:713-717): split at whitespace after `.trim()`; empty clears it (`undefined`). */
    data class Command(val id: String, val typed: String) : ProfileEdit

    /** Home (:737): the trimmed value; empty clears it (`value || undefined`). */
    data class Home(val id: String, val typed: String) : ProfileEdit

    /** EnvEditor's key blur (:384-391): the trimmed new name, with the key's value (onto an existing name: that one is overwritten, as on the web). */
    data class EnvKey(val id: String, val key: String, val typed: String) : ProfileEdit

    /** EnvEditor's value (:400-404), not trimmed. [value] is a secret. */
    data class EnvValue(val id: String, val key: String, val value: SecretText) : ProfileEdit

    /** EnvEditor's remove (:410). */
    data class EnvRemove(val id: String, val key: String) : ProfileEdit

    /** EnvEditor's Add (:367-373): the trimmed name with [value] (a secret, not trimmed); an existing name is overwritten, as on the web. */
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

/** One env change of the editor, by its action. Values are secrets ([SecretText]). */
sealed interface EnvChange {
    data class Add(val key: String, val value: SecretText) : EnvChange
    data class Change(val key: String, val value: SecretText) : EnvChange
    data class Rename(val from: String, val to: String) : EnvChange
    data class Remove(val key: String) : EnvChange

    /** The keys it touches (both names for a rename). */
    val keys: List<String>
        get() = when (this) {
            is Add -> listOf(key)
            is Change -> listOf(key)
            is Rename -> listOf(from, to)
            is Remove -> listOf(key)
        }

}

/** Why a write was not built or not sent. */
enum class ProvidersRefusal {
    /** No list, or one the app cannot write back. */
    NotWritable,

    /** The edit's profile (or row) is not in the newest list any more. */
    Gone,

    /** Built from an older list (a broadcast landed since) or from before a reconnect. */
    Stale,

    /** A write is still waiting for its broadcast: one built now would undo it. */
    InFlight,

    /** The value passes the server's limits or rules. */
    Invalid,

    /** No live socket for the list's server. */
    NotConnected,
}

/** A builder's answer: a write to send, nothing to send, or why not. */
sealed interface ProvidersBuild {
    data class Ready(val write: ProvidersWrite) : ProvidersBuild
    data object NoChange : ProvidersBuild
    data class Refused(val reason: ProvidersRefusal) : ProvidersBuild

    val writeOrNull: ProvidersWrite? get() = (this as? Ready)?.write
}

/**
 * A `set-providers` write: the WHOLE list as it will be sent, and the [generation] and [epoch] of
 * the list it was built from. Made only by [ProvidersPatch] (the constructor is internal to this
 * module), and the client sends nothing else as `set-providers` ([TetherClient.setProviders]).
 * [toString] prints ids only.
 */
class ProvidersWrite internal constructor(
    val profiles: List<JsonObject>,
    val generation: Long,
    val epoch: Long = 0L,
) {
    val message: ClientMessage.SetProviders get() = ClientMessage.SetProviders(profiles)

    override fun toString(): String =
        "ProvidersWrite(ids=${profiles.map { (it["id"] as? JsonPrimitive)?.content }}, generation=$generation, epoch=$epoch)"
}

/**
 * r2 (verifier F1), r3: a write waits for its broadcast. Until a list that CONTAINS it arrives
 * (every profile the write changed, added or removed reads as it was sent), the newest list does
 * not show it, so another write built now would undo it (the server replaces the whole list). A
 * list from another client landing first does not lift the guard (r3). After [timeoutMs] the
 * write is overdue ([overdue]): the client asks for the list again, and the first list folded
 * after that request (the server answers in order, so it reflects the write, whether it was taken
 * or refused) lifts it. A new socket lifts it too (the list is then this socket's to fetch).
 */
class ProvidersInFlight(private val now: () -> Long = { System.nanoTime() / 1_000_000 }, val timeoutMs: Long = TIMEOUT_MS) {
    private class Pending(val epoch: Long, val expected: Map<String, JsonObject?>, val at: Long) {
        /** The generation of the newest list when the overdue write's list was asked for again. */
        var askedAt: Long? = null
    }

    private var pending: Pending? = null

    /** r4: how the last write ended, once its guard lifted (null: none yet, or one is waiting). */
    private var outcome: ProvidersWriteStatus.Outcome? = null

    /** Records [write], sent, as built from [base]: the profiles it changes, as they should read once it lands. */
    @Synchronized
    fun sent(write: ProvidersWrite, base: ProvidersList?) {
        val before = base?.raws.orEmpty()
        fun idOf(o: JsonObject) = (o["id"] as? JsonPrimitive)?.content
        val expected = LinkedHashMap<String, JsonObject?>()
        val sentIds = write.profiles.mapNotNull(::idOf).toSet()
        for (p in write.profiles) {
            val id = idOf(p) ?: continue
            if (before.firstOrNull { idOf(it) == id } != p) expected[id] = p
        }
        for (o in before) idOf(o)?.let { if (it !in sentIds) expected[it] = null }
        pending = Pending(write.epoch, expected, now())
        outcome = null
    }

    /** Whether a write is still waiting against [newest] (lifting the guard when it no longer is). */
    @Synchronized
    fun waiting(newest: ProvidersList?): Boolean {
        val p = pending ?: return false
        if (newest == null) return true
        val held = p.expected.all { (id, exp) -> newest.raws.firstOrNull { (it["id"] as? JsonPrimitive)?.content == id } == exp }
        outcome = when {
            newest.epoch != p.epoch -> ProvidersWriteStatus.Outcome.Unknown
            held -> ProvidersWriteStatus.Outcome.Saved
            // r4: the reply to the overdue re-request does not hold it: the server did not take it.
            p.askedAt?.let { newest.generation > it } == true -> ProvidersWriteStatus.Outcome.NotSaved
            else -> return true
        }
        pending = null
        return false
    }

    /** r4: the write's state against [newest] (lifting the guard if it can): waiting (and whether overdue), or how it ended. */
    @Synchronized
    fun status(newest: ProvidersList?): ProvidersWriteStatus {
        if (waiting(newest)) {
            val p = pending!!
            return ProvidersWriteStatus.Waiting(overdue = p.askedAt != null || now() - p.at >= timeoutMs)
        }
        return outcome?.let { ProvidersWriteStatus.Done(it) } ?: ProvidersWriteStatus.Idle
    }

    @Synchronized
    fun refusal(newest: ProvidersList?): ProvidersRefusal? = if (newest != null && waiting(newest)) ProvidersRefusal.InFlight else null

    /**
     * True once, when the waiting write passes [timeoutMs]: the caller asks the server for the list
     * (the client does), and the next list folded after [newest] lifts the guard.
     */
    @Synchronized
    fun overdue(newest: ProvidersList?): Boolean {
        if (!waiting(newest)) return false
        val p = pending ?: return false
        if (p.askedAt != null || now() - p.at < timeoutMs || newest == null) return false
        p.askedAt = newest.generation
        return true
    }

    companion object {
        const val TIMEOUT_MS = 10_000L
    }
}

/** r4: what became of the last `set-providers` write the client sent (one source of truth: the client's guard). */
sealed interface ProvidersWriteStatus {
    data object Idle : ProvidersWriteStatus

    /** Sent, and no list holds it yet. [overdue]: past the timeout (the client asked for the list again). */
    data class Waiting(val overdue: Boolean) : ProvidersWriteStatus

    data class Done(val outcome: Outcome) : ProvidersWriteStatus

    enum class Outcome {
        /** A list holds it. */
        Saved,

        /** The reply to the re-request does not hold it: the server refused it. */
        NotSaved,

        /** The socket changed before it was seen. */
        Unknown,
    }
}

/**
 * The builders of [ProvidersWrite] and the one rule every send path applies ([refusal]). Each
 * builder takes the newest list and answers a write, nothing to send, or why not.
 */
object ProvidersPatch {
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

    /** A plain edit applied to [list]; null when nothing is to be sent (see [build] for why). */
    fun write(list: ProvidersList?, edit: ProfileEdit): ProvidersWrite? = build(list, edit).writeOrNull

    /** A plain edit applied to [list]: the write, nothing to send, or why not. */
    fun build(list: ProvidersList?, edit: ProfileEdit): ProvidersBuild {
        if (list == null || !list.writable) return ProvidersBuild.Refused(ProvidersRefusal.NotWritable)
        val base = list.raws
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
            is ProfileEdit.Remove -> if (base.none { idOf(it) == edit.id }) return gone() else base.filter { idOf(it) != edit.id }
            is ProfileEdit.Rename -> {
                val value = jsTrim(edit.typed)
                if (value.isEmpty() || value == edit.id) return ProvidersBuild.NoChange
                if (base.none { idOf(it) == edit.id }) return gone()
                base.map { if (idOf(it) == edit.id) with(it, "id", JsonPrimitive(value)) else it }
            }
            else -> {
                val target = base.firstOrNull { idOf(it) == profileId(edit) } ?: return gone()
                val profile = list.profile(profileId(edit)) ?: return gone()
                val updated = when (val r = applyTo(profile, target, edit)) {
                    is Applied.To -> r.raw
                    Applied.Nothing -> return ProvidersBuild.NoChange
                    is Applied.No -> return ProvidersBuild.Refused(r.reason)
                }
                if (updated == target) return ProvidersBuild.NoChange
                base.map { if (it === target) updated else it }
            }
        }
        if (next == base) return ProvidersBuild.NoChange
        return ProvidersBuild.Ready(ProvidersWrite(next, list.generation, epoch = list.epoch))
    }

    /** The name an env change adds or renames to (null for a value change or a remove). */
    fun newName(change: EnvChange): String? = when (change) {
        is EnvChange.Add -> change.key
        is EnvChange.Rename -> change.to
        else -> null
    }

    /** The env change a plain env edit makes (null for any other edit). */
    fun envChangeOf(edit: ProfileEdit): EnvChange? = when (edit) {
        is ProfileEdit.EnvKey -> EnvChange.Rename(edit.key, jsTrim(edit.typed))
        is ProfileEdit.EnvValue -> EnvChange.Change(edit.key, edit.value)
        is ProfileEdit.EnvRemove -> EnvChange.Remove(edit.key)
        is ProfileEdit.EnvAdd -> EnvChange.Add(jsTrim(edit.typedKey), edit.value)
        else -> null
    }

    /**
     * Why [write] must NOT be sent against [newest] (the client's newest list), or null when it may.
     * Every send path applies it, the client's under the same lock as its frames:
     * - no newest list, or one that cannot be written back;
     * - [write] was built from another list than [newest] (a broadcast landed since, or the socket
     *   changed: sending it would undo what the server holds now).
     */
    fun refusal(write: ProvidersWrite, newest: ProvidersList?): ProvidersRefusal? {
        if (newest == null || !newest.writable) return ProvidersRefusal.NotWritable
        if (write.generation != newest.generation || write.epoch != newest.epoch) return ProvidersRefusal.Stale
        return null
    }

    // ---- the web's handlers --------------------------------------------------------------------

    private fun gone() = ProvidersBuild.Refused(ProvidersRefusal.Gone)

    private fun invalid() = ProvidersBuild.Refused(ProvidersRefusal.Invalid)

    private sealed interface Applied {
        data class To(val raw: JsonObject) : Applied
        data object Nothing : Applied
        data class No(val reason: ProvidersRefusal) : Applied
    }

    private fun applied(raw: JsonObject?): Applied = if (raw == null) Applied.Nothing else Applied.To(raw)

    private fun profileId(edit: ProfileEdit): String = when (edit) {
        is ProfileEdit.Enabled -> edit.id
        is ProfileEdit.Label -> edit.id
        is ProfileEdit.Rename -> edit.id
        is ProfileEdit.Extends -> edit.id
        is ProfileEdit.Command -> edit.id
        is ProfileEdit.Home -> edit.id
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

    private fun applyTo(profile: Profile, raw: JsonObject, edit: ProfileEdit): Applied = when (edit) {
        is ProfileEdit.Enabled -> applied(if (edit.enabled == profile.enabled) null else with(raw, "enabled", JsonPrimitive(edit.enabled)))
        is ProfileEdit.Label -> applied(jsTrim(edit.typed).takeIf { it.isNotEmpty() && it != profile.label }?.let { with(raw, "label", JsonPrimitive(it)) })
        is ProfileEdit.Extends -> when {
            edit.value !in ProfileLimits.EXTENDS -> Applied.No(ProvidersRefusal.Invalid)
            edit.value == profile.extends -> Applied.Nothing
            else -> Applied.To(with(raw, "extends", JsonPrimitive(edit.value)))
        }
        is ProfileEdit.Command -> {
            val parts = commandParts(edit.typed)
            when {
                // :716 compares the joined text: the same words with other spacing are no change.
                parts.joinToString(" ") == (profile.command ?: emptyList()).joinToString(" ") -> Applied.Nothing
                !commandFits(parts) -> Applied.No(ProvidersRefusal.Invalid)
                parts.isEmpty() -> Applied.To(without(raw, "command"))
                else -> Applied.To(with(raw, "command", JsonArray(parts.map(::JsonPrimitive))))
            }
        }
        is ProfileEdit.Home -> {
            val value = jsTrim(edit.typed)
            when {
                value == (profile.homeDir ?: "") -> Applied.Nothing
                value.length > ProfileLimits.HOME -> Applied.No(ProvidersRefusal.Invalid)
                value.isEmpty() -> Applied.To(without(raw, "homeDir"))
                else -> Applied.To(with(raw, "homeDir", JsonPrimitive(value)))
            }
        }
        is ProfileEdit.EnvKey, is ProfileEdit.EnvValue, is ProfileEdit.EnvRemove, is ProfileEdit.EnvAdd -> applyEnv(raw, envChangeOf(edit)!!)
        is ProfileEdit.DropEnv -> applied(splitList(raw, "dropEnv", edit.typed, profile.dropEnv))
        is ProfileEdit.DisallowedTools -> applied(splitList(raw, "disallowedTools", edit.typed, profile.disallowedTools))
        is ProfileEdit.Order -> {
            val trimmed = jsTrim(edit.typed)
            applied(
                when {
                    trimmed.isEmpty() -> if (profile.order != null) without(raw, "order") else null
                    trimmed.all { it in '0'..'9' } && trimmed.length <= 7 -> {
                        val n = trimmed.toLong()
                        if (n > ProfileLimits.ORDER || n == profile.order) null else with(raw, "order", JsonPrimitive(n))
                    }
                    // Not a whole number: nothing (the browser's number input never yields one).
                    else -> null
                },
            )
        }
        is ProfileEdit.VerifiedThrough -> {
            val value = jsTrim(edit.typed)
            applied(
                when {
                    value == (profile.verifiedThrough ?: "") -> null
                    value.isEmpty() -> without(raw, "verifiedThrough")
                    value.length > ProfileLimits.VERSION -> null
                    else -> with(raw, "verifiedThrough", JsonPrimitive(value))
                },
            )
        }
        is ProfileEdit.Model -> applied(modelEdit(raw, edit.list, edit.op))
        is ProfileEdit.Add, is ProfileEdit.Remove, is ProfileEdit.Rename -> Applied.Nothing
    }

    /**
     * The env editor's handlers (:359-411). ta-coik.5: a rename or an add onto a name the profile
     * already has overwrites that entry in its place, as the web's `{ ...env, [key]: value }` and
     * `delete next[key]; next[nextKey] = value` do.
     */
    private fun applyEnv(raw: JsonObject, change: EnvChange): Applied {
        val env = raw["env"] as? JsonObject
        return when (change) {
            is EnvChange.Rename -> {
                val value = env?.get(change.from) ?: return Applied.No(ProvidersRefusal.Gone)
                if (change.to.isEmpty() || change.to == change.from) return Applied.Nothing
                if (change.to.length > ProfileLimits.COMMAND_ENTRY) return Applied.No(ProvidersRefusal.Invalid)
                val next = LinkedHashMap(env)
                next.remove(change.from)
                next[change.to] = value
                Applied.To(withEnv(raw, next))
            }
            is EnvChange.Change -> {
                val now = env?.get(change.key) ?: return Applied.No(ProvidersRefusal.Gone)
                if ((now as? JsonPrimitive)?.content == change.value.reveal()) return Applied.Nothing
                if (change.value.reveal().length > ProfileLimits.ENV_VALUE) return Applied.No(ProvidersRefusal.Invalid)
                val next = LinkedHashMap(env)
                next[change.key] = JsonPrimitive(change.value.reveal())
                Applied.To(withEnv(raw, next))
            }
            is EnvChange.Remove -> {
                if (env == null || change.key !in env) return Applied.No(ProvidersRefusal.Gone)
                val next = LinkedHashMap(env)
                next.remove(change.key)
                Applied.To(withEnv(raw, next))
            }
            is EnvChange.Add -> {
                if (change.key.isEmpty()) return Applied.Nothing
                if (change.key.length > ProfileLimits.COMMAND_ENTRY || change.value.reveal().length > ProfileLimits.ENV_VALUE) return Applied.No(ProvidersRefusal.Invalid)
                if ((env == null || change.key !in env) && (env?.size ?: 0) >= ProfileLimits.ENV_KEYS) return Applied.No(ProvidersRefusal.Invalid)
                val next = LinkedHashMap(env ?: emptyMap())
                next[change.key] = JsonPrimitive(change.value.reveal())
                Applied.To(withEnv(raw, next))
            }
        }
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
}
