package com.tether.app.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Client -> server frames: every member of tether lib/protocol.ts `ClientMessage`
 * at v129 (see [TYPES]). Serialized by hand via buildJsonObject so field names
 * are EXACTLY what lib/protocol-validate.mjs accepts:
 *  - optional fields are omitted entirely, never sent as null — except where the
 *    TS type itself allows null ([OrNull], or a required `T | null` field);
 *  - the discriminator is always `type`.
 *
 * Deep, server-validated payloads the app does not build field-by-field yet
 * (Codex/opencode control actions, the ACP/provider registries, the partial
 * server-settings patch) ride as raw [JsonObject]s: the feature task that builds
 * them owns their typed form, and protocol-validate.mjs re-validates them anyway.
 *
 * [decode] is the inverse (used by the conformance test and for replaying a
 * persisted outbound frame); it is strict on required fields and returns a
 * failed [Result] rather than throwing.
 */
sealed interface ClientMessage {
    fun toJsonObject(): JsonObject

    fun encode(): String = TetherJson.encodeToString(JsonObject.serializer(), toJsonObject())

    // ---- handshake / liveness -------------------------------------------------

    /** v129: [client] = "android" opts into the native window (absent = web strict equality). */
    data class Hello(val protocolVersion: Int = PROTOCOL_VERSION, val client: String? = null) : ClientMessage {
        override fun toJsonObject() = frame("hello") {
            put("protocolVersion", protocolVersion)
            opt("client", client)
        }
    }

    data class Ping(val nonce: String? = null) : ClientMessage {
        override fun toJsonObject() = frame("ping") { opt("nonce", nonce) }
    }

    // ---- multi-host nodes (v109) --------------------------------------------

    data class NodeAdd(
        val credential: String,
        val label: String? = null,
        val baseUrl: String? = null,
        val requestId: String? = null,
    ) : ClientMessage {
        override fun toJsonObject() = frame("node-add") {
            put("credential", credential)
            opt("label", label)
            opt("baseUrl", baseUrl)
            opt("requestId", requestId)
        }
    }

    data class NodeRemove(val nodeId: String, val requestId: String? = null) : ClientMessage {
        override fun toJsonObject() = frame("node-remove") {
            put("nodeId", nodeId)
            opt("requestId", requestId)
        }
    }

    data class NodeProbe(val nodeId: String, val requestId: String? = null) : ClientMessage {
        override fun toJsonObject() = frame("node-probe") {
            put("nodeId", nodeId)
            opt("requestId", requestId)
        }
    }

    // ---- session creation / discovery ---------------------------------------

    data class Create(
        val provider: String,
        val cwd: String? = null,
        val name: String? = null,
        val permissionMode: String? = null,
        val sandboxPolicy: String? = null,
        val useWorktree: Boolean? = null,
        val worktree: WorktreeCreateRequest? = null,
        val acpAgentId: String? = null,
        val profileId: String? = null,
        val model: String? = null,
        val reasoningEffort: String? = null,
        /** TS `"never" | null`, optional. */
        val approvalPolicy: OrNull<String>? = null,
        /** TS `"auto_review" | null`, optional. */
        val approvalsReviewer: OrNull<String>? = null,
        val requestId: String? = null,
    ) : ClientMessage {
        override fun toJsonObject() = frame("create") {
            put("provider", provider)
            opt("cwd", cwd)
            opt("name", name)
            opt("permissionMode", permissionMode)
            opt("sandboxPolicy", sandboxPolicy)
            opt("useWorktree", useWorktree)
            if (worktree != null) put("worktree", worktree.toJsonObject())
            opt("acpAgentId", acpAgentId)
            opt("profileId", profileId)
            opt("model", model)
            opt("reasoningEffort", reasoningEffort)
            if (approvalPolicy != null) put("approvalPolicy", approvalPolicy.value)
            if (approvalsReviewer != null) put("approvalsReviewer", approvalsReviewer.value)
            opt("requestId", requestId)
        }
    }

    data class Resume(val historyId: String, val cwd: String, val profileId: String? = null) : ClientMessage {
        override fun toJsonObject() = frame("resume") {
            put("historyId", historyId)
            put("cwd", cwd)
            opt("profileId", profileId)
        }
    }

    data class Discover(
        val cwd: String,
        val lastSeen: Map<String, Long>? = null,
        val watch: List<String>? = null,
        val requestId: String? = null,
    ) : ClientMessage {
        override fun toJsonObject() = frame("discover") {
            put("cwd", cwd)
            if (lastSeen != null) {
                put("lastSeen", buildJsonObject { for ((k, v) in lastSeen) put(k, JsonPrimitive(v)) })
            }
            if (watch != null) put("watch", strings(watch))
            opt("requestId", requestId)
        }
    }

    data class MarkSeen(val historyId: String, val seenAt: Long) : ClientMessage {
        override fun toJsonObject() = frame("mark-seen") {
            put("historyId", historyId)
            put("seenAt", seenAt)
        }
    }

