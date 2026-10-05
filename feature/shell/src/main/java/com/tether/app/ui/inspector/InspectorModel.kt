package com.tether.app.ui.inspector

import androidx.compose.runtime.Immutable
import com.tether.app.client.ChangeRequestReading
import com.tether.app.client.ConsentGuard
import com.tether.app.client.LabelText
import com.tether.app.client.WorktreeLogsReading
import com.tether.app.client.WorktreeSkipCopy
import com.tether.app.protocol.fold.numberToString
import com.tether.app.protocol.helpers.ClaudeResetGrantsView
import com.tether.app.protocol.helpers.Format
import com.tether.app.protocol.helpers.ModelPicker
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.model.SessionView
import com.tether.app.protocol.model.SubagentDefault
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.ui.chat.ProviderNoticeView
import com.tether.app.ui.chat.RunSource
import com.tether.app.ui.chat.STATUS_TEXT
import com.tether.app.ui.chat.SubagentRun
import com.tether.app.ui.chat.WorktreeDiffSummaryView
import com.tether.app.ui.chat.providerNotices
import com.tether.app.ui.chat.runStatusText
import com.tether.app.ui.chat.subagentRosterSummary
import com.tether.app.ui.chat.usageGapReason
import com.tether.app.ui.chat.worktreeDiffSummary
import com.tether.app.ui.statusline.ReadingEnv
import com.tether.app.ui.statusline.TelemetryMetrics
import com.tether.app.ui.statusline.WrapUpReading
import com.tether.app.ui.statusline.contextReading
import com.tether.app.ui.statusline.contextSnapshotReading
import com.tether.app.ui.statusline.gitDivergence
import com.tether.app.ui.statusline.taskReading
import com.tether.app.ui.statusline.windowReading
import com.tether.app.ui.statusline.wrapUpReading
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/*
 * T9.1 / ta-coik.10: the state-mapping layer of the session telemetry panel (tether 90fbb9f
 * components/inspector.tsx, the f4c4133 redesign, and its parts: repository-panel.tsx,
 * worktree-services-card.tsx, the CodexNotices lead-in). Pure: the session row, the reducer's
 * projection and the client's per-session replies in; one [InspectorModel] out, band by band in
 * the web's order. Nothing here sends anything.
 *
 * Server and agent text never reaches the screen as a bare string: every value is a [Seg] that
 * names its drawing rule ([Rule]): the app's own words, a cleaned LABEL (names), LINE (paths,
 * branches, ids, model ids, the account: every hidden, reordering or line-breaking code point a
 * visible token), CODE (a multi-line log) or PROSE (messages). The composables draw each rule through `com.tether.app.ui.text`.
 */

/** How one piece of text is drawn. */
enum class Rule {
    /** The app's own copy (never server text). */
    App,

    /** A name or label, already cleaned by [LabelText] (bidi controls and invisibles dropped, bounded). */
    Label,

    /**
     * A one-line name, path, branch, id or model id: SafeText's line rule (the code rule, and TAB /
     * LF / CR as tokens too, so a value can never break into a second, forged row), LTR.
     */
    Line,

    /** A multi-line code block (a setup log): SafeText's code rule, its line breaks kept. */
    Code,

    /** A message: SafeText's prose rule. */
    Prose,
}

/** One run of text and its rule. */
@Immutable
data class Seg(val text: String, val rule: Rule)

/** A line of [Seg]s. */
typealias Line = List<Seg>

internal fun app(text: String): Seg = Seg(text, Rule.App)
internal fun label(text: String?): Seg = Seg(LabelText.label(text), Rule.Label)

/** Longest code / prose value drawn (a path or a message; the server bounds them, this bounds the work). */
internal const val MAX_CODE = 1_000
internal const val MAX_PROSE = LabelText.MAX_ERROR

internal fun code(text: String): Seg = Seg(bound(text, MAX_CODE), Rule.Line)
internal fun prose(text: String): Seg = Seg(bound(text, MAX_PROSE), Rule.Prose)

private fun bound(text: String, max: Int): String =
    if (text.length <= max) text else ConsentGuard.cutCodePoints(text, max - 1) + "…"

/** The plain text of a line (tests, accessibility). */
fun Line.plain(): String = joinToString("") { it.text }

// --- The model -------------------------------------------------------------------------------

/*
 * ta-coik.10: the panel as the deployed web draws it (tether 90fbb9f components/inspector.tsx, the
 * f4c4133 "Headroom first" redesign + #233), band by band in the operator's order: header, the
 * attention strip, Context, Limits, Subagents, the selected run, MCP health, Tokens, Repository,
 * Services, Codex notices, Runtime, the acp capability set.
 */
@Immutable
data class InspectorModel(
    val identity: Identity,
    /** inspector.tsx:634-684: model · effort · account, the divergence note and the "Now" task. */
    val header: Header,
    /** inspector.tsx:586-596, 693-711: only what would stop the work; empty for a healthy session. */
    val attention: Attention,
    /** inspector.tsx:717-748. */
    val context: ContextBand,
    /** inspector.tsx:751-779; null until the session has metrics (the band is omitted). */
    val limits: LimitsSection?,
    /** Every run in the session (the transcript's derivation) and the selected one, resolved by lookup. */
    val runs: List<SubagentRun>,
    val activeRunId: String?,
    /** inspector.tsx:418-489; null without runs. */
    val subagents: SubagentsBand?,
    /** inspector.tsx:285-348: the selected run's own readings; null with no run selected. */
    val runUsage: RunUsage?,
    /** A run is selected: "Session" states that everything below is session-scoped again. */
    val sessionDivider: Boolean,
    /** Every provider but opencode shows the MCP card (inspector.tsx:809). */
    val mcpHealth: Boolean,
    /** `opencode-serve-v2` only: the "Plugins" card (inspector.tsx:816). */
    val opencodePlugins: Boolean,
    /** inspector.tsx:611-628, 821-849; null when there is nothing to count. */
    val tokens: TokensBand?,
    val repository: RepositorySection?,
    val services: ServicesSection?,
    /** `codex-app-server-v2` only (inspector.tsx:870-872). */
    val codexNotices: List<ProviderNoticeView>,
    /** inspector.tsx:876-1001. */
    val runtime: RuntimeSection,
    /** The acp engine's advertised capability set; null for every other provider. */
    val acpCapabilities: List<Pair<String, Boolean>>?,
)

