package com.tether.app.ui.inspector

import com.tether.app.client.ChangeRequestReading
import com.tether.app.protocol.model.SessionMetrics
import com.tether.app.protocol.model.UsageWindow
import com.tether.app.protocol.model.WorktreeInfo
import com.tether.app.ui.inspector.InspectorBoards.MIN
import com.tether.app.ui.inspector.InspectorBoards.NOW
import com.tether.app.ui.inspector.InspectorBoards.obj
import com.tether.app.ui.text.SafeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T9.1 / ta-coik.10: projection → band model (tether 90fbb9f inspector.tsx, the f4c4133 redesign),
 * empty, partial, full, the web reference's own fixture, and hostile text.
 */
class InspectorModelTest {

    private fun InspectorModel.row(label: String): SpecRow? = runtime.rows.firstOrNull { it.label == label }
    private fun InspectorModel.labels(): List<String> = runtime.rows.map { it.label }

    // ---- empty ------------------------------------------------------------------------------

    @Test
    fun anEmptySessionShowsTheHeaderAndTheHonestEmptyContextBandOnly() {
        val m = InspectorBoards.sparseModel
        assertEquals("Claude Code", m.identity.providerLabel.text)
        assertEquals("Ready", m.identity.statusText.text)
        assertEquals("Not reported yet", m.header.model.plain())
        assertEquals("—", m.header.effort.text)
        assertNull(m.header.account)
        assertNull(m.header.task)
        assertFalse(m.attention.any)
        assertEquals(ContextBand(aside = "Waiting", awaiting = true, gauge = null, noWindow = false), m.context)
        assertNull("no metrics: the Limits band is omitted", m.limits)
        assertNull(m.subagents)
        assertNull(m.tokens)
        assertTrue(m.runs.isEmpty())
        assertNull(m.repository)
        assertNull(m.services)
        assertFalse("no projection, no MCP card", m.mcpHealth)
        assertTrue(m.codexNotices.isEmpty())
        assertFalse(m.sessionDivider)
        assertNull(m.runtime.cli)
        assertTrue(m.labels().isEmpty())
        assertNull(m.acpCapabilities)
    }

    @Test
    fun anUnknownProviderAndStatusFallBackToTheirCleanedValues() {
        val m = InspectorBoards.model(InspectorBoards.session(provider = "new‮engine", status = "sleeping"))
        assertEquals("newengine", m.identity.providerLabel.text)
        assertEquals(Rule.Label, m.identity.providerLabel.rule)
        assertEquals("sleeping", m.identity.statusText.text)
    }

    // ---- partial ----------------------------------------------------------------------------

    @Test
    fun aPartialSessionShowsOnlyWhatWasReported() {
        val metrics = SessionMetrics(totalTokens = 12_500, contextWindow = 200_000, fiveHour = UsageWindow(12.0, 300, null))
        val m = InspectorBoards.model(InspectorBoards.session(metrics = metrics))
        // No context meter: the band says so, and the window size moves into the Tokens ledger.
        assertEquals(ContextBand("Live", awaiting = false, gauge = null, noWindow = true), m.context)
        assertEquals(
            listOf(LedgerRow("Tokens", "12.5K", note = "Current session"), LedgerRow("Context window", "200K")),
            m.tokens!!.rows,
        )
        assertTrue(m.tokens!!.perModel.isEmpty())
        val limits = m.limits!!
        assertEquals(listOf(GaugeRow("5 hour", 12, "12%", "Reset time unavailable")), limits.gauges)
        assertFalse(limits.noReading)
        assertNull(limits.resetGrants)
        assertNull("no branch, no diff, no PR: no repository panel", m.repository)
    }

    /** #233 (0b4d91f): metrics but no window, no grants, no credits: the neutral sentence. */
    @Test
    fun limitsWithNoReadingSayItNeutrally() {
        val m = InspectorBoards.model(InspectorBoards.session(metrics = SessionMetrics(totalTokens = 1)))
        assertTrue(m.limits!!.gauges.isEmpty())
        assertTrue(m.limits!!.noReading)
        // An idle Claude session's served windows fill the band like a live one's.
        val idle = InspectorBoards.model(InspectorBoards.session(status = "idle", metrics = SessionMetrics(weekly = UsageWindow(40.0, 10_080, null))))
        assertEquals(listOf("Weekly"), idle.limits!!.gauges.map { it.label })
        assertFalse(idle.limits!!.noReading)
    }

