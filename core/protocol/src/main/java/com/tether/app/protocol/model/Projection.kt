package com.tether.app.protocol.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Kotlin port of Tether's SessionProjection (see specs/reducer-spec.md and
 * aidash/lib/protocol.ts). These classes are BOTH the
 * wire shape of `snapshot.state` (deserialized with ignoreUnknownKeys=true,
 * explicitNulls=false) and the in-memory state folded by the reducer.
 *
 * Conventions:
 * - Vocabulary fields (status, outcome, reason, ...) are Strings, stored
 *   verbatim for forward-compat; constants live in [Vocab].
 * - "absent key" in JS maps to null here (elapsedSeconds, aborted, exit, ...).
 * - Timestamps are epoch milliseconds (Long); they come from journal `ts`,
 *   never from the device clock.
 */

object Vocab {
    const val OUTCOME_OK = "ok"
    const val OUTCOME_CANCELLED = "cancelled"
    const val OUTCOME_ERROR = "error"
    const val OUTCOME_UNKNOWN = "outcome_unknown"

    const val SESSION_READY = "ready"
    const val SESSION_ACTIVE = "active"
    const val SESSION_WAITING = "waiting"
    const val SESSION_EXITED = "exited"

    const val TURN_RUNNING = "running"
    const val TURN_CANCELLING = "cancelling"
    const val TURN_DONE = "done"

    const val BLOCK_USER_MESSAGE = "user_message"
    const val BLOCK_MESSAGE = "message"
    const val BLOCK_THINKING = "thinking"
    const val BLOCK_TOOL = "tool"

    const val RATE_LIMIT_RESUME_DELAY_MS = 120_000L
    const val RATE_LIMIT_RESUME_MAX_HORIZON_MS = 18_000_000L
}

@Serializable
data class SessionProjection(
    val tetherSessionId: String,
    val provider: String,
    val cwd: String,
    val nativeSessionId: String? = null,
    val cliCapabilities: List<String> = emptyList(),
    val cliVersion: String? = null,
    val cliInventory: CliInventory? = null,
    val mcpHealth: Map<String, McpHealthProjection> = emptyMap(),
    val rateLimit: RateLimitState? = null,
    val rateLimitResume: RateLimitResumeState? = null,
    val todo: TodoProjection? = null,
    val status: String = Vocab.SESSION_READY,
    val lastTurnOutcome: String? = null,
    val lastError: String? = null,
    val unattributedPermissionDenials: List<PermissionDenialProjection> = emptyList(),
    val providerNotices: List<ProviderNoticeProjection> = emptyList(),
    val notices: List<SessionNotice> = emptyList(),
    val turnOrder: List<String> = emptyList(),
    val turnsById: Map<String, TurnProjection> = emptyMap(),
    val activeTurnId: String? = null,
    val queuedMessages: List<QueuedMessage> = emptyList(),
)

@Serializable
data class TurnProjection(
    val turnId: String,
    val idempotencyKey: String? = null,
    val continuation: Boolean = false,
    val status: String = Vocab.TURN_RUNNING,
    val startedAt: Long? = null,
    val liveTokens: Long? = null,
    val run: TurnRun? = null,
    val runCount: Int = 0,
    val activeMs: Long = 0,
    val outcome: String? = null,
    val blocks: List<String> = emptyList(),
    val blocksById: Map<String, TurnBlock> = emptyMap(),
    val pendingApprovals: Map<String, PendingApproval> = emptyMap(),
    val pendingQuestions: Map<String, PendingQuestion> = emptyMap(),
    val permissionDenials: List<PermissionDenialProjection> = emptyList(),
    val usage: TurnUsage? = null,
    val apiRetry: ApiRetryState? = null,
    val plan: PlanProjection? = null,
    val diff: DiffProjection? = null,
    val modelReroutes: List<ModelRerouteProjection> = emptyList(),
    val reviews: List<ReviewProjection> = emptyList(),
    val compactions: List<CompactionProjection> = emptyList(),
    val providerNotices: List<ProviderNoticeProjection> = emptyList(),
    val error: String? = null,
    /** Written by process_exit; undeclared in protocol.ts but present on the wire. */
    val exit: ProcessExit? = null,
    val warnings: List<TurnWarning> = emptyList(),
)

@Serializable
data class TurnRun(val index: Int, val startedAt: Long, val tokensStart: Long)

@Serializable
data class ProcessExit(val code: Int? = null, val signal: String? = null)

@Serializable
data class TurnWarning(val type: String, val message: String? = null, val raw: JsonElement? = null)

@Serializable
data class TurnBlock(
    val blockId: String,
    val kind: String,
    val text: String? = null,
    val attachments: List<AttachmentMeta>? = null,
    val name: String? = null,
    val input: JsonElement? = null,
    val output: JsonElement? = null,
    val isError: Boolean? = null,
    val done: Boolean? = null,
    /** Present only while a tool is live; DELETED (null) on tool_end. */
    val elapsedSeconds: Double? = null,
    /** Only ever literal true; never false. */
    val aborted: Boolean? = null,
    val subagent: SubagentThread? = null,
)

@Serializable
data class AttachmentMeta(val name: String, val mediaType: String)

@Serializable
data class SubagentThread(
    val order: List<String> = emptyList(),
    val entries: Map<String, SubagentEntry> = emptyMap(),
    val usage: SubagentUsage? = null,
)