/** `.ti-harness`: the provider mark, its label and the session status in words. */
@Immutable
data class Identity(val provider: String, val providerLabel: Seg, val status: String, val statusText: Seg)

/** `.ti-head` below the harness row. */
@Immutable
data class Header(
    /** The CONFIGURED model's reading, or "Not reported yet". */
    val model: Line,
    /** "last served <model>" only when the main thread was demonstrably served by another one (#179). */
    val lastServed: Seg?,
    /** The effort, first letter capitalised (`capitalized`), or "—". */
    val effort: Seg,
    /** v92 (Claude only): the account this session bills, and its organisation. */
    val account: Seg?,
    val organization: Seg?,
    /** `modelReading().note`: the divergence, in `--warning`. */
    val note: Seg?,
    /** v39: what the agent says it is doing now ("Now"). */
    val task: Seg?,
    val taskProgress: String?,
)

/** One `.ti-alert` of the rate limit: its words and whether it is the rejected (danger) state. */
@Immutable
data class RateLimitAlert(val text: String, val danger: Boolean)

@Immutable
data class Attention(
    /** Issue #195: the Wrap-Up Allowance; it REPLACES the rate-limit alert while it runs. */
    val wrapUp: WrapUpReading?,
    val rateLimit: RateLimitAlert?,
    /** "1 failed, 2 need sign-in", or null. */
    val mcpProblem: String?,
    val setupFailed: Boolean,
) {
    val any: Boolean get() = wrapUp != null || rateLimit != null || mcpProblem != null || setupFailed
}

/** One `Gauge` row (label | track | number, caption under); [percent] null draws no bar. */
@Immutable
data class GaugeRow(val label: String, val percent: Int?, val value: String, val caption: String)

@Immutable
data class ContextBand(
    /** "Live", "Snapshot · 09:05 AM", "Waiting"; "Session · …" while a run is selected. */
    val aside: String,
    /** No metrics yet: the honest empty state. */
    val awaiting: Boolean,
    val gauge: GaugeRow?,
    /** "This engine has not reported a context window." */
    val noWindow: Boolean,
)

/** The Limits band: the account's windows, then its resets. The reset actions are T9.2's. */
@Immutable
data class LimitsSection(
    val gauges: List<GaugeRow>,
    /** Claude: "1 reset left · expires in 26 d" and its note; null when there is nothing to say. */
    val resetGrants: Pair<String, String>?,
    /** Codex: the banked reset count ("2 available"); null when none (or never checked). */
    val bankedResets: String?,
    /** #233: "No current 5-hour or weekly reading for this account." */
    val noReading: Boolean,
)

/** One run's model or effort and where it came from: served (no note), "asked" or "declared". */
@Immutable
data class RunSetting(val text: Seg, val source: String) {
    val note: String get() = SOURCE_NOTE[source].orEmpty()

    companion object {
        const val SERVED = "served"
        const val REQUESTED = "requested"
        const val DECLARED = "declared"

        /** inspector.tsx:400 SOURCE_NOTE. */
        val SOURCE_NOTE = mapOf(SERVED to "", REQUESTED to "asked", DECLARED to "declared")
    }
}

/** One `.ti-run-row`. */
@Immutable
data class RunRow(
    val runId: String,
    val title: Seg,
    /** RUN_RUNNING / RUN_ERROR / RUN_DONE: the glyph (spinner, alert, check). */
    val status: String,
    /** `runStatusText`, in words. */
    val statusText: Seg,
    /** "63.2M tok", or null ("no usage", titled with [gap]). */
    val tokens: String?,
    val gap: String?,
    val model: RunSetting?,
    val effort: RunSetting?,
    val active: Boolean,
    val nested: Boolean,
) {
    val error: Boolean get() = status == com.tether.app.ui.chat.RUN_ERROR
}

@Immutable
data class SubagentsBand(
    /** "10 total · 2 running · 1 failed". */
    val aside: String,
    val rows: List<RunRow>,
    /** The selected run sits behind "Show N more": that disclosure starts open. */
    val moreOpen: Boolean,
    /** "Tokens captured for 3 of 10 runs." when the totals cover only some runs. */
    val partialNote: String?,
) {
    val head: List<RunRow> get() = rows.take(VISIBLE_RUNS)
    val rest: List<RunRow> get() = rows.drop(VISIBLE_RUNS)

    companion object {
        /** inspector.tsx:408. */
        const val VISIBLE_RUNS = 6
    }
}

/** One `.ti-ledger` row; [sub] rows are the parts of the row above. */
@Immutable
data class LedgerRow(val label: String, val value: String, val note: String? = null, val sub: Boolean = false)

/** One `ModelUsageEstimate` (inspector.tsx:241-274). */
@Immutable
data class ModelUsageRow(val identity: Seg, val contributors: List<Seg>, val provider: Line, val ledger: List<LedgerRow>)

@Immutable
data class TokensBand(val rows: List<LedgerRow>, val perModel: List<ModelUsageRow>) {
    /** The disclosure's count: "2 models". */
    val perModelCount: String get() = "${perModel.size} ${if (perModel.size == 1) "model" else "models"}"
}

/** inspector.tsx:285-348: the Sub-agent band, scoped to ONE run. */
@Immutable
data class RunUsage(
    val runId: String,
    val title: Seg,
    val status: String,
    val ledger: List<LedgerRow>,
    /** Why the run has no token reading (never a "—" standing for zero); null when measured. */
    val gap: String?,
    val specs: List<SpecRow>,
)

/** The Runtime disclosure: its summary's CLI version and its rows. */
@Immutable
data class RuntimeSection(val cli: Seg?, val rows: List<SpecRow>)

/** One `.ti-specs` row: a label, a mono value and its notes. */
@Immutable
data class SpecRow(
    val label: String,
    val value: Line,
    val notes: List<Note> = emptyList(),
    /** `text-transform: capitalize` on the value. */
    val capitalize: Boolean = false,
    /** The CLI inventory's names, behind a "Names" disclosure. */
    val names: List<Line> = emptyList(),
)

/** A small note under a value; [warning] = `--warning`; [status] = role="status". */
@Immutable
data class Note(val line: Line, val warning: Boolean = false, val status: Boolean = false)

