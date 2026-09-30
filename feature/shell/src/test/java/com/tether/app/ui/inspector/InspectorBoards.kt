package com.tether.app.ui.inspector

import com.tether.app.client.ChangeRequestReading
import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.ProviderCapabilities
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.model.SessionMetrics
import com.tether.app.protocol.model.SessionView
import com.tether.app.protocol.model.UsageWindow
import com.tether.app.protocol.model.WorktreeInfo
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.ui.chat.collectSubagentRuns
import com.tether.app.ui.statusline.ReadingEnv
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.ZoneOffset
import java.util.Locale

/**
 * T9.1: the inspector's seeded states — a sparse session (no telemetry yet) and a populated one
 * with every section filled — folded by the real reducer, on a frozen clock.
 */
object InspectorBoards {
    const val NOW: Long = 1_790_078_400_000
    val env: ReadingEnv = ReadingEnv(NOW.toDouble(), Locale.US, ZoneOffset.UTC)
    const val MIN = 60_000L
    const val DAY = 24 * 60 * MIN

    fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun e(type: String, turnId: String?, seq: Long, json: String = "{}"): AgentEvent =
        if (turnId == null) {
            evNullTurn(type, seq = seq, ts = NOW) { obj(json).forEach { (k, v) -> put(k, v) } }
        } else {
            ev(type, turnId, seq = seq, ts = NOW) { obj(json).forEach { (k, v) -> put(k, v) } }
        }

    val providers = listOf(
        ProviderInfo("claude", "Claude Code", "C", true),
        ProviderInfo("codex", "Codex", "X", true),
        ProviderInfo("acp", "ACP agent", "A", true, ProviderCapabilities(interactiveApprovals = true, plans = true)),
    )

    fun session(
        provider: String = "claude",
        status: String = "active",
        metrics: SessionMetrics? = null,
        worktree: WorktreeInfo? = null,
        model: String? = null,
        engineGeneration: String? = null,
        acpAgentId: String? = null,
        cwd: String = "/workspace/tether",
    ) = AgentSession(
        id = "s1",
        provider = provider,
        engineGeneration = engineGeneration,
        name = "Inspector parity",
        cwd = cwd,
        worktree = worktree,
        status = status,
        startedAt = NOW - 30 * MIN,
        updatedAt = NOW,
        metrics = metrics,
        model = model,
        acpAgentId = acpAgentId,
    )

    /** A session that has not answered yet: no metrics, no projection. */
    val sparse: AgentSession = session(status = "ready")

    val fullMetrics = SessionMetrics(
        model = "claude-opus-5-5",
        effort = "high",
        totalTokens = 1_284_000,
        contextWindow = 1_000_000,
        contextPercent = 42.4,
        contextTokens = 424_000,
        fiveHour = UsageWindow(38.0, 300, NOW + 95 * MIN),
        weekly = UsageWindow(81.0, 10_080, NOW + 2 * DAY + 3 * 60 * MIN),
        fable = UsageWindow(93.0, 10_080, null),
        gitBranch = "feature/inspector",
        gitAhead = 2,
        gitBehind = 0,
        cacheReadInputTokens = 1_100_000,
        cacheMissInputTokens = 184_000,
        accountEmail = "operator@example.test",
        accountOrganization = "Example Org",
        claudeResetGrants = obj(
            """{"eligible":true,"atLimit":false,"exhausted":[],"availableCount":1,
               "grants":[{"id":"g1","resetsTotal":2,"resetsLeft":1,"endsAt":${NOW + 26 * DAY},"clears":["five_hour"],
                 "paused":false,"usableNow":true,"useRequiresLimit":true}]}""",
        ),
    )

    val fullWorktree = WorktreeInfo(
        path = "/workspace/tether/.tether/worktrees/inspector",
        branch = "tether/inspector",
        status = "active",
        mode = "branch-off",
        baseRef = "main",
        setupStatus = "failed",
        configWarnings = Json.parseToJsonElement("""["scripts.dev: expected a string"]"""),
    )