    @Test
    fun aMissingOptionalFieldOmitsItsRowOrNote() {
        // A worktree with no v98 fields (legacy manifest): no base note, no setup row, no config rows.
        val legacy = WorktreeInfo(path = "/w/.t/x", branch = "tether/x", status = "retained")
        val m = InspectorBoards.model(InspectorBoards.session(worktree = legacy, metrics = SessionMetrics(effort = "low")))
        assertEquals(listOf("Worktree", "Branch"), m.labels())
        assertTrue(m.row("Branch")!!.notes.isEmpty())
        assertEquals("retained", m.row("Worktree")!!.value.plain())
        assertTrue(m.row("Worktree")!!.capitalize)
        assertEquals("Low", m.header.effort.text)
        // No accountEmail: no account even for Claude; no cliVersion: no CLI row and no summary.
        assertNull(m.header.account)
        assertNull(m.row("CLI"))
        assertNull(m.runtime.cli)
        // The worktree's own branch is the repository branch when git reported none.
        assertEquals("tether/x", m.repository!!.branch!!.text)
        assertNull(m.repository!!.divergence)
    }

    // ---- full -------------------------------------------------------------------------------

    @Test
    fun aFullSessionFillsEveryBandInTheWebsOrder() {
        val m = InspectorBoards.fullModel
        assertEquals("claude-opus-5-5", m.header.model.plain())
        assertEquals(Rule.Line, m.header.model.single().rule)
        assertEquals("High", m.header.effort.text)
        assertEquals("operator@example.test", m.header.account!!.text)
        assertEquals("Example Org", m.header.organization!!.text)
        assertEquals("Porting the inspector", m.header.task!!.text)
        assertEquals("1 of 2 complete", m.header.taskProgress)

        // The needs-auth MCP server and the failed setup hook are lifted into the attention strip.
        assertEquals(Attention(wrapUp = null, rateLimit = null, mcpProblem = "1 needs sign-in", setupFailed = true), m.attention)

        assertEquals("Live", m.context.aside)
        assertEquals(GaugeRow("Used", 42, "42%", "424K of 1M tokens"), m.context.gauge)

        val limits = m.limits!!
        assertEquals(listOf("5 hour", "Weekly", "Fable"), limits.gauges.map { it.label })
        assertEquals(GaugeRow("5 hour", 38, "38%", "resets in 1h 35m"), limits.gauges[0])
        assertEquals("93%", limits.gauges[2].value)
        assertEquals("1 reset left · expires in 26 d" to "Not at a limit · use a reset from Usage", limits.resetGrants)
        assertNull("banked resets are Codex's", limits.bankedResets)
        assertFalse(limits.noReading)

        val band = m.subagents!!
        assertEquals("1 total", band.aside)
        val run = band.rows.single()
        assertEquals("Audit the parser", run.title.text)
        assertEquals("done", run.statusText.text)
        assertNull(run.tokens)
        assertNull("no served model, no request, no declaration", run.model)
        assertNull(run.effort)
        assertFalse(band.moreOpen)
        assertNull(band.partialNote)
        assertNull(m.runUsage)

        val tokens = m.tokens!!
        assertEquals(
            listOf(
                LedgerRow("Processed", "1.3M", note = "Current session"),
                LedgerRow("Cache reads", "1.1M", sub = true),
                LedgerRow("Fresh input", "184K", sub = true),
                LedgerRow("Last turn", "48.2K", note = "Whole-tree tokens"),
            ),
            tokens.rows,
        )
        assertEquals("2 models", tokens.perModelCount)
        val opus = tokens.perModel[0]
        assertEquals("claude-opus-5-5", opus.identity.text)
        assertEquals(Rule.Line, opus.identity.rule)
        assertEquals(listOf("Main session"), opus.contributors.map { it.text })
        assertEquals("anthropic", opus.provider.plain())
        assertEquals(
            listOf("Input" to "41,000", "Output" to "3,200", "Cache read" to "38,000", "Cache write" to "1,200", "Web searches" to "0", "Window" to "1,000,000", "Max output" to "64,000"),
            opus.ledger.map { it.label to it.value },
        )
        val haiku = tokens.perModel[1]
        assertEquals("claude-haiku-4-5", haiku.identity.text)
        assertEquals("Provider not reported · raw claude-haiku-4-5-20251001", haiku.provider.plain())
        assertEquals("—", haiku.ledger.first { it.label == "Web searches" }.value)
        // The run of this turn served no model the entry names: an unidentified sub-agent.
        assertEquals(listOf("Unidentified sub-agent"), haiku.contributors.map { it.text })

        val repo = m.repository!!
        assertEquals("feature/inspector", repo.branch!!.text)
        assertEquals("2 commits ahead upstream", repo.divergence)
        assertEquals(PullRequestLine("Pull request #12", "Open · Approved", "https://example.test/pr/12"), repo.pullRequest)
        assertEquals("2 files", repo.changesCount)

        assertTrue(m.mcpHealth)
        val services = m.services!!
        assertEquals("2 declared", services.count)
        assertEquals("Running · :5173", services.scripts[0].status)
        assertEquals("dev--inspector.example.test", services.scripts[0].address!!.text)
        assertEquals("Failed · exit 1", services.scripts[1].status)
        assertEquals("1 test failed", services.scripts[1].error!!.text)
        assertNull(services.setup)

        assertEquals("2.3.1", m.runtime.cli!!.text)
        assertEquals(listOf("Worktree", "Branch", "Setup", "Config", "CLI", "Inventory"), m.labels())
        assertEquals("Branched off main", m.row("Branch")!!.notes.single().line.plain())
        assertEquals("Failed", m.row("Setup")!!.value.plain())
        assertEquals("scripts.dev: expected a string", m.row("Config")!!.notes.single().line.plain())
        assertEquals("2 protocol capabilities: interrupt, set_model", m.row("CLI")!!.notes.single().line.plain())
        val inventory = m.row("Inventory")!!
        assertEquals("2 commands · 3 tools", inventory.value.plain())
        assertEquals("Commands: /review, /compact · Tether support is decided separately", inventory.names[0].plain())
        assertEquals("Tools: Bash, Read, Edit", inventory.names[1].plain())
    }

