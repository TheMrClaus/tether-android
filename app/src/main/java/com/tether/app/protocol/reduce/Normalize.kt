package com.tether.app.protocol.reduce

import com.tether.app.protocol.model.ApprovalChoice
import com.tether.app.protocol.model.ApprovalNetwork
import com.tether.app.protocol.model.ApprovalRequestMetadata
import com.tether.app.protocol.model.CliCommand
import com.tether.app.protocol.model.CliInventory
import com.tether.app.protocol.model.CliMcpServer
import com.tether.app.protocol.model.GrantedFileSystem
import com.tether.app.protocol.model.GrantedNetwork
import com.tether.app.protocol.model.GrantedPermissions
import com.tether.app.protocol.model.PermissionDenialProjection
import com.tether.app.protocol.model.PlanStepProjection
import com.tether.app.protocol.model.ProviderNoticeProjection
import com.tether.app.protocol.model.ReviewProjection
import com.tether.app.protocol.model.TodoItemProjection
import com.tether.app.protocol.model.TodoProjection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Bounded-normalization helpers, a faithful Kotlin port of the pure helpers in
 * aidash engines/events.mjs. All display-text truncation is by Unicode CODE
 * POINTS (`Array.from` semantics), while approval choice IDs are checked by
 * UTF-16 length (`String.length` semantics) — exactly like the JS source.
 */
internal object Limits {
    const val APPROVAL_CHOICES = 8
    const val APPROVAL_CHOICE_ID_CHARS = 128
    const val PATHS = 64
    const val PLAN_STEPS = 100
    const val MODEL_REROUTES = 20
    const val REVIEWS = 50
    const val COMPACTIONS = 50
    const val PROVIDER_NOTICES = 50
    const val MCP_SERVERS = 128
    const val ID_CHARS = 200
    const val LABEL_CHARS = 200
    const val PROSE_CHARS = 2_000
    const val COMMAND_CHARS = 16_000
    const val DIFF_CHARS = 256_000
    const val PATH_CHARS = 4_096
    const val TODO_ITEMS = 100
    const val TODO_TEXT_CHARS = 2_000
    const val MAX_WARNINGS = 50

    // CLI_INVENTORY_LIMITS
    const val INV_COMMANDS = 128
    const val INV_TOOLS = 256
    const val INV_MCP_SERVERS = 64
    const val INV_ALIASES_PER_COMMAND = 16
    const val INV_NAME_CHARS = 100
    const val INV_DESCRIPTION_CHARS = 500
    const val INV_ARGUMENT_HINT_CHARS = 200
}

internal val PROVIDER_IDS = setOf("claude", "codex", "gemini", "opencode")
internal val APPROVAL_KINDS = setOf("tool", "command", "file-change", "network", "permissions")
internal val PLAN_STEP_STATUSES = setOf("pending", "in_progress", "completed")
internal val TODO_STATUSES = setOf("pending", "in_progress", "completed")
internal val REVIEW_COMPLETION_STATUSES = setOf("completed", "cancelled", "failed")
internal val MCP_HEALTH_STATUSES =
    setOf("starting", "ready", "failed", "cancelled", "needs-auth", "disabled", "unknown")
internal val MCP_SERVER_STATUSES = setOf("connected", "failed", "needs-auth", "pending", "disabled")
internal val PROVIDER_NOTICE_LEVELS = setOf("info", "warning", "error")
internal val RATE_LIMIT_STATUSES = setOf("allowed", "allowed_warning", "rejected")
internal val PERMISSION_DENIAL_REASON_VALUES = setOf(
    "classifier", "safety_check", "rule", "mode", "working_dir", "sandbox",
    "hook", "prompt_tool", "async_agent", "other", "unknown",
)
internal val PERMISSION_DENIAL_REASON_CODE = Regex("^[A-Za-z][A-Za-z0-9_-]{0,39}$")

// ---------------------------------------------------------------------------
// Primitive coercion (works on raw JsonElements so malformed payloads degrade)
// ---------------------------------------------------------------------------

internal fun asString(el: JsonElement?): String? {
    val p = el as? JsonPrimitive ?: return null
    return if (p.isString) p.content else null
}

internal fun asBoolean(el: JsonElement?): Boolean? {
    val p = el as? JsonPrimitive ?: return null
    if (p.isString) return null
    return p.booleanOrNull
}

internal fun asNumber(el: JsonElement?): Double? {
    val p = el as? JsonPrimitive ?: return null
    if (p.isString) return null
    return p.doubleOrNull
}

/** boundedDisplayText: null for non-string, truncated by code points otherwise. */
internal fun boundedDisplayText(value: String?, maxChars: Int): String? {
    if (value == null) return null
    val count = value.codePointCount(0, value.length)
    if (count <= maxChars) return value
    return value.substring(0, value.offsetByCodePoints(0, maxChars))
}

