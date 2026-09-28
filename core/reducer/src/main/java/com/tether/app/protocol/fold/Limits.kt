package com.tether.app.protocol.fold

import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsValue

// T2.1 H1: every constant table the v128 reducer reads, including the few that sit in the
// adapter region of events.mjs (nonNegativeFiniteNumber/blockEventTs, TASK_*,
// MODEL_FALLBACK_TRIGGER_CLASSES). Values are copied verbatim; family units read them from
// here rather than re-declaring them.
object Limits {

    // events.mjs:155
    val TURN_INTERRUPT_SOURCES: List<String> =
        listOf("message-send", "operator-stop", "control-api", "parent-cancel", "watchdog", "teardown")

    // events.mjs:159
    const val MAX_TURN_INTERRUPTED_NOTICES = 100

    // events.mjs:161
    object TURN_OUTCOMES {
        const val OK = "ok"
        const val CANCELLED = "cancelled"
        const val ERROR = "error"
        const val UNKNOWN = "outcome_unknown"
    }

    // events.mjs:168
    const val RATE_LIMIT_RESUME_DELAY_MS = 2.0 * 60 * 1000

    // events.mjs:180
    const val RATE_LIMIT_RESUME_MAX_HORIZON_MS = 5.0 * 60 * 60 * 1000

    // events.mjs:182
    object SESSION_STATUS {
        const val READY = "ready"
        const val ACTIVE = "active"
        const val WAITING = "waiting"
        const val EXITED = "exited"
    }

    // events.mjs:192
    object PROVIDER_PROJECTION_LIMITS {
        const val approvalChoices = 8
        const val approvalChoiceIdChars = 128
        const val paths = 64
        const val planSteps = 100
        const val modelReroutes = 20
        const val modelFallbacks = 20
        const val reviews = 50
        const val compactions = 50
        const val providerNotices = 50
        const val mcpServers = 128
        const val idChars = 200
        const val labelChars = 200
        const val proseChars = 2_000
        const val commandChars = 16_000
        const val diffChars = 256_000
        const val pathChars = 4_096
    }

    // events.mjs:215
    const val MAX_DISMISSED_NOTICES = 200

    // events.mjs:511
    val API_RETRY_RESOLVED_BY: Set<String> = setOf(
        "message_started",
        "message_delta",
        "message_completed",
        "thinking_delta",
        "thinking_completed",
        "thinking_stop",
        "tool_start",
        "tool_progress",
        "tool_output_delta",
        "tool_end",
        "permission_denied",
        "subagent_message",
        "plan_updated",
        "todo_item_created",
        "todo_item_id_assigned",
        "todo_item_updated",
        "diff_updated",
        "model_rerouted",
        "review_started",
        "review_completed",
        "context_compacted",
        "usage",
        "token_progress",
        "cancelled",
        "turn_end",
    )

    // events.mjs:562
    object CLI_INVENTORY_LIMITS {
        const val commands = 128
        const val tools = 256
        const val mcpServers = 64
        const val aliasesPerCommand = 16
        const val nameChars = 100
        const val descriptionChars = 500
        const val argumentHintChars = 200
    }

    // events.mjs:572
    val MCP_SERVER_STATUSES: Set<String> = setOf("connected", "failed", "needs-auth", "pending", "disabled")

    // events.mjs:579
    val CLAUDE_MCP_STATUS_TO_HEALTH: Map<String, String> = linkedMapOf(
        "connected" to "ready",
        "pending" to "starting",
        "failed" to "failed",
        "needs-auth" to "needs-auth",
        "disabled" to "disabled",
    )

    // events.mjs:600
    val PROVIDER_IDS: Set<String> = setOf("claude", "codex", "opencode")

    // events.mjs:601
    val APPROVAL_KINDS: Set<String> = setOf("tool", "command", "file-change", "network", "permissions")

    // events.mjs:602
    val PLAN_STEP_STATUSES: Set<String> = setOf("pending", "in_progress", "completed")

    // events.mjs:603
    val REVIEW_COMPLETION_STATUSES: Set<String> = setOf("completed", "cancelled", "failed")

    // events.mjs:604
    val MCP_HEALTH_STATUSES: Set<String> =
        setOf("starting", "ready", "failed", "cancelled", "needs-auth", "disabled", "unknown")

    // events.mjs:605
    val PROVIDER_NOTICE_LEVELS: Set<String> = setOf("info", "warning", "error")

    // events.mjs:750
    object TODO_LIMITS {
        const val items = PROVIDER_PROJECTION_LIMITS.planSteps
        const val textChars = PROVIDER_PROJECTION_LIMITS.proseChars
    }

    // events.mjs:759
    val TODO_STATUSES: Set<String> = setOf("pending", "in_progress", "completed")

    // events.mjs:921
    const val MAX_BACKGROUND_COMMANDS = 100

