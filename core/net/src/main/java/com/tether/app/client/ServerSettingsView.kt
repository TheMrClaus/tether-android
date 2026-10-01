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
 * ta-t7l: a value the server sends in PLAINTEXT that is a secret (`password`, `proxyToken`). It
 * prints as `SecretText(***)` whatever it holds, so no log line, assertion message or data-class
 * `toString` can carry it; [reveal] is the one way to read it, called only by the revealed row.
 * It is never put in saved state or the preference store. In memory it lives in the client's last
 * `server-settings` frame, which the client drops on a server switch, a sign-out and an
 * auth-required state (r2), and in the settings UI only while a row is revealed.
 */
class SecretText(private val value: String) {
    fun reveal(): String = value
    val isEmpty: Boolean get() = value.isEmpty()
    override fun toString(): String = "SecretText(***)"
    override fun equals(other: Any?): Boolean = other is SecretText && other.value == value
    override fun hashCode(): Int = value.hashCode()
}

/**
 * How a [ServerSetting] is read and written (settings-dialog.tsx's Server*Row helpers). ta-dh1:
 * [Runs] is a value that sets what the server RUNS (an engine's home, command or launch command):
 * read like [Text], but written ONLY by [ServerSettingsPatch.engineValue], after a confirmation
 * that shows the new value (owner decision 2026-10-01); [ServerSettingsPatch.text] refuses it.
 */
enum class SettingKind { Text, Secret, Number, Toggle, Choice, Paths, Runs }

/**
 * The ServerSettings keys (lib/protocol.ts 887c222 :1194) the Settings tabs show, by their wire
 * key. [maxBytes]: the server's size limit for a string key (protocol-validate.mjs 887c222
 * :1846-1866, UTF-8 bytes); a longer value is never sent, since the server would refuse it.
 */
enum class ServerSetting(val key: String, val kind: SettingKind, val maxBytes: Int = 0) {
    Host("host", SettingKind.Text),
    Port("port", SettingKind.Number),
    Password("password", SettingKind.Secret),
    ProxyToken("proxyToken", SettingKind.Secret),
    StateDir("stateDir", SettingKind.Text),
    WorkspaceRoot("workspaceRoot", SettingKind.Text),
    ClaudePersistent("claudePersistent", SettingKind.Toggle),
    ClaudeTaskTelemetry("claudeTaskTelemetry", SettingKind.Toggle),
    WarmMaxSessions("warmMaxSessions", SettingKind.Number),
    MaxConcurrentTurns("maxConcurrentTurns", SettingKind.Number),
    WarmIdleEvictionMs("warmIdleEvictionMs", SettingKind.Number),
    WarmBgHardCapMs("warmBgHardCapMs", SettingKind.Number),
    WarmSweepMs("warmSweepMs", SettingKind.Number),
    ShutdownDrainMs("shutdownDrainMs", SettingKind.Number),
    MessageInterruptMode("messageInterruptMode", SettingKind.Choice),
    ClaudeModelFallback("claudeModelFallback", SettingKind.Choice),
    ArchiveOnMerge("archiveOnMerge", SettingKind.Toggle),
    DefaultPermissionMode("defaultPermissionMode", SettingKind.Choice),
    DefaultSandboxPolicy("defaultSandboxPolicy", SettingKind.Choice),
    DefaultUseWorktree("defaultUseWorktree", SettingKind.Toggle),
    AllowedRoots("allowedRoots", SettingKind.Paths),
    SpawnExtraWritableRoots("spawnExtraWritableRoots", SettingKind.Paths),
    PreferSpawnAgent("preferSpawnAgent", SettingKind.Choice),
    MetadataGenerationEnabled("metadataGenerationEnabled", SettingKind.Toggle),
    MetadataGenerationMode("metadataGenerationMode", SettingKind.Choice),
    MetadataGenerationProvider("metadataGenerationProvider", SettingKind.Text),
    MetadataGenerationProviders("metadataGenerationProviders", SettingKind.Text),

