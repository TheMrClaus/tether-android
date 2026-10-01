package com.tether.app.ui.inspector

import androidx.compose.runtime.Immutable
import com.tether.app.client.ChangeRequestReading
import com.tether.app.client.ConsentGuard
import com.tether.app.client.LabelText
import com.tether.app.protocol.fold.numberToString
import com.tether.app.protocol.helpers.ClaudeResetGrantsView
import com.tether.app.protocol.helpers.Format
import com.tether.app.protocol.helpers.ModelPicker
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.model.SessionView
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
import com.tether.app.ui.chat.usageGapReason
import com.tether.app.ui.chat.worktreeDiffSummary
import com.tether.app.ui.statusline.ContextReading
import com.tether.app.ui.statusline.ContextSnapshotReading
import com.tether.app.ui.statusline.ReadingEnv
import com.tether.app.ui.statusline.TelemetryMetrics
import com.tether.app.ui.statusline.WindowReading
import com.tether.app.ui.statusline.contextReading
import com.tether.app.ui.statusline.contextSnapshotReading
import com.tether.app.ui.statusline.gitDivergence
import com.tether.app.ui.statusline.taskReading
import com.tether.app.ui.statusline.windowReading
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
 * T9.1: the state-mapping layer of the inspector (components/inspector.tsx 289-735 and its parts:
 * repository-panel.tsx, worktree-services-card.tsx, the CodexNotices lead-in). Pure: the session
 * row, the reducer's projection and the client's per-session replies in; one [InspectorModel] out,
 * section by section in the web's order. Nothing here sends anything.
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

@Immutable
data class InspectorModel(
    val identity: Identity,
    /** The roster (every run in the session) and the selected run, resolved by lookup. */
    val runs: List<SubagentRun>,
    val activeRunId: String?,
    val usage: UsageSection,
    val repository: RepositorySection?,
    val limits: LimitsSection,
    /** Every provider but opencode shows the MCP card (inspector.tsx:534). */
    val mcpHealth: Boolean,
    val services: ServicesSection?,
    /** `codex-app-server-v2` only (inspector.tsx:547-549). */
    val codexNotices: List<ProviderNoticeView>,
    /** `opencode-serve-v2` only: the "Plugins" card (inspector.tsx:554-556). */
    val opencodePlugins: Boolean,
    /** A run is selected: "Session" states that everything below is session-scoped again. */
    val sessionDivider: Boolean,
    val runtime: List<SpecRow>,
    /** The acp engine's advertised capability set; null for every other provider. */
    val acpCapabilities: List<Pair<String, Boolean>>?,
)

/** `.inspector-heading`: the provider mark, its label and the session status in words. */
@Immutable
data class Identity(val provider: String, val providerLabel: Seg, val status: String, val statusText: Seg)

sealed interface UsageSection

/** inspector.tsx:410-471: the session's Usage block. */
@Immutable
data class SessionUsage(
    /** "Live", or "Snapshot · 09:05 AM" when the context reading came from the transcript. */
    val badge: String,
    /** "Processed" when the provider reported a cache split, else "Tokens". */
    val totalLabel: String,
    val totalValue: String,
    val totalCaption: String,
    /** The last turn's whole-tree tokens; null when not reported (the cell is omitted). */
    val lastTurn: String?,
    val subagentsSpawned: String,
    val context: ContextReading?,
    val snapshot: ContextSnapshotReading?,
    val perModel: List<ModelUsageRow>,
) : UsageSection {
    /** "Per-model breakdown", with the count when there is more than one. */
    val perModelSummary: String
        get() = "Per-model breakdown" + if (perModel.size > 1) " (${perModel.size})" else ""
}

/** One `ModelUsageEstimate` (inspector.tsx:154-179). */
@Immutable
data class ModelUsageRow(val identity: Seg, val contributors: List<Seg>, val provider: Line, val figures: List<String>)

/** inspector.tsx:190-255: the Usage block scoped to ONE sub-agent run. */
@Immutable
data class RunUsage(
    val runId: String,
    val title: Seg,
    val status: String,
    val tokens: String,
    val tokensCaption: String,
    val steps: String,
    val stepsCaption: String,
    /** Why the run has no token reading (never a "—" standing for zero); null when measured. */
    val gap: String?,
    val specs: List<SpecRow>,
) : UsageSection

