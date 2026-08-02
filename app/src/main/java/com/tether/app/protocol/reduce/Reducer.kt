package com.tether.app.protocol.reduce

import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.TetherJson
import com.tether.app.protocol.model.ApiRetryState
import com.tether.app.protocol.model.AttachmentMeta
import com.tether.app.protocol.model.DiffProjection
import com.tether.app.protocol.model.McpHealthProjection
import com.tether.app.protocol.model.ModelRerouteProjection
import com.tether.app.protocol.model.ModelUsageEntry
import com.tether.app.protocol.model.PendingApproval
import com.tether.app.protocol.model.PendingQuestion
import com.tether.app.protocol.model.PermissionDenialProjection
import com.tether.app.protocol.model.PlanProjection
import com.tether.app.protocol.model.ProcessExit
import com.tether.app.protocol.model.ProviderNoticeProjection
import com.tether.app.protocol.model.QuestionPrompt
import com.tether.app.protocol.model.QueuedMessage
import com.tether.app.protocol.model.RateLimitResumeState
import com.tether.app.protocol.model.RateLimitState
import com.tether.app.protocol.model.SessionNotice
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.model.SubagentEntry
import com.tether.app.protocol.model.SubagentThread
import com.tether.app.protocol.model.SubagentUsage
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.protocol.model.TurnProjection
import com.tether.app.protocol.model.TurnRun
import com.tether.app.protocol.model.TurnUsage
import com.tether.app.protocol.model.TurnWarning
import com.tether.app.protocol.model.Vocab
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure Kotlin port of the aidash session/turn reducer
 * (engines/events.mjs reduce()). No I/O, no clocks, no mutation: every clock
 * reading comes from `event.ts`, and no-op paths return the SAME instance.
 */

fun initialSessionState(
    tetherSessionId: String,
    provider: String,
    cwd: String,
    nativeSessionId: String? = null,
): SessionProjection = SessionProjection(
    tetherSessionId = tetherSessionId,
    provider = provider,
    cwd = cwd,
    nativeSessionId = nativeSessionId,
)

fun currentTurn(state: SessionProjection): TurnProjection? =
    state.activeTurnId?.let { state.turnsById[it] }

internal fun newTurnProjection(
    turnId: String,
    idempotencyKey: String?,
    continuation: Boolean = false,
    startedAt: Long? = null,
): TurnProjection = TurnProjection(
    turnId = turnId,
    idempotencyKey = idempotencyKey,
    continuation = continuation,
    startedAt = startedAt,
)

/** Events that prove the provider got past an HTTP retry (API_RETRY_RESOLVED_BY). */
private val API_RETRY_RESOLVED_BY = setOf(
    "message_started", "message_delta", "message_completed",
    "thinking_delta", "thinking_completed", "thinking_stop",
    "tool_start", "tool_progress", "tool_output_delta", "tool_end",
    "permission_denied", "subagent_message", "plan_updated", "diff_updated",
    "model_rerouted", "review_started", "review_completed", "context_compacted",
    "usage", "token_progress", "cancelled", "turn_end",
)

/** reduce(state, event) = syncTurnRun(clearResolvedApiRetry(reduceEvent(state, event), event), event) */
fun reduce(state: SessionProjection, event: AgentEvent): SessionProjection =
    syncTurnRun(clearResolvedApiRetry(reduceEvent(state, event), event), event)

// ---------------------------------------------------------------------------
// Pass 2 — clearResolvedApiRetry
// ---------------------------------------------------------------------------

private fun clearResolvedApiRetry(state: SessionProjection, event: AgentEvent): SessionProjection {
    if (event.type !in API_RETRY_RESOLVED_BY) return state
    val turnId = event.turnId ?: return state
    val turn = state.turnsById[turnId] ?: return state
    if (turn.apiRetry == null) return state
    return state.copy(turnsById = state.turnsById + (turnId to turn.copy(apiRetry = null)))
}

// ---------------------------------------------------------------------------
// Pass 3 — syncTurnRun (v38 run bookkeeping)
// ---------------------------------------------------------------------------

private fun turnIsWorking(turn: TurnProjection?): Boolean {
    if (turn == null) return false
    if (turn.status != Vocab.TURN_RUNNING && turn.status != Vocab.TURN_CANCELLING) return false
    if (turn.pendingApprovals.isNotEmpty()) return false
    if (turn.pendingQuestions.isNotEmpty()) return false
    return true
}

