package com.tether.app.protocol.model

import kotlinx.serialization.Serializable

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
)

@Serializable
data class WorktreeInfo(
    val path: String,
    val branch: String,
    val status: String,
    val notice: String? = null,
)

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
)

@Serializable
data class HistorySession(
    val historyId: String,
    val provider: String,
    val name: String,
    val cwd: String,
    val updatedAt: Long,
    val digest: HistoryDigest? = null,
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