/** repository-panel.tsx: the branch, the linked pull request (its link and refresh) and the changes. */
@Immutable
data class RepositorySection(
    val branch: Seg?,
    val divergence: String?,
    /** Null when no change-request reply has arrived for the session. */
    val pullRequest: PullRequestLine?,
    val changes: WorktreeDiffSummaryView?,
    val changedFiles: Int,
    /**
     * T8.5 (repository-panel.tsx:27, 40-51): "Draft commit message" / "Draft pull request" are
     * shown when the server's metadata generation is on and the session is not read-only.
     */
    val canDraft: Boolean = false,
) {
    val changesCount: String get() = "$changedFiles ${if (changedFiles == 1) "file" else "files"}"
}

/**
 * `inspector-pull-request`: the headline ("Pull request #12", "No pull request", "PR status
 * unavailable") and its state. [url]: the pull request's address when the reply carries one
 * (repository-panel.tsx:56 makes the headline a link then); a link opens it on a tap.
 */
@Immutable
data class PullRequestLine(val headline: String, val state: String?, val url: String? = null)


/** worktree-services-card.tsx: the declared scripts, each with Run / Stop / Restart and its "Output of" view. */
@Immutable
data class ServicesSection(
    val count: String,
    val setup: ServiceSetup?,
    /**
     * ta-m7ef (v143, worktree-services-card.tsx 1bf4a465): why this checkout's setup / teardown did NOT run
     * (`setupSkipped`, then `teardownSkipped`), each as the web's sentence; empty when both ran or neither was declared.
     */
    val skipped: List<String> = emptyList(),
    val configWarnings: List<Seg>,
    val scripts: List<ServiceRow>,
    /** The session's last `worktree-logs` reply (null = none yet); shown under the row it names. */
    val logs: ServiceLogs? = null,
)

/**
 * One `worktree-logs` reply: the script it names ([name], exact) and its output as one code block,
 * null when it has no lines ("No output yet.").
 */
@Immutable
data class ServiceLogs(val name: String, val output: Seg?)

@Immutable
data class ServiceSetup(val text: String, val failed: Boolean, val log: Seg?)

@Immutable
data class ServiceRow(
    val name: Seg,
    /** The script's name exactly as the snapshot declares it: what the `worktree-script` / `worktree-logs` frames name. */
    val scriptName: String,
    /** Running or starting: Restart and Stop; otherwise Run (worktree-services-card.tsx:101, 110-127). */
    val running: Boolean,
    val status: String,
    val failed: Boolean,
    val command: Seg,
    /** A running service's own address (proxyHost), shown as plain text (T9.1); only with [open]. */
    val address: Seg?,
    /** Why a running service has no address of its own (also when its link failed the T15.7 pin). */
    val unavailable: String?,
    val error: Seg?,
    /** T15.7: the "Open" link, pinned by [ServiceOpenLink]; null = no link. */
    val open: ServiceOpen? = null,
    /**
     * ta-coik.2: the "On this machine" link (`proxyPath`, sent only to a loopback console), pinned by
     * [ServiceOpenLink.resolveLocal]; null = no link. [toString] never prints it (it carries the
     * path form's capability).
     */
    val local: ServiceLocal? = null,
)

/** ta-coik.2: a running service's "On this machine" link ([url]: the path form on the paired origin). */
@Immutable
data class ServiceLocal(val url: String) {
    override fun toString(): String = "ServiceLocal(url=<redacted>)"
}

/**
 * T15.7 / ta-coik.2: a running service's "Open" link: [url] (the console's pinned worktree-open
 * route, resolved against the paired origin) is asked with the app's sign-in, and the browser goes
 * where it redirects, which must be on [serviceUrl] (the snapshot's `proxyUrl`: its scheme, host and
 * port, ta-t5rl). [host] is the service's own hostname (`proxyHost`, drawn by the line rule).
 * [toString] never prints the URL, so a model dumped into a log or a test failure does not carry it.
 */
@Immutable
data class ServiceOpen(val url: String, val host: Seg, val serviceUrl: String? = null) {
    override fun toString(): String = "ServiceOpen(url=<redacted>)"
}

// --- Inputs ----------------------------------------------------------------------------------

/** The client's per-session replies the inspector reads (null = not received). */
@Immutable
data class InspectorReplies(
    /** The `worktree-diff` summary: absent key = never received; a JSON null = not a repo. */
    val worktreeDiff: JsonObject? = null,
    val worktreeScripts: JsonObject? = null,
    val changeRequest: ChangeRequestReading? = null,
    /** ta-coik.14: the session's last `worktree-logs` reply. */
    val worktreeLogs: WorktreeLogsReading? = null,
)


// --- The mapping -----------------------------------------------------------------------------

/** inspector.tsx:491-1009, from the session, the provider list and the projection. */
fun inspectorModel(
    session: AgentSession,
    providers: List<ProviderInfo>,
    state: SessionView?,
    runs: List<SubagentRun>,
    selectedRunId: String?,
    replies: InspectorReplies,
    env: ReadingEnv,
    /** T15.7: the paired server's canonical origin (`serverOrigin(serverUrl)`); null = no service link. */
    serverOrigin: String? = null,
    /** T8.5 (dashboard.tsx:1460): `serverSettings?.settings.metadataGenerationEnabled === true`. */
    metadataGenerationEnabled: Boolean = false,
): InspectorModel {
    val activeRun = selectedRunId?.let { id -> runs.firstOrNull { it.runId == id } }
    val metrics = TelemetryMetrics.from(session.metrics)
    // v138: the declared defaults are Claude's only (inspector.tsx:786, 794).
    val defaults = if (session.provider == "claude") session.metrics?.subagentDefaultMap.orEmpty() else emptyMap()
    return InspectorModel(
        identity = identity(session, providers),
        header = header(session, state),
        attention = attention(session, state, env),
        context = contextBand(session, metrics, env, activeRun != null),
        limits = if (session.metrics != null) limits(session, env) else null,
        runs = runs,
        activeRunId = activeRun?.runId,
        subagents = subagentsBand(runs, activeRun?.runId, defaults),
        runUsage = activeRun?.let { runUsage(it, it.agentType?.let(defaults::get)) },
        sessionDivider = activeRun != null,
        mcpHealth = session.provider != "opencode" && state != null,
        opencodePlugins = session.engineGeneration == "opencode-serve-v2" && state != null,
        tokens = tokensBand(session, state, runs, metrics, env),
        repository = repository(session, replies, serverOrigin, metadataGenerationEnabled),
        services = if (session.worktree != null) services(replies.worktreeScripts, session.id, serverOrigin, replies.worktreeLogs) else null,
        codexNotices = if (session.engineGeneration == "codex-app-server-v2" && state != null) {
            // Render-only here: the transcript's copy of each notice carries the dismiss X.
            providerNotices(state.obj["providerNotices"], "Codex").map { it.copy(dismissKey = null) }
        } else {
            emptyList()
        },
        runtime = runtime(session, state),
        acpCapabilities = if (session.provider == "acp") acpCapabilities(providers) else null,
    )
}