internal fun boundedDisplayText(el: JsonElement?, maxChars: Int): String? =
    boundedDisplayText(asString(el), maxChars)

/** boundedIdentifier: non-empty string, code-point capped at 200. */
internal fun boundedIdentifier(el: JsonElement?): String? {
    val value = asString(el) ?: return null
    if (value.isEmpty()) return null
    return boundedDisplayText(value, Limits.ID_CHARS)
}

/** Choice IDs are opaque tokens: UTF-16 length 1..128, REJECTED not truncated. */
internal fun boundedApprovalChoiceId(el: JsonElement?): String? {
    val value = asString(el) ?: return null
    if (value.isEmpty() || value.length > Limits.APPROVAL_CHOICE_ID_CHARS) return null
    return value
}

/** normalizeStringList: null (= undefined) when not an array. */
internal fun normalizeStringList(el: JsonElement?, limit: Int, maxChars: Int): List<String>? {
    val array = el as? JsonArray ?: return null
    val result = ArrayList<String>()
    for (item in array) {
        if (result.size >= limit) break
        val value = asString(item) ?: continue
        if (value.isEmpty()) continue
        result.add(boundedDisplayText(value, maxChars)!!)
    }
    return result
}

// ---------------------------------------------------------------------------
// Approvals
// ---------------------------------------------------------------------------

internal fun normalizeGrantedPermissions(el: JsonElement?): GrantedPermissions? {
    val obj = el as? JsonObject ?: return null
    var fileSystem: GrantedFileSystem? = null
    val fs = obj["fileSystem"] as? JsonObject
    if (fs != null) {
        fileSystem = GrantedFileSystem(
            read = normalizeStringList(fs["read"], Limits.PATHS, Limits.PATH_CHARS),
            write = normalizeStringList(fs["write"], Limits.PATHS, Limits.PATH_CHARS),
        )
    }
    var network: GrantedNetwork? = null
    val net = obj["network"] as? JsonObject
    val enabled = asBoolean(net?.get("enabled"))
    if (net != null && enabled != null) network = GrantedNetwork(enabled)
    return GrantedPermissions(fileSystem = fileSystem, network = network)
}

/** null means "event carried no choices array" (kept absent on the approval). */
internal fun normalizeApprovalChoices(el: JsonElement?): List<ApprovalChoice>? {
    val array = el as? JsonArray ?: return null
    val normalized = ArrayList<ApprovalChoice>()
    val seen = HashSet<String>()
    for (raw in array) {
        if (normalized.size >= Limits.APPROVAL_CHOICES) break
        val choice = raw as? JsonObject ?: continue
        val choiceId = boundedApprovalChoiceId(choice["choiceId"]) ?: continue
        val label = boundedDisplayText(choice["label"], Limits.LABEL_CHARS)
        if (label.isNullOrEmpty() || !seen.add(choiceId)) continue
        val grant = asString(choice["permissionGrant"])
        normalized.add(
            ApprovalChoice(
                choiceId = choiceId,
                label = label,
                description = boundedDisplayText(choice["description"], Limits.PROSE_CHARS),
                permissionGrant = if (grant == "exact" || grant == "subset") grant else null,
            ),
        )
    }
    return normalized
}

internal fun normalizeApprovalMetadata(el: JsonElement?): ApprovalRequestMetadata? {
    val obj = el as? JsonObject ?: return null
    val provider = asString(obj["provider"])
    val kind = asString(obj["kind"])
    if (provider !in PROVIDER_IDS || kind !in APPROVAL_KINDS) return null
    var network: ApprovalNetwork? = null
    val net = obj["network"] as? JsonObject
    if (net != null) {
        val host = boundedDisplayText(net["host"], Limits.LABEL_CHARS)
        if (!host.isNullOrEmpty()) {
            val port = asNumber(net["port"])
            network = ApprovalNetwork(
                host = host,
                protocol = boundedDisplayText(net["protocol"], Limits.LABEL_CHARS),
                port = if (port != null && port == Math.floor(port) && port >= 0 && port <= 65_535) {
                    port.toInt()
                } else {
                    null
                },
            )
        }
    }
    return ApprovalRequestMetadata(
        provider = provider!!,
        kind = kind!!,
        reason = boundedDisplayText(obj["reason"], Limits.PROSE_CHARS),
        command = boundedDisplayText(obj["command"], Limits.COMMAND_CHARS),
        cwd = boundedDisplayText(obj["cwd"], Limits.PATH_CHARS),
        paths = normalizeStringList(obj["paths"], Limits.PATHS, Limits.PATH_CHARS),
        network = network,
        requestedPermissions = normalizeGrantedPermissions(obj["requestedPermissions"]),
    )
}