@Serializable
data class SubagentEntry(
    val key: String,
    val kind: String,
    val text: String? = null,
    val name: String? = null,
    val input: JsonElement? = null,
    val output: JsonElement? = null,
    val isError: Boolean? = null,
    val done: Boolean? = null,
    val elapsedSeconds: Double? = null,
)

@Serializable
data class SubagentUsage(
    val model: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadInputTokens: Long? = null,
    val cacheCreationInputTokens: Long? = null,
)

@Serializable
data class PendingApproval(
    val requestId: String,
    val toolId: String,
    val name: String,
    val input: JsonElement? = null,
    val choices: List<ApprovalChoice>? = null,
    val metadata: ApprovalRequestMetadata? = null,
)

@Serializable
data class ApprovalChoice(
    val choiceId: String,
    val label: String,
    val description: String? = null,
    /** "exact" | "subset" */
    val permissionGrant: String? = null,
)

@Serializable
data class ApprovalRequestMetadata(
    val provider: String,
    val kind: String,
    val reason: String? = null,
    val command: String? = null,
    val cwd: String? = null,
    val paths: List<String>? = null,
    val network: ApprovalNetwork? = null,
    val requestedPermissions: GrantedPermissions? = null,
)

@Serializable
data class ApprovalNetwork(val host: String, val protocol: String? = null, val port: Int? = null)

@Serializable
data class GrantedPermissions(
    val fileSystem: GrantedFileSystem? = null,
    val network: GrantedNetwork? = null,
)

@Serializable
data class GrantedFileSystem(val read: List<String>? = null, val write: List<String>? = null)

@Serializable
data class GrantedNetwork(val enabled: Boolean)

@Serializable
data class PendingQuestion(
    val requestId: String,
    val toolId: String,
    val questions: List<QuestionPrompt> = emptyList(),
)

@Serializable
data class QuestionPrompt(
    val question: String,
    val header: String,
    val multiSelect: Boolean = false,
    val options: List<QuestionOption> = emptyList(),
)

@Serializable
data class QuestionOption(
    val label: String,
    val description: String = "",
    val preview: String? = null,
)

@Serializable
data class PermissionDenialProjection(
    val toolId: String,
    val name: String,
    val reason: String = "unknown",
    val reasonCode: String? = null,
    val subagent: Boolean? = null,
)

@Serializable
data class ProviderNoticeProjection(
    val noticeId: String,
    val level: String = "warning",
    val code: String? = null,
    val message: String,
)

@Serializable
data class SessionNotice(
    val kind: String,
    val outstanding: Long? = null,
    val count: Long? = null,
    val reason: String? = null,
    val seq: Long? = null,
)

@Serializable
data class QueuedMessage(val queueId: String, val text: String)

@Serializable
data class TurnUsage(
    val model: String? = null,
    val rawModel: String? = null,
    val perTurnTokens: Long? = null,
    val cumulativeTokens: Long? = null,
    val contextWindow: Long? = null,
    val estimatedCostUSD: Double? = null,
    val modelUsages: List<ModelUsageEntry>? = null,
)

@Serializable
data class ModelUsageEntry(
    val model: String,
    val canonicalModel: String? = null,
    val provider: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadInputTokens: Long? = null,
    val cacheCreationInputTokens: Long? = null,
    val webSearchRequests: Long? = null,
    val costUSD: Double? = null,
    val contextWindow: Long? = null,
    val maxOutputTokens: Long? = null,
)

@Serializable
data class ApiRetryState(
    val attempt: Int,
    val maxRetries: Int? = null,
    val delayMs: Long? = null,
    val errorStatus: Int? = null,
    val error: String? = null,
)

@Serializable
data class PlanProjection(
    val explanation: String? = null,
    val steps: List<PlanStepProjection> = emptyList(),
)

@Serializable
data class PlanStepProjection(val step: String, val status: String)

@Serializable
data class DiffProjection(val unifiedDiff: String)

@Serializable
data class ModelRerouteProjection(val fromModel: String, val toModel: String, val reason: String)

@Serializable
data class ReviewProjection(
    val reviewId: String,
    val status: String,
    val target: String? = null,
    val result: String? = null,
)

@Serializable
data class CompactionProjection(val itemId: String)

@Serializable
data class McpHealthProjection(
    val name: String,
    val status: String = "unknown",
    val error: String? = null,
    val failureReason: String? = null,
)

@Serializable
data class RateLimitState(
    val status: String,
    val limitType: String? = null,
    val utilization: Double? = null,
    val resetsAt: Long? = null,
)

@Serializable
data class RateLimitResumeState(
    val status: String,
    val resetsAt: Long,
    val resumeAt: Long,
)

@Serializable
data class CliInventory(
    val commands: List<CliCommand> = emptyList(),
    val tools: List<String> = emptyList(),
    val mcpServers: List<CliMcpServer> = emptyList(),
)

@Serializable
data class CliCommand(
    val name: String,
    val description: String? = null,
    val argumentHint: String? = null,
    val aliases: List<String>? = null,
)

@Serializable
data class CliMcpServer(val name: String, val status: String = "unknown")

@Serializable
data class TodoProjection(
    val items: List<TodoItemProjection> = emptyList(),
    val activeForm: String? = null,
    val completed: Int = 0,
    val total: Int = 0,
)

@Serializable
data class TodoItemProjection(
    val content: String,
    val activeForm: String = "",
    val status: String,
)
