package com.tether.app.ui.settings

import com.tether.app.client.ServerSettingsView
import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.TetherJson
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
        )
        base.putAll(overrides)
        return JsonObject(base.mapValues { (_, v) -> toJson(v) })
    }

    private fun toJson(v: Any?): kotlinx.serialization.json.JsonElement = when (v) {
        null -> kotlinx.serialization.json.JsonNull
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is List<*> -> kotlinx.serialization.json.JsonArray(v.map(::toJson))
        else -> error("unsupported $v")
    }

    fun frame(
        settings: JsonObject = settingsJson(),
        envForced: Map<String, Boolean> = mapOf("stateDir" to true),
        restartRequired: Boolean = false,
    ) = ServerMessage.ServerSettings(settings, envForced, restartRequired, emptyList(), JsonObject(emptyMap()))

    fun view(
        settings: JsonObject = settingsJson(),
        envForced: Map<String, Boolean> = mapOf("stateDir" to true),
        restartRequired: Boolean = false,
    ) = ServerSettingsView.of(frame(settings, envForced, restartRequired))

    val ADVANCED = ServerMessage.AdvancedSettings(
        claudeCliVersion = null,
        discovered = listOf(com.tether.app.protocol.ClaudeCliVersion("2.1.225"), com.tether.app.protocol.ClaudeCliVersion("2.1.220")),
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
    ) = ServerSettingsBinding(view, advanced, origin, writer)

    fun json(text: String): JsonObject = TetherJson.parseToJsonElement(text).jsonObject
}

/** Records every write with the origin it was bound to; [reply] plays the server's answer. */
class RecordingWriter(private val reply: (JsonObject) -> Unit = {}) : ServerSettingsWriter {
    val patches = mutableListOf<Pair<JsonObject, String>>()
    val cli = mutableListOf<Pair<ClientMessage.SetAdvancedSettings, String>>()

    /** The frames as sent: `{"type":"set-server-settings","settings":…}`. */
    fun frames(): List<JsonObject> = patches.map { ClientMessage.SetServerSettings(it.first).toJsonObject() }

    override fun patch(patch: JsonObject, origin: String): Boolean {
        patches += patch to origin
        reply(patch)
        return true
    }

    override fun cliVersion(message: ClientMessage.SetAdvancedSettings, origin: String): Boolean {
        cli += message to origin
        return true
    }
}