internal fun identity(session: AgentSession, providers: List<ProviderInfo>): Identity {
    val named = providers.firstOrNull { it.id == session.provider }?.label?.let(LabelText::label)?.takeIf { it.isNotEmpty() }
    val status = Format.statusCopy[session.status]?.let(::app) ?: label(session.status)
    return Identity(session.provider, named?.let { Seg(it, Rule.Label) } ?: label(session.provider), session.status, status)
}

// ---- Header

/** inspector.tsx:234-236 `capitalized`: the first character upper-cased, the rest as sent. */
internal fun capitalized(value: String?): String =
    if (value.isNullOrEmpty()) "" else value.substring(0, 1).uppercase(Locale.ROOT) + value.substring(1)

/** inspector.tsx:552-580, 634-684. */
internal fun header(session: AgentSession, state: SessionView?): Header {
    val reading = modelReading(session, state)
    val readingLabel = strOf(reading["label"]).orEmpty()
    val metrics = session.metrics
    val email = if (session.provider == "claude") metrics?.accountEmail?.takeIf { it.isNotEmpty() } else null
    val task = taskReading(state?.todo)
    val effort = capitalized(metrics?.effort)
    return Header(
        model = if (readingLabel == "—" || readingLabel.isEmpty()) listOf(app("Not reported yet")) else listOf(code(readingLabel)),
        lastServed = strOf(reading["lastServed"])?.let(::code),
        effort = if (effort.isEmpty()) app("—") else label(effort),
        // The account identity is an id: the one-line rule, so a look-alike shows its hidden code points.
        account = email?.let(::code),
        organization = if (email != null) metrics?.accountOrganization?.takeIf { it.isNotEmpty() }?.let(::label) else null,
        note = strOf(reading["note"])?.let(::prose),
        task = task?.let { prose(it.text) },
        taskProgress = task?.progress,
    )
}

/** inspector.tsx:552-566: the configured model and, when it demonstrably differs, what served it. */
internal fun modelReading(session: AgentSession, state: SessionView?): JsObj {
    val latest = latestTurn(state)
    val served = strOf(latest?.second?.get("model"))?.takeIf { it.isNotEmpty() } ?: session.metrics?.model
    val fallback = latest?.let { (turnId, _) -> (state?.turn(turnId)?.obj?.get("modelFallbacks") as? JsArr)?.lastOrNull() } ?: JsNull
    return ModelPicker.modelReading(
        JsObj.of(
            "configured" to (session.model?.let(::JsStr) ?: JsNull),
            "served" to (served?.let(::JsStr) ?: JsNull),
            "fallback" to fallback,
        ),
    )
}

// ---- Attention

/** inspector.tsx:586-596: the MCP count in words, the card's own predicate (failed / needs-auth). */
internal fun mcpProblem(state: SessionView?): String? {
    val health = state?.obj?.get("mcpHealth") as? JsObj ?: return null
    var failed = 0
    var needsAuth = 0
    for ((_, value) in health.entries) {
        when (strOf((value as? JsObj)?.get("status"))) {
            "failed" -> failed++
            "needs-auth" -> needsAuth++
        }
    }
    return listOfNotNull(
        if (failed > 0) "$failed failed" else null,
        if (needsAuth > 0) "$needsAuth ${if (needsAuth == 1) "needs" else "need"} sign-in" else null,
    ).joinToString(", ").ifEmpty { null }
}

internal fun attention(session: AgentSession, state: SessionView?, env: ReadingEnv): Attention {
    val wrapUp = if (state != null) wrapUpReading(state, env) else null
    val rateStatus = ((state?.obj?.get("rateLimit") as? JsObj)?.get("status") as? JsStr)?.value
    val rateLimit = if (wrapUp == null) {
        rateLimitNoticeText(state, env.nowMs)?.let { RateLimitAlert(it, danger = rateStatus == "rejected") }
    } else {
        null
    }
    return Attention(
        wrapUp = wrapUp,
        rateLimit = rateLimit,
        mcpProblem = if (session.provider != "opencode") mcpProblem(state) else null,
        setupFailed = session.worktree?.setupStatus == "failed",
    )
}

// ---- Context

internal fun contextBand(session: AgentSession, metrics: TelemetryMetrics?, env: ReadingEnv, runSelected: Boolean): ContextBand {
    val context = contextReading(metrics, env)
    val snapshot = contextSnapshotReading(metrics, env)
    val aside = when {
        session.metrics == null -> "Waiting"
        snapshot != null -> "Snapshot · ${snapshot.asOf}"
        else -> "Live"
    }
    val gauge = when {
        session.metrics == null -> null
        context != null -> GaugeRow("Used", context.percent, "${context.percent}%", context.caption)
        snapshot != null -> GaugeRow("Used", null, Format.compactNumber(snapshot.tokens), "Snapshot · ${snapshot.asOf} · window size not reported")
        else -> null
    }
    return ContextBand(
        aside = if (runSelected) "Session · $aside" else aside,
        awaiting = session.metrics == null,
        gauge = gauge,
        noWindow = session.metrics != null && gauge == null,
    )
}

// ---- Usage helpers

/** inspector.tsx:214-222: the newest turn that carries usage, and its id. */
internal fun latestTurn(state: SessionView?): Pair<String, JsObj>? {
    if (state == null) return null
    val order = state.turnOrder
    for (index in order.indices.reversed()) {
        val turnId = (order[index] as? JsStr)?.value ?: continue
        val usage = state.turn(turnId)?.usage ?: continue
        return turnId to usage
    }
    return null
}

private fun finiteOf(value: JsValue?): Double? = (value as? JsNum)?.value?.takeIf { it.isFinite() }
private fun strOf(value: JsValue?): String? = (value as? JsStr)?.value