private fun syncTurnRun(state: SessionProjection, event: AgentEvent): SessionProjection {
    val turnId = event.turnId ?: state.activeTurnId ?: return state
    val turn = state.turnsById[turnId] ?: return state
    val ts = event.ts ?: return state
    val working = turnIsWorking(turn)
    if (working == (turn.run != null)) return state
    return if (working) {
        val run = TurnRun(index = turn.runCount, startedAt = ts, tokensStart = turn.liveTokens ?: 0)
        state.copy(turnsById = state.turnsById + (turnId to turn.copy(run = run, runCount = turn.runCount + 1)))
    } else {
        val elapsed = maxOf(0L, ts - turn.run!!.startedAt)
        state.copy(
            turnsById = state.turnsById +
                (turnId to turn.copy(run = null, activeMs = turn.activeMs + elapsed)),
        )
    }
}

// ---------------------------------------------------------------------------
// Pass 1 — reduceEvent
// ---------------------------------------------------------------------------

private fun isOpenCurrentTurn(state: SessionProjection, turnId: String?): Boolean =
    turnId != null && state.activeTurnId == turnId && state.turnsById[turnId]?.status != Vocab.TURN_DONE

private inline fun updateTurn(
    state: SessionProjection,
    updater: (TurnProjection) -> TurnProjection,
): SessionProjection {
    val turnId = state.activeTurnId ?: return state
    val turn = state.turnsById[turnId] ?: return state
    return state.copy(turnsById = state.turnsById + (turnId to updater(turn)))
}

private inline fun updateTurnById(
    state: SessionProjection,
    turnId: String?,
    updater: (TurnProjection) -> TurnProjection,
): SessionProjection {
    if (turnId == null) return state
    val turn = state.turnsById[turnId] ?: return state
    val next = updater(turn)
    if (next === turn) return state
    return state.copy(turnsById = state.turnsById + (turnId to next))
}

/**
 * Adds blockId to the ordered `blocks` list the FIRST time it is seen, then
 * only patches blocksById on later calls. `build` receives the existing block
 * (null when new).
 */
private inline fun upsertBlock(
    turn: TurnProjection,
    blockId: String,
    build: (TurnBlock?) -> TurnBlock,
): TurnProjection {
    val existing = turn.blocksById[blockId]
    val next = build(existing)
    return if (existing != null) {
        turn.copy(blocksById = turn.blocksById + (blockId to next))
    } else {
        turn.copy(blocks = turn.blocks + blockId, blocksById = turn.blocksById + (blockId to next))
    }
}

private fun deriveSessionStatus(turn: TurnProjection?): String {
    if (turn == null || turn.status == Vocab.TURN_DONE) return Vocab.SESSION_READY
    if (turn.pendingApprovals.isNotEmpty()) return Vocab.SESSION_WAITING
    if (turn.pendingQuestions.isNotEmpty()) return Vocab.SESSION_WAITING
    return Vocab.SESSION_ACTIVE
}

private fun legacyUnknownErrorMessage(raw: JsonElement?): String? {
    val obj = raw as? JsonObject ?: return null
    if (asString(obj["type"]) == "error") {
        val message = asString(obj["message"])
        if (!message.isNullOrBlank()) return message
    }
    if (asString(obj["type"]) == "turn.failed") {
        val message = asString((obj["error"] as? JsonObject)?.get("message"))
        if (!message.isNullOrBlank()) return message
    }
    return null
}

private fun clearElapsedProgress(entry: SubagentEntry): SubagentEntry =
    if (entry.elapsedSeconds == null) entry else entry.copy(elapsedSeconds = null)

private fun foldSubagentItems(
    existing: SubagentThread?,
    items: JsonArray,
    usage: SubagentUsage?,
): SubagentThread {
    val order = ArrayList(existing?.order ?: emptyList())
    val entries = HashMap(existing?.entries ?: emptyMap())
    for (raw in items) {
        val item = raw as? JsonObject ?: continue
        val key = asString(item["key"]) ?: continue
        val prev = entries[key]
        if (prev == null && key !in entries) order.add(key)
        when (asString(item["kind"])) {
            "message" -> entries[key] = SubagentEntry(key = key, kind = "message", text = asString(item["text"]))
            "thinking" -> entries[key] = SubagentEntry(key = key, kind = "thinking", text = asString(item["text"]))
            "tool" -> entries[key] = (prev ?: SubagentEntry(key = key, kind = "tool")).copy(
                key = key,
                kind = "tool",
                name = asString(item["name"]),
                input = item["input"],
                done = prev?.done ?: false,
            )
            "tool_result" -> entries[key] = clearElapsedProgress(
                prev ?: SubagentEntry(key = key, kind = "tool"),
            ).copy(output = item["output"], isError = asBoolean(item["isError"]), done = true)
            else -> { /* unknown item kind: key ordered, no entry (matches JS) */ }
        }
    }
    val carried = usage ?: existing?.usage
    return SubagentThread(order = order, entries = entries, usage = carried)
}