    data class SetSessionOrder(val cwd: String, val order: List<String>) : ClientMessage {
        override fun toJsonObject() = frame("set-session-order") {
            put("cwd", cwd)
            put("order", strings(order))
        }
    }

    data class Search(val cwd: String, val query: String) : ClientMessage {
        override fun toJsonObject() = frame("search") {
            put("cwd", cwd)
            put("query", query)
        }
    }

    data class GlobalSearch(
        val requestId: Long,
        val query: String,
        val providers: List<String>? = null,
        val since: Long? = null,
        val until: Long? = null,
        val cwd: String? = null,
    ) : ClientMessage {
        override fun toJsonObject() = frame("global-search") {
            put("requestId", requestId)
            put("query", query)
            if (providers != null) put("providers", strings(providers))
            opt("since", since)
            opt("until", until)
            opt("cwd", cwd)
        }
    }

    data class Browse(val cwd: String? = null, val requestId: String? = null) : ClientMessage {
        override fun toJsonObject() = frame("browse") {
            opt("cwd", cwd)
            opt("requestId", requestId)
        }
    }

    data class CreateFolder(val name: String, val cwd: String? = null) : ClientMessage {
        override fun toJsonObject() = frame("create-folder") {
            opt("cwd", cwd)
            put("name", name)
        }
    }

    // ---- worktrees / git (v98, #159) ----------------------------------------

    data class WorktreeInspect(val cwd: String, val requestId: String? = null) : ClientMessage {
        override fun toJsonObject() = frame("worktree-inspect") {
            put("cwd", cwd)
            opt("requestId", requestId)
        }
    }

    data class WorktreeScriptsRequest(val sessionId: String) : ClientMessage {
        override fun toJsonObject() = frame("worktree-scripts") { put("sessionId", sessionId) }
    }

    /** [action]: "start" | "stop" | "restart". */
    data class WorktreeScript(val sessionId: String, val name: String, val action: String) : ClientMessage {
        override fun toJsonObject() = frame("worktree-script") {
            put("sessionId", sessionId)
            put("name", name)
            put("action", action)
        }
    }

    data class WorktreeLogsRequest(val sessionId: String, val name: String) : ClientMessage {
        override fun toJsonObject() = frame("worktree-logs") {
            put("sessionId", sessionId)
            put("name", name)
        }
    }

    data class WorktreeDiffRequest(val sessionId: String) : ClientMessage {
        override fun toJsonObject() = frame("worktree-diff") { put("sessionId", sessionId) }
    }

    data class GitDiffFileRequest(val sessionId: String, val path: String) : ClientMessage {
        override fun toJsonObject() = frame("git-diff-file") {
            put("sessionId", sessionId)
            put("path", path)
        }
    }

    data class ChangeRequestFetch(val sessionId: String, val refresh: Boolean? = null) : ClientMessage {
        override fun toJsonObject() = frame("change-request") {
            put("sessionId", sessionId)
            opt("refresh", refresh)
        }
    }

    // ---- attach / session row controls --------------------------------------

    data class Attach(val sessionId: String, val afterSeq: Long? = null) : ClientMessage {
        override fun toJsonObject() = frame("attach") {
            put("sessionId", sessionId)
            opt("afterSeq", afterSeq)
        }
    }

    /** v115: lazy-load trimmed turns; indices into turnOrder, [toIndex] exclusive. */
    data class FetchTurns(val sessionId: String, val fromIndex: Int, val toIndex: Int) : ClientMessage {
        override fun toJsonObject() = frame("fetch-turns") {
            put("sessionId", sessionId)
            put("fromIndex", fromIndex)
            put("toIndex", toIndex)
        }
    }

    data class Pin(val sessionId: String, val pinned: Boolean) : ClientMessage {
        override fun toJsonObject() = frame("pin") {
            put("sessionId", sessionId)
            put("pinned", pinned)
        }
    }

    data class Rename(val sessionId: String, val name: String) : ClientMessage {
        override fun toJsonObject() = frame("rename") {
            put("sessionId", sessionId)
            put("name", name)
        }
    }

    data class Archive(val sessionId: String) : ClientMessage {
        override fun toJsonObject() = frame("archive") { put("sessionId", sessionId) }
    }

    data class Kill(val sessionId: String) : ClientMessage {
        override fun toJsonObject() = frame("kill") { put("sessionId", sessionId) }
    }

    // ---- turns -----------------------------------------------------------------

    /**
     * Attachments ride an idle send only (v15, server-side: they are never
     * queued). [Attachment.data] is base64 without a data: URI prefix. Null or
     * empty = no attachments; the field is then omitted from the frame entirely.
     */
    data class Send(
        val sessionId: String,
        val text: String,
        val idempotencyKey: String,
        val attachments: List<Attachment>? = null,
        val mention: DelegateMention? = null,
    ) : ClientMessage {
        override fun toJsonObject() = frame("send") {
            put("sessionId", sessionId)
            put("text", text)
            put("idempotencyKey", idempotencyKey)
            if (!attachments.isNullOrEmpty()) {
                put(
                    "attachments",
                    buildJsonArray {
                        for (att in attachments) {
                            add(buildJsonObject {
                                put("name", att.name)
                                put("mediaType", att.mediaType)
                                put("data", att.data)
                            })
                        }
                    },
                )
            }
            if (mention != null) put("mention", mention.toJsonObject())
        }
    }

