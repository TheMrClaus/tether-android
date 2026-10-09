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

    /** T15.7: the paired server's canonical origin (`serverOrigin`), what a service's Open link resolves against. */
    const val ORIGIN = "https://console.example.test:443"

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
                  "proxyHost":"dev--inspector.example.test","proxyUrl":"https://dev--inspector.example.test/","proxyPath":null,"proxyAuthUrl":"/api/worktree/open?session=s1&script=dev","proxyUnavailable":null},
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
        serverOrigin: String? = ORIGIN,
        metadataGenerationEnabled: Boolean = false,
    ): InspectorModel = inspectorModel(session, providers, state, collectSubagentRuns(state?.obj), selectedRunId, replies, env, serverOrigin, metadataGenerationEnabled)

    val sparseModel: InspectorModel get() = model(sparse)
    val fullModel: InspectorModel get() = model(full, fullState, fullReplies)

    /**
     * ta-coik.10: the web reference's own fixture (tether 90fbb9f tests/telemetry-panel.spec.ts),
     * the data behind the design/telemetry-panel PNGs — a live Claude session with ten subagents (two
     * in the background), five MCP servers, a 1M window and v138 declared defaults — folded by the
     * real reducer on the frozen clock, in the spec's four variants.
     */
    object Reference {
        enum class Variant(val id: String) { Full("full"), Empty("empty"), Attention("attention"), Selected("selected") }

        private const val HOUR = 60 * MIN

        val metrics = SessionMetrics(
            model = "claude-opus-5-5",
            effort = "medium",
            totalTokens = 1_100_000_000,
            cacheReadInputTokens = 1_078_600_000,
            cacheMissInputTokens = 21_400_000,
            contextPercent = 19.0,
            contextTokens = 188_000,
            contextWindow = 1_000_000,
            fiveHour = UsageWindow(5.0, 300, NOW + (4 * 60 + 15) * MIN),
            weekly = UsageWindow(25.0, 10_080, NOW + (5 * 24 + 1) * HOUR),
            gitBranch = "main",
            gitAhead = 0,
            gitBehind = 0,
            accountEmail = "operator@example.com",
            accountOrganization = "Team 4",
            claudeResetGrants = obj(
                """{"eligible":true,"atLimit":false,"exhausted":[],"availableCount":1,
                   "grants":[{"id":"g1","resetsTotal":1,"resetsLeft":1,"endsAt":${NOW + 21 * DAY},"clears":["weekly"],
                     "paused":false,"usableNow":false,"useRequiresLimit":true}]}""",
            ),
            subagentDefaults = obj(
                """{"executor":{"model":"claude-opus-5-5","effort":"medium"},"verifier":{"model":"claude-opus-5-5","effort":"low"},
                   "security-executor":{"model":"claude-opus-5-5","effort":"high"},"security-reviewer":{"model":"claude-opus-5-5","effort":"high"}}""",
            ),
        )

        val attentionMetrics = metrics.copy(
            contextPercent = 81.0,
            contextTokens = 810_000,
            fiveHour = UsageWindow(93.0, 300, NOW + 38 * MIN),
            weekly = UsageWindow(77.0, 10_080, NOW + 2 * DAY),
        )

        fun session(metrics: SessionMetrics?) = AgentSession(
            id = "panel-fixture",
            provider = "claude",
            name = "Tether Android native parity",
            cwd = "/workspace/tether",
            status = "ready",
            startedAt = NOW - 3 * HOUR,
            updatedAt = NOW,
            metrics = metrics,
            model = "claude-opus-5-5",
        )

        /** description, subagent_type, tokens, background, Agent-call effort. */
        private val runs = listOf(
            listOf("Maker: ta-895 port", "security-executor", 63_200_000L, true, null),
            listOf("Verify ta-895", "verifier", 3_600_000L, false, null),
            listOf("Security review ta-895", "security-reviewer", 3_600_000L, false, null),
            listOf("Maker: ta-ceo defaults", "executor", 25_200_000L, false, null),
            listOf("Verify ta-ceo", "verifier", 4_700_000L, false, null),
            listOf("Maker: ta-ccu tokens", "executor", 18_600_000L, false, null),
            listOf("Maker: ta-lx3 tether", "executor", 9_400_000L, true, "high"),
            listOf("Maker: ta-3uk hello", "executor", 2_300_000L, false, null),
            listOf("Verify ta-ccu", "verifier", 3_300_000L, false, null),
            listOf("Verify ta-3uk", "verifier", 1_400_000L, false, null),
        )

        private val MCP = listOf("blender", "claude.ai Claude Docs", "elevenlabs", "tether", "unity")

        private fun events(attention: Boolean): List<AgentEvent> = buildList {
            var seq = 0L
            fun add(type: String, turnId: String?, json: String = "{}") = add(e(type, turnId, ++seq, json))
            add("turn_started", "t1")
            add(
                "native_session_id", "t1",
                """{"nativeSessionId":"fixture-native","cliVersion":"2.1.284",
                   "cliCapabilities":["interrupt_receipt_v1","interrupt_cancel_queued_v1","msg_lifecycle_v1","mcp_read_resource_v1","mcp_tool_ui_meta_v1"],
                   "cliInventory":{"commands":[${(listOf("github-task", "impeccable", "native-orchestration", "relay", "relay-advisor", "relay-committee", "relay-handoff", "relay-help") + (0 until 47).map { "cmd-$it" }).joinToString(",") { """{"name":"$it","description":""}""" }}],
                   "tools":[${(listOf("Task", "AskUserQuestion", "Bash", "CronCreate", "CronDelete", "CronList", "Edit", "EnterPlanMode") + (0 until 152).map { "tool-$it" }).joinToString(",") { "\"$it\"" }}],"mcpServers":[]}}""",
            )
            runs.forEachIndexed { index, (description, type, tokens, background, effort) ->
                val toolId = "toolu_$index"
                val input = buildString {
                    append("""{"description":"$description","subagent_type":"$type"""")
                    if (background == true) append(""","run_in_background":true""")
                    if (effort != null) append(""","effort":"$effort"""")
                    append("}")
                }
                add("tool_start", "t1", """{"toolId":"$toolId","name":"Agent","input":$input}""")
                add(
                    "subagent_message", "t1",
                    """{"parentToolUseId":"$toolId","items":[{"key":"m$index","kind":"message","text":"…"}],
                       "usage":{"model":"claude-opus-5-5","inputTokens":2000,"outputTokens":8000,"cacheReadInputTokens":${(tokens as Long) - 12_000},"cacheCreationInputTokens":2000}}""",
                )
                if (background != true) add("tool_end", "t1", """{"toolId":"$toolId","output":"done","isError":false}""")
            }
            add(
                "usage", "t1",
                """{"perTurnTokens":79900000,"model":"claude-opus-5-5","modelUsages":[
                   {"model":"claude-opus-5-5","canonicalModel":"claude-opus-5-5","provider":"firstParty","inputTokens":256,"outputTokens":65516,"cacheReadInputTokens":10562235,"cacheCreationInputTokens":175644,"webSearchRequests":0,"contextWindow":1000000,"maxOutputTokens":128000},
                   {"model":"claude-opus-5-5[1m]","canonicalModel":"claude-opus-5-5","provider":"firstParty","inputTokens":1378,"outputTokens":400310,"cacheReadInputTokens":65213194,"cacheCreationInputTokens":1082424,"webSearchRequests":0,"contextWindow":1000000,"maxOutputTokens":128000}]}""",
            )
            if (attention) add("rate_limit", "t1", """{"status":"allowed_warning","limitType":"five_hour","resetsAt":${NOW + 38 * MIN}}""")
            add("turn_end", "t1", """{"outcome":"ok"}""")
            add(
                "todo_updated", null,
                """{"items":[{"content":"a","status":"completed","activeForm":"A"},{"content":"b","status":"completed","activeForm":"B"},
                   {"content":"c","status":"in_progress","activeForm":"Verifying the Android parity slice"},
                   {"content":"d","status":"pending","activeForm":"D"},{"content":"e","status":"pending","activeForm":"E"}]}""",
            )
            MCP.forEach { name ->
                if (attention && name == "unity") {
                    add("mcp_health_updated", null, """{"name":"unity","status":"failed","error":"The Unity editor is not running, so the bridge could not connect."}""")
                } else {
                    add("mcp_health_updated", null, """{"name":"$name","status":"ready"}""")
                }
            }
        }

        val state: SessionView by lazy { SessionView(foldTree(freshTree(), *events(attention = false).toTypedArray())) }
        val attentionState: SessionView by lazy { SessionView(foldTree(freshTree(), *events(attention = true).toTypedArray())) }

        val replies = InspectorReplies(worktreeDiff = obj("""{"baseRef":"origin/main","commitsAhead":0,"committed":[],"uncommitted":[]}"""))

        val providers = listOf(ProviderInfo("claude", "Claude Code", "C", true))

        fun stateOf(variant: Variant): SessionView? = when (variant) {
            Variant.Empty -> null
            Variant.Attention -> attentionState
            else -> state
        }

        fun model(variant: Variant): InspectorModel {
            val st = stateOf(variant)
            val runs = collectSubagentRuns(st?.obj)
            val session = when (variant) {
                Variant.Empty -> session(null)
                Variant.Attention -> session(attentionMetrics)
                else -> session(metrics)
            }
            val selected = if (variant == Variant.Selected) runs.first().runId else null
            return inspectorModel(session, providers, st, runs, selected, replies, env, ORIGIN)
        }
    }
}