/** One `.telemetry-specs` row: an uppercase label, a mono value and its notes. */
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

/** A `.telemetry-note` (faint) or `.model-divergence` (warning) under a value; [status] = role="status". */
@Immutable
data class Note(val line: Line, val warning: Boolean = false, val status: Boolean = false)

/** repository-panel.tsx: the branch, the linked pull request and the changes (read-only). */
@Immutable
data class RepositorySection(
    val branch: Seg?,
    val divergence: String?,
    /** Null when no change-request reply has arrived for the session. */
    val pullRequest: PullRequestLine?,
    val changes: WorktreeDiffSummaryView?,
    val changedFiles: Int,
) {
    val changesCount: String get() = "$changedFiles ${if (changedFiles == 1) "file" else "files"}"
}

/** `inspector-pull-request`: the headline ("Pull request #12", "No pull request", "PR status unavailable") and its state. */
@Immutable
data class PullRequestLine(val headline: String, val state: String?)

/** inspector.tsx:488-513 (Account limits). The reset actions are T9.2's: display only here. */
@Immutable
data class LimitsSection(
    val windows: List<Pair<String, WindowReading>>,
    /** No metrics at all yet: "Telemetry appears after the agent completes its first response." */
    val awaitingTelemetry: Boolean,
    /** Claude: "1 reset left · expires in 26 d" and its note; null when there is nothing to say. */
    val resetGrants: Pair<String, String>?,
    /** Codex: the banked reset count ("2 available"); null when none (or never checked). */
    val bankedResets: String?,
) {
    val empty: Boolean get() = windows.isEmpty() && !awaitingTelemetry && resetGrants == null && bankedResets == null
}

/** worktree-services-card.tsx, display only (running and stopping services is T8.3's). */
@Immutable
data class ServicesSection(
    val count: String,
    val setup: ServiceSetup?,
    val configWarnings: List<Seg>,
    val scripts: List<ServiceRow>,
)

@Immutable
data class ServiceSetup(val text: String, val failed: Boolean, val log: Seg?)

@Immutable
data class ServiceRow(
    val name: Seg,
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
)

/**
 * T15.7: a running service's "Open" link: [url] (the console's pinned worktree-open route, resolved
 * against the paired origin) opens in the external browser after the confirm sheet, which shows
 * [host] (the service's own hostname, `proxyHost`, drawn by the line rule). [toString] never
 * prints the URL, so a model dumped into a log or a test failure does not carry it.
 */
@Immutable
data class ServiceOpen(val url: String, val host: Seg) {
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
)

// --- The mapping -----------------------------------------------------------------------------

/** inspector.tsx 289-735, from the session, the provider list and the projection. */
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
): InspectorModel {
    val activeRun = selectedRunId?.let { id -> runs.firstOrNull { it.runId == id } }
    val metrics = TelemetryMetrics.from(session.metrics)
    return InspectorModel(
        identity = identity(session, providers),
        runs = runs,
        activeRunId = activeRun?.runId,
        usage = if (activeRun != null) runUsage(activeRun) else sessionUsage(session, state, runs, metrics, env),
        repository = repository(session, replies),
        limits = limits(session, env),
        mcpHealth = session.provider != "opencode" && state != null,
        services = if (session.worktree != null) services(replies.worktreeScripts, session.id, serverOrigin) else null,
        codexNotices = if (session.engineGeneration == "codex-app-server-v2" && state != null) {
            // Render-only here: the transcript's copy of each notice carries the dismiss X.
            providerNotices(state.obj["providerNotices"], "Codex").map { it.copy(dismissKey = null) }
        } else {
            emptyList()
        },
        opencodePlugins = session.engineGeneration == "opencode-serve-v2" && state != null,
        sessionDivider = activeRun != null,
        runtime = runtime(session, state),
        acpCapabilities = if (session.provider == "acp") acpCapabilities(providers) else null,
    )
}

internal fun identity(session: AgentSession, providers: List<ProviderInfo>): Identity {
    val named = providers.firstOrNull { it.id == session.provider }?.label?.let(LabelText::label)?.takeIf { it.isNotEmpty() }
    val status = Format.statusCopy[session.status]?.let(::app) ?: label(session.status)
    return Identity(session.provider, named?.let { Seg(it, Rule.Label) } ?: label(session.provider), session.status, status)
}