    @Test
    fun aSelectedRunGetsItsBandAndTheSessionBoundaryWhileHeadroomStaysSessionScoped() {
        val run = InspectorBoards.fullModel.runs.single()
        val m = InspectorBoards.model(InspectorBoards.full, InspectorBoards.fullState, InspectorBoards.fullReplies, selectedRunId = run.runId)
        val u = m.runUsage!!
        assertEquals("Audit the parser", u.title.text)
        assertEquals("done", u.status)
        assertEquals(listOf(LedgerRow("Tokens", "—", note = "Not captured"), LedgerRow("Steps", "0", note = "Recorded")), u.ledger)
        assertNotNull("an uncaptured run says why", u.gap)
        assertEquals(listOf("Harness", "Agent type"), u.specs.map { it.label })
        assertTrue(m.sessionDivider)
        assertTrue(m.subagents!!.rows.single().active)
        // The context and account windows stay session-scoped, and the aside says so.
        assertEquals("Session · Live", m.context.aside)
        assertEquals(3, m.limits!!.gauges.size)
        // A selection that no longer resolves degrades to session scope.
        val stale = InspectorBoards.model(InspectorBoards.full, InspectorBoards.fullState, selectedRunId = "gone")
        assertNull(stale.runUsage)
        assertFalse(stale.sessionDivider)
        assertEquals("Live", stale.context.aside)
    }

    // ---- the web reference fixture (the design/telemetry-panel PNGs) ----------------------------

