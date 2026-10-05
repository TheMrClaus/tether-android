package com.tether.app.ui.setup

import com.tether.app.client.BinaryCheck
import com.tether.app.client.ClaudeAccountProfile
import com.tether.app.client.SetupClaudeStatus
import com.tether.app.client.ClaudeLoginPoll
import com.tether.app.client.ClaudeLoginStarted
import com.tether.app.client.SetupGitHubPoll
import com.tether.app.client.GitHubStatus
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

    // GitHub and Claude accounts (ta-pqui): scripted answers; what was sent is recorded.
    var githubStatus: SetupCall<GitHubStatus> = SetupCall.Ok(GitHubStatus(true, "2.60.0", false, null, emptyList(), false))
    var githubStart: SetupCall<Unit> = SetupCall.Ok(Unit)
    val githubPolls = java.util.concurrent.CopyOnWriteArrayList<SetupCall<SetupGitHubPoll>>()
    var githubToken: SetupCall<Unit> = SetupCall.Ok(Unit)
    val githubTokens = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile var githubCancels = 0
    var claudeList: SetupCall<List<ClaudeAccountProfile>> = SetupCall.Ok(emptyList())
    var claudeStatuses: Map<String, SetupClaudeStatus> = emptyMap()
    var claudeAdd: (String) -> SetupCall<String?> = { SetupCall.Ok(null) }
    val claudeAdded = java.util.concurrent.CopyOnWriteArrayList<String>()
    var claudeStart: SetupCall<ClaudeLoginStarted> = SetupCall.Ok(ClaudeLoginStarted("pending-url", null))
    val claudeStarted = java.util.concurrent.CopyOnWriteArrayList<String>()
    val claudePolls = java.util.concurrent.CopyOnWriteArrayList<SetupCall<ClaudeLoginPoll>>()
    var claudeCode: SetupCall<Unit> = SetupCall.Ok(Unit)
    val claudeCodes = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    val claudeCancels = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile var claudeListCalls = 0

    override suspend fun githubStatus() = githubStatus
    override suspend fun githubLoginStart() = githubStart
    override suspend fun githubLoginPoll(): SetupCall<SetupGitHubPoll> =
        // The last scripted answer repeats (a poll keeps asking until the flow settles).
        (if (githubPolls.size > 1) githubPolls.removeAt(0) else githubPolls.firstOrNull()) ?: SetupCall.Ok(SetupGitHubPoll(true, "pending", null, null, null))
    override suspend fun githubLoginCancel() {
        githubCancels += 1
    }
    override suspend fun githubSaveToken(token: String): SetupCall<Unit> {
        githubTokens += token
        return githubToken
    }
    override suspend fun claudeAccounts(): SetupCall<List<ClaudeAccountProfile>> {
        claudeListCalls += 1
        return claudeList
    }
    override suspend fun claudeAccountStatus(id: String) = claudeStatuses[id] ?: SetupClaudeStatus(false, null)
    override suspend fun claudeAccountAdd(nickname: String): SetupCall<String?> {
        claudeAdded += nickname
        return claudeAdd(nickname)
    }
    override suspend fun claudeLoginStart(id: String): SetupCall<ClaudeLoginStarted> {
        claudeStarted += id
        return claudeStart
    }
    override suspend fun claudeLoginPoll(id: String): SetupCall<ClaudeLoginPoll> =
        (if (claudePolls.size > 1) claudePolls.removeAt(0) else claudePolls.firstOrNull()) ?: SetupCall.Ok(ClaudeLoginPoll(true, "pending-url", null, null))
    override suspend fun claudeLoginCode(id: String, code: String): SetupCall<Unit> {
        claudeCodes += id to code
        return claudeCode
    }
    override suspend fun claudeLoginCancel(id: String) {
        claudeCancels += id
    }

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