/** inspector.tsx:224-226: `Intl.NumberFormat("en")` (grouped, up to 3 fraction digits), "—" when not finite. */
internal fun usageNumber(value: JsValue?): String {
    val d = finiteOf(value) ?: return "—"
    val format = DecimalFormat("#,##0.###", DecimalFormatSymbols(Locale.US)).apply { roundingMode = RoundingMode.HALF_UP }
    return format.format(d)
}

// ---- Tokens

internal fun tokensBand(session: AgentSession, state: SessionView?, runs: List<SubagentRun>, metrics: TelemetryMetrics?, env: ReadingEnv): TokensBand? {
    val raw = session.metrics
    val cacheRead = raw?.cacheReadInputTokens?.toDouble()
    val cacheMiss = raw?.cacheMissInputTokens?.toDouble()
    val split = cacheRead != null || cacheMiss != null
    val latest = latestTurn(state)
    val usage = latest?.second
    val rows = buildList {
        raw?.totalTokens?.let { total ->
            add(LedgerRow(if (split) "Processed" else "Tokens", Format.compactNumber(total.toDouble()), note = "Current session"))
            cacheRead?.let { add(LedgerRow("Cache reads", Format.compactNumber(it), sub = true)) }
            cacheMiss?.let { add(LedgerRow("Fresh input", Format.compactNumber(it), sub = true)) }
        }
        finiteOf(usage?.get("perTurnTokens"))?.let { add(LedgerRow("Last turn", Format.compactNumber(it), note = "Whole-tree tokens")) }
        // The window SIZE prints in the context meter's caption once that reading exists.
        if (contextReading(metrics, env) == null) {
            raw?.contextWindow?.let { add(LedgerRow("Context window", Format.compactNumber(it.toDouble()))) }
        }
    }
    val lastTurnRuns = latest?.let { (turnId, _) -> runs.filter { it.turnId == turnId } }.orEmpty()
    val perModel = (usage?.get("modelUsages") as? JsArr)?.mapNotNull { it as? JsObj }.orEmpty()
        .take(LabelText.MAX_ITEMS)
        .map { entry -> modelUsageRow(entry, lastTurnRuns, strOf(usage?.get("model")), strOf(usage?.get("rawModel"))) }
    if (rows.isEmpty() && perModel.isEmpty()) return null
    return TokensBand(rows, perModel)
}

/** subagent-run-model.mjs `modelUsageContributors`: which run(s) an entry's numbers belong to. */
internal fun modelUsageContributors(entry: JsObj, runs: List<SubagentRun>, mainModel: String?, mainRawModel: String?): List<String> {
    val model = strOf(entry["model"])
    val canonical = strOf(entry["canonicalModel"])
    fun matches(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        if (value == model) return true
        return !canonical.isNullOrEmpty() && value == canonical
    }
    val labels = ArrayList<String>()
    if (matches(mainRawModel) || matches(mainModel)) labels.add("Main session")
    for (run in runs) if (matches(strOf(run.usage?.get("model")))) labels.add(run.title)
    if (labels.isEmpty()) labels.add(if (runs.isEmpty()) "Main session" else "Unidentified sub-agent")
    return labels
}

internal fun modelUsageRow(entry: JsObj, runs: List<SubagentRun>, mainModel: String?, mainRawModel: String?): ModelUsageRow {
    val model = strOf(entry["model"]).orEmpty()
    val canonical = strOf(entry["canonicalModel"])?.takeIf { it.isNotEmpty() }
    val provider = strOf(entry["provider"])?.takeIf { it.isNotEmpty() }
    val providerLine = buildList {
        add(if (provider != null) label(provider) else app("Provider not reported"))
        if (canonical != null && canonical != model) {
            add(app(" · raw "))
            add(code(model))
        }
    }
    return ModelUsageRow(
        identity = code(canonical ?: model),
        contributors = modelUsageContributors(entry, runs, mainModel, mainRawModel).map { name ->
            if (name == "Main session" || name == "Unidentified sub-agent") app(name) else label(name)
        },
        provider = providerLine,
        ledger = listOf(
            LedgerRow("Input", usageNumber(entry["inputTokens"])),
            LedgerRow("Output", usageNumber(entry["outputTokens"])),
            LedgerRow("Cache read", usageNumber(entry["cacheReadInputTokens"])),
            LedgerRow("Cache write", usageNumber(entry["cacheCreationInputTokens"])),
            LedgerRow("Web searches", usageNumber(entry["webSearchRequests"])),
            LedgerRow("Window", usageNumber(entry["contextWindow"])),
            LedgerRow("Max output", usageNumber(entry["maxOutputTokens"])),
        ),
    )
}

// ---- Subagents

/**
 * inspector.tsx:383-398 `runModelReading`: model = served > requested > declared; effort =
 * requested > declared. A run never reports its own effort, so nothing declared is shown as measured.
 */
internal fun runModelReading(run: SubagentRun, declared: SubagentDefault?): Pair<RunSetting?, RunSetting?> {
    val served = strOf(run.usage?.get("model"))?.takeIf { it.isNotEmpty() }
    val requestedModel = run.requestedModel?.takeIf { it.isNotEmpty() }
    val requestedEffort = run.requestedEffort?.takeIf { it.isNotEmpty() }
    val declaredModel = declared?.model
    val declaredEffort = declared?.effort
    val model = when {
        served != null -> RunSetting(code(served), RunSetting.SERVED)
        requestedModel != null -> RunSetting(code(requestedModel), RunSetting.REQUESTED)
        declaredModel != null -> RunSetting(code(declaredModel), RunSetting.DECLARED)
        else -> null
    }
    val effort = when {
        requestedEffort != null -> RunSetting(label(requestedEffort), RunSetting.REQUESTED)
        declaredEffort != null -> RunSetting(label(declaredEffort), RunSetting.DECLARED)
        else -> null
    }
    return model to effort
}

internal fun subagentsBand(runs: List<SubagentRun>, activeRunId: String?, defaults: Map<String, SubagentDefault>): SubagentsBand? {
    if (runs.isEmpty()) return null
    val summary = subagentRosterSummary(runs)
    val aside = listOfNotNull(
        "${summary.total} total",
        if (summary.running > 0) "${summary.running} running" else null,
        if (summary.errored > 0) "${summary.errored} failed" else null,
    ).joinToString(" · ")
    val rows = runs.map { run ->
        val (model, effort) = runModelReading(run, run.agentType?.let(defaults::get))
        val measured = run.totalTokens != null
        RunRow(
            runId = run.runId,
            title = label(run.title),
            status = run.status,
            statusText = label(runStatusText(run)),
            tokens = if (measured) "${Format.compactNumber(run.totalTokens!!)} tok" else null,
            gap = if (measured) null else usageGapReason(run),
            model = model,
            effort = effort,
            active = run.runId == activeRunId,
            nested = run.depth > 1,
        )
    }
    return SubagentsBand(
        aside = aside,
        rows = rows,
        moreOpen = rows.drop(SubagentsBand.VISIBLE_RUNS).any { it.active },
        partialNote = if (summary.partial && summary.measured > 0) "Tokens captured for ${summary.measured} of ${summary.total} runs." else null,
    )
}

