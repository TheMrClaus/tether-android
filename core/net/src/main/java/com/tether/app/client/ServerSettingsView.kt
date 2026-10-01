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

/** How a [ServerSetting] is read and written (settings-dialog.tsx's Server*Row helpers). */
enum class SettingKind { Text, Secret, Number, Toggle, Choice, Paths }

/**
 * The ServerSettings keys (lib/protocol.ts 887c222 :1194) the Advanced and Metadata tabs show, by
 * their wire key. The Engines keys (homes, commands, launch command, headless modes, share host
 * config) are ta-dh1's.
 */
enum class ServerSetting(val key: String, val kind: SettingKind) {
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
) {
    fun forced(setting: ServerSetting): Boolean = setting.key in envForced

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
            ServerSettingsView(frame.settings, frame.envForced.filterValues { it }.keys, frame.restartRequired)

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

    /**
     * ServerTextRow's commit (:129-132): `{ [field]: current || null }` when the field differs from
     * what it showed ([shown]: the server value as the field was filled with it).
     */
    fun text(view: ServerSettingsView, setting: ServerSetting, edited: String, shown: String): JsonObject? {
        if (view.forced(setting) || edited == shown) return null
        return patch(setting, if (edited.isEmpty()) JsonNull else JsonPrimitive(edited))
    }

    /**
     * ServerNumberRow's commit (:181-187): the trimmed text as a number, or null when empty, sent when
     * it differs from the server's. Text that is not a whole number sends nothing (the browser's
     * number input never yields one; the server would refuse it).
     */
    fun number(view: ServerSettingsView, setting: ServerSetting, text: String): JsonObject? {
        if (view.forced(setting)) return null
        val trimmed = text.trim()
        val parsed = if (trimmed.isEmpty()) null else (trimmed.toLongOrNull() ?: return null)
        if (parsed == view.number(setting)) return null
        return patch(setting, if (parsed == null) JsonNull else JsonPrimitive(parsed))
    }

    /** ServerToggleRow (:230): `{ [field]: !value }`. */
    fun toggle(view: ServerSettingsView, setting: ServerSetting): JsonObject? {
        if (view.forced(setting)) return null
        return patch(setting, JsonPrimitive(!view.toggle(setting)))
    }

    /** ServerSelectRow (:274): `{ [field]: next === "" ? null : next }`, when it is another option. */
    fun choice(view: ServerSettingsView, setting: ServerSetting, next: String): JsonObject? {
        if (view.forced(setting) || next == view.choice(setting)) return null
        return patch(setting, if (next.isEmpty()) JsonNull else JsonPrimitive(next))
    }

    /** ServerRootsRow's Add (:298-304): the trimmed path appended, the WHOLE list sent; empty or a duplicate sends nothing. */
    fun addPath(view: ServerSettingsView, setting: ServerSetting, typed: String): JsonObject? {
        if (view.forced(setting)) return null
        val value = typed.trim()
        val roots = view.paths(setting)
        if (value.isEmpty() || value in roots) return null
        return patch(setting, JsonArray((roots + value).map(::JsonPrimitive)))
    }

    /** ServerRootsRow's remove (:305): the list without [path]. */
    fun removePath(view: ServerSettingsView, setting: ServerSetting, path: String): JsonObject? {
        if (view.forced(setting)) return null
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
}