    @Test
    fun theReferenceSessionReadsLikeTheWebsPhoneSheet() {
        val m = InspectorBoards.Reference.model(InspectorBoards.Reference.Variant.Full)
        assertEquals("claude-opus-5-5", m.header.model.plain())
        assertEquals("Medium", m.header.effort.text)
        assertEquals("operator@example.com", m.header.account!!.text)
        assertEquals("Team 4", m.header.organization!!.text)
        assertEquals("Verifying the Android parity slice", m.header.task!!.text)
        assertEquals("2 of 5 complete", m.header.taskProgress)
        assertFalse(m.attention.any)
        assertEquals(GaugeRow("Used", 19, "19%", "188K of 1M tokens"), m.context.gauge)
        assertEquals(
            listOf(GaugeRow("5 hour", 5, "5%", "resets in 4h 15m"), GaugeRow("Weekly", 25, "25%", "resets in 5d 1h")),
            m.limits!!.gauges,
        )
        assertEquals("1 reset left · expires in 21 d", m.limits!!.resetGrants!!.first)

        val band = m.subagents!!
        assertEquals("10 total · 2 running", band.aside)
        assertEquals(6, band.head.size)
        assertEquals(4, band.rest.size)
        fun line(row: RunRow) = listOf(
            row.title.text,
            row.statusText.text,
            row.tokens ?: "no usage",
            row.model?.let { "${it.text.text} ${it.note}".trim() } ?: "model not captured",
            row.effort?.let { "${it.text.text} ${it.note}" } ?: "effort not set",
        )
        assertEquals(listOf("Maker: ta-895 port", "running", "63.2M tok", "claude-opus-5-5", "high declared"), line(band.rows[0]))
        assertEquals(listOf("Verify ta-895", "done", "3.6M tok", "claude-opus-5-5", "low declared"), line(band.rows[1]))
        assertEquals(listOf("Maker: ta-ceo defaults", "done", "25.2M tok", "claude-opus-5-5", "medium declared"), line(band.rows[3]))
        // An explicit Agent-call effort beats the declaration and says "asked".
        assertEquals(listOf("Maker: ta-lx3 tether", "running", "9.4M tok", "claude-opus-5-5", "high asked"), line(band.rows[6]))
        assertEquals(RunSetting.SERVED, band.rows[0].model!!.source)
        assertNull("every run's tokens were captured", band.partialNote)

        assertEquals(
            listOf(
                LedgerRow("Processed", "1.1B", note = "Current session"),
                LedgerRow("Cache reads", "1.1B", sub = true),
                LedgerRow("Fresh input", "21.4M", sub = true),
                LedgerRow("Last turn", "79.9M", note = "Whole-tree tokens"),
            ),
            m.tokens!!.rows,
        )
        assertEquals("2 models", m.tokens!!.perModelCount)
        assertEquals("main", m.repository!!.branch!!.text)
        assertEquals("In sync with upstream", m.repository!!.divergence)
        assertEquals("0 files", m.repository!!.changesCount)
        assertEquals("2.1.284", m.runtime.cli!!.text)
    }

    @Test
    fun theReferenceAttentionVariantLiftsTheRateLimitAndTheFailedServer() {
        val m = InspectorBoards.Reference.model(InspectorBoards.Reference.Variant.Attention)
        assertEquals(
            Attention(
                wrapUp = null,
                rateLimit = RateLimitAlert("Approaching the rate limit (five hour) — resets in 38m", danger = false),
                mcpProblem = "1 failed",
                setupFailed = false,
            ),
            m.attention,
        )
        assertEquals(GaugeRow("Used", 81, "81%", "810K of 1M tokens"), m.context.gauge)
        assertEquals(listOf(93, 77), m.limits!!.gauges.map { it.percent })
    }

    @Test
    fun theReferenceSelectedVariantPutsTheRunsDeclaredSettingsInItsBand() {
        val m = InspectorBoards.Reference.model(InspectorBoards.Reference.Variant.Selected)
        val u = m.runUsage!!
        assertEquals("Maker: ta-895 port", u.title.text)
        assertEquals("running", u.status)
        assertEquals(listOf(LedgerRow("Tokens", "63.2M", note = "This sub-agent only"), LedgerRow("Steps", "1", note = "Recorded")), u.ledger)
        assertNull(u.gap)
        assertEquals(listOf("Harness", "Agent type", "Model", "Effort"), u.specs.map { it.label })
        assertEquals("claude", u.specs[0].value.plain())
        assertEquals("security-executor", u.specs[1].value.plain())
        assertEquals("claude-opus-5-5", u.specs[2].value.plain())
        assertTrue(u.specs[2].notes.isEmpty())
        assertEquals("high", u.specs[3].value.plain())
        assertEquals("Declared by the security-executor agent type", u.specs[3].notes.single().line.plain())
        assertEquals("Session · Live", m.context.aside)
        assertTrue(m.subagents!!.rows[0].active)
    }