// ---------------------------------------------------------------------------
// Plans / todos
// ---------------------------------------------------------------------------

internal fun normalizePlanSteps(el: JsonElement?): List<PlanStepProjection> {
    val array = el as? JsonArray ?: return emptyList()
    val normalized = ArrayList<PlanStepProjection>()
    for (item in array) {
        if (normalized.size >= Limits.PLAN_STEPS) break
        val obj = item as? JsonObject ?: continue
        val step = boundedDisplayText(obj["step"], Limits.PROSE_CHARS)
        val status = asString(obj["status"])
        if (step.isNullOrEmpty() || status !in PLAN_STEP_STATUSES) continue
        normalized.add(PlanStepProjection(step = step, status = status!!))
    }
    return normalized
}

private fun isBlank(value: String?): Boolean = value == null || value.trim().isEmpty()

internal fun normalizeTodoItems(el: JsonElement?): List<TodoItemProjection> {
    val array = el as? JsonArray ?: return emptyList()
    val normalized = ArrayList<TodoItemProjection>()
    for (item in array) {
        if (normalized.size >= Limits.TODO_ITEMS) break
        val obj = item as? JsonObject ?: continue
        val content = boundedDisplayText(obj["content"], Limits.TODO_TEXT_CHARS)
        val status = asString(obj["status"])
        if (isBlank(content) || status !in TODO_STATUSES) continue
        val activeForm = boundedDisplayText(obj["activeForm"], Limits.TODO_TEXT_CHARS)
        normalized.add(
            TodoItemProjection(
                content = content!!,
                activeForm = if (isBlank(activeForm)) "" else activeForm!!,
                status = status!!,
            ),
        )
    }
    return normalized
}

internal fun todoProjection(items: List<TodoItemProjection>): TodoProjection {
    val active = items.firstOrNull { it.status == "in_progress" }
    return TodoProjection(
        items = items,
        activeForm = active?.let { it.activeForm.ifEmpty { it.content } },
        completed = items.count { it.status == "completed" },
        total = items.size,
    )
}

// ---------------------------------------------------------------------------
// Reviews / notices / denials
// ---------------------------------------------------------------------------

/**
 * Upsert by reviewId with JS shallow-merge semantics: only the keys the new
 * record CARRIES overwrite (target/result use null == "key absent").
 */
internal fun upsertReview(
    reviews: List<ReviewProjection>,
    reviewId: String,
    status: String,
    target: String?,
    result: String?,
): List<ReviewProjection> {
    val index = reviews.indexOfFirst { it.reviewId == reviewId }
    val next = if (index == -1) {
        reviews + ReviewProjection(
            reviewId = reviewId,
            status = status,
            target = target,
            result = result,
        )
    } else {
        reviews.mapIndexed { i, item ->
            if (i != index) {
                item
            } else {
                item.copy(
                    status = status,
                    target = target ?: item.target,
                    result = result ?: item.result,
                )
            }
        }
    }
    return next.takeLast(Limits.REVIEWS)
}

/** Returns the SAME list instance when the noticeId is already present. */
internal fun appendProviderNotice(
    notices: List<ProviderNoticeProjection>,
    notice: ProviderNoticeProjection,
): List<ProviderNoticeProjection> {
    if (notices.any { it.noticeId == notice.noticeId }) return notices
    return (notices + notice).takeLast(Limits.PROVIDER_NOTICES)
}

internal fun permissionDenialReasonCode(el: JsonElement?): String? {
    val value = asString(el) ?: return null
    return if (PERMISSION_DENIAL_REASON_CODE.matches(value)) value else null
}

private fun samePermissionDenial(a: PermissionDenialProjection, b: PermissionDenialProjection): Boolean =
    a.toolId == b.toolId && a.name == b.name && a.reason == b.reason &&
        a.reasonCode == b.reasonCode && (a.subagent == true) == (b.subagent == true)

/** Dedupe by toolId; a later record may only ENRICH the existing entry. */
internal fun upsertPermissionDenial(
    denials: List<PermissionDenialProjection>,
    denial: PermissionDenialProjection,
): List<PermissionDenialProjection> {
    val index = denials.indexOfFirst { it.toolId == denial.toolId }
    if (index == -1) return denials + denial
    val existing = denials[index]
    val enriched = existing.copy(
        name = denial.name.ifEmpty { existing.name },
        reason = if (existing.reason == "unknown") denial.reason else existing.reason,
        reasonCode = existing.reasonCode ?: denial.reasonCode,
        subagent = if (existing.subagent == true || denial.subagent == true) true else null,
    )
    if (samePermissionDenial(existing, enriched)) return denials
    return denials.mapIndexed { i, item -> if (i == index) enriched else item }
}

