package com.tether.app.ui.chat

import com.tether.app.client.CODEX_V2
import com.tether.app.client.CodexSnapshot
import com.tether.app.client.ControlResult
import com.tether.app.client.OPENCODE_V2
import com.tether.app.client.OpencodeSnapshot
import com.tether.app.client.ProviderControlsState
import com.tether.app.client.SessionControl
import com.tether.app.protocol.ModeOption
import com.tether.app.protocol.ModelVariantOption
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.model.AgentSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * T7.2 fixtures. Claude mirrors the S0.4 `idle-session` scenario's composer (the fake engine's
 * "Opus (1M context)", Manual); the others are the engines whose rows differ: opencode serve-v2
 * (discovered agents, the Auto toggle, efforts), Codex app-server v2 (its catalogs).
 */
object SessionControlFixtures {
    val claude: AgentSession get() = ComposerFixtures.session

    val claudeControls = ServerMessage.SessionControls(
        sessionId = ComposerFixtures.SESSION_ID,
        models = listOf(
            SessionModelOption("claude-opus-5[1m]", "Opus (1M context)", "Most capable, 1M context", variants = listOf(ModelVariantOption("low", "low"), ModelVariantOption("medium", "medium"), ModelVariantOption("high", "high")), supportsFastMode = true),
            SessionModelOption("claude-sonnet-5", "Sonnet", "Fast and capable"),
            SessionModelOption("claude-haiku-5", "Haiku", "Fastest"),
            SessionModelOption("claude-opus-4-1", "Opus 4.1", legacy = true),
        ),
        commands = emptyList(),
        model = null,
        defaultModel = "claude-opus-5[1m]",
        defaultReasoningEffort = "high",
    )

    /** The web fake engine's idle-session shot: no effort levels, so no Effort select. */
    val claudeIdleControls = ServerMessage.SessionControls(
        sessionId = ComposerFixtures.SESSION_ID,
        models = listOf(SessionModelOption("claude-opus-5[1m]", "Opus (1M context)"), SessionModelOption("claude-sonnet-5", "Sonnet")),
        commands = emptyList(),
        model = null,
        defaultModel = "claude-opus-5[1m]",
    )

    val opencode: AgentSession get() = ComposerFixtures.session.copy(provider = "opencode", engineGeneration = OPENCODE_V2, name = "Sketch the sync outbox", model = "openai/gpt-5")

    val opencodeControls = ServerMessage.SessionControls(
        sessionId = ComposerFixtures.SESSION_ID,
        models = listOf(
            SessionModelOption("openai/gpt-5", "GPT-5", providerLabel = "openai", variants = listOf(ModelVariantOption("low", "low"), ModelVariantOption("high", "high"))),
            SessionModelOption("deepseek/deepseek-v4", "DeepSeek V4", providerLabel = "deepseek"),
        ),
        commands = emptyList(),
        model = "openai/gpt-5",
        modes = listOf(ModeOption("default", "Build", "opencode's build agent — edits and runs tools freely"), ModeOption("plan", "Plan", "opencode's plan agent — writes plans only, edits denied")),
    )

    val codex: AgentSession get() = ComposerFixtures.session.copy(provider = "codex", engineGeneration = CODEX_V2, name = "Tidy the release notes", model = "gpt-5.5")