    data class RunCommand(
        val sessionId: String,
        val command: String,
        val idempotencyKey: String,
        val background: Boolean? = null,
    ) : ClientMessage {
        override fun toJsonObject() = frame("run-command") {
            put("sessionId", sessionId)
            put("command", command)
            put("idempotencyKey", idempotencyKey)
            opt("background", background)
        }
    }

    data class BackgroundCommand(val sessionId: String) : ClientMessage {
        override fun toJsonObject() = frame("background-command") { put("sessionId", sessionId) }
    }

    data class StopCommand(val sessionId: String, val commandId: String) : ClientMessage {
        override fun toJsonObject() = frame("stop-command") {
            put("sessionId", sessionId)
            put("commandId", commandId)
        }
    }

    data class QueueAdd(val sessionId: String, val queueId: String, val text: String) : ClientMessage {
        override fun toJsonObject() = frame("queue-add") {
            put("sessionId", sessionId)
            put("queueId", queueId)
            put("text", text)
        }
    }

    data class QueueEdit(val sessionId: String, val queueId: String, val text: String) : ClientMessage {
        override fun toJsonObject() = frame("queue-edit") {
            put("sessionId", sessionId)
            put("queueId", queueId)
            put("text", text)
        }
    }

    data class QueueRemove(val sessionId: String, val queueId: String) : ClientMessage {
        override fun toJsonObject() = frame("queue-remove") {
            put("sessionId", sessionId)
            put("queueId", queueId)
        }
    }

    data class Interrupt(val sessionId: String) : ClientMessage {
        override fun toJsonObject() = frame("interrupt") { put("sessionId", sessionId) }
    }

    data class DismissNotice(val sessionId: String, val dismissKey: String) : ClientMessage {
        override fun toJsonObject() = frame("dismiss-notice") {
            put("sessionId", sessionId)
            put("dismissKey", dismissKey)
        }
    }

    /** [action]: "dismiss" | "schedule" | "resume-now". */
    data class RateLimitResume(val sessionId: String, val resetsAt: Long, val action: String) : ClientMessage {
        override fun toJsonObject() = frame("rate-limit-resume") {
            put("sessionId", sessionId)
            put("resetsAt", resetsAt)
            put("action", action)
        }
    }

    // ---- scheduled actions -----------------------------------------------------

    data object ScheduledActionsRequest : ClientMessage {
        override fun toJsonObject() = frame("scheduled-actions") {}
    }

    data class ScheduleCreate(val schedule: ScheduledActionInput) : ClientMessage {
        override fun toJsonObject() = frame("schedule-create") { put("schedule", schedule.toJsonObject()) }
    }

    data class ScheduleUpdate(val scheduleId: String, val schedule: ScheduledActionInput) : ClientMessage {
        override fun toJsonObject() = frame("schedule-update") {
            put("scheduleId", scheduleId)
            put("schedule", schedule.toJsonObject())
        }
    }

    /** [action]: "pause" | "resume" | "run" | "delete". */
    data class ScheduleControl(val scheduleId: String, val action: String) : ClientMessage {
        override fun toJsonObject() = frame("schedule-control") {
            put("scheduleId", scheduleId)
            put("action", action)
        }
    }

    // ---- approvals / questions -------------------------------------------------

    /**
     * Both TS variants: exactly one of [choiceId] | [decision] ("allow"|"deny")
     * must be non-null (protocol-validate rejects both/neither), and
     * [grantedPermissions] is only legal with [choiceId].
     */
    data class Approval(
        val sessionId: String,
        val requestId: String,
        val choiceId: String? = null,
        val decision: String? = null,
        val grantedPermissions: GrantedPermissions? = null,
    ) : ClientMessage {
        init {
            require((choiceId != null) != (decision != null)) {
                "approval must carry exactly one of choiceId or decision"
            }
            require(grantedPermissions == null || choiceId != null) {
                "approval.grantedPermissions requires choiceId"
            }
        }

        override fun toJsonObject() = frame("approval") {
            put("sessionId", sessionId)
            put("requestId", requestId)
            opt("choiceId", choiceId)
            opt("decision", decision)
            if (grantedPermissions != null) put("grantedPermissions", grantedPermissions.toJsonObject())
        }
    }

    /**
     * Answer a parked AskUserQuestion (Claude/Reasonix keyed shape). Wire nesting
     * is `answers: { answers: { "<question text>": "<label(s)>" }, response? }`.
     */
    data class Question(
        val sessionId: String,
        val requestId: String,
        val answers: Map<String, String>,
        val response: String? = null,
    ) : ClientMessage {
        override fun toJsonObject() = frame("question") {
            put("sessionId", sessionId)
            put("requestId", requestId)
            put(
                "answers",
                buildJsonObject {
                    put("answers", buildJsonObject { for ((k, v) in answers) put(k, JsonPrimitive(v)) })
                    opt("response", response)
                },
            )
        }
    }