    @Test
    fun aRunBehindShowMoreOpensThatDisclosureWhenSelected() {
        val runs = com.tether.app.ui.chat.collectSubagentRuns(InspectorBoards.Reference.state.obj)
        val m = inspectorModel(
            InspectorBoards.Reference.session(InspectorBoards.Reference.metrics), InspectorBoards.Reference.providers,
            InspectorBoards.Reference.state, runs, runs[8].runId, InspectorReplies(), InspectorBoards.env,
        )
        assertTrue(m.subagents!!.moreOpen)
        assertFalse(InspectorBoards.Reference.model(InspectorBoards.Reference.Variant.Selected).subagents!!.moreOpen)
    }

    /** v138 provenance: served > requested > declared for the model; requested > declared for the effort. */
    @Test
    fun aRunsModelAndEffortCarryTheirProvenance() {
        val defaults = com.tether.app.protocol.model.SubagentDefault("decl-model", "decl-effort")
        val base = InspectorBoards.Reference.model(InspectorBoards.Reference.Variant.Full).runs[1]
        fun reading(run: com.tether.app.ui.chat.SubagentRun, d: com.tether.app.protocol.model.SubagentDefault?) =
            runModelReading(run, d).let { (m, e) -> listOf(m?.text?.text, m?.source, e?.text?.text, e?.source) }
        assertEquals(listOf("claude-opus-5-5", "served", "decl-effort", "declared"), reading(base, defaults))
        val unserved = base.copy(usage = null, totalTokens = null)
        assertEquals(listOf("decl-model", "declared", "decl-effort", "declared"), reading(unserved, defaults))
        assertEquals(listOf("asked-model", "requested", "asked-effort", "requested"), reading(unserved.copy(requestedModel = "asked-model", requestedEffort = "asked-effort"), defaults))
        assertEquals(listOf(null, null, null, null), reading(unserved, null))
    }

    /** Declared defaults are Claude's: another provider's metrics never label a run "declared". */
    @Test
    fun declaredDefaultsApplyToClaudeOnly() {
        val st = InspectorBoards.Reference.state
        val runs = com.tether.app.ui.chat.collectSubagentRuns(st.obj)
        val codex = InspectorBoards.Reference.session(InspectorBoards.Reference.metrics).copy(provider = "codex")
        val m = inspectorModel(codex, InspectorBoards.Reference.providers, st, runs, null, InspectorReplies(), InspectorBoards.env)
        assertNull(m.subagents!!.rows[1].effort)
        assertEquals(RunSetting.REQUESTED, m.subagents!!.rows[6].effort!!.source)
    }

    /** An older server sends no subagentDefaults: every row says "effort not set" unless asked. */
    @Test
    fun withoutDefaultsTheEffortIsNotSet() {
        val st = InspectorBoards.Reference.state
        val runs = com.tether.app.ui.chat.collectSubagentRuns(st.obj)
        val old = InspectorBoards.Reference.session(InspectorBoards.Reference.metrics.copy(subagentDefaults = null))
        val m = inspectorModel(old, InspectorBoards.Reference.providers, st, runs, null, InspectorReplies(), InspectorBoards.env)
        assertEquals(listOf(6), m.subagents!!.rows.mapIndexedNotNull { i, r -> if (r.effort != null) i else null })
    }

    @Test
    fun aWrapUpReplacesTheRateLimitAlertWhileItCoversTheTurn() {
        val state = com.tether.app.protocol.model.SessionView(
            com.tether.app.protocol.reduce.foldTree(
                com.tether.app.protocol.reduce.freshTree(),
                com.tether.app.protocol.reduce.ev("turn_started", "t1", seq = 1, ts = NOW),
                com.tether.app.protocol.reduce.ev("rate_limit", "t1", seq = 2, ts = NOW) {
                    put("status", "rejected"); put("limitType", "five_hour"); put("resetsAt", NOW + 30 * MIN); put("grace", "wrap_up")
                },
            ),
        )
        val m = InspectorBoards.model(InspectorBoards.session(), state)
        assertNotNull(m.attention.wrapUp)
        assertNull(m.attention.rateLimit)
        // Past the reset the allowance is over and the alert expires on its own clock.
        val later = inspectorModel(InspectorBoards.session(), InspectorBoards.providers, state, emptyList(), null, InspectorReplies(), com.tether.app.ui.statusline.ReadingEnv((NOW + 31 * MIN).toDouble(), java.util.Locale.US, java.time.ZoneOffset.UTC))
        assertFalse(later.attention.any)
    }