// ---- Usage

/** inspector.tsx:134-142: the newest turn that carries usage, and its id. */
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

/** inspector.tsx:144-146: `Intl.NumberFormat("en")` (grouped, up to 3 fraction digits), "—" when not finite. */
internal fun usageNumber(value: JsValue?): String {
    val d = finiteOf(value) ?: return "—"
    val format = DecimalFormat("#,##0.###", DecimalFormatSymbols(Locale.US)).apply { roundingMode = RoundingMode.HALF_UP }
    return format.format(d)
}

internal fun sessionUsage(
    session: AgentSession,
    state: SessionView?,
    runs: List<SubagentRun>,
    metrics: TelemetryMetrics?,
    env: ReadingEnv,
): SessionUsage {
    val raw = session.metrics
    val context = contextReading(metrics, env)
    val snapshot = contextSnapshotReading(metrics, env)
    val cacheRead = raw?.cacheReadInputTokens?.toDouble()
    val cacheMiss = raw?.cacheMissInputTokens?.toDouble()
    val split = cacheRead != null || cacheMiss != null
    val caption = when {
        split -> "${Format.compactNumber(cacheRead?.let(::JsNum))} cached · ${Format.compactNumber(cacheMiss?.let(::JsNum))} fresh"
        context == null && (raw?.contextWindow ?: 0L) != 0L ->
            "${Format.compactNumber(raw!!.contextWindow!!.toDouble())} context"
        else -> "Current session"
    }
    val latest = latestTurn(state)
    val usage = latest?.second
    val lastTurnRuns = latest?.let { (turnId, _) -> runs.filter { it.turnId == turnId } }.orEmpty()
    val perModel = (usage?.get("modelUsages") as? JsArr)?.mapNotNull { it as? JsObj }.orEmpty()
        .take(LabelText.MAX_ITEMS)
        .map { entry -> modelUsageRow(entry, lastTurnRuns, strOf(usage?.get("model")), strOf(usage?.get("rawModel"))) }
    val n = runs.size
    return SessionUsage(
        badge = snapshot?.let { "Snapshot · ${it.asOf}" } ?: "Live",
        totalLabel = if (split) "Processed" else "Tokens",
        totalValue = Format.compactNumber(raw?.totalTokens?.toDouble()?.let(::JsNum)),
        totalCaption = caption,
        lastTurn = finiteOf(usage?.get("perTurnTokens"))?.let { Format.compactNumber(it) },
        subagentsSpawned = "$n ${if (n == 1) "subagent" else "subagents"} spawned",
        context = context,
        snapshot = snapshot,
        perModel = perModel,
    )
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
        figures = listOf(
            "${usageNumber(entry["inputTokens"])} in · ${usageNumber(entry["outputTokens"])} out",
            "${usageNumber(entry["cacheReadInputTokens"])} cache read · ${usageNumber(entry["cacheCreationInputTokens"])} cache write",
            "${usageNumber(entry["webSearchRequests"])} web searches",
            "${usageNumber(entry["contextWindow"])} context · ${usageNumber(entry["maxOutputTokens"])} max output",
        ),
    )
}