/** inspector.tsx:285-348. */
internal fun runUsage(run: SubagentRun, declared: SubagentDefault? = null): RunUsage {
    val measured = run.totalTokens != null
    val thread = run.source == RunSource.THREAD
    val served = strOf(run.usage?.get("model"))?.takeIf { it.isNotEmpty() }
    val requested = run.requestedModel?.takeIf { it.isNotEmpty() }
    val agentType = run.agentType?.takeIf { it.isNotEmpty() }
    val requestedEffort = run.requestedEffort?.takeIf { it.isNotEmpty() }
    val declaredModel = declared?.model
    val declaredEffort = declared?.effort
    val specs = buildList {
        run.provider?.takeIf { it.isNotEmpty() }?.let { provider ->
            add(SpecRow("Harness", listOf(label(provider)), if (run.source == RunSource.DELEGATE) listOf(Note(listOf(app("Delegated Tether session")))) else emptyList()))
        }
        agentType?.let { add(SpecRow("Agent type", listOf(label(it)))) }
        if (served != null) {
            val note = if (requested != null && requested != served) {
                listOf(Note(listOf(app("Requested "), code(requested), app(", but this is what served it"))))
            } else {
                emptyList()
            }
            add(SpecRow("Model", listOf(code(served)), note))
        } else if (requested != null) {
            add(SpecRow("Model", listOf(code(requested)), listOf(Note(listOf(app("Requested; served model not captured"))))))
        } else if (declaredModel != null) {
            add(SpecRow("Model", listOf(code(declaredModel)), listOf(Note(listOf(app("Declared by the "), label(agentType), app(" agent type; no served response yet"))))))
        }
        if (requestedEffort != null) {
            add(SpecRow("Effort", listOf(label(requestedEffort)), listOf(Note(listOf(app("Requested on the Agent call"))))))
        } else if (declaredEffort != null) {
            add(SpecRow("Effort", listOf(label(declaredEffort)), listOf(Note(listOf(app("Declared by the "), label(agentType), app(" agent type"))))))
        }
    }
    return RunUsage(
        runId = run.runId,
        title = label(run.title),
        status = STATUS_TEXT[run.status] ?: run.status,
        ledger = listOf(
            LedgerRow("Tokens", if (measured) Format.compactNumber(run.totalTokens!!) else "—", note = if (measured) "This sub-agent only" else "Not captured"),
            // Issue #172: a child run in its own native thread streams no steps here.
            LedgerRow("Steps", if (thread) "—" else run.steps.toString(), note = if (thread) "Not streamed" else "Recorded"),
        ),
        gap = if (measured) null else "${usageGapReason(run)}.",
        specs = specs,
    )
}

// ---- Repository

private val CR_STATE = mapOf("OPEN" to "Open", "MERGED" to "Merged", "CLOSED" to "Closed")
private val CR_REVIEW = mapOf("APPROVED" to "Approved", "CHANGES_REQUESTED" to "Changes requested", "REVIEW_REQUIRED" to "Review required")

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

/** repository-panel.tsx `changeRequestLine`: "Open · Draft · Approved · Conflicts" (the parts that apply). */
internal fun changeRequestLine(cr: JsonObject): String {
    val state = cr.string("state")
    return listOfNotNull(
        state?.let { CR_STATE[it] ?: LabelText.label(it) },
        if (cr.flag("isDraft") && state == "OPEN") "Draft" else null,
        // An unknown decision is dropped, as the web's `CR_REVIEW[x]` is undefined and filtered out.
        cr.string("reviewDecision")?.let { CR_REVIEW[it] },
        if (cr.string("mergeable") == "CONFLICTING") "Conflicts" else null,
    ).filter { it.isNotEmpty() }.joinToString(" · ")
}

internal fun pullRequestLine(reading: ChangeRequestReading, consoleOrigin: String? = null): PullRequestLine {
    if (reading.unknown) return PullRequestLine("PR status unavailable", null)
    val cr = reading.changeRequest ?: return PullRequestLine("No pull request", null)
    val number = cr.number("number")?.let(::numberToString) ?: "?"
    // repository-panel.tsx:56: a link when `cr.url` is set, any address but React's blocked javascript: (ta-coik.18).
    val url = pullRequestHref(cr.string("url"), consoleOrigin)
    return PullRequestLine("Pull request #$number", changeRequestLine(cr).ifEmpty { null }, url)
}

internal fun repository(
    session: AgentSession,
    replies: InspectorReplies,
    consoleOrigin: String? = null,
    metadataGenerationEnabled: Boolean = false,
): RepositorySection? {
    val branch = session.metrics?.gitBranch?.takeIf { it.isNotEmpty() } ?: session.worktree?.branch?.takeIf { it.isNotEmpty() }
    val diff = worktreeDiffSummary(replies.worktreeDiff)
    val cr = replies.changeRequest
    // repository-panel.tsx:27-28: `metadataGenerationEnabled && onRequestDraft && !session.readOnly`.
    val canDraft = metadataGenerationEnabled && !session.readOnly
    if (branch == null && diff == null && cr == null && !canDraft) return null
    val changed = diff?.let { (it.committed + it.uncommitted).map { e -> e.path }.toSet().size } ?: 0
    return RepositorySection(
        branch = branch?.let(::code),
        divergence = gitDivergence(session.metrics?.gitAhead?.toDouble(), session.metrics?.gitBehind?.toDouble()),
        pullRequest = cr?.let { pullRequestLine(it, consoleOrigin) },
        changes = diff,
        changedFiles = changed,
        canDraft = canDraft,
    )
}


// ---- Account limits

internal fun jsOf(element: JsonElement?): JsValue = if (element == null) JsNull else JsCodec.fromJson(element)