    // ta-dh1: the Engines tab (settings-dialog.tsx 887c222 :2107-2249).
    HeadlessModes("headlessModes", SettingKind.Text, maxBytes = 256),
    ClaudeHome("claudeHome", SettingKind.Runs, maxBytes = 4096),
    CodexHome("codexHome", SettingKind.Runs, maxBytes = 4096),
    OpencodeHome("opencodeHome", SettingKind.Runs, maxBytes = 4096),
    ReasonixHome("reasonixHome", SettingKind.Runs, maxBytes = 4096),
    PiHome("piHome", SettingKind.Runs, maxBytes = 4096),
    DshHome("dshHome", SettingKind.Runs, maxBytes = 4096),
    ClaudeCommand("claudeCommand", SettingKind.Runs, maxBytes = 256),
    CodexCommand("codexCommand", SettingKind.Runs, maxBytes = 256),
    OpencodeCommand("opencodeCommand", SettingKind.Runs, maxBytes = 256),
    ReasonixCommand("reasonixCommand", SettingKind.Runs, maxBytes = 256),
    PiCommand("piCommand", SettingKind.Runs, maxBytes = 256),
    DshCommand("dshCommand", SettingKind.Runs, maxBytes = 256),
    ClaudeLaunchCommand("claudeLaunchCommand", SettingKind.Runs, maxBytes = 256),
    ShareHostConfig("shareHostConfig", SettingKind.Toggle),
}

/**
 * ta-t7l: one `server-settings` frame as the settings tabs read it, by the web's own coercions
 * (settings-dialog.tsx 887c222 :114-357). Tolerant: a missing key, or one of another type, reads
 * as the web's fallback (`""`, null, false, `[]`), never an error. Every key the frame carries is
 * kept as it came ([raw]), so the sidebar's `pinnedWorkspaces` and the keys of later slices read on.
 *
 * [toString] prints the keys and the env locks only: the frame holds `password` and `proxyToken`
 * in plaintext, and those are read only through [secret].
 */