internal fun runUsage(run: SubagentRun): RunUsage {
    val measured = run.totalTokens != null
    val thread = run.source == RunSource.THREAD
    val served = strOf(run.usage?.get("model"))?.takeIf { it.isNotEmpty() }
    val requested = run.requestedModel?.takeIf { it.isNotEmpty() }
    val specs = buildList {
        run.provider?.takeIf { it.isNotEmpty() }?.let { provider ->
            add(SpecRow("Harness", listOf(label(provider)), if (run.source == RunSource.DELEGATE) listOf(Note(listOf(app("Delegated Tether session")))) else emptyList()))
        }
        run.agentType?.takeIf { it.isNotEmpty() }?.let { add(SpecRow("Agent type", listOf(label(it)))) }
        if (served != null) {
            val note = if (requested != null && requested != served) {
                listOf(Note(listOf(app("Requested "), code(requested), app(", but this is what served it"))))
            } else {
                emptyList()
            }
            add(SpecRow("Model", listOf(code(served)), note))
        } else if (requested != null) {
            add(SpecRow("Model", listOf(code(requested)), listOf(Note(listOf(app("Requested; served model not captured"))))))
        }
        run.requestedEffort?.takeIf { it.isNotEmpty() }?.let {
            add(SpecRow("Effort", listOf(label(it)), listOf(Note(listOf(app("Requested on the Agent call"))))))
        }
    }
    return RunUsage(
        runId = run.runId,
        title = label(run.title),
        status = STATUS_TEXT[run.status] ?: run.status,
        tokens = if (measured) Format.compactNumber(run.totalTokens!!) else "—",
        tokensCaption = if (measured) "This sub-agent only" else "Not captured",
        steps = if (thread) "—" else run.steps.toString(),
        stepsCaption = if (thread) "Not streamed" else "Recorded",
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
        cr.string("reviewDecision")?.let { CR_REVIEW[it] ?: LabelText.label(it) },
        if (cr.string("mergeable") == "CONFLICTING") "Conflicts" else null,
    ).filter { it.isNotEmpty() }.joinToString(" · ")
}

internal fun pullRequestLine(reading: ChangeRequestReading): PullRequestLine {
    if (reading.unknown) return PullRequestLine("PR status unavailable", null)
    val cr = reading.changeRequest ?: return PullRequestLine("No pull request", null)
    val number = cr.number("number")?.let(::numberToString) ?: "?"
    return PullRequestLine("Pull request #$number", changeRequestLine(cr).ifEmpty { null })
}

internal fun repository(session: AgentSession, replies: InspectorReplies): RepositorySection? {
    val branch = session.metrics?.gitBranch?.takeIf { it.isNotEmpty() } ?: session.worktree?.branch?.takeIf { it.isNotEmpty() }
    val diff = worktreeDiffSummary(replies.worktreeDiff)
    val cr = replies.changeRequest
    if (branch == null && diff == null && cr == null) return null
    val changed = diff?.let { (it.committed + it.uncommitted).map { e -> e.path }.toSet().size } ?: 0
    return RepositorySection(
        branch = branch?.let(::code),
        divergence = gitDivergence(session.metrics?.gitAhead?.toDouble(), session.metrics?.gitBehind?.toDouble()),
        pullRequest = cr?.let(::pullRequestLine),
        changes = diff,
        changedFiles = changed,
    )
}

// ---- Account limits

internal fun jsOf(element: JsonElement?): JsValue = if (element == null) JsNull else JsCodec.fromJson(element)