/** inspector.tsx:599-605, 751-779 (#233: the neutral empty copy). */
internal fun limits(session: AgentSession, env: ReadingEnv): LimitsSection {
    val metrics = session.metrics
    val t = TelemetryMetrics.from(metrics)
    val gauges = buildList {
        for ((label, window) in listOf("5 hour" to t?.fiveHour, "Weekly" to t?.weekly, "Fable" to t?.fable)) {
            if (window == null) continue
            val reading = windowReading(window, env)
            add(GaugeRow(label, reading.percent, reading.percent?.let { "$it%" } ?: "—", reading.caption))
        }
    }
    val grants = if (session.provider == "claude") metrics?.claudeResetGrants?.takeIf { it is JsonObject } else null
    val resetGrants = grants?.let { summary ->
        val headline = runCatching { ClaudeResetGrantsView.resetGrantsHeadline(jsOf(summary), env.nowMs) }.getOrNull() ?: return@let null
        val atLimit = (summary as JsonObject).flag("atLimit")
        headline to "${if (atLimit) "At the limit now" else "Not at a limit"} · use a reset from Usage"
    }
    val credits = if (session.provider == "codex") metrics?.codexResetCredits as? JsonObject else null
    val available = credits?.number("availableCount")?.takeIf { it > 0 }
    return LimitsSection(
        gauges = gauges,
        resetGrants = resetGrants,
        bankedResets = available?.let { "${numberToString(it)} available" },
        noReading = gauges.isEmpty() && grants == null && available == null,
    )
}

// ---- Worktree services

private val SCRIPT_STATUS = mapOf(
    "idle" to "Idle",
    "starting" to "Starting",
    "running" to "Running",
    "stopping" to "Stopping",
    "exited" to "Stopped",
    "failed" to "Failed",
)

private val UNAVAILABLE_COPY = mapOf(
    "not-configured" to "Own address not configured — set TETHER_SERVICE_ORIGIN (see docs/worktrees.md).",
    "label-too-long" to "No own address: its hostname would exceed 63 characters. Shorten the script name or slug.",
    "label-invalid" to "No own address: its script name or worktree slug is not a valid hostname part.",
)

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

/**
 * worktree-services-card.tsx; null when the card would be an empty frame. T15.7's "Open" link
 * exists only for [sessionId] on [serverOrigin] (see [ServiceOpenLink]); [logs] is the session's
 * last `worktree-logs` reply.
 */