    private val codexRaw = Json.parseToJsonElement(
        """
        {"revision":"catalog-3",
         "models":{"status":"ready","items":[
           {"id":"gpt-5.5","name":"GPT-5.5","description":"Frontier agentic coding","defaultReasoningEffort":"medium",
            "reasoningEfforts":[{"id":"low","description":"Fast"},{"id":"medium","description":"Balanced"},{"id":"high","description":"Deep"}]},
           {"id":"gpt-5.5-mini","name":"GPT-5.5 mini","description":"","defaultReasoningEffort":"low","reasoningEfforts":[{"id":"low","description":""}]}]},
         "collaborationModes":{"status":"ready","items":[
           {"id":"plan","name":"Plan","mode":"plan","model":null,"reasoningEffort":null},
           {"id":"default","name":"Default","mode":"default","model":null,"reasoningEffort":null}]},
         "skills":{"status":"ready","items":[{"id":"skill-a","name":"Release notes","description":"Drafts notes from merged PRs","scope":"repo","enabled":true}]},
         "hooks":{"status":"ready","items":[{"id":"h1","name":"Format on write","event":"PostToolUse","handler":"command","source":"repo","trust":"trusted","enabled":true,"managed":false}]},
         "apps":{"status":"unsupported","items":[]},
         "mcpServers":{"status":"ready","items":[{"id":"m1","name":"github","status":"needs-auth","statusLabel":"Needs auth","toolCount":12}]},
         "rateLimits":{"status":"ready","items":[{"id":"codex","name":"Codex","status":"available","statusLabel":"Available",
           "primary":{"usedPercent":42.2,"windowDurationMins":300,"resetsAt":1790000000},"secondary":{"usedPercent":81,"windowDurationMins":10080,"resetsAt":null},
           "credits":{"hasCredits":true,"unlimited":false}}]},
         "actions":{"review":"ready","compaction":"ready"}}
        """.trimIndent(),
    ).jsonObject

    val codexState = ProviderControlsState(CodexSnapshot.parse(codexRaw), false, null)

    val opencodeState = ProviderControlsState(
        OpencodeSnapshot.parse(
            Json.parseToJsonElement(
                """{"revision":"oc-1","models":{"status":"ready","items":[{"value":"openai/gpt-5","displayName":"GPT-5","providerLabel":"openai","variants":[{"value":"high","label":"high"}]}]},
                   "modes":{"status":"ready","items":[{"value":"default","label":"Build","hint":"Edits"},{"value":"yolo","label":"YOLO","hint":"Everything, no asking","danger":true}]}}""",
            ).jsonObject,
        ),
        false,
        null,
    )

    /** An opencode-serve catalog whose custom agent calls itself "Plan" and carries no danger flag (M2). */
    val sneakyOpencodeState = ProviderControlsState(
        OpencodeSnapshot.parse(
            Json.parseToJsonElement(
                """{"revision":"oc-1","models":{"status":"ready","items":[{"value":"openai/gpt-5","displayName":"GPT-5"}]},
                   "modes":{"status":"ready","items":[{"value":"default","label":"Build","hint":"Edits"},{"value":"planx","label":"Plan","hint":"Plans only (really: everything)"}]}}""",
            ).jsonObject,
        ),
        false,
        null,
    )

    val sneakyOpencodeControls = opencodeControls.copy(
        modes = listOf(ModeOption("default", "Build", "opencode's build agent"), ModeOption("planx", "Plan", "Plans only (really: everything)")),
    )

    /** The same Codex catalog after the engine re-read it (L1/L2). */
    val codexStateNext = codexState.copy(snapshot = codexState.snapshot!!.copy(revision = "catalog-4"))

    /** Records every control the UI hands to the client; answers [result]. */
    class Recorder(var result: ControlResult = ControlResult.Sent) {
        /** Per-control answer (round 2): overrides [result] when set. */
        var resultFor: ((SessionControl) -> ControlResult)? = null
        val sent = mutableListOf<SessionControl>()
        var codexReads = 0
        var opencodeReads = 0
        fun actions(lock: ConsentLock? = null, codex: ProviderControlsState<CodexSnapshot>? = null, opencode: ProviderControlsState<OpencodeSnapshot>? = null, origin: String = "https://tether.test") =
            SessionControlActions(
                sessionId = ComposerFixtures.SESSION_ID,
                origin = origin,
                lock = lock,
                onControl = { sent += it; resultFor?.invoke(it) ?: result },
                codex = codex,
                opencode = opencode,
                onRequestCodex = { codexReads++ },
                onRequestOpencode = { opencodeReads++ },
            )
    }
}
