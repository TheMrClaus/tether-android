package com.tether.app.protocol.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Wire shape of an AgentSession row (server publicSession()). */
@Serializable
data class AgentSession(
    val id: String,
    val provider: String,
    val engineGeneration: String? = null,
    val name: String,
    val cwd: String,
    val runtimeCwd: String? = null,
    val worktree: WorktreeInfo? = null,
    val status: String,
    val startedAt: Long,
    val updatedAt: Long,
    val endedAt: Long? = null,
    val exitCode: Int? = null,
    val historyId: String? = null,
    val pinned: Boolean = false,
    val runtimeArchived: Boolean = false,
    val metrics: SessionMetrics? = null,
    val mode: String = "headless",
    val nativeSessionId: String? = null,
    val resumeTargetNativeId: String? = null,
    val permissionMode: String? = null,
    val model: String? = null,
    val sandboxPolicy: String? = null,
    val lastTurnOutcome: String? = null,
    // T5.1: the sidebar fields (lib/protocol.ts AgentSession). v70 `lastMessageAt` keys the
    // "last-active" sort; `nameIsCustom` keeps an operator rename over the discovered title;
    // v101 `parentSessionId` groups delegate children, `handedOffTo` badges a handed-off source.
    val lastMessageAt: Long? = null,
    // v130 (S13.1, SYNC_DESIGN §6.1 A): the session's journal head seq as of serialization, on
    // every AgentSession the server emits. A HINT: the attach reply's `throughSeq` stays
    // authoritative. Absent when the server holds no journal for the session, or before v130.
    val lastSeq: Long? = null,
    val nameIsCustom: Boolean = false,
    val parentSessionId: String? = null,
    val handedOffTo: String? = null,
    // T6.3: lib/protocol.ts:1723 — an imported replay (or replay-only provider) Tether does not drive.
    val readOnly: Boolean = false,
    // T7.2 (lib/protocol.ts:1733-1768): the session settings the composer row reads.
    /** The operator-selected reasoning effort (opencode `--variant`, Claude SDK `effort`). */
    val reasoningEffort: String? = null,
    /** v95: Claude fast mode, the CLI's own report: "off" | "cooldown" | "on". */
    val fastModeState: String? = null,
    val fastModeDisabledReason: String? = null,
    /** Codex: the applied collaboration mode (matched against the catalog's items). */
    val collaborationMode: CollaborationMode? = null,
    /** Codex + OpenCode: "never" behind their Auto toggle; null keeps interactive prompts. */
    val approvalPolicy: String? = null,
    /** v101 (T6.6): Claude / Codex "auto-continue when the limit resets" (set-auto-continue-on-limit). */
    val autoContinueOnLimit: Boolean = false,
    /** v74 (T9.1, lib/protocol.ts AgentSession): the generic `acp` engine's agent id; absent for other providers. */
    val acpAgentId: String? = null,
    /**
     * v135 (issue #227 part 2): how this session came to exist — "console" | "api-requested" |
     * "api-agent" | "delegate" | "handoff" | "adopted", null on a manifest predating the field.
     * DISPLAY METADATA ONLY: nothing may branch an approval, consent, scope or lease on it. Raw so
     * a malformed stamp never drops the session row; read it with [createdViaStamp].
     */
    val createdVia: JsonElement? = null,
) {
    /** [createdVia] when it is a JSON string (an unknown future stamp is kept as-is), else null. */
    val createdViaStamp: String?
        get() = (createdVia as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** TS `AgentSession.collaborationMode`: `{ mode, settings: { model, reasoning_effort } }`. */
@Serializable
data class CollaborationMode(
    val mode: String? = null,
    val settings: CollaborationSettings? = null,
)

@Serializable
data class CollaborationSettings(
    val model: String? = null,
    @kotlinx.serialization.SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

@Serializable
data class WorktreeInfo(
    val path: String,
    val branch: String,
    val status: String,
    val notice: String? = null,
    // T9.1: the v98 fields the inspector's Runtime details read (lib/protocol.ts SessionWorktree).
    // All absent on a pre-v98 manifest (the legacy `tether/<id>` layout).
    val slug: String? = null,
    /** "branch-off" | "checkout-branch" | "checkout-pr" (open: an unknown mode reads as branch-off). */
    val mode: String? = null,
    val baseRef: String? = null,
    val baseCommit: String? = null,
    val prNumber: Long? = null,
    /** "none" | "pending" | "running" | "ok" | "failed". */
    val setupStatus: String? = null,
    /**
     * Bounded prose from the project-config parser, raw: a `string[]` on the wire, kept as the
     * element so a malformed list never drops the session row (read it with [configWarningList]).
     */
    val configWarnings: JsonElement? = null,
) {
    /** [configWarnings]' string elements, in order (anything else is skipped). */
    val configWarningList: List<String>
        get() = (configWarnings as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
}

@Serializable
data class SessionMetrics(
    val model: String? = null,
    val effort: String? = null,
    val totalTokens: Long? = null,
    val contextWindow: Long? = null,
    val subagents: Int? = null,
    val fiveHour: UsageWindow? = null,
    val weekly: UsageWindow? = null,
    val fable: UsageWindow? = null,
    val contextPercent: Double? = null,
    val contextTokens: Long? = null,
    val sessionCostUSD: Double? = null,
    val gitBranch: String? = null,
    val gitAhead: Int? = null,
    val gitBehind: Int? = null,
    // T9.1: the SessionMetrics fields the inspector reads (lib/protocol.ts SessionMetrics, all <= v126).
    /** Cache read / miss partition the provider-cumulative input tokens (not additive to [totalTokens]). */
    val cacheReadInputTokens: Long? = null,
    val cacheMissInputTokens: Long? = null,
    /** Issue #164: set when the context reading came from the transcript tail (its wall-clock ms), never live. */
    val contextSnapshotAt: Long? = null,
    /** v92: the Claude identity this session talks to (Claude only; absent until resolved). */
    val accountEmail: String? = null,
    val accountOrganization: String? = null,
    /**
     * v90: `CodexResetCreditsSummary` (Codex only), raw so a malformed summary never drops the row.
     * Present-but-empty (`availableCount: 0`) differs from absent (never checked).
     */
    val codexResetCredits: JsonElement? = null,
    /** v126: `ClaudeResetGrantsSummary` (Claude only; a cache the Usage page fills), raw like [codexResetCredits]. */
    val claudeResetGrants: JsonElement? = null,
)

@Serializable
data class UsageWindow(
    val usedPercent: Double,
    val windowMinutes: Long,
    val resetsAt: Long? = null,
)

@Serializable
data class ProviderInfo(
    val id: String,
    val label: String,
    val glyph: String,
    val available: Boolean,
    val capabilities: ProviderCapabilities? = null,
)

@Serializable
data class ProviderCapabilities(
    val persistentSessions: Boolean = false,
    val interactiveApprovals: Boolean = false,
    val interactiveQuestions: Boolean = false,
    val sandboxChoices: Boolean = false,
    val streamingText: Boolean = false,
    val streamingToolOutput: Boolean = false,
    val tokenDeltas: Boolean = false,
    val reasoningVisibility: Boolean = false,
    val reasoningSummaries: Boolean = false,
    val plans: Boolean = false,
    val diffs: Boolean = false,
    val modelSelection: Boolean = false,
    val collaborationModes: Boolean = false,
    val providerControls: Boolean = false,
    val providerCatalogs: Boolean = false,
    /**
     * T7.3 (v53, lib/protocol.ts:974): the composer's `!` command mode is offered for this
     * provider (a server-side Tether feature, not an engine one). False when absent: the app never
     * offers command mode unless the server says so.
     */
    val commandRunner: Boolean = false,
)

@Serializable
data class HistorySession(
    val historyId: String,
    val provider: String,
    val name: String,
    val cwd: String,
    val updatedAt: Long,
    val digest: HistoryDigest? = null,
    // T5.1: the sidebar fields (lib/protocol.ts HistorySession). v68 `lastSeenAt` is the
    // server's authoritative seen stamp; v122 `origin` / `spawnedBy` drive the agent-CLI lens.
    val createdAt: Long? = null,
    val lastSeenAt: Long? = null,
    val origin: String? = null,
    val spawnedBy: HistorySpawnLink? = null,
    // T5.2: v89 (issue #105) the profile the transcript was written under; absent for the
    // implicit default profile. Sent back on `resume` (use-tether.ts resumeHistory).
    val profileId: String? = null,
)

/** v122 HistorySession.spawnedBy (lib/protocol.ts HistorySpawnLink). */
@Serializable
data class HistorySpawnLink(
    val tetherSessionId: String,
    val spawnKey: String? = null,
    val completion: String? = null,
)

@Serializable
data class HistoryDigest(val newTurns: Int, val snippet: String)

@Serializable
data class DirectoryListing(
    val current: String,
    val parent: String? = null,
    val entries: List<DirectoryEntry> = emptyList(),
)

@Serializable
data class DirectoryEntry(val name: String, val path: String)