internal fun limits(session: AgentSession, env: ReadingEnv): LimitsSection {
    val metrics = session.metrics
    val t = TelemetryMetrics.from(metrics)
    val windows = buildList {
        t?.fiveHour?.let { add("5 hour" to windowReading(it, env)) }
        t?.weekly?.let { add("Weekly" to windowReading(it, env)) }
        t?.fable?.let { add("Fable" to windowReading(it, env)) }
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
        windows = windows,
        awaitingTelemetry = metrics == null,
        resetGrants = resetGrants,
        bankedResets = available?.let { "${numberToString(it)} available" },
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
 * worktree-services-card.tsx; null when the card would be an empty frame. Display only but for
 * T15.7's "Open" link, which exists only for [sessionId] on [serverOrigin] (see [ServiceOpenLink]).
 */
internal fun services(snapshot: JsonObject?, sessionId: String? = null, serverOrigin: String? = null): ServicesSection? {
    if (snapshot == null) return null
    val scripts = (snapshot["scripts"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty().take(LabelText.MAX_ITEMS)
    val setupStatus = snapshot.string("setupStatus")
    val running = setupStatus == "running" || setupStatus == "pending"
    val failed = setupStatus == "failed"
    val warnings = snapshot.strings("configWarnings").take(LabelText.MAX_ITEMS)
    if (scripts.isEmpty() && !running && !failed && warnings.isEmpty()) return null
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
        configWarnings = warnings.map(::prose),
        scripts = scripts.mapNotNull { serviceRow(it, sessionId, serverOrigin) },
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
    )
}

/**
 * T15.7: the row's "Open" link, or null. Fail closed: a service the server gives a reason for
 * (`proxyUnavailable` present and not null, whatever its value) or no `proxyHost` (the confirm sheet
 * must name the service) has no link, nor has any `proxyAuthUrl` [ServiceOpenLink.resolve] refuses.
 * The "On this machine" link (`proxyPath`, the console's loopback-only path form) is never offered:
 * the app is not a browser on the daemon's machine (TRACKER Decision log, 2026-10-01).
 */
private fun serviceOpen(o: JsonObject, name: String, sessionId: String?, serverOrigin: String?): ServiceOpen? {
    if (sessionId == null) return null
    if (o["proxyUnavailable"].let { it != null && it !is JsonNull }) return null
    val host = o.string("proxyHost")?.takeIf { it.isNotEmpty() } ?: return null
    val url = ServiceOpenLink.resolve(o.string("proxyAuthUrl"), sessionId, name, serverOrigin) ?: return null
    return ServiceOpen(url, code(host))
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

internal fun runtime(session: AgentSession, state: SessionView?): List<SpecRow> = buildList {
    taskReading(state?.todo)?.let { task ->
        add(SpecRow("Current task", listOf(prose(task.text)), listOfNotNull(task.progress?.let { Note(listOf(app(it))) })))
    }
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
        add(SpecRow("Worktree branch", listOf(code(w.branch)), listOfNotNull(base?.let { Note(it) })))
        val setup = w.setupStatus
        if (setup != null && setup != "none" && setup != "ok") {
            val failed = setup == "failed"
            add(
                SpecRow(
                    "Worktree setup",
                    listOf(app(if (failed) "Failed" else "Running")),
                    listOf(
                        Note(
                            listOf(
                                app(
                                    if (failed) {
                                        "This project's setup commands did not finish. The checkout is still usable — see the Services panel for the log."
                                    } else {
                                        "Running this project's setup commands. The first turn starts when they finish."
                                    },
                                ),
                            ),
                            status = true,
                        ),
                    ),
                ),
            )
        }
        w.configWarningList.take(LabelText.MAX_ITEMS).forEach { warning ->
            add(SpecRow("Worktree config", emptyList(), listOf(Note(listOf(prose(warning)), status = true))))
        }
    }
    add(modelRow(session, state))
    add(SpecRow("Effort", listOf(session.metrics?.effort?.takeIf { it.isNotEmpty() }?.let(::label) ?: app("—")), capitalize = true))
    val email = session.metrics?.accountEmail?.takeIf { it.isNotEmpty() }
    if (session.provider == "claude" && email != null) {
        // The account identity is an id: the one-line rule, so a look-alike shows its hidden code points.
        add(SpecRow("Account", listOf(code(email)), listOfNotNull(session.metrics?.accountOrganization?.takeIf { it.isNotEmpty() }?.let { Note(listOf(code(it))) })))
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
    val obj = state?.obj
    strOf(obj?.get("cliVersion"))?.takeIf { it.isNotEmpty() }?.let { version ->
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

/** inspector.tsx:355-364, 652-663: the configured model and, when it demonstrably differs, what served it. */
internal fun modelRow(session: AgentSession, state: SessionView?): SpecRow {
    val latest = latestTurn(state)
    val served = strOf(latest?.second?.get("model"))?.takeIf { it.isNotEmpty() } ?: session.metrics?.model
    val fallback = latest?.let { (turnId, _) -> (state?.turn(turnId)?.obj?.get("modelFallbacks") as? JsArr)?.lastOrNull() } ?: JsNull
    val reading = ModelPicker.modelReading(
        JsObj.of(
            "configured" to (session.model?.let(::JsStr) ?: JsNull),
            "served" to (served?.let(::JsStr) ?: JsNull),
            "fallback" to fallback,
        ),
    )
    val readingLabel = strOf(reading["label"]).orEmpty()
    val value = buildList {
        add(if (readingLabel == "—") app("—") else code(readingLabel))
        strOf(reading["lastServed"])?.let {
            add(app(" · last served "))
            add(code(it))
        }
    }
    val note = strOf(reading["note"])?.let { listOf(Note(listOf(prose(it)), warning = true, status = true)) }.orEmpty()
    return SpecRow("Model", value, note)
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