    @Test
    fun aRejectedRateLimitIsTheDangerAlert() {
        val m = InspectorBoards.model(InspectorBoards.session(), InspectorFixtures.rateLimited())
        assertEquals(RateLimitAlert("Rate limited — requests are being rejected (seven day) — resets in 1h 35m", danger = true), m.attention.rateLimit)
    }

    @Test
    fun codexShowsItsEngineBankedResetsAndNotices() {
        val metrics = SessionMetrics(codexResetCredits = obj("""{"availableCount":2,"credits":[]}"""))
        val m = InspectorBoards.model(InspectorBoards.session(provider = "codex", engineGeneration = "codex-app-server-v2", metrics = metrics))
        assertEquals("2 available", m.limits!!.bankedResets)
        assertEquals("App server v2", m.row("Engine")!!.value.plain())
        assertEquals("Persistent, interactive Codex session", m.row("Engine")!!.notes.single().line.plain())
        // None banked: no row (present-but-empty is a real answer, not a zero to print).
        val none = InspectorBoards.model(InspectorBoards.session(provider = "codex", metrics = SessionMetrics(codexResetCredits = obj("""{"availableCount":0}"""))))
        assertNull(none.limits!!.bankedResets)
        assertEquals("Legacy exec v1", none.row("Engine")!!.value.plain())
        // A malformed summary is ignored, never a crash.
        val bad = InspectorBoards.model(InspectorBoards.session(provider = "codex", metrics = SessionMetrics(codexResetCredits = Json.parseToJsonElement("\"x\""))))
        assertNull(bad.limits!!.bankedResets)
    }

    @Test
    fun acpShowsItsAgentAndTheAdvertisedCapabilities() {
        val m = InspectorBoards.model(InspectorBoards.session(provider = "acp", acpAgentId = "gemini-cli"))
        assertEquals("gemini-cli", m.row("ACP agent")!!.value.plain())
        assertEquals("generic ACP v1 engine", m.row("ACP agent")!!.notes.single().line.plain())
        assertEquals(listOf("Approvals" to true, "Questions" to false, "Sandbox" to false, "Plans" to true, "Model / mode pickers" to false), m.acpCapabilities)
        assertEquals("—", InspectorBoards.model(InspectorBoards.session(provider = "acp")).row("ACP agent")!!.value.plain())
    }

    @Test
    fun theChangeRequestLineSaysWhatItKnows() {
        val base = InspectorBoards.session(metrics = SessionMetrics(gitBranch = "b"))
        fun pr(reading: ChangeRequestReading) = InspectorBoards.model(base, replies = InspectorReplies(changeRequest = reading)).repository!!.pullRequest
        assertEquals(PullRequestLine("PR status unavailable", null), pr(ChangeRequestReading(null, unknown = true)))
        assertEquals(PullRequestLine("No pull request", null), pr(ChangeRequestReading(null, unknown = false)))
        assertEquals(
            PullRequestLine("Pull request #3", "Open · Draft · Changes requested · Conflicts"),
            pr(ChangeRequestReading(obj("""{"number":3,"state":"OPEN","isDraft":true,"reviewDecision":"CHANGES_REQUESTED","mergeable":"CONFLICTING"}"""), false)),
        )
        // ta-dl4: an unknown decision is dropped, as on the web (CR_REVIEW[x] is undefined, filtered out).
        assertEquals(
            PullRequestLine("Pull request #4", "Open"),
            pr(ChangeRequestReading(obj("""{"number":4,"state":"OPEN","isDraft":false,"reviewDecision":"FOO_BAR","mergeable":"MERGEABLE"}"""), false)),
        )
    }

