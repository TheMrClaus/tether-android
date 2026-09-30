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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T9.1: projection → section model (inspector.tsx 289-735), empty, partial, full and hostile. */
class InspectorModelTest {

    private fun InspectorModel.row(label: String): SpecRow? = runtime.firstOrNull { it.label == label }
    private fun InspectorModel.labels(): List<String> = runtime.map { it.label }
    private val InspectorModel.session: SessionUsage get() = usage as SessionUsage

    // ---- empty ------------------------------------------------------------------------------

    @Test
    fun anEmptySessionShowsIdentityTheEmptyUsageAndTheTelemetryNote() {
        val m = InspectorBoards.sparseModel
        assertEquals("Claude Code", m.identity.providerLabel.text)
        assertEquals("Ready", m.identity.statusText.text)
        val u = m.session
        assertEquals("Live", u.badge)
        assertEquals("Tokens", u.totalLabel)
        assertEquals("—", u.totalValue)
        assertEquals("Current session", u.totalCaption)
        assertNull(u.lastTurn)
        assertEquals("0 subagents spawned", u.subagentsSpawned)
        assertNull(u.context)
        assertNull(u.snapshot)
        assertTrue(u.perModel.isEmpty())
        assertTrue(m.runs.isEmpty())
        assertNull(m.repository)
        assertTrue(m.limits.awaitingTelemetry)
        assertTrue(m.limits.windows.isEmpty())
        assertNull(m.services)
        assertFalse("no projection, no MCP card", m.mcpHealth)
        assertTrue(m.codexNotices.isEmpty())
        assertFalse(m.sessionDivider)
        // Only the two unconditional runtime rows.
        assertEquals(listOf("Model", "Effort"), m.labels())
        assertEquals("—", m.row("Model")!!.value.plain())
        assertEquals("—", m.row("Effort")!!.value.plain())
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
        val u = m.session
        assertEquals("Tokens", u.totalLabel)
        assertEquals("12.5K", u.totalValue)
        // No context meter: the window size moves into the totals caption.
        assertNull(u.context)
        assertEquals("200K context", u.totalCaption)
        assertEquals(listOf("5 hour"), m.limits.windows.map { it.first })
        assertEquals("Reset time unavailable", m.limits.windows.single().second.caption)
        assertEquals(12, m.limits.windows.single().second.percent)
        assertFalse(m.limits.awaitingTelemetry)
        assertNull(m.limits.resetGrants)
        assertNull("no branch, no diff, no PR: no repository panel", m.repository)
    }

    @Test
    fun aMissingOptionalFieldOmitsItsRowOrNote() {
        // A worktree with no v98 fields (legacy manifest): no base note, no setup row, no config rows.
        val legacy = WorktreeInfo(path = "/w/.t/x", branch = "tether/x", status = "retained")
        val m = InspectorBoards.model(InspectorBoards.session(worktree = legacy, metrics = SessionMetrics(effort = "low")))
        assertEquals(listOf("Worktree", "Worktree branch", "Model", "Effort"), m.labels())
        assertTrue(m.row("Worktree branch")!!.notes.isEmpty())
        assertEquals("retained", m.row("Worktree")!!.value.plain())
        assertTrue(m.row("Worktree")!!.capitalize)
        // No accountEmail: no Account row even for Claude; no cliVersion: no CLI row.
        assertNull(m.row("Account"))
        assertNull(m.row("CLI"))
        // The worktree's own branch is the repository branch when git reported none.
        assertEquals("tether/x", m.repository!!.branch!!.text)
        assertNull(m.repository!!.divergence)
    }

    // ---- full -------------------------------------------------------------------------------