    /** v64 opencode shape of `question`: `answers: { answers: string[][] }` (one inner list per question). */
    data class QuestionPositional(
        val sessionId: String,
        val requestId: String,
        val answers: List<List<String>>,
    ) : ClientMessage {
        override fun toJsonObject() = frame("question") {
            put("sessionId", sessionId)
            put("requestId", requestId)
            put(
                "answers",
                buildJsonObject {
                    put("answers", buildJsonArray { for (inner in answers) add(strings(inner)) })
                },
            )
        }
    }

    // ---- per-session engine controls -------------------------------------------

    data class SetMode(val sessionId: String, val permissionMode: String) : ClientMessage {
        override fun toJsonObject() = frame("set-mode") {
            put("sessionId", sessionId)
            put("permissionMode", permissionMode)
        }
    }

    /**
     * Switch the session's model ("" or "default" clears to the CLI default).
     * No direct reply: the ack is the `session` broadcast carrying
     * session.model, and the frame is silently dropped for providers without a
     * model switch — never wait on feedback for them.
     */
    data class SetModel(val sessionId: String, val model: String) : ClientMessage {
        override fun toJsonObject() = frame("set-model") {
            put("sessionId", sessionId)
            put("model", model)
        }
    }

    data class SetReasoningEffort(val sessionId: String, val reasoningEffort: String) : ClientMessage {
        override fun toJsonObject() = frame("set-reasoning-effort") {
            put("sessionId", sessionId)
            put("reasoningEffort", reasoningEffort)
        }
    }

    data class SetFastMode(val sessionId: String, val enabled: Boolean) : ClientMessage {
        override fun toJsonObject() = frame("set-fast-mode") {
            put("sessionId", sessionId)
            put("enabled", enabled)
        }
    }

    data class SetAutoContinueOnLimit(val sessionId: String, val enabled: Boolean) : ClientMessage {
        override fun toJsonObject() = frame("set-auto-continue-on-limit") {
            put("sessionId", sessionId)
            put("enabled", enabled)
        }
    }

    /** Ask for the session's models + slash-command list (reply: session-controls). v96 [warm]. */
    data class SessionControlsRequest(val sessionId: String, val warm: Boolean? = null) : ClientMessage {
        override fun toJsonObject() = frame("session-controls") {
            put("sessionId", sessionId)
            opt("warm", warm)
        }
    }

    data class CodexControlsRequest(val sessionId: String) : ClientMessage {
        override fun toJsonObject() = frame("codex-controls") { put("sessionId", sessionId) }
    }

    /** [action]: a CodexControlAction (lib/protocol-validate.mjs validateCodexControlAction), raw. */
    data class CodexControlAction(val sessionId: String, val action: JsonObject) : ClientMessage {
        override fun toJsonObject() = frame("codex-control-action") {
            put("sessionId", sessionId)
            put("action", action)
        }
    }

    data class OpencodeControlsRequest(val sessionId: String) : ClientMessage {
        override fun toJsonObject() = frame("opencode-controls") { put("sessionId", sessionId) }
    }

    /** [action]: an OpencodeServeControlAction, raw. */
    data class OpencodeControlAction(val sessionId: String, val action: JsonObject) : ClientMessage {
        override fun toJsonObject() = frame("opencode-control-action") {
            put("sessionId", sessionId)
            put("action", action)
        }
    }

    // ---- operator settings / registries ---------------------------------------

    data object AdvancedSettingsRequest : ClientMessage {
        override fun toJsonObject() = frame("advanced-settings") {}
    }

    /** [claudeCliVersion] is REQUIRED `string | null` on the wire: null is sent as JSON null. */
    data class SetAdvancedSettings(val claudeCliVersion: String?) : ClientMessage {
        override fun toJsonObject() = frame("set-advanced-settings") { put("claudeCliVersion", claudeCliVersion) }
    }

    data object ServerSettingsRequest : ClientMessage {
        override fun toJsonObject() = frame("server-settings") {}
    }

    /** [settings]: a Partial<ServerSettings> patch (only present keys change), raw. */
    data class SetServerSettings(val settings: JsonObject) : ClientMessage {
        override fun toJsonObject() = frame("set-server-settings") { put("settings", settings) }
    }

    data object DetectEngines : ClientMessage {
        override fun toJsonObject() = frame("detect-engines") {}
    }

    /** Retired server-side (answered with `error`), still in the TS union. */
    data object AcpAgentsRequest : ClientMessage {
        override fun toJsonObject() = frame("acp-agents") {}
    }

    data object ProvidersRequest : ClientMessage {
        override fun toJsonObject() = frame("providers") {}
    }

