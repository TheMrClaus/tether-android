package com.tether.app.ui.setup

import com.tether.app.client.BinaryCheck
import com.tether.app.client.EngineDetection
import com.tether.app.client.SetupApi
import com.tether.app.client.SetupCall
import com.tether.app.client.SetupFinish
import com.tether.app.client.SetupState
import com.tether.app.protocol.model.DirectoryEntry
import com.tether.app.protocol.model.DirectoryListing
import kotlinx.serialization.json.JsonObject

/** A scripted [SetupApi] for the model's rules and the goldens: nothing on a network. */
class FakeSetupApi(
    var state: SetupCall<SetupState> = SetupCall.Ok(sampleState()),
    var detect: SetupCall<Map<String, EngineDetection>> = SetupCall.Ok(sampleDetection()),
) : SetupApi {
    val completed = mutableListOf<JsonObject>()
    val validated = mutableListOf<Pair<String, String>>()
    val browsed = mutableListOf<String?>()
    var browse: (String?) -> SetupCall<DirectoryListing> = { path -> SetupCall.Ok(sampleListing(path ?: "/home/op")) }
    var validate: (String, String) -> SetupCall<BinaryCheck> = { _, _ -> SetupCall.Ok(BinaryCheck(true, "Found here.")) }
    var complete: () -> SetupCall<SetupFinish> = { SetupCall.Ok(SetupFinish(restart = "manual", runtime = "native")) }
    var configuredAnswers = ArrayDeque<Boolean>()

    override suspend fun state() = state
    override suspend fun detect() = detect
    override suspend fun browse(path: String?): SetupCall<DirectoryListing> {
        browsed += path
        return browse.invoke(path)
    }
    override suspend fun validateEngineBinary(engine: String, value: String): SetupCall<BinaryCheck> {
        validated += engine to value
        return validate(engine, value)
    }
    override suspend fun complete(settings: JsonObject): SetupCall<SetupFinish> {
        completed += settings
        return complete.invoke()
    }
    override suspend fun configured(): Boolean = configuredAnswers.removeFirstOrNull() ?: false

    companion object {
        fun sampleState(
            runtime: String = "native",
            forced: Map<String, Boolean> = emptyMap(),
            settings: Map<String, String> = emptyMap(),
            candidates: List<String> = emptyList(),
            dev: Boolean = false,
        ) = SetupState(
            runtime = runtime,
            dev = dev,
            supportedModes = listOf("fake", "claude", "codex", "opencode", "reasonix", "pi", "acp", "dsh"),
            envForced = forced,
            settings = settings,
            hasPassword = false,
            defaultFolder = "/home/op",
            workspaceCandidates = candidates,
        )

        fun sampleDetection(): Map<String, EngineDetection> = mapOf(
            "claude" to EngineDetection(true, "2.1.0", "host install", "/home/op/.claude", "/home/op/.local/bin/claude"),
            "codex" to EngineDetection(true, "0.40.0", "user PATH", null, "/usr/bin/codex"),
            "opencode" to EngineDetection(false, null, "not found", null, null),
            "reasonix" to EngineDetection(true, "0.9.2", "bundled", null, "/srv/tether/node_modules/.bin/reasonix"),
            "pi" to EngineDetection(false, null, "not found", null, null),
        )

        fun sampleListing(current: String) = DirectoryListing(
            current = current,
            parent = if (current == "/") null else current.substringBeforeLast('/').ifEmpty { "/" },
            entries = listOf(
                DirectoryEntry("projects", current.trimEnd('/') + "/projects"),
                DirectoryEntry("work", current.trimEnd('/') + "/work"),
            ),
        )
    }
}