internal fun services(
    snapshot: JsonObject?,
    sessionId: String? = null,
    serverOrigin: String? = null,
    logs: WorktreeLogsReading? = null,
): ServicesSection? {
    if (snapshot == null) return null
    val scripts = (snapshot["scripts"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty().take(LabelText.MAX_ITEMS)
    val setupStatus = snapshot.string("setupStatus")
    val running = setupStatus == "running" || setupStatus == "pending"
    val failed = setupStatus == "failed"
    val warnings = snapshot.strings("configWarnings").take(LabelText.MAX_ITEMS)
    // v143 (ta-6t1): the hooks were stripped at create for want of a matching approval / a declared teardown
    // was refused at archive. An unknown reason still says it did not run.
    val skipped = listOfNotNull(
        snapshot.string("setupSkipped")?.let(WorktreeSkipCopy::setup),
        snapshot.string("teardownSkipped")?.let(WorktreeSkipCopy::teardown),
    )
    if (scripts.isEmpty() && !running && !failed && skipped.isEmpty() && warnings.isEmpty()) return null
    val log = snapshot.strings("setupLog").takeLast(8)
    val setup = if (running || failed) {
        ServiceSetup(
            text = if (running) {
                "Running this project's setup commands — the first turn starts when they finish."
            } else {
                "This project's setup commands failed. The checkout is still usable."
            },
            failed = failed,
            log = log.takeIf { it.isNotEmpty() }?.let { Seg(bound(it.joinToString("\n"), MAX_PROSE * 4), Rule.Code) },
        )
    } else {
        null
    }
    return ServicesSection(
        count = if (scripts.isEmpty()) "none declared" else "${scripts.size} declared",
        setup = setup,
        skipped = skipped,
        configWarnings = warnings.map(::prose),
        scripts = scripts.mapNotNull { serviceRow(it, sessionId, serverOrigin) },
        logs = logs?.let { ServiceLogs(it.name, it.lines.takeIf { l -> l.isNotEmpty() }?.let { l -> Seg(l.joinToString("\n"), Rule.Code) }) },
    )
}

private fun serviceRow(o: JsonObject, sessionId: String?, serverOrigin: String?): ServiceRow? {
    val name = o.string("name") ?: return null
    val status = o.string("status") ?: "idle"
    val failed = status == "failed"
    val service = o.string("type") == "service"
    val port = o.number("port")?.takeIf { it != 0.0 }
    val exit = o.number("exitCode")
    val words = buildString {
        append(SCRIPT_STATUS[status] ?: LabelText.label(status))
        if (service && port != null) append(" · :${numberToString(port)}")
        if (failed && exit != null) append(" · exit ${numberToString(exit)}")
    }
    // The web shows "Open" (and its "not configured" text) for a RUNNING service only.
    val live = service && status == "running"
    val open = if (live) serviceOpen(o, name, sessionId, serverOrigin) else null
    return ServiceRow(
        name = code(name),
        scriptName = name,
        running = status == "running" || status == "starting",
        status = words,
        failed = failed,
        command = code(o.string("command").orEmpty()),
        address = open?.host,
        unavailable = if (live && open == null) {
            UNAVAILABLE_COPY[o.string("proxyUnavailable") ?: "not-configured"] ?: UNAVAILABLE_COPY.getValue("not-configured")
        } else {
            null
        },
        error = o.string("error")?.takeIf { it.isNotEmpty() }?.let(::prose),
        open = open,
        // worktree-services-card.tsx:148: shown for a running service whenever the server sent it.
        local = if (live && sessionId != null) {
            ServiceOpenLink.resolveLocal(o.string("proxyPath"), sessionId, name, serverOrigin)?.let(::ServiceLocal)
        } else {
            null
        },
    )
}

/**
 * T15.7: the row's "Open" link, or null. Fail closed: a service the server gives a reason for
 * (`proxyUnavailable` present and not null, whatever its value) or no `proxyHost` (the confirm sheet
 * must name the service, and the redirect is pinned to it) has no link, nor has any `proxyAuthUrl`
 * [ServiceOpenLink.resolve] refuses.
 */
private fun serviceOpen(o: JsonObject, name: String, sessionId: String?, serverOrigin: String?): ServiceOpen? {
    if (sessionId == null) return null
    if (o["proxyUnavailable"].let { it != null && it !is JsonNull }) return null
    val host = o.string("proxyHost")?.takeIf { it.isNotEmpty() } ?: return null
    val url = ServiceOpenLink.resolve(o.string("proxyAuthUrl"), sessionId, name, serverOrigin) ?: return null
    return ServiceOpen(url, code(host), o.string("proxyUrl"))
}

// ---- Runtime details

/** inspector.tsx:148-152: the first [limit] names, then ", +N more"; "None" when empty. */
internal fun inventoryNames(names: List<String>, limit: Int = 8): Line {
    if (names.isEmpty()) return listOf(app("None"))
    val out = ArrayList<Seg>()
    names.take(limit).forEachIndexed { i, name ->
        if (i > 0) out.add(app(", "))
        out.add(code(name))
    }
    val remaining = names.size - minOf(names.size, limit)
    if (remaining > 0) out.add(app(", +$remaining more"))
    return out
}

internal fun runtime(session: AgentSession, state: SessionView?): RuntimeSection {
    val obj = state?.obj
    val cli = strOf(obj?.get("cliVersion"))?.takeIf { it.isNotEmpty() }
    val rows = buildList {
        if (session.provider == "codex") {
            val v2 = session.engineGeneration == "codex-app-server-v2"
            add(
                SpecRow(
                    "Engine",
                    listOf(app(if (v2) "App server v2" else "Legacy exec v1")),
                    listOf(Note(listOf(app(if (v2) "Persistent, interactive Codex session" else "Preserved without automatic migration")))),
                ),
            )
        }
        session.worktree?.let { w ->
            add(
                SpecRow(
                    "Worktree",
                    listOf(label(w.status)),
                    listOfNotNull(
                        Note(listOf(code(w.path))),
                        w.notice?.takeIf { it.isNotEmpty() }?.let { Note(listOf(prose(it)), status = true) },
                    ),
                    capitalize = true,
                ),
            )
            val base = w.baseRef?.takeIf { it.isNotEmpty() }?.let { ref ->
                when (w.mode) {
                    "checkout-pr" -> listOf(app("Pull request #${w.prNumber?.toString() ?: "null"} from "), code(ref))
                    "checkout-branch" -> listOf(app("Existing branch, checked out in its own directory"))
                    else -> listOf(app("Branched off "), code(ref))
                }
            }
            add(SpecRow("Branch", listOf(code(w.branch)), listOfNotNull(base?.let { Note(it) })))
            val setup = w.setupStatus
            if (setup != null && setup != "none" && setup != "ok") {
                val failed = setup == "failed"
                val text = if (failed) {
                    "This project's setup commands did not finish. The checkout is still usable — see the Services panel for the log."
                } else {
                    "Running this project's setup commands. The first turn starts when they finish."
                }
                add(SpecRow("Setup", listOf(app(if (failed) "Failed" else "Running")), listOf(Note(listOf(app(text)), status = true))))
            }
            // v143 (ta-6t1, inspector.tsx 1bf4a465): the hooks were stripped at create for want of a matching approval.
            w.setupSkipped?.let { reason ->
                add(SpecRow("Setup", listOf(app("Not run")), listOf(Note(listOf(app(WorktreeSkipCopy.setup(reason))), status = true))))
            }
            // v143 r2: a declared teardown was refused at archive.
            w.teardownSkipped?.let { reason ->
                add(SpecRow("Teardown", listOf(app("Not run")), listOf(Note(listOf(app(WorktreeSkipCopy.teardown(reason))), status = true))))
            }
            w.configWarningList.take(LabelText.MAX_ITEMS).forEach { warning ->
                add(SpecRow("Config", emptyList(), listOf(Note(listOf(prose(warning)), status = true))))
            }
        }
        if (session.provider == "acp") {
            add(
                SpecRow(
                    "ACP agent",
                    listOf(session.acpAgentId?.takeIf { it.isNotEmpty() }?.let(::code) ?: app("—")),
                    listOf(Note(listOf(app("generic ACP v1 engine")))),
                ),
            )
        }
        cli?.let { version ->
            val caps = (obj?.get("cliCapabilities") as? JsArr)?.mapNotNull { strOf(it) }.orEmpty()
            val note = if (caps.isNotEmpty()) {
                val line = ArrayList<Seg>()
                line.add(app("${caps.size} protocol ${if (caps.size == 1) "capability" else "capabilities"}: "))
                caps.take(LabelText.MAX_ITEMS).forEachIndexed { i, cap ->
                    if (i > 0) line.add(app(", "))
                    line.add(code(cap))
                }
                listOf(Note(line))
            } else {
                emptyList()
            }
            add(SpecRow("CLI", listOf(code(version)), note))
        }
        state?.cliInventory?.let { inventory ->
            val commands = (inventory["commands"] as? JsArr)?.mapNotNull { (it as? JsObj)?.let { c -> strOf(c["name"]) } }.orEmpty()
            val tools = (inventory["tools"] as? JsArr)?.mapNotNull { strOf(it) }.orEmpty()
            val commandCount = (inventory["commands"] as? JsArr)?.size ?: 0
            val toolCount = (inventory["tools"] as? JsArr)?.size ?: 0
            add(
                SpecRow(
                    "Inventory",
                    listOf(app("$commandCount commands · $toolCount tools")),
                    names = listOf(
                        listOf(app("Commands: ")) + inventoryNames(commands.map { "/$it" }) + app(" · Tether support is decided separately"),
                        listOf(app("Tools: ")) + inventoryNames(tools),
                    ),
                ),
            )
        }
    }
    return RuntimeSection(cli?.let(::code), rows)
}

/** inspector.tsx:262-287: the acp provider's advertised capability set, or null without one. */
internal fun acpCapabilities(providers: List<ProviderInfo>): List<Pair<String, Boolean>>? {
    val c = providers.firstOrNull { it.id == "acp" }?.capabilities ?: return null
    return listOf(
        "Approvals" to c.interactiveApprovals,
        "Questions" to c.interactiveQuestions,
        "Sandbox" to c.sandboxChoices,
        "Plans" to c.plans,
        "Model / mode pickers" to c.modelSelection,
    )
}