    @Test
    fun aFullSessionFillsEverySectionInTheWebsOrder() {
        val m = InspectorBoards.fullModel
        val u = m.session
        assertEquals("Processed", u.totalLabel)
        assertEquals("1.3M", u.totalValue)
        assertEquals("1.1M cached · 184K fresh", u.totalCaption)
        assertEquals("48.2K", u.lastTurn)
        assertEquals("1 subagent spawned", u.subagentsSpawned)
        assertEquals(42, u.context!!.percent)
        assertEquals("424K of 1M tokens", u.context!!.caption)
        assertEquals("Per-model breakdown (2)", u.perModelSummary)
        val opus = u.perModel[0]
        assertEquals("claude-opus-5-5", opus.identity.text)
        assertEquals(Rule.Code, opus.identity.rule)
        assertEquals(listOf("Main session"), opus.contributors.map { it.text })
        assertEquals("anthropic", opus.provider.plain())
        assertEquals("41,000 in · 3,200 out", opus.figures[0])
        assertEquals("38,000 cache read · 1,200 cache write", opus.figures[1])
        assertEquals("1,000,000 context · 64,000 max output", opus.figures[3])
        val haiku = u.perModel[1]
        assertEquals("claude-haiku-4-5", haiku.identity.text)
        assertEquals("Provider not reported · raw claude-haiku-4-5-20251001", haiku.provider.plain())
        assertEquals("— web searches", haiku.figures[2])
        // The run of this turn served no model the entry names: an unidentified sub-agent.
        assertEquals(listOf("Unidentified sub-agent"), haiku.contributors.map { it.text })

        assertEquals(1, m.runs.size)
        assertEquals("Audit the parser", m.runs.single().title)

        val repo = m.repository!!
        assertEquals("feature/inspector", repo.branch!!.text)
        assertEquals("2 commits ahead upstream", repo.divergence)
        assertEquals(PullRequestLine("Pull request #12", "Open · Approved"), repo.pullRequest)
        assertEquals("2 files", repo.changesCount)

        assertEquals(listOf("5 hour", "Weekly", "Fable"), m.limits.windows.map { it.first })
        assertEquals("resets in 1h 35m", m.limits.windows[0].second.caption)
        assertEquals("1 reset left · expires in 26 d", m.limits.resetGrants!!.first)
        assertEquals("Not at a limit · use a reset from Usage", m.limits.resetGrants!!.second)
        assertNull("banked resets are Codex's", m.limits.bankedResets)

        assertTrue(m.mcpHealth)
        val services = m.services!!
        assertEquals("2 declared", services.count)
        assertEquals("Running · :5173", services.scripts[0].status)
        assertEquals("dev--inspector.example.test", services.scripts[0].address!!.text)
        assertEquals("Failed · exit 1", services.scripts[1].status)
        assertEquals("1 test failed", services.scripts[1].error!!.text)
        assertNull(services.setup)

        assertEquals(
            listOf("Current task", "Worktree", "Worktree branch", "Worktree setup", "Worktree config", "Model", "Effort", "Account", "CLI", "Inventory"),
            m.labels(),
        )
        assertEquals("Porting the inspector", m.row("Current task")!!.value.plain())
        assertEquals("1 of 2 complete", m.row("Current task")!!.notes.single().line.plain())
        assertEquals("Branched off main", m.row("Worktree branch")!!.notes.single().line.plain())
        assertEquals("Failed", m.row("Worktree setup")!!.value.plain())
        assertEquals("scripts.dev: expected a string", m.row("Worktree config")!!.notes.single().line.plain())
        assertEquals("claude-opus-5-5", m.row("Model")!!.value.plain())
        assertEquals("high", m.row("Effort")!!.value.plain())
        assertEquals("operator@example.test", m.row("Account")!!.value.plain())
        assertEquals("Example Org", m.row("Account")!!.notes.single().line.plain())
        assertEquals("2.3.1", m.row("CLI")!!.value.plain())
        assertEquals("2 protocol capabilities: interrupt, set_model", m.row("CLI")!!.notes.single().line.plain())
        val inventory = m.row("Inventory")!!
        assertEquals("2 commands · 3 tools", inventory.value.plain())
        assertEquals("Commands: /review, /compact · Tether support is decided separately", inventory.names[0].plain())
        assertEquals("Tools: Bash, Read, Edit", inventory.names[1].plain())
    }

    @Test
    fun aSelectedRunScopesUsageAndStatesTheSessionBoundary() {
        val run = InspectorBoards.fullModel.runs.single()
        val m = InspectorBoards.model(InspectorBoards.full, InspectorBoards.fullState, InspectorBoards.fullReplies, selectedRunId = run.runId)
        val u = m.usage as RunUsage
        assertEquals("Audit the parser", u.title.text)
        assertEquals("done", u.status)
        assertEquals("—", u.tokens)
        assertEquals("Not captured", u.tokensCaption)
        assertNotNull("an uncaptured run says why", u.gap)
        assertEquals(listOf("Harness", "Agent type"), u.specs.map { it.label })
        assertTrue(m.sessionDivider)
        // The account windows stay session-scoped, unconditionally.
        assertEquals(3, m.limits.windows.size)
        // A selection that no longer resolves degrades to session scope.
        val stale = InspectorBoards.model(InspectorBoards.full, InspectorBoards.fullState, selectedRunId = "gone")
        assertTrue(stale.usage is SessionUsage)
        assertFalse(stale.sessionDivider)
    }

