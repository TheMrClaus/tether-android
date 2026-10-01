package com.tether.app.ui.settings

import com.tether.app.client.ConfirmedEngineWrite
import com.tether.app.client.ServerSettingsPatch
import com.tether.app.client.ServerSettingsView
import com.tether.app.protocol.ClaudeCliVersion
import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.TetherJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** ta-t7l: seeded `server-settings` / `advanced-settings` frames (fake values only) and a recording writer. */
object ServerFixtures {
    const val ORIGIN = "https://tether.test"
    const val OTHER_ORIGIN = "https://other.test"

    /** Behaviour tests: what must never leave the revealed field. */
    const val SENTINEL = "SENTINEL-pw-9b3f-do-not-leak"
    const val TOKEN_SENTINEL = "SENTINEL-proxy-5e21-do-not-leak"

    /** Goldens: an obviously fake value for the revealed shot. */
    const val FAKE_PASSWORD = "FAKE-demo-password"

    /** The metadata fallback list as the server keeps it: a JSON-encoded string. */
    const val PROVIDERS = """[{"provider":"anthropic","model":"claude-3-5-haiku-latest"},{"provider":"openai","model":"gpt-4o-mini"},{"provider":"ollama","model":""}]"""

    fun settingsJson(
        password: String = FAKE_PASSWORD,
        proxyToken: String = "",
        overrides: Map<String, Any?> = emptyMap(),
    ): JsonObject {
        val base = linkedMapOf<String, Any?>(
            "host" to "0.0.0.0", "port" to 4173, "password" to password, "proxyToken" to proxyToken,
            "stateDir" to "/srv/tether/state", "workspaceRoot" to "/srv/work",
            "claudePersistent" to true, "claudeTaskTelemetry" to true,
            "warmMaxSessions" to 8, "maxConcurrentTurns" to 0, "warmIdleEvictionMs" to 900000, "warmBgHardCapMs" to 1800000,
            "warmSweepMs" to 60000, "shutdownDrainMs" to 0,
            "messageInterruptMode" to "interrupt", "claudeModelFallback" to "cli", "archiveOnMerge" to false,
            "defaultPermissionMode" to "default", "defaultSandboxPolicy" to null, "defaultUseWorktree" to false,
            "allowedRoots" to listOf("/srv/work", "/srv/scratch"), "spawnExtraWritableRoots" to emptyList<String>(),
            "preferSpawnAgent" to "deny", "pinnedWorkspaces" to null,
            "metadataGenerationEnabled" to true, "metadataGenerationMode" to "manual",
            "metadataGenerationProvider" to "anthropic:claude-3-5-haiku-latest", "metadataGenerationProviders" to PROVIDERS,
            // ta-dh1: the Engines tab. Claude's home is empty (the real HOME, issue #86); OpenCode,
            // Reasonix, Pi and DeepSeek Harness have none (their switches are blocked).
            "headlessModes" to "claude,codex", "claudeHome" to "", "codexHome" to "/srv/homes/codex",
            "opencodeHome" to null, "reasonixHome" to null, "piHome" to null, "dshHome" to null,
            "claudeCommand" to "claude", "codexCommand" to "codex", "opencodeCommand" to "opencode",
            "reasonixCommand" to "reasonix", "piCommand" to "pi", "dshCommand" to "dsh",
            "claudeLaunchCommand" to "", "shareHostConfig" to true,
        )
        base.putAll(overrides)
        return JsonObject(base.mapValues { (_, v) -> toJson(v) })
    }