// ---------------------------------------------------------------------------
// CLI inventory
// ---------------------------------------------------------------------------

private fun identifier(el: JsonElement?, stripLeadingSlash: Boolean = false): String? {
    val value = asString(el) ?: return null
    val normalized = if (stripLeadingSlash) value.trim().removePrefix("/") else value.trim()
    if (normalized.isEmpty()) return null
    if (normalized.codePointCount(0, normalized.length) > Limits.INV_NAME_CHARS) return null
    return normalized
}

internal fun normalizeClaudeCommands(el: JsonElement?): List<CliCommand> {
    val array = el as? JsonArray ?: return emptyList()
    val normalized = ArrayList<CliCommand>()
    val seen = HashSet<String>()
    for (raw in array) {
        if (normalized.size >= Limits.INV_COMMANDS) break
        val (nameEl, source) = when (raw) {
            is JsonPrimitive -> raw to null
            is JsonObject -> raw["name"] to raw
            else -> continue
        }
        val name = identifier(nameEl, stripLeadingSlash = true) ?: continue
        if (!seen.add(name)) continue
        var aliases: List<String>? = null
        val rawAliases = source?.get("aliases") as? JsonArray
        if (rawAliases != null) {
            val collected = ArrayList<String>()
            val aliasSeen = hashSetOf(name)
            for (rawAlias in rawAliases) {
                if (collected.size >= Limits.INV_ALIASES_PER_COMMAND) break
                val alias = identifier(rawAlias, stripLeadingSlash = true) ?: continue
                if (!aliasSeen.add(alias)) continue
                collected.add(alias)
            }
            if (collected.isNotEmpty()) aliases = collected
        }
        normalized.add(
            CliCommand(
                name = name,
                description = boundedDisplayText(source?.get("description"), Limits.INV_DESCRIPTION_CHARS),
                argumentHint = boundedDisplayText(source?.get("argumentHint"), Limits.INV_ARGUMENT_HINT_CHARS),
                aliases = aliases,
            ),
        )
    }
    return normalized
}

internal fun normalizeClaudeTools(el: JsonElement?): List<String> {
    val array = el as? JsonArray ?: return emptyList()
    val normalized = ArrayList<String>()
    val seen = HashSet<String>()
    for (raw in array) {
        if (normalized.size >= Limits.INV_TOOLS) break
        val name = identifier(raw) ?: continue
        if (!seen.add(name)) continue
        normalized.add(name)
    }
    return normalized
}

internal fun normalizeClaudeMcpServers(el: JsonElement?): List<CliMcpServer> {
    val array = el as? JsonArray ?: return emptyList()
    val normalized = ArrayList<CliMcpServer>()
    val seen = HashSet<String>()
    for (raw in array) {
        if (normalized.size >= Limits.INV_MCP_SERVERS) break
        val obj = raw as? JsonObject ?: continue
        val name = identifier(obj["name"]) ?: continue
        if (!seen.add(name)) continue
        val status = asString(obj["status"])
        normalized.add(CliMcpServer(name = name, status = if (status in MCP_SERVER_STATUSES) status!! else "unknown"))
    }
    return normalized
}

private fun sameCliCommand(a: CliCommand, b: CliCommand): Boolean =
    a.name == b.name && a.description == b.description && a.argumentHint == b.argumentHint &&
        (a.aliases ?: emptyList<String>()) == (b.aliases ?: emptyList<String>())

internal fun sameCliInventory(a: CliInventory?, b: CliInventory?): Boolean {
    if (a == null || b == null) return a === b || (a == null && b == null)
    if (a.commands.size != b.commands.size || a.tools != b.tools || a.mcpServers.size != b.mcpServers.size) {
        return false
    }
    return a.commands.indices.all { sameCliCommand(a.commands[it], b.commands[it]) } &&
        a.mcpServers.indices.all {
            a.mcpServers[it].name == b.mcpServers[it].name && a.mcpServers[it].status == b.mcpServers[it].status
        }
}

/** Metadata-less commands (from a bare init) inherit the previous same-name command. */
internal fun normalizeProjectedCliInventory(el: JsonElement?, previous: CliInventory?): CliInventory? {
    val obj = el as? JsonObject ?: return null
    val commands = normalizeClaudeCommands(obj["commands"])
    val previousCommands = (previous?.commands ?: emptyList()).associateBy { it.name }
    val merged = commands.map { command ->
        val prior = previousCommands[command.name]
        if (prior != null && command.description == null && command.argumentHint == null && command.aliases == null) {
            prior
        } else {
            command
        }
    }
    return CliInventory(
        commands = merged,
        tools = normalizeClaudeTools(obj["tools"]),
        mcpServers = normalizeClaudeMcpServers(obj["mcpServers"]),
    )
}