    /** [agents]: AcpAgentEntry[], raw. */
    data class SetAcpAgents(val agents: List<JsonObject>) : ClientMessage {
        override fun toJsonObject() = frame("set-acp-agents") { put("agents", JsonArray(agents)) }
    }

    /** [profiles]: ProfileEntry[], raw. */
    data class SetProviders(val profiles: List<JsonObject>) : ClientMessage {
        override fun toJsonObject() = frame("set-providers") { put("profiles", JsonArray(profiles)) }
    }

    data object ProvidersSnapshotRequest : ClientMessage {
        override fun toJsonObject() = frame("providers-snapshot") {}
    }

    /** [providers]: ProviderCatalogEntry KEYS; null = every provider. */
    data class RefreshProviders(val providers: List<String>? = null) : ClientMessage {
        override fun toJsonObject() = frame("refresh-providers") {
            if (providers != null) put("providers", strings(providers))
        }
    }

    /** [draftKind]: "commitMessage" | "pullRequest". */
    data class MetadataDraftRequest(val requestId: String, val draftKind: String, val sessionId: String) : ClientMessage {
        override fun toJsonObject() = frame("metadata-draft-request") {
            put("requestId", requestId)
            put("draftKind", draftKind)
            put("sessionId", sessionId)
        }
    }

    // ---- handoff (session collaboration P1) -----------------------------------

    data class HandoffBriefRequest(
        val sourceId: String,
        val targetId: String,
        val budgetChars: Long? = null,
        val requestId: String? = null,
    ) : ClientMessage {
        override fun toJsonObject() = frame("handoff-brief") {
            put("sourceId", sourceId)
            put("targetId", targetId)
            opt("budgetChars", budgetChars)
            opt("requestId", requestId)
        }
    }

    data class Handoff(
        val sourceId: String,
        val targetId: String,
        val engineText: String,
        val requestId: String? = null,
    ) : ClientMessage {
        override fun toJsonObject() = frame("handoff") {
            put("sourceId", sourceId)
            put("targetId", targetId)
            put("engineText", engineText)
            opt("requestId", requestId)
        }
    }

    companion object {
        /** Every `type` discriminator of the v129 ClientMessage union this module models. */
        val TYPES: Set<String> get() = ClientDecoders.decoders.keys

        /** Decode an outbound frame back into its type. Never throws; failure carries the reason. */
        fun decode(obj: JsonObject): Result<ClientMessage> {
            val type = obj.str("type") ?: return Result.failure(IllegalArgumentException("missing `type`"))
            val decoder = ClientDecoders.decoders[type]
                ?: return Result.failure(IllegalArgumentException("unknown client message type: $type"))
            return try {
                Result.success(decoder(Req(obj)))
            } catch (e: MalformedFrame) {
                Result.failure(IllegalArgumentException("$type: ${e.reason}"))
            } catch (e: IllegalArgumentException) {
                Result.failure(IllegalArgumentException("$type: ${e.message}"))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Nested client-side payload types
// ---------------------------------------------------------------------------

/**
 * A file/image carried by [ClientMessage.Send] (v15): [data] is base64 bytes,
 * no data: URI prefix. @Serializable only so PendingRecord can carry it; it is
 * never persisted (attachment records are excluded from the durable store).
 * protocol-validate normalizes an attachment to exactly these three fields.
 */
@Serializable
data class Attachment(val name: String, val mediaType: String, val data: String)

/** v98 `create.worktree`. [mode]: "branch-off" | "checkout-branch" | "checkout-pr". */
data class WorktreeCreateRequest(
    val mode: String,
    val baseRef: String? = null,
    val branch: String? = null,
    val slug: String? = null,
    val prNumber: Long? = null,
    val remote: String? = null,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("mode", mode)
        opt("baseRef", baseRef)
        opt("branch", branch)
        opt("slug", slug)
        opt("prNumber", prNumber)
        opt("remote", remote)
    }

    internal companion object {
        fun from(r: Req) = WorktreeCreateRequest(
            mode = r.str("mode"),
            baseRef = r.o.str("baseRef"),
            branch = r.o.str("branch"),
            slug = r.o.str("slug"),
            prNumber = r.o.long("prNumber"),
            remote = r.o.str("remote"),
        )
    }
}

/** v103 `send.mention`: [kind] is always "delegate"; [mode]: "review" | "build". */
data class DelegateMention(
    val provider: String,
    val mode: String,
    val model: String? = null,
    val reasoningEffort: String? = null,
) {
    val kind: String get() = "delegate"

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("kind", kind)
        put("provider", provider)
        opt("model", model)
        opt("reasoningEffort", reasoningEffort)
        put("mode", mode)
    }

    internal companion object {
        fun from(r: Req): DelegateMention {
            if (r.str("kind") != "delegate") throw MalformedFrame("mention.kind must be \"delegate\"")
            return DelegateMention(
                provider = r.str("provider"),
                mode = r.str("mode"),
                model = r.o.str("model"),
                reasoningEffort = r.o.str("reasoningEffort"),
            )
        }
    }
}

/**
 * Provider-neutral permission subset (approval.grantedPermissions; also
 * ApprovalRequestMetadata.requestedPermissions). A null list = absent key.
 */
data class GrantedPermissions(
    val fileSystemRead: List<String>? = null,
    val fileSystemWrite: List<String>? = null,
    /** True when a `fileSystem` object is present (possibly with neither list). */
    val hasFileSystem: Boolean = fileSystemRead != null || fileSystemWrite != null,
    val networkEnabled: Boolean? = null,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        if (hasFileSystem) {
            put(
                "fileSystem",
                buildJsonObject {
                    if (fileSystemRead != null) put("read", strings(fileSystemRead))
                    if (fileSystemWrite != null) put("write", strings(fileSystemWrite))
                },
            )
        }
        if (networkEnabled != null) put("network", buildJsonObject { put("enabled", networkEnabled) })
    }

    companion object {
        /** Tolerant: a wrongly-typed sub-field degrades to absent. */
        fun from(o: JsonObject): GrantedPermissions {
            val fs = o.obj("fileSystem")
            return GrantedPermissions(
                fileSystemRead = fs?.strList("read"),
                fileSystemWrite = fs?.strList("write"),
                hasFileSystem = fs != null,
                networkEnabled = o.obj("network")?.boolOrNull("enabled"),
            )
        }
    }
}

/**
 * `schedule-create` / `schedule-update` payload. Every field is REQUIRED on the
 * wire; the nullable ones are TS `T | null` and are sent as explicit JSON null.
 */
data class ScheduledActionInput(
    val name: String,
    val prompt: String,
    val cwd: String,
    val provider: String,
    val profileId: String?,
    val model: String?,
    val reasoningEffort: String?,
    val permissionMode: String?,
    val sandboxPolicy: String?,
    val useWorktree: Boolean,
    val cron: String,
    val timeZone: String,
    val maxRuns: Int?,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("name", name)
        put("prompt", prompt)
        put("cwd", cwd)
        put("provider", provider)
        put("profileId", profileId)
        put("model", model)
        put("reasoningEffort", reasoningEffort)
        put("permissionMode", permissionMode)
        put("sandboxPolicy", sandboxPolicy)
        put("useWorktree", useWorktree)
        put("cron", cron)
        put("timeZone", timeZone)
        put("maxRuns", maxRuns)
    }