private fun decodeUsage(fields: JsonObject): TurnUsage {
    val modelUsages: List<ModelUsageEntry>? = (fields["modelUsages"] as? JsonArray)?.let {
        try {
            TetherJson.decodeFromJsonElement(ListSerializer(ModelUsageEntry.serializer()), it)
        } catch (_: Exception) {
            null
        }
    }
    return TurnUsage(
        model = asString(fields["model"]),
        rawModel = asString(fields["rawModel"]),
        perTurnTokens = asNumber(fields["perTurnTokens"])?.toLong(),
        cumulativeTokens = asNumber(fields["cumulativeTokens"])?.toLong(),
        contextWindow = asNumber(fields["contextWindow"])?.toLong(),
        estimatedCostUSD = asNumber(fields["estimatedCostUSD"]),
        modelUsages = modelUsages,
    )
}

private fun decodeSubagentUsage(el: JsonElement?): SubagentUsage? {
    val obj = el as? JsonObject ?: return null
    return SubagentUsage(
        model = asString(obj["model"]),
        inputTokens = asNumber(obj["inputTokens"])?.toLong(),
        outputTokens = asNumber(obj["outputTokens"])?.toLong(),
        cacheReadInputTokens = asNumber(obj["cacheReadInputTokens"])?.toLong(),
        cacheCreationInputTokens = asNumber(obj["cacheCreationInputTokens"])?.toLong(),
    )
}

private fun decodeQuestions(el: JsonElement?): List<QuestionPrompt> {
    val array = el as? JsonArray ?: return emptyList()
    return try {
        TetherJson.decodeFromJsonElement(ListSerializer(QuestionPrompt.serializer()), array)
    } catch (_: Exception) {
        emptyList()
    }
}