    val full: AgentSession = session(metrics = fullMetrics, worktree = fullWorktree, model = "claude-opus-5-5")

    val fullState: SessionView by lazy {
        SessionView(
            foldTree(
                freshTree(),
                e("turn_started", "t1", 1),
                e("native_session_id", "t1", 2, """{"nativeSessionId":"n1","cliVersion":"2.3.1","cliCapabilities":["interrupt","set_model"],
                    "cliInventory":{"commands":[{"name":"review"},{"name":"compact"}],"tools":["Bash","Read","Edit"],"mcpServers":[]}}"""),
                e("todo_updated", null, 3, """{"items":[{"content":"Port the inspector","status":"in_progress","activeForm":"Porting the inspector"},
                    {"content":"Record goldens","status":"completed","activeForm":"Recording goldens"}]}"""),
                e("tool_start", "t1", 4, """{"toolId":"u1","name":"Task","input":{"description":"Audit the parser","subagent_type":"code-reviewer","prompt":"Audit it"}}"""),
                e("tool_end", "t1", 5, """{"toolId":"u1","output":"done","isError":false}"""),
                e("usage", "t1", 6, """{"model":"claude-opus-5-5","perTurnTokens":48200,"modelUsages":[
                    {"model":"claude-opus-5-5","provider":"anthropic","inputTokens":41000,"outputTokens":3200,"cacheReadInputTokens":38000,"cacheCreationInputTokens":1200,"webSearchRequests":0,"contextWindow":1000000,"maxOutputTokens":64000},
                    {"model":"claude-haiku-4-5-20251001","canonicalModel":"claude-haiku-4-5","inputTokens":4000}]}"""),
                e("mcp_health_updated", null, 7, """{"name":"linear","status":"ready"}"""),
                e("mcp_health_updated", null, 8, """{"name":"github","status":"needs-auth","error":"Sign in to GitHub to use this server."}"""),
            ),
        )
    }

    val fullReplies = InspectorReplies(
        worktreeDiff = obj(
            """{"branch":"tether/inspector","baseRef":"main","baseCommit":"c0ffee","head":"beef","commitsAhead":2,
               "committed":[{"path":"feature/shell/Inspector.kt","status":"A"},{"path":"docs/parity/TRACKER.md","status":"M"}],
               "uncommitted":[{"path":"feature/shell/Inspector.kt","status":" M"}]}""",
        ),
        worktreeScripts = obj(
            """{"sessionId":"s1","worktreePath":"/w","branch":"tether/inspector","setupStatus":"ok","setupLog":[],"configWarnings":[],
               "scripts":[
                 {"name":"dev","type":"service","command":"npm run dev","status":"running","port":5173,"exitCode":null,"startedAt":1,"endedAt":null,"error":null,
                  "proxyHost":"dev--inspector.example.test","proxyUrl":"https://dev--inspector.example.test/","proxyPath":null,"proxyAuthUrl":"https://console.example.test/h/1"},
                 {"name":"test","type":"script","command":"npm test","status":"failed","port":null,"exitCode":1,"startedAt":1,"endedAt":2,"error":"1 test failed",
                  "proxyHost":null,"proxyUrl":null,"proxyPath":null}]}""",
        ),
        changeRequest = ChangeRequestReading(
            obj("""{"number":12,"url":"https://example.test/pr/12","state":"OPEN","headRefOid":null,"mergeable":"MERGEABLE","reviewDecision":"APPROVED","mergedAt":null,"headRefName":null,"baseRefName":"main","isDraft":false}"""),
            unknown = false,
        ),
    )

    fun model(
        session: AgentSession,
        state: SessionView? = null,
        replies: InspectorReplies = InspectorReplies(),
        selectedRunId: String? = null,
    ): InspectorModel = inspectorModel(session, providers, state, collectSubagentRuns(state?.obj), selectedRunId, replies, env)

    val sparseModel: InspectorModel get() = model(sparse)
    val fullModel: InspectorModel get() = model(full, fullState, fullReplies)
}