    internal companion object {
        fun from(r: Req) = ScheduledActionInput(
            name = r.str("name"),
            prompt = r.str("prompt"),
            cwd = r.str("cwd"),
            provider = r.str("provider"),
            profileId = r.o.str("profileId"),
            model = r.o.str("model"),
            reasoningEffort = r.o.str("reasoningEffort"),
            permissionMode = r.o.str("permissionMode"),
            sandboxPolicy = r.o.str("sandboxPolicy"),
            useWorktree = r.bool("useWorktree"),
            cron = r.str("cron"),
            timeZone = r.str("timeZone"),
            maxRuns = r.o.long("maxRuns")?.toInt(),
        )
    }
}

// ---------------------------------------------------------------------------
// Encoding helpers: an absent optional is OMITTED, never written as null.
// ---------------------------------------------------------------------------

private inline fun frame(type: String, body: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
    put("type", type)
    body()
}

private fun JsonObjectBuilder.opt(key: String, value: String?) {
    if (value != null) put(key, value)
}

private fun JsonObjectBuilder.opt(key: String, value: Long?) {
    if (value != null) put(key, value)
}

private fun JsonObjectBuilder.opt(key: String, value: Boolean?) {
    if (value != null) put(key, value)
}

private fun strings(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

// ---------------------------------------------------------------------------
// Decoders (strict on required fields; see ClientMessage.decode)
// ---------------------------------------------------------------------------

private object ClientDecoders {
    private fun Req.orNull(key: String): OrNull<String>? = when {
        !o.containsKey(key) -> null
        o[key] is JsonNull -> OrNull(null)
        else -> OrNull(str(key))
    }

    private fun Req.lastSeen(): Map<String, Long>? = o.obj("lastSeen")?.let { m ->
        buildMap { for ((k, v) in m) v.asLong()?.let { put(k, it) } }
    }

    private fun Req.sub(key: String): Req = Req(obj(key))

    private fun Req.nested(key: String): Req? = o.obj(key)?.let(::Req)

    private fun positional(arr: JsonArray): List<List<String>> = arr.map { inner ->
        (inner as? JsonArray ?: throw MalformedFrame("answers.answers entries must be arrays")).map {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                ?: throw MalformedFrame("answers.answers entries must contain only strings")
        }
    }

    val decoders: Map<String, (Req) -> ClientMessage> = linkedMapOf(
        "hello" to { r -> ClientMessage.Hello(r.int("protocolVersion"), r.o.str("client")) },
        "node-add" to { r ->
            ClientMessage.NodeAdd(r.str("credential"), r.o.str("label"), r.o.str("baseUrl"), r.o.str("requestId"))
        },
        "node-remove" to { r -> ClientMessage.NodeRemove(r.str("nodeId"), r.o.str("requestId")) },
        "node-probe" to { r -> ClientMessage.NodeProbe(r.str("nodeId"), r.o.str("requestId")) },
        "ping" to { r -> ClientMessage.Ping(r.o.str("nonce")) },
        "create" to { r ->
            ClientMessage.Create(
                provider = r.str("provider"),
                cwd = r.o.str("cwd"),
                name = r.o.str("name"),
                permissionMode = r.o.str("permissionMode"),
                sandboxPolicy = r.o.str("sandboxPolicy"),
                useWorktree = r.o.boolOrNull("useWorktree"),
                worktree = r.nested("worktree")?.let(WorktreeCreateRequest::from),
                acpAgentId = r.o.str("acpAgentId"),
                profileId = r.o.str("profileId"),
                model = r.o.str("model"),
                reasoningEffort = r.o.str("reasoningEffort"),
                approvalPolicy = r.orNull("approvalPolicy"),
                approvalsReviewer = r.orNull("approvalsReviewer"),
                requestId = r.o.str("requestId"),
            )
        },
        "resume" to { r -> ClientMessage.Resume(r.str("historyId"), r.str("cwd"), r.o.str("profileId")) },
        "worktree-inspect" to { r -> ClientMessage.WorktreeInspect(r.str("cwd"), r.o.str("requestId")) },
        "worktree-scripts" to { r -> ClientMessage.WorktreeScriptsRequest(r.str("sessionId")) },
        "worktree-script" to { r -> ClientMessage.WorktreeScript(r.str("sessionId"), r.str("name"), r.str("action")) },
        "worktree-logs" to { r -> ClientMessage.WorktreeLogsRequest(r.str("sessionId"), r.str("name")) },
        "worktree-diff" to { r -> ClientMessage.WorktreeDiffRequest(r.str("sessionId")) },
        "git-diff-file" to { r -> ClientMessage.GitDiffFileRequest(r.str("sessionId"), r.str("path")) },
        "change-request" to { r -> ClientMessage.ChangeRequestFetch(r.str("sessionId"), r.o.boolOrNull("refresh")) },
        "discover" to { r ->
            ClientMessage.Discover(r.str("cwd"), r.lastSeen(), r.o.strList("watch"), r.o.str("requestId"))
        },
        "mark-seen" to { r -> ClientMessage.MarkSeen(r.str("historyId"), r.long("seenAt")) },
        "set-session-order" to { r -> ClientMessage.SetSessionOrder(r.str("cwd"), r.strList("order")) },
        "search" to { r -> ClientMessage.Search(r.str("cwd"), r.str("query")) },
        "global-search" to { r ->
            ClientMessage.GlobalSearch(
                requestId = r.long("requestId"),
                query = r.str("query"),
                providers = r.o.strList("providers"),
                since = r.o.long("since"),
                until = r.o.long("until"),
                cwd = r.o.str("cwd"),
            )
        },
        "browse" to { r -> ClientMessage.Browse(r.o.str("cwd"), r.o.str("requestId")) },
        "create-folder" to { r -> ClientMessage.CreateFolder(r.str("name"), r.o.str("cwd")) },
        "attach" to { r -> ClientMessage.Attach(r.str("sessionId"), r.o.long("afterSeq")) },
        "fetch-turns" to { r -> ClientMessage.FetchTurns(r.str("sessionId"), r.int("fromIndex"), r.int("toIndex")) },
        "pin" to { r -> ClientMessage.Pin(r.str("sessionId"), r.bool("pinned")) },
        "rename" to { r -> ClientMessage.Rename(r.str("sessionId"), r.str("name")) },
        "archive" to { r -> ClientMessage.Archive(r.str("sessionId")) },
        "kill" to { r -> ClientMessage.Kill(r.str("sessionId")) },
        "send" to { r ->
            ClientMessage.Send(
                sessionId = r.str("sessionId"),
                text = r.str("text"),
                idempotencyKey = r.str("idempotencyKey"),
                attachments = r.o.objList("attachments")?.map { a ->
                    val ar = Req(a)
                    Attachment(ar.str("name"), ar.str("mediaType"), ar.str("data"))
                },
                mention = r.nested("mention")?.let(DelegateMention::from),
            )
        },
        "run-command" to { r ->
            ClientMessage.RunCommand(r.str("sessionId"), r.str("command"), r.str("idempotencyKey"), r.o.boolOrNull("background"))
        },
        "background-command" to { r -> ClientMessage.BackgroundCommand(r.str("sessionId")) },
        "stop-command" to { r -> ClientMessage.StopCommand(r.str("sessionId"), r.str("commandId")) },
        "queue-add" to { r -> ClientMessage.QueueAdd(r.str("sessionId"), r.str("queueId"), r.str("text")) },
        "queue-edit" to { r -> ClientMessage.QueueEdit(r.str("sessionId"), r.str("queueId"), r.str("text")) },
        "queue-remove" to { r -> ClientMessage.QueueRemove(r.str("sessionId"), r.str("queueId")) },
        "interrupt" to { r -> ClientMessage.Interrupt(r.str("sessionId")) },
        "dismiss-notice" to { r -> ClientMessage.DismissNotice(r.str("sessionId"), r.str("dismissKey")) },
        "rate-limit-resume" to { r -> ClientMessage.RateLimitResume(r.str("sessionId"), r.long("resetsAt"), r.str("action")) },
        "scheduled-actions" to { _ -> ClientMessage.ScheduledActionsRequest },
        "schedule-create" to { r -> ClientMessage.ScheduleCreate(ScheduledActionInput.from(r.sub("schedule"))) },
        "schedule-update" to { r ->
            ClientMessage.ScheduleUpdate(r.str("scheduleId"), ScheduledActionInput.from(r.sub("schedule")))
        },
        "schedule-control" to { r -> ClientMessage.ScheduleControl(r.str("scheduleId"), r.str("action")) },
        "approval" to { r ->
            ClientMessage.Approval(
                sessionId = r.str("sessionId"),
                requestId = r.str("requestId"),
                choiceId = r.o.str("choiceId"),
                decision = r.o.str("decision"),
                grantedPermissions = r.o.obj("grantedPermissions")?.let(GrantedPermissions::from),
            )
        },
        "question" to { r ->
            val payload = r.sub("answers")
            when (val inner = payload.o["answers"]) {
                is JsonArray -> ClientMessage.QuestionPositional(r.str("sessionId"), r.str("requestId"), positional(inner))
                is JsonObject -> ClientMessage.Question(
                    sessionId = r.str("sessionId"),
                    requestId = r.str("requestId"),
                    answers = buildMap { for (k in inner.keys) inner.str(k)?.let { put(k, it) } },
                    response = payload.o.str("response"),
                )
                else -> throw MalformedFrame("answers.answers must be an object or an array")
            }
        },
        "set-mode" to { r -> ClientMessage.SetMode(r.str("sessionId"), r.str("permissionMode")) },
        "set-model" to { r -> ClientMessage.SetModel(r.str("sessionId"), r.str("model")) },
        "set-reasoning-effort" to { r -> ClientMessage.SetReasoningEffort(r.str("sessionId"), r.str("reasoningEffort")) },
        "set-fast-mode" to { r -> ClientMessage.SetFastMode(r.str("sessionId"), r.bool("enabled")) },
        "set-auto-continue-on-limit" to { r -> ClientMessage.SetAutoContinueOnLimit(r.str("sessionId"), r.bool("enabled")) },
        "session-controls" to { r -> ClientMessage.SessionControlsRequest(r.str("sessionId"), r.o.boolOrNull("warm")) },
        "codex-controls" to { r -> ClientMessage.CodexControlsRequest(r.str("sessionId")) },
        "codex-control-action" to { r -> ClientMessage.CodexControlAction(r.str("sessionId"), r.obj("action")) },
        "opencode-controls" to { r -> ClientMessage.OpencodeControlsRequest(r.str("sessionId")) },
        "opencode-control-action" to { r -> ClientMessage.OpencodeControlAction(r.str("sessionId"), r.obj("action")) },
        "advanced-settings" to { _ -> ClientMessage.AdvancedSettingsRequest },
        "set-advanced-settings" to { r ->
            if (!r.o.containsKey("claudeCliVersion")) throw MalformedFrame("required field `claudeCliVersion` is missing")
            ClientMessage.SetAdvancedSettings(r.o.str("claudeCliVersion"))
        },
        "server-settings" to { _ -> ClientMessage.ServerSettingsRequest },
        "set-server-settings" to { r -> ClientMessage.SetServerSettings(r.obj("settings")) },
        "detect-engines" to { _ -> ClientMessage.DetectEngines },
        "acp-agents" to { _ -> ClientMessage.AcpAgentsRequest },
        "providers" to { _ -> ClientMessage.ProvidersRequest },
        "set-acp-agents" to { r -> ClientMessage.SetAcpAgents(r.objList("agents")) },
        "set-providers" to { r -> ClientMessage.SetProviders(r.objList("profiles")) },
        "providers-snapshot" to { _ -> ClientMessage.ProvidersSnapshotRequest },
        "refresh-providers" to { r -> ClientMessage.RefreshProviders(r.o.strList("providers")) },
        "metadata-draft-request" to { r ->
            ClientMessage.MetadataDraftRequest(r.str("requestId"), r.str("draftKind"), r.str("sessionId"))
        },
        "handoff-brief" to { r ->
            ClientMessage.HandoffBriefRequest(r.str("sourceId"), r.str("targetId"), r.o.long("budgetChars"), r.o.str("requestId"))
        },
        "handoff" to { r ->
            ClientMessage.Handoff(r.str("sourceId"), r.str("targetId"), r.str("engineText"), r.o.str("requestId"))
        },
    )
}