private fun decodeAttachments(el: JsonElement?): List<AttachmentMeta>? {
    val array = el as? JsonArray ?: return null
    if (array.isEmpty()) return null
    return array.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        AttachmentMeta(name = asString(obj["name"]) ?: "", mediaType = asString(obj["mediaType"]) ?: "")
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun reduceEvent(state: SessionProjection, event: AgentEvent): SessionProjection {
    val f = event.raw
    return when (event.type) {
        "native_session_id" -> {
            val capabilities = (f["cliCapabilities"] as? JsonArray)
                ?.mapNotNull { asString(it) }
            val version = asString(f["cliVersion"])?.takeIf { it.isNotEmpty() }
            val inventory = normalizeProjectedCliInventory(f["cliInventory"], state.cliInventory)
            val nativeSessionId = asString(f["nativeSessionId"])
            val idUnchanged = state.nativeSessionId == nativeSessionId
            val capabilitiesUnchanged = capabilities == null || state.cliCapabilities == capabilities
            val versionUnchanged = version == null || state.cliVersion == version
            val inventoryUnchanged = inventory == null || sameCliInventory(state.cliInventory, inventory)
            if (idUnchanged && capabilitiesUnchanged && versionUnchanged && inventoryUnchanged) {
                state
            } else {
                state.copy(
                    nativeSessionId = nativeSessionId,
                    cliCapabilities = capabilities ?: state.cliCapabilities,
                    cliVersion = version ?: state.cliVersion,
                    cliInventory = inventory ?: state.cliInventory,
                )
            }
        }

        "cli_inventory_reset" ->
            if (state.cliInventory == null) state else state.copy(cliInventory = null)

        "cli_commands_changed" -> {
            if (f["commands"] !is JsonArray) return state
            val commands = normalizeClaudeCommands(f["commands"])
            val inventory = com.tether.app.protocol.model.CliInventory(
                commands = commands,
                tools = state.cliInventory?.tools ?: emptyList(),
                mcpServers = state.cliInventory?.mcpServers ?: emptyList(),
            )
            if (sameCliInventory(state.cliInventory, inventory)) state else state.copy(cliInventory = inventory)
        }

        "api_retry" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val attempt = asNumber(f["attempt"])?.toInt() ?: return state
            updateTurn(state) { turn ->
                turn.copy(
                    apiRetry = ApiRetryState(
                        attempt = attempt,
                        maxRetries = asNumber(f["maxRetries"])?.toInt(),
                        delayMs = asNumber(f["delayMs"])?.toLong(),
                        errorStatus = asNumber(f["errorStatus"])?.toInt(),
                        error = asString(f["error"]),
                    ),
                )
            }
        }

        "rate_limit" -> {
            val status = asString(f["status"]) ?: return state
            val next = RateLimitState(
                status = status,
                limitType = asString(f["limitType"]),
                utilization = asNumber(f["utilization"]),
                resetsAt = asNumber(f["resetsAt"])?.toLong(),
            )
            val prev = state.rateLimit
            val unchanged = prev != null && prev == next
            var rateLimitResume = state.rateLimitResume
            val stampedNow = event.ts
            val resetsAt = next.resetsAt
            val horizon = if (stampedNow == null || resetsAt == null) null else resetsAt - stampedNow
            if (
                next.status == "rejected" && resetsAt != null && resetsAt > 0 &&
                horizon != null && horizon > 0 && horizon <= Vocab.RATE_LIMIT_RESUME_MAX_HORIZON_MS &&
                rateLimitResume?.resetsAt != resetsAt
            ) {
                rateLimitResume = RateLimitResumeState(
                    status = "awaiting_choice",
                    resetsAt = resetsAt,
                    resumeAt = resetsAt + Vocab.RATE_LIMIT_RESUME_DELAY_MS,
                )
            }
            if (unchanged && rateLimitResume === state.rateLimitResume) {
                state
            } else {
                state.copy(rateLimit = next, rateLimitResume = rateLimitResume)
            }
        }

        "rate_limit_resume_scheduled" -> {
            val current = state.rateLimitResume ?: return state
            val resetsAt = asNumber(f["resetsAt"])?.toLong()
            val resumeAt = asNumber(f["resumeAt"])?.toLong()
            if (
                current.resetsAt != resetsAt || current.status != "awaiting_choice" ||
                resumeAt == null || resetsAt == null || resumeAt != resetsAt + Vocab.RATE_LIMIT_RESUME_DELAY_MS
            ) {
                state
            } else {
                state.copy(
                    rateLimitResume = RateLimitResumeState(
                        status = "scheduled",
                        resetsAt = resetsAt,
                        resumeAt = resumeAt,
                    ),
                )
            }
        }

        "rate_limit_resume_dismissed" -> {
            val current = state.rateLimitResume ?: return state
            val resetsAt = asNumber(f["resetsAt"])?.toLong()
            if (current.resetsAt != resetsAt || current.status == "fired") {
                state
            } else {
                state.copy(rateLimitResume = current.copy(status = "dismissed"))
            }
        }

        "rate_limit_resume_fired" -> {
            val current = state.rateLimitResume ?: return state
            val resetsAt = asNumber(f["resetsAt"])?.toLong()
            if (current.resetsAt != resetsAt || current.status != "scheduled") {
                state
            } else {
                state.copy(rateLimitResume = current.copy(status = "fired"))
            }
        }

        "todo_updated" -> {
            val items = normalizeTodoItems(f["items"])
            if (items.isEmpty()) return state
            val todo = todoProjection(items)
            if (state.todo == todo) state else state.copy(todo = todo)
        }

        "plan_updated" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val explanationEl = f["explanation"]
            val explanation = if (explanationEl == null || explanationEl is kotlinx.serialization.json.JsonNull) {
                null
            } else {
                boundedDisplayText(explanationEl, Limits.PROSE_CHARS)
            }
            val plan = PlanProjection(explanation = explanation, steps = normalizePlanSteps(f["steps"]))
            updateTurn(state) { it.copy(plan = plan) }
        }

        "diff_updated" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val unifiedDiff = asString(f["unifiedDiff"]) ?: return state
            updateTurn(state) {
                it.copy(diff = DiffProjection(boundedDisplayText(unifiedDiff, Limits.DIFF_CHARS)!!))
            }
        }

        "model_rerouted" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val fromModel = boundedIdentifier(f["fromModel"])
            val toModel = boundedIdentifier(f["toModel"])
            val reason = boundedDisplayText(f["reason"], Limits.PROSE_CHARS)
            if (fromModel == null || toModel == null || reason.isNullOrEmpty()) return state
            updateTurn(state) { turn ->
                val last = turn.modelReroutes.lastOrNull()
                if (last?.fromModel == fromModel && last.toModel == toModel && last.reason == reason) {
                    turn
                } else {
                    turn.copy(
                        modelReroutes = (
                            turn.modelReroutes +
                                ModelRerouteProjection(fromModel, toModel, reason)
                            ).takeLast(Limits.MODEL_REROUTES),
                    )
                }
            }
        }

        "review_started" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val reviewId = boundedIdentifier(f["reviewId"]) ?: return state
            val target = boundedDisplayText(f["target"], Limits.PROSE_CHARS)
            updateTurn(state) { turn ->
                turn.copy(reviews = upsertReview(turn.reviews, reviewId, "started", target, null))
            }
        }

        "review_completed" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val reviewId = boundedIdentifier(f["reviewId"]) ?: return state
            val status = asString(f["status"])
            if (status !in REVIEW_COMPLETION_STATUSES) return state
            val result = boundedDisplayText(f["result"], Limits.PROSE_CHARS)
            updateTurn(state) { turn ->
                turn.copy(reviews = upsertReview(turn.reviews, reviewId, status!!, null, result))
            }
        }

        "mcp_health_updated" -> {
            val name = boundedIdentifier(f["name"]) ?: return state
            val rawStatus = asString(f["status"])
            val health = McpHealthProjection(
                name = name,
                status = if (rawStatus in MCP_HEALTH_STATUSES) rawStatus!! else "unknown",
                error = boundedDisplayText(f["error"], Limits.PROSE_CHARS),
                failureReason = boundedDisplayText(f["failureReason"], Limits.LABEL_CHARS),
            )
            val previous = state.mcpHealth[name]
            if (
                previous != null && previous.status == health.status &&
                previous.error == health.error && previous.failureReason == health.failureReason
            ) {
                return state
            }
            if (previous == null && state.mcpHealth.size >= Limits.MCP_SERVERS) return state
            state.copy(mcpHealth = state.mcpHealth + (name to health))
        }

        "context_compacted" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val itemId = boundedIdentifier(f["itemId"]) ?: return state
            updateTurn(state) { turn ->
                if (turn.compactions.any { it.itemId == itemId }) {
                    turn
                } else {
                    turn.copy(
                        compactions = (
                            turn.compactions +
                                com.tether.app.protocol.model.CompactionProjection(itemId)
                            ).takeLast(Limits.COMPACTIONS),
                    )
                }
            }
        }

        "provider_notice" -> {
            val noticeId = boundedIdentifier(f["noticeId"]) ?: return state
            val message = boundedDisplayText(f["message"], Limits.PROSE_CHARS)
            if (message.isNullOrEmpty()) return state
            val rawLevel = asString(f["level"])
            val notice = ProviderNoticeProjection(
                noticeId = noticeId,
                level = if (rawLevel in PROVIDER_NOTICE_LEVELS) rawLevel!! else "warning",
                code = boundedDisplayText(f["code"], Limits.LABEL_CHARS),
                message = message,
            )
            if (event.turnId == null) {
                val providerNotices = appendProviderNotice(state.providerNotices, notice)
                if (providerNotices === state.providerNotices) state else state.copy(providerNotices = providerNotices)
            } else {
                if (!isOpenCurrentTurn(state, event.turnId)) return state
                updateTurn(state) { turn ->
                    val providerNotices = appendProviderNotice(turn.providerNotices, notice)
                    if (providerNotices === turn.providerNotices) turn else turn.copy(providerNotices = providerNotices)
                }
            }
        }

        "permission_denied" -> {
            val toolId = asString(f["toolId"]) ?: return state
            val rawReason = asString(f["reason"])
            val denial = PermissionDenialProjection(
                toolId = toolId,
                name = asString(f["name"]) ?: "",
                reason = if (rawReason in PERMISSION_DENIAL_REASON_VALUES) rawReason!! else "unknown",
                reasonCode = permissionDenialReasonCode(f["reasonCode"]),
                subagent = if (asBoolean(f["subagent"]) == true) true else null,
            )
            if (event.turnId == null) {
                val denials = upsertPermissionDenial(state.unattributedPermissionDenials, denial)
                if (denials === state.unattributedPermissionDenials) {
                    state
                } else {
                    state.copy(unattributedPermissionDenials = denials)
                }
            } else {
                if (!isOpenCurrentTurn(state, event.turnId)) return state
                updateTurn(state) { turn ->
                    val denials = upsertPermissionDenial(turn.permissionDenials, denial)
                    if (denials === turn.permissionDenials) turn else turn.copy(permissionDenials = denials)
                }
            }
        }

        "turn_started" -> {
            val turnId = event.turnId ?: return state
            if (state.turnsById.containsKey(turnId)) return state // duplicate turn_started
            val active = state.activeTurnId
            if (active != null && state.turnsById[active]?.status != Vocab.TURN_DONE) return state
            val turn = newTurnProjection(
                turnId = turnId,
                idempotencyKey = asString(f["idempotencyKey"]),
                continuation = asBoolean(f["continuation"]) == true,
                startedAt = event.ts,
            )
            state.copy(
                status = Vocab.SESSION_ACTIVE,
                activeTurnId = turnId,
                turnOrder = state.turnOrder + turnId,
                turnsById = state.turnsById + (turnId to turn),
            )
        }

        "user_message_accepted" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val blockId = "user:${event.turnId}"
            updateTurn(state) { turn ->
                upsertBlock(turn, blockId) {
                    TurnBlock(
                        blockId = blockId,
                        kind = Vocab.BLOCK_USER_MESSAGE,
                        text = asString(f["text"]),
                        attachments = decodeAttachments(f["attachments"]),
                    )
                }
            }
        }

        "message_started" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val blockId = asString(f["blockId"]) ?: return state
            updateTurn(state) { turn ->
                upsertBlock(turn, blockId) { existing ->
                    existing ?: TurnBlock(blockId = blockId, kind = Vocab.BLOCK_MESSAGE, text = "", done = false)
                }
            }
        }

        "message_delta" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val blockId = asString(f["blockId"]) ?: return state
            val text = asString(f["text"]) ?: ""
            updateTurn(state) { turn ->
                upsertBlock(turn, blockId) { existing ->
                    (existing ?: TurnBlock(blockId = blockId, kind = Vocab.BLOCK_MESSAGE, done = false))
                        .copy(text = (existing?.text ?: "") + text)
                }
            }
        }

        "message_completed" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val blockId = asString(f["blockId"]) ?: return state
            updateTurn(state) { turn ->
                upsertBlock(turn, blockId) {
                    TurnBlock(
                        blockId = blockId,
                        kind = Vocab.BLOCK_MESSAGE,
                        text = asString(f["text"]),
                        done = true,
                        aborted = if (asBoolean(f["aborted"]) == true) true else null,
                    )
                }
            }
        }

        "thinking_delta" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val blockId = asString(f["blockId"]) ?: return state
            val text = asString(f["text"]) ?: ""
            updateTurn(state) { turn ->
                upsertBlock(turn, blockId) { existing ->
                    (existing ?: TurnBlock(blockId = blockId, kind = Vocab.BLOCK_THINKING, done = false))
                        .copy(text = (existing?.text ?: "") + text)
                }
            }
        }

        "thinking_completed" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val blockId = asString(f["blockId"]) ?: return state
            updateTurn(state) { turn ->
                upsertBlock(turn, blockId) {
                    TurnBlock(blockId = blockId, kind = Vocab.BLOCK_THINKING, text = asString(f["text"]), done = true)
                }
            }
        }

        "thinking_stop" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val blockId = asString(f["blockId"]) ?: return state
            val active = state.activeTurnId?.let { state.turnsById[it] }
            if (active?.blocksById?.get(blockId) == null) return state
            updateTurn(state) { turn ->
                upsertBlock(turn, blockId) { existing -> existing!!.copy(done = true) }
            }
        }

        "tool_start" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val toolId = asString(f["toolId"]) ?: return state
            updateTurn(state) { turn ->
                upsertBlock(turn, toolId) {
                    TurnBlock(
                        blockId = toolId,
                        kind = Vocab.BLOCK_TOOL,
                        name = asString(f["name"]),
                        input = f["input"],
                        output = null,
                        isError = false,
                        done = false,
                    )
                }
            }
        }

        "tool_output_delta" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val toolId = asString(f["toolId"]) ?: return state
            val chunk = asString(f["chunk"]) ?: ""
            updateTurn(state) { turn ->
                upsertBlock(turn, toolId) { existing ->
                    val prevText = (existing?.output as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
                    (existing ?: TurnBlock(blockId = toolId, kind = Vocab.BLOCK_TOOL, done = false))
                        .copy(output = JsonPrimitive(prevText + chunk))
                }
            }
        }

        "tool_progress" -> {
            val toolId = asString(f["toolId"]) ?: return state
            val elapsedSeconds = asNumber(f["elapsedSeconds"])
            val parentToolUseId = asString(f["parentToolUseId"])
            if (parentToolUseId != null) {
                // Nested branch: may amend a done turn, patch-only.
                val turn = state.turnsById[event.turnId] ?: return state
                val parent = turn.blocksById[parentToolUseId] ?: return state
                val child = if (parent.kind == Vocab.BLOCK_TOOL) parent.subagent?.entries?.get(toolId) else null
                if (
                    child == null || child.kind != "tool" || child.done == true ||
                    child.elapsedSeconds == elapsedSeconds
                ) {
                    return state
                }
                updateTurnById(state, event.turnId) {
                    it.copy(
                        blocksById = it.blocksById + (
                            parentToolUseId to parent.copy(
                                subagent = parent.subagent!!.copy(
                                    entries = parent.subagent.entries +
                                        (toolId to child.copy(elapsedSeconds = elapsedSeconds)),
                                ),
                            )
                            ),
                    )
                }
            } else {
                if (!isOpenCurrentTurn(state, event.turnId)) return state
                val tool = state.turnsById[event.turnId]?.blocksById?.get(toolId)
                if (
                    tool == null || tool.kind != Vocab.BLOCK_TOOL || tool.done == true ||
                    tool.elapsedSeconds == elapsedSeconds
                ) {
                    return state
                }
                updateTurn(state) { turn ->
                    turn.copy(blocksById = turn.blocksById + (toolId to tool.copy(elapsedSeconds = elapsedSeconds)))
                }
            }
        }

        "tool_end" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val toolId = asString(f["toolId"]) ?: return state
            updateTurn(state) { turn ->
                upsertBlock(turn, toolId) { existing ->
                    (existing ?: TurnBlock(blockId = toolId, kind = Vocab.BLOCK_TOOL)).copy(
                        elapsedSeconds = null, // DELETED on tool_end
                        output = f["output"],
                        isError = asBoolean(f["isError"]),
                        done = true,
                    )
                }
            }
        }

        "approval_request" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val requestId = asString(f["requestId"]) ?: return state
            val approval = PendingApproval(
                requestId = requestId,
                toolId = asString(f["toolId"]) ?: "",
                name = asString(f["name"]) ?: "",
                input = f["input"],
                choices = normalizeApprovalChoices(f["choices"]),
                metadata = normalizeApprovalMetadata(f["metadata"]),
            )
            val next = updateTurn(state) { turn ->
                turn.copy(pendingApprovals = turn.pendingApprovals + (requestId to approval))
            }
            next.copy(status = deriveSessionStatus(currentTurn(next)))
        }

        "question_request" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val requestId = asString(f["requestId"]) ?: return state
            val question = PendingQuestion(
                requestId = requestId,
                toolId = asString(f["toolId"]) ?: "",
                questions = decodeQuestions(f["questions"]),
            )
            val next = updateTurn(state) { turn ->
                turn.copy(pendingQuestions = turn.pendingQuestions + (requestId to question))
            }
            next.copy(status = deriveSessionStatus(currentTurn(next)))
        }

        "question_resolved", "question_cancelled" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val requestId = asString(f["requestId"])
            val next = updateTurn(state) { turn ->
                turn.copy(pendingQuestions = turn.pendingQuestions - requestId.orEmpty())
            }
            next.copy(status = deriveSessionStatus(currentTurn(next)))
        }

        "approval_resolved", "approval_expired" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val requestId = asString(f["requestId"])
            val next = updateTurn(state) { turn ->
                turn.copy(pendingApprovals = turn.pendingApprovals - requestId.orEmpty())
            }
            next.copy(status = deriveSessionStatus(currentTurn(next)))
        }

        "cancel_requested" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            updateTurn(state) { it.copy(status = Vocab.TURN_CANCELLING) }
        }

        "cancelled" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val next = updateTurn(state) {
                it.copy(status = Vocab.TURN_DONE, outcome = Vocab.OUTCOME_CANCELLED)
            }
            next.copy(
                status = Vocab.SESSION_READY,
                activeTurnId = null,
                lastTurnOutcome = Vocab.OUTCOME_CANCELLED,
            )
        }

        "process_exit" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            updateTurn(state) {
                it.copy(exit = ProcessExit(code = asNumber(f["code"])?.toInt(), signal = asString(f["signal"])))
            }
        }

        "usage" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            updateTurn(state) { it.copy(usage = decodeUsage(f)) }
        }

        "token_progress" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val tokens = asNumber(f["tokens"])?.takeIf { it.isFinite() && it >= 0 }?.toLong() ?: return state
            updateTurn(state) { turn ->
                val next = if (turn.liveTokens == null) tokens else maxOf(turn.liveTokens, tokens)
                if (next == turn.liveTokens) turn else turn.copy(liveTokens = next)
            }
        }

        "turn_activity" -> {
            // Compaction restore: deliberately unguarded (the turn is done by then).
            val turnId = event.turnId ?: return state
            val turn = state.turnsById[turnId] ?: return state
            val activeMs = asNumber(f["activeMs"])?.takeIf { it.isFinite() && it >= 0 }?.toLong()
            val runCount = asNumber(f["runCount"])?.takeIf { it.isFinite() && it >= 0 }?.toInt()
            if (activeMs == null && runCount == null) return state
            state.copy(
                turnsById = state.turnsById + (
                    turnId to turn.copy(
                        activeMs = activeMs ?: turn.activeMs,
                        runCount = runCount ?: turn.runCount,
                    )
                    ),
            )
        }

        "subagent_message" -> {
            // May amend a done turn's EXISTING tool card; drops if parent absent.
            val parentToolUseId = asString(f["parentToolUseId"]) ?: return state
            val items = f["items"] as? JsonArray ?: return state
            updateTurnById(state, event.turnId) { turn ->
                val parent = turn.blocksById[parentToolUseId]
                if (parent == null || parent.kind != Vocab.BLOCK_TOOL) {
                    turn
                } else {
                    turn.copy(
                        blocksById = turn.blocksById + (
                            parentToolUseId to parent.copy(
                                subagent = foldSubagentItems(parent.subagent, items, decodeSubagentUsage(f["usage"])),
                            )
                            ),
                    )
                }
            }
        }

        "warning", "unknown_event" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val legacyError = if (event.type == "unknown_event") legacyUnknownErrorMessage(f["raw"]) else null
            updateTurn(state) { turn ->
                turn.copy(
                    error = turn.error ?: legacyError,
                    warnings = (
                        turn.warnings +
                            TurnWarning(type = event.type, message = asString(f["message"]), raw = f["raw"])
                        ).takeLast(Limits.MAX_WARNINGS),
                )
            }
        }

        "error" -> {
            val message = asString(f["message"])
            if (event.turnId == null) return state.copy(lastError = message)
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            updateTurn(state) { it.copy(error = it.error ?: message) }
        }

        "turn_end" -> {
            if (!isOpenCurrentTurn(state, event.turnId)) return state
            val outcome = asString(f["outcome"]) // VERBATIM, not validated
            val next = updateTurn(state) { it.copy(status = Vocab.TURN_DONE, outcome = outcome) }
            next.copy(status = Vocab.SESSION_READY, activeTurnId = null, lastTurnOutcome = outcome)
        }

        "background_interrupted", "background_abandoned" -> {
            val seq = event.seq
            if (seq != null && state.notices.any { it.seq == seq }) return state
            val notice = SessionNotice(
                kind = event.type,
                outstanding = asNumber(f["outstanding"])?.toLong() ?: 0,
                reason = asString(f["reason"]),
                seq = seq,
            )
            state.copy(notices = state.notices + notice)
        }

        "external_advancement" -> {
            val seq = event.seq
            if (seq != null && state.notices.any { it.seq == seq }) return state
            state.copy(
                notices = state.notices + SessionNotice(
                    kind = "external_advancement",
                    count = asNumber(f["count"])?.toLong(),
                    seq = seq,
                ),
            )
        }

        "queued_message_added" -> {
            val queueId = asString(f["queueId"]) ?: return state
            if (state.queuedMessages.any { it.queueId == queueId }) return state
            state.copy(
                queuedMessages = state.queuedMessages + QueuedMessage(queueId, asString(f["text"]) ?: ""),
            )
        }

        "queued_message_updated" -> {
            val queueId = asString(f["queueId"]) ?: return state
            if (state.queuedMessages.none { it.queueId == queueId }) return state
            state.copy(
                queuedMessages = state.queuedMessages.map {
                    if (it.queueId == queueId) QueuedMessage(queueId, asString(f["text"]) ?: "") else it
                },
            )
        }

        "queued_message_removed" -> {
            val queueId = asString(f["queueId"]) ?: return state
            if (state.queuedMessages.none { it.queueId == queueId }) return state
            state.copy(queuedMessages = state.queuedMessages.filter { it.queueId != queueId })
        }

        // task_started / task_progress / task_completed / background_tasks_changed /
        // background_pending and every unknown type: silent no-ops.
        else -> state
    }
}