    private fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map(::toJson))
        else -> error("unsupported $v")
    }

    /**
     * ta-dh1: what the host-CLI scan found (fake paths): Claude, Codex, Reasonix and Pi installed,
     * OpenCode not found (its config home detected), no entry yet for DeepSeek Harness ("scanning…").
     */
    val DETECTED: JsonObject = json(
        """{
            "claude":{"engine":"claude","found":true,"binPath":"/home/op/.local/bin/claude","version":"2.1.284","source":"host install","configDir":"/home/op/.claude","configPresent":true},
            "codex":{"engine":"codex","found":true,"binPath":"/home/op/.codex/bin/codex","version":"0.159.0","source":"user PATH","configDir":"/home/op/.codex","configPresent":true},
            "opencode":{"engine":"opencode","found":false,"binPath":null,"version":null,"source":"not found","configDir":"/home/op/.config/opencode","configPresent":false},
            "reasonix":{"engine":"reasonix","found":true,"binPath":"/opt/reasonix/bin/reasonix","version":"1.39.4","source":"bundled","configDir":"/home/op/.reasonix","configPresent":false},
            "pi":{"engine":"pi","found":true,"binPath":"/home/op/.local/bin/pi","version":null,"source":"user PATH","configDir":"/home/op/.pi/agent","configPresent":false}
        }""",
    )

    fun frame(
        settings: JsonObject = settingsJson(),
        envForced: Map<String, Boolean> = mapOf("stateDir" to true),
        restartRequired: Boolean = false,
        detected: JsonObject = DETECTED,
    ) = ServerMessage.ServerSettings(settings, envForced, restartRequired, emptyList(), detected)

    fun view(
        settings: JsonObject = settingsJson(),
        envForced: Map<String, Boolean> = mapOf("stateDir" to true),
        restartRequired: Boolean = false,
        detected: JsonObject = DETECTED,
    ) = ServerSettingsView.of(frame(settings, envForced, restartRequired, detected))

    /** [settings] with [patch] applied, as the server's reply would carry it. */
    fun applied(settings: JsonObject, patch: JsonObject) = JsonObject(settings + patch)

    val ADVANCED = ServerMessage.AdvancedSettings(
        claudeCliVersion = null,
        discovered = listOf(ClaudeCliVersion("2.1.225"), ClaudeCliVersion("2.1.220")),
        envForced = false,
        envPath = null,
        effectiveSource = "host install (auto)",
        effectiveVersion = "2.1.225",
    )

    val ADVANCED_FORCED = ADVANCED.copy(envForced = true, envPath = "/opt/claude/bin/claude")

    fun binding(
        view: ServerSettingsView? = view(),
        advanced: ServerMessage.AdvancedSettings? = ADVANCED,
        origin: String? = ORIGIN,
        writer: ServerSettingsWriter = ServerSettingsWriter.None,
        replies: Long = 0L,
    ) = ServerSettingsBinding(view, advanced, origin, writer, replies)

    fun json(text: String): JsonObject = TetherJson.parseToJsonElement(text).jsonObject
}

/** Records every write with the origin it was bound to; [reply] plays the server's answer. */
class RecordingWriter(private val reply: (JsonObject) -> Unit = {}) : ServerSettingsWriter {
    val patches = mutableListOf<Pair<JsonObject, String>>()
    val cli = mutableListOf<Pair<ClientMessage.SetAdvancedSettings, String>>()

    /** ta-dh1: each `detect-engines` ("Scan again"), by the origin it was bound to. */
    val scans = mutableListOf<String>()

    /** The frames as sent: `{"type":"set-server-settings","settings":…}`. */
    fun frames(): List<JsonObject> = patches.map { ClientMessage.SetServerSettings(it.first).toJsonObject() }

    /** ta-dh1 r2: the confirmed engine writes (also in [patches], as the frame they send). */
    val confirmedWrites = mutableListOf<ConfirmedEngineWrite>()

    override fun patch(patch: JsonObject, origin: String): Boolean {
        // r2: a plain patch never names what the server runs (the binding refuses one before here).
        check(!ServerSettingsPatch.touchesWhatRuns(patch)) { "an unconfirmed write of ${patch.keys}" }
        patches += patch to origin
        reply(patch)
        return true
    }

    override fun cliVersion(message: ClientMessage.SetAdvancedSettings, origin: String): Boolean {
        cli += message to origin
        return true
    }

    override fun detectEngines(origin: String): Boolean {
        scans += origin
        return true
    }

    override fun confirmed(write: ConfirmedEngineWrite, origin: String): Boolean {
        confirmedWrites += write
        patches += write.patch to origin
        reply(write.patch)
        return true
    }
}