    /** ta-coik.14 (repository-panel.tsx:56): the headline is a link exactly when the reply carries the pull request's address. */
    @Test
    fun thePullRequestHeadlineLinksToItsAddress() {
        val base = InspectorBoards.session(metrics = SessionMetrics(gitBranch = "b"))
        fun pr(json: String) = InspectorBoards.model(base, replies = InspectorReplies(changeRequest = ChangeRequestReading(obj(json), false))).repository!!.pullRequest!!
        assertEquals("https://github.com/o/r/pull/9", pr("""{"number":9,"url":"https://github.com/o/r/pull/9","state":"OPEN"}""").url)
        // `cr.url ? <a> : text`: null and empty are plain text.
        assertNull(pr("""{"number":9,"url":null,"state":"OPEN"}""").url)
        assertNull(pr("""{"number":9,"url":"","state":"OPEN"}""").url)
        assertNull(pr("""{"number":9,"state":"OPEN"}""").url)
        // Outside the web's link schemes it is never a link (a browser refuses a javascript: tab too).
        assertNull(pr("""{"number":9,"url":"javascript:alert(1)","state":"OPEN"}""").url)
        assertEquals("Pull request #9", pr("""{"number":9,"url":"javascript:alert(1)","state":"OPEN"}""").headline)
    }

    /**
     * ta-coik.14 (worktree-services-card.tsx:101, 110-127): Restart and Stop while a script runs or
     * starts, Run in every other state; the frames name the script exactly as declared.
     */
    @Test
    fun eachScriptOffersTheWebsKeysForItsState() {
        val states = listOf("idle", "starting", "running", "stopping", "exited", "failed")
        val entries = states.joinToString(",") { """{"name":"s-$it","type":"script","command":"x","status":"$it"}""" }
        val rows = services(obj("""{"sessionId":"s1","scripts":[$entries],"setupStatus":"ok","setupLog":[],"configWarnings":[]}"""))!!.scripts
        assertEquals(states.associate { "s-$it" to (it == "running" || it == "starting") }, rows.associate { it.scriptName to it.running })
        val rlo = "\u202e"
        val hostile = services(obj("""{"sessionId":"s1","scripts":[{"name":"dev$rlo","type":"script","command":"x","status":"idle"}]}"""))!!.scripts.single()
        assertEquals("dev\u202e", hostile.scriptName)
    }

    /** ta-coik.14 (worktree-services-card.tsx:157-163): the last `worktree-logs` reply, under the script it names. */
    @Test
    fun theLogsReplyIsKeptForTheScriptItNames() {
        val snapshot = obj("""{"sessionId":"s1","scripts":[{"name":"dev","type":"script","command":"x","status":"idle"}]}""")
        assertNull(services(snapshot)!!.logs)
        val logs = services(snapshot, logs = com.tether.app.client.WorktreeLogsReading("dev", listOf("a", "b"), 0))!!.logs!!
        assertEquals("dev", logs.name)
        assertEquals(Seg("a\nb", Rule.Code), logs.output)
        assertNull(services(snapshot, logs = com.tether.app.client.WorktreeLogsReading("dev", emptyList(), 0))!!.logs!!.output)
    }

    @Test
    fun aSnapshotContextReadingSaysSoAndIsNeverLive() {
        val metrics = SessionMetrics(contextTokens = 90_000, contextSnapshotAt = NOW - 10 * MIN)
        val c = InspectorBoards.model(InspectorBoards.session(metrics = metrics)).context
        assertEquals("Snapshot · 11:50 AM", c.aside)
        // No honest percentage: no bar, the count with its provenance.
        assertEquals(GaugeRow("Used", null, "90K", "Snapshot · 11:50 AM · window size not reported"), c.gauge)
        assertFalse(c.noWindow)
    }

    @Test
    fun theServicesCardIsOmittedWhenItWouldBeEmpty() {
        assertNull(services(obj("""{"sessionId":"s1","scripts":[],"setupStatus":"ok","setupLog":[],"configWarnings":[]}""")))
        val running = services(obj("""{"sessionId":"s1","scripts":[],"setupStatus":"running","setupLog":["a","b"],"configWarnings":[]}"""))!!
        assertEquals("none declared", running.count)
        assertFalse(running.setup!!.failed)
        assertEquals("a\nb", running.setup!!.log!!.text)
        // A running service with no address of its own says why.
        val noOrigin = services(obj("""{"sessionId":"s1","scripts":[{"name":"web","type":"service","command":"x","status":"running","proxyAuthUrl":null,"proxyUnavailable":"label-too-long"}]}"""))!!
        assertNull(noOrigin.scripts.single().address)
        assertEquals("No own address: its hostname would exceed 63 characters. Shorten the script name or slug.", noOrigin.scripts.single().unavailable)
    }