class ServerSettingsView private constructor(
    private val raw: JsonObject,
    /** The keys an environment variable forces (`envForced[key] === true`): shown locked, never written. */
    val envForced: Set<String>,
    /** `restartRequired`: the server booted with values that differ from what is now persisted. */
    val restartRequired: Boolean,
    /** `detected`: Record<string, EngineDetection>, raw; read only for the [EngineCard] ids. */
    private val detected: JsonObject = JsonObject(emptyMap()),
) {
    fun forced(setting: ServerSetting): Boolean = setting.key in envForced

    /**
     * ta-dh1: `detected[engine.id]` (settings-dialog.tsx:2127), decoded tolerantly; null when the
     * server sent none for it (the card then says "scanning…"). Only the six [EngineCard] ids are
     * ever read, so a frame with any number of entries costs six lookups; each is decoded once.
     */
    fun detection(engine: EngineCard): EngineDetection? = detections[engine]

    private val detections: Map<EngineCard, EngineDetection?> by lazy {
        EngineCard.entries.associateWith { EngineDetection.of(detected[it.id]) }
    }

    /**
     * ta-dh1: settings-dialog.tsx:47 `headlessModesList`: the comma list, each entry trimmed, the
     * empty ones dropped (decoded once).
     */
    val headlessModes: List<String> by lazy { headlessModesList(text(ServerSetting.HeadlessModes)) }

    /**
     * ta-dh1 (issue #86, settings-dialog.tsx:2136): the engine has no home and needs one (Claude's
     * is optional: an empty home is the operator's real HOME), so its switch is blocked.
     */
    fun needsHome(engine: EngineCard): Boolean = text(engine.home).isEmpty() && !engine.homeOptional

    /**
     * ServerTextRow: `String(settings[field] ?? "")`. r2: a [SettingKind.Secret] reads "" here, so the
     * plaintext can only be had through [secret] (and its [SecretText.reveal]), never by mistake.
     */
    fun text(setting: ServerSetting): String = if (setting.kind == SettingKind.Secret) "" else stringOf(raw[setting.key])

    /** The secret rows' value, the same coercion as [text], kept in a [SecretText]. */
    fun secret(setting: ServerSetting): SecretText = SecretText(stringOf(raw[setting.key]))

    /** ServerNumberRow: the value when it is a number, else null (an integral value; a fraction is cut). */
    fun number(setting: ServerSetting): Long? {
        val p = raw[setting.key] as? JsonPrimitive ?: return null
        if (p is JsonNull || p.isString) return null
        p.longOrNull?.let { return it }
        val d = p.doubleOrNull ?: return null
        return if (d.isFinite()) d.toLong() else null
    }

    /** ServerToggleRow: `Boolean(settings[field])` (JavaScript truthiness). */
    fun toggle(setting: ServerSetting): Boolean = when (val v = raw[setting.key]) {
        null, is JsonNull -> false
        is JsonPrimitive -> if (v.isString) v.content.isNotEmpty() else v.booleanOrNull ?: (v.doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: false)
        else -> true
    }

    /** ServerSelectRow: null / absent is the empty option (`""`), anything else its string (a secret reads ""). */
    fun choice(setting: ServerSetting): String = if (setting.kind == SettingKind.Secret) "" else stringOf(raw[setting.key])

    /** ServerRootsRow: `Array.isArray(raw) ? raw : []` (a non-string entry is dropped). */
    fun paths(setting: ServerSetting): List<String> =
        (raw[setting.key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

    override fun toString(): String =
        "ServerSettingsView(keys=${raw.keys.sorted()}, envForced=${envForced.sorted()}, restartRequired=$restartRequired)"

    companion object {
        fun of(frame: ServerMessage.ServerSettings): ServerSettingsView =
            ServerSettingsView(frame.settings, frame.envForced.filterValues { it }.keys, frame.restartRequired, frame.detected)

        /** settings-dialog.tsx:47: `String(raw ?? "").split(",").map(trim).filter(Boolean)`. */
        fun headlessModesList(raw: String): List<String> = raw.split(',').map(::jsTrim).filter { it.isNotEmpty() }

        /** `String(x ?? "")` for the shapes a setting can hold: a primitive's text, else "". */
        private fun stringOf(value: JsonElement?): String = when (value) {
            null, is JsonNull -> ""
            is JsonPrimitive -> value.content
            else -> ""
        }
    }
}

/**
 * ta-t7l: the `set-server-settings` patches the tabs send, each a Partial<ServerSettings> holding
 * ONLY the key that changed, valued as the web's row sends it (settings-dialog.tsx 887c222). A
 * write that would change nothing, or names a key the environment forces, is null: nothing is sent.
 */
object ServerSettingsPatch {
    private fun patch(setting: ServerSetting, value: JsonElement) = JsonObject(mapOf(setting.key to value))

    /** r2: the keys that set what the server runs ([SettingKind.Runs]): written only as a [ConfirmedEngineWrite]. */
    val runsKeys: Set<String> by lazy { ServerSetting.entries.filter { it.kind == SettingKind.Runs }.map { it.key }.toSet() }

    /** r2: [patch] names a key that sets what the server runs (so it may go out only as a [ConfirmedEngineWrite]). */
    fun touchesWhatRuns(patch: JsonObject): Boolean = patch.keys.any { it in runsKeys }

    /** r2: a builder that writes without a confirmation never takes a [SettingKind.Runs] key (the send path refuses one too). */
    private fun unconfirmed(setting: ServerSetting) = setting.kind != SettingKind.Runs

    /**
     * ServerTextRow's commit (:129-132): `{ [field]: current || null }` when the field differs from
     * what it showed ([shown]: the server value as the field was filled with it). ta-dh1: never a
     * [SettingKind.Runs] key (those go through [engineValue], after their confirmation).
     */
    fun text(view: ServerSettingsView, setting: ServerSetting, edited: String, shown: String): JsonObject? {
        if (!unconfirmed(setting) || view.forced(setting) || edited == shown) return null
        return patch(setting, if (edited.isEmpty()) JsonNull else JsonPrimitive(edited))
    }

    /**
     * ServerNumberRow's commit (:181-187): the trimmed text as a number, or null when empty, sent when
     * it differs from the server's. Text that is not a whole number sends nothing (the browser's
     * number input never yields one; the server would refuse it).
     */
    fun number(view: ServerSettingsView, setting: ServerSetting, text: String): JsonObject? {
        if (!unconfirmed(setting) || view.forced(setting)) return null
        val trimmed = text.trim()
        val parsed = if (trimmed.isEmpty()) null else (trimmed.toLongOrNull() ?: return null)
        if (parsed == view.number(setting)) return null
        return patch(setting, if (parsed == null) JsonNull else JsonPrimitive(parsed))
    }

    /** ServerToggleRow (:230): `{ [field]: !value }`. */
    fun toggle(view: ServerSettingsView, setting: ServerSetting): JsonObject? {
        if (!unconfirmed(setting) || view.forced(setting)) return null
        return patch(setting, JsonPrimitive(!view.toggle(setting)))
    }

    /** ServerSelectRow (:274): `{ [field]: next === "" ? null : next }`, when it is another option. */
    fun choice(view: ServerSettingsView, setting: ServerSetting, next: String): JsonObject? {
        if (!unconfirmed(setting) || view.forced(setting) || next == view.choice(setting)) return null
        return patch(setting, if (next.isEmpty()) JsonNull else JsonPrimitive(next))
    }

    /** ServerRootsRow's Add (:298-304): the trimmed path appended, the WHOLE list sent; empty or a duplicate sends nothing. */
    fun addPath(view: ServerSettingsView, setting: ServerSetting, typed: String): JsonObject? {
        if (!unconfirmed(setting) || view.forced(setting)) return null
        val value = typed.trim()
        val roots = view.paths(setting)
        if (value.isEmpty() || value in roots) return null
        return patch(setting, JsonArray((roots + value).map(::JsonPrimitive)))
    }

    /** ServerRootsRow's remove (:305): the list without [path]. */
    fun removePath(view: ServerSettingsView, setting: ServerSetting, path: String): JsonObject? {
        if (!unconfirmed(setting) || view.forced(setting)) return null
        val roots = view.paths(setting)
        if (path !in roots) return null
        return patch(setting, JsonArray(roots.filter { it != path }.map(::JsonPrimitive)))
    }

    /**
     * The Claude CLI picker (settings-dialog.tsx:2437, use-tether.ts:1838): `set-advanced-settings
     * { claudeCliVersion: next || null }`. Null when the environment forces the CLI (the server
     * ignores the write then; the web shows no picker) or the pick is the current one.
     */
    fun cliVersion(advanced: ServerMessage.AdvancedSettings, next: String): ClientMessage.SetAdvancedSettings? {
        if (advanced.envForced || next == advanced.claudeCliVersion.orEmpty()) return null
        return ClientMessage.SetAdvancedSettings(next.ifEmpty { null })
    }

    /**
     * ta-dh1: an engine card's switch (settings-dialog.tsx:2114-2117 `toggleEngine`): the list with
     * [engine] added or removed, written as `{ headlessModes: next.join(",") }`. Null when the
     * environment forces the list, when the switch is blocked (the engine [needs a home][ServerSettingsView.needsHome]
     * and the environment does not force one: :2154, issue #86), or when the list would pass the
     * server's size limit.
     */
    fun headlessMode(view: ServerSettingsView, engine: EngineCard): JsonObject? {
        if (view.forced(ServerSetting.HeadlessModes)) return null
        if (view.needsHome(engine) && !view.forced(engine.home)) return null
        val modes = view.headlessModes
        val next = if (engine.id in modes) modes.filter { it != engine.id } else modes + engine.id
        val joined = next.joinToString(",")
        if (!fits(ServerSetting.HeadlessModes, joined)) return null
        return patch(ServerSetting.HeadlessModes, JsonPrimitive(joined))
    }

    /**
     * ta-dh1: an engine's home, command or launch command ([SettingKind.Runs]), the value the user
     * CONFIRMED, built when they confirm from the latest frame ([view]). As the web's blur
     * (settings-dialog.tsx:2175, 2207, 2226): a home or the launch command sends `value || null`, a
     * command sends `value` (an empty command runs the engine's own name, server.mjs
     * `providerCommands`). Null when the key is env-forced, the value is the server's already, or it
     * passes the server's size limit. [value] is sent exactly as given: the caller trims it (the
     * web's `.trim()`, [jsTrim]) BEFORE the confirmation, which shows the trimmed value.
     *
     * r2: the ONLY producer of a [ConfirmedEngineWrite], the only form in which the client sends a
     * [SettingKind.Runs] key ([TetherClient.setServerSettings] refuses one in a plain patch).
     */
    fun engineValue(view: ServerSettingsView, setting: ServerSetting, value: String): ConfirmedEngineWrite? {
        if (setting.kind != SettingKind.Runs || view.forced(setting)) return null
        if (value == view.text(setting) || !fits(setting, value)) return null
        val nullable = setting !in EngineCard.commandSettings
        return ConfirmedEngineWrite(patch(setting, if (nullable && value.isEmpty()) JsonNull else JsonPrimitive(value)))
    }

    /** Within [setting]'s server limit (UTF-8 bytes; protocol-validate.mjs `isBoundedString`). */
    fun fits(setting: ServerSetting, value: String): Boolean =
        setting.maxBytes <= 0 || value.toByteArray(Charsets.UTF_8).size <= setting.maxBytes
}

/**
 * ta-dh1 r2: a `set-server-settings` patch naming a key that sets what the server RUNS (an engine's
 * home, command or launch command), as confirmed by the user. Only [ServerSettingsPatch.engineValue]
 * makes one (the constructor is internal to this module), and the client sends such a key only in
 * this form: [TetherClient.setServerSettings] refuses a plain patch that names one. [toString]
 * names the key, never the value.
 */
class ConfirmedEngineWrite internal constructor(val patch: JsonObject) {
    override fun toString(): String = "ConfirmedEngineWrite(${patch.keys.sorted()})"
    override fun equals(other: Any?): Boolean = other is ConfirmedEngineWrite && other.patch == patch
    override fun hashCode(): Int = patch.hashCode()
}