    // events.mjs:922
    val BACKGROUND_COMMAND_STATUSES: Set<String> = setOf("running", "finished", "stopped", "error", "interrupted")

    // events.mjs:957
    const val MAX_SPAWNED_RUNS = 50

    // events.mjs:967 (79c3d37) — v130 (S13.1-C): how many removed queueIds the projection retains.
    const val MAX_REMOVED_QUEUE_IDS = 50

    // events.mjs:958
    const val MAX_SPAWNED_RUN_KEYS = 2000

    // events.mjs:959
    const val SPAWNED_RUN_OUTPUT_CAP_CHARS = 64 * 1024

    // events.mjs:960
    val SPAWNED_RUN_STATUSES: Set<String> = setOf("running", "finished", "error", "stopped", "interrupted")

    // events.mjs:961
    val SPAWNED_RUN_ORIGINS: Set<String> = setOf("spawned", "discovered")

    // events.mjs:962
    val SPAWNED_RUN_MODES: Set<String> = setOf("review", "build")

    // events.mjs:991
    val SPAWNED_RUN_COMPARED_FIELDS: List<String> = listOf(
        "origin", "provider", "title", "prompt", "mode", "model", "cwd", "logFile", "nativeId",
        "parentTurnId", "toolId", "status", "exitCode", "signal", "startedAt", "endedAt",
    )

    // events.mjs:1003
    const val MAX_SPAWNED_RUN_MEDIA = 48

    // events.mjs:1004
    val SPAWNED_RUN_MEDIA_URL = Regex("^/api/tool-media/[0-9a-f]{64}\\.(?:png|jpg|jpeg|gif|webp|mp4)$")

    // events.mjs:1005
    val SPAWNED_RUN_MEDIA_SOURCES: Set<String> = setOf("input", "viewed")

    // events.mjs:1184
    val RATE_LIMIT_STATUSES: Set<String> = setOf("allowed", "allowed_warning", "rejected")

    // events.mjs:1188
    val RATE_LIMIT_GRACE: Set<String> = setOf("wrap_up", "wrap_up_then_credits")

    // events.mjs:1203
    val FAST_MODE_STATES: Set<String> = setOf("off", "cooldown", "on")

    // events.mjs:1318
    val PERMISSION_DENIAL_REASON_CODE = Regex("^[A-Za-z][A-Za-z0-9_-]{0,39}$")

    // events.mjs:1328
    val PERMISSION_DENIAL_REASON_VALUES: Set<String> = setOf(
        "classifier", "safety_check", "rule", "mode", "working_dir", "sandbox", "hook",
        "prompt_tool", "async_agent", "other", "unknown",
    )

    // events.mjs:1538
    object BACKGROUND_TASK_LIMITS {
        const val tasks = 200
        const val idChars = PROVIDER_PROJECTION_LIMITS.idChars
        const val labelChars = PROVIDER_PROJECTION_LIMITS.labelChars
    }

    // events.mjs:2680 (declared inline in the warning/unknown_event case)
    const val MAX_WARNINGS = 50

    // events.mjs:3336
    val TASK_TERMINAL_STATUSES: Set<String> = setOf("completed", "failed", "stopped", "killed")

    // events.mjs:3337
    val TASK_PROGRESS_STATUSES: Set<String> = setOf("pending", "running", "paused")

    // events.mjs:3412
    val MODEL_FALLBACK_TRIGGER_CLASSES: Map<String, String> = linkedMapOf(
        "overloaded" to "capacity",
        "server_error" to "capacity",
        "model_not_found" to "unavailable",
        "permission_denied" to "unavailable",
        "model_blocked" to "unavailable",
        "last_resort" to "error",
    )
}

// events.mjs:3088 — `undefined` (Kotlin null) unless a finite, non-negative number.
fun nonNegativeFiniteNumber(value: JsValue?): JsNum? =
    if (value is JsNum && value.value.isFinite() && value.value >= 0) value else null

// events.mjs:3095 — spread as `...blockEventTs(event)`: `{ ts }` or `{}`.
fun blockEventTs(event: JsObj?): JsObj {
    val ts = nonNegativeFiniteNumber(event?.get("ts")) ?: return JsObj.EMPTY
    return JsObj.of("ts" to ts)
}

// events.mjs:3421
fun modelFallbackClass(trigger: JsValue?): String? {
    val key = (trigger as? com.tether.app.protocol.tree.JsStr)?.value ?: return null
    return Limits.MODEL_FALLBACK_TRIGGER_CLASSES[key]
}

// events.mjs:3428
fun modelFallbackLeadIn(trigger: JsValue?): String = when (modelFallbackClass(trigger)) {
    "capacity" -> "Model switched · capacity"
    "unavailable" -> "Model switched · not available"
    "error" -> "Model switched · unretryable error"
    else -> "Model switched"
}