    // ---- hostile text -----------------------------------------------------------------------

    @Test
    fun hostileServerTextIsRoutedThroughTheTextRules() {
        val rlo = "‮"
        val metrics = SessionMetrics(
            gitBranch = "main${rlo}lanigiro",
            accountEmail = "admin${rlo}@evil.test",
            accountOrganization = "Org​⁦x",
        )
        val worktree = WorktreeInfo(path = "/w/${rlo}txt.exe", branch = "tether/⁧x", status = "active", notice = "Checked out${rlo} elsewhere", baseRef = "ma​in")
        val m = InspectorBoards.model(InspectorBoards.session(metrics = metrics, worktree = worktree, cwd = "/w/${rlo}cwd"))

        // Branch, path and base ref: the code rule, so every hidden code point becomes a visible token.
        val branch = m.repository!!.branch!!
        assertEquals(Rule.Line, branch.rule)
        assertTrue(SafeText.line(branch.text).contains("⟨U+202E⟩"))
        val path = m.row("Worktree")!!.notes[0].line.single()
        assertEquals(Rule.Line, path.rule)
        assertTrue(SafeText.line(path.text).contains("⟨U+202E⟩"))
        val base = m.row("Branch")!!.notes.single().line
        assertEquals(Rule.Line, base.last().rule)
        assertTrue(SafeText.line(base.last().text).contains("⟨U+200B⟩"))
        assertTrue(SafeText.line(m.row("Branch")!!.value.single().text).contains("⟨U+2067⟩"))
        // The notice is a message: the prose rule (explicit bidi controls are tokens there too).
        val notice = m.row("Worktree")!!.notes[1].line.single()
        assertEquals(Rule.Prose, notice.rule)
        assertTrue(SafeText.prose(notice.text).contains("⟨U+202E⟩"))
        // The account identity is an id: the one-line rule, so a look-alike shows what it hides.
        val account = m.header.account!!
        assertEquals(Rule.Line, account.rule)
        assertTrue(SafeText.line(account.text).contains("⟨U+202E⟩"))
        // The organisation is a name: the label rule cleans its invisibles away.
        assertEquals(Rule.Label, m.header.organization!!.rule)
        assertEquals("Orgx", m.header.organization!!.text)
    }

    @Test
    fun aLineBreakOrTabInABranchOrPathIsATokenNeverASecondRow() {
        val metrics = SessionMetrics(gitBranch = "main\nAccount: forged")
        val worktree = WorktreeInfo(path = "/w/a\tb\nc", branch = "tether/x\ty", status = "active")
        val m = InspectorBoards.model(InspectorBoards.session(metrics = metrics, worktree = worktree, cwd = "/w/\nx"))
        val branch = SafeText.line(m.repository!!.branch!!.text)
        assertTrue(branch.contains("⟨U+000A⟩"))
        assertFalse(branch.contains('\n'))
        val path = SafeText.line(m.row("Worktree")!!.notes[0].line.single().text)
        assertTrue(path.contains("⟨U+0009⟩"))
        assertTrue(path.contains("⟨U+000A⟩"))
        assertFalse(path.contains('\n') || path.contains('\t'))
        assertTrue(SafeText.line(m.row("Branch")!!.value.single().text).contains("⟨U+0009⟩"))
    }

    @Test
    fun hostileModelAndRunTextIsRuled() {
        val metrics = SessionMetrics(model = "opus‮")
        val m = InspectorBoards.model(InspectorBoards.session(metrics = metrics))
        val model = m.header.model.single()
        assertEquals(Rule.Line, model.rule)
        assertTrue(SafeText.line(model.text).contains("⟨U+202E⟩"))
    }

    @Test
    fun aHugeValueIsBoundedBeforeItIsDrawn() {
        val m = InspectorBoards.model(InspectorBoards.session(worktree = WorktreeInfo(path = "p".repeat(50_000), branch = "b", status = "active")))
        val path = m.row("Worktree")!!.notes[0].line.single().text
        assertTrue(path.length <= MAX_CODE)
        assertTrue(path.endsWith("…"))
    }
}