    @Test
    fun codexShowsItsEngineBankedResetsAndNotices() {
        val metrics = SessionMetrics(codexResetCredits = obj("""{"availableCount":2,"credits":[]}"""))
        val m = InspectorBoards.model(InspectorBoards.session(provider = "codex", engineGeneration = "codex-app-server-v2", metrics = metrics))
        assertEquals("2 available", m.limits.bankedResets)
        assertEquals("App server v2", m.row("Engine")!!.value.plain())
        assertEquals("Persistent, interactive Codex session", m.row("Engine")!!.notes.single().line.plain())
        // None banked: no row (present-but-empty is a real answer, not a zero to print).
        val none = InspectorBoards.model(InspectorBoards.session(provider = "codex", metrics = SessionMetrics(codexResetCredits = obj("""{"availableCount":0}"""))))
        assertNull(none.limits.bankedResets)
        assertEquals("Legacy exec v1", none.row("Engine")!!.value.plain())
        // A malformed summary is ignored, never a crash.
        val bad = InspectorBoards.model(InspectorBoards.session(provider = "codex", metrics = SessionMetrics(codexResetCredits = Json.parseToJsonElement("\"x\""))))
        assertNull(bad.limits.bankedResets)
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
    }

    @Test
    fun aSnapshotContextReadingSaysSoAndIsNeverLive() {
        val metrics = SessionMetrics(contextTokens = 90_000, contextSnapshotAt = NOW - 10 * MIN)
        val u = InspectorBoards.model(InspectorBoards.session(metrics = metrics)).session
        assertEquals("Snapshot · 11:50 AM", u.badge)
        assertNull(u.context)
        assertEquals(90_000.0, u.snapshot!!.tokens, 0.0)
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
        assertEquals(Rule.Code, branch.rule)
        assertTrue(SafeText.code(branch.text).contains("⟨U+202E⟩"))
        val path = m.row("Worktree")!!.notes[0].line.single()
        assertEquals(Rule.Code, path.rule)
        assertTrue(SafeText.code(path.text).contains("⟨U+202E⟩"))
        val base = m.row("Worktree branch")!!.notes.single().line
        assertEquals(Rule.Code, base.last().rule)
        assertTrue(SafeText.code(base.last().text).contains("⟨U+200B⟩"))
        assertTrue(SafeText.code(m.row("Worktree branch")!!.value.single().text).contains("⟨U+2067⟩"))
        // The notice is a message: the prose rule (explicit bidi controls are tokens there too).
        val notice = m.row("Worktree")!!.notes[1].line.single()
        assertEquals(Rule.Prose, notice.rule)
        assertTrue(SafeText.prose(notice.text).contains("⟨U+202E⟩"))
        // The account label is a label: cleaned, the override and invisibles gone.
        val account = m.row("Account")!!
        assertEquals(Rule.Label, account.value.single().rule)
        assertEquals("admin@evil.test", account.value.plain())
        assertEquals("Orgx", account.notes.single().line.plain())
    }

    @Test
    fun hostileModelAndRunTextIsRuled() {
        val metrics = SessionMetrics(model = "opus‮")
        val m = InspectorBoards.model(InspectorBoards.session(metrics = metrics))
        val model = m.row("Model")!!.value.single()
        assertEquals(Rule.Code, model.rule)
        assertTrue(SafeText.code(model.text).contains("⟨U+202E⟩"))
    }

    @Test
    fun aHugeValueIsBoundedBeforeItIsDrawn() {
        val m = InspectorBoards.model(InspectorBoards.session(worktree = WorktreeInfo(path = "p".repeat(50_000), branch = "b", status = "active")))
        val path = m.row("Worktree")!!.notes[0].line.single().text
        assertTrue(path.length <= MAX_CODE)
        assertTrue(path.endsWith("…"))
    }
}
