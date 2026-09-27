package com.tether.app.protocol.fold

import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.js
import com.tether.app.protocol.tree.num
import com.tether.app.protocol.tree.obj
import com.tether.app.protocol.tree.str

// T2.1 H1: the v128 reducer entry point — a line-for-line port of engines/events.mjs `reduce`
// over the JsValue tree. State and events are the canonical JSON trees (JS undefined = absent
// key); the fold is pure and never reads a clock (FoldPurityTest guards this directory).
//
// Family units own the per-event folds: FoldTurn (A), FoldInteraction + FoldNotices (B),
// FoldSessionLists + FoldBackground (C). This file only dispatches, plus the api_retry case
// and the two post-passes.

// events.mjs:1463
fun reduce(state: JsObj, event: JsObj): JsObj =
    syncTurnRun(clearResolvedApiRetry(reduceEvent(state, event), event), event)

// events.mjs:1609 — every `case` label, explicitly (FoldEventLabelCoverageTest checks this list
// against the vendored events.mjs). An unknown or non-string type is the JS `default`: a no-op.
internal fun reduceEvent(state: JsObj, event: JsObj): JsObj {
    val type = event["type"].str ?: return state
    return when (type) {
        "native_session_id" -> foldTurn(state, event, type) // events.mjs:1611
        "cli_inventory_reset" -> foldSessionLists(state, event, type) // events.mjs:1633
        "cli_commands_changed" -> foldSessionLists(state, event, type) // events.mjs:1636
        "api_retry" -> foldApiRetry(state, event) // events.mjs:1647
        "rate_limit" -> foldInteraction(state, event, type) // events.mjs:1665
        "fast_mode" -> foldInteraction(state, event, type) // events.mjs:1696
        "limit_hit" -> foldInteraction(state, event, type) // events.mjs:1707
        "rate_limit_resume_scheduled" -> foldInteraction(state, event, type) // events.mjs:1730
        "rate_limit_resume_dismissed" -> foldInteraction(state, event, type) // events.mjs:1747
        "rate_limit_resume_fired" -> foldInteraction(state, event, type) // events.mjs:1753
        "todo_updated" -> foldSessionLists(state, event, type) // events.mjs:1764
        "todo_item_created" -> foldSessionLists(state, event, type) // events.mjs:1782
        "todo_item_id_assigned" -> foldSessionLists(state, event, type) // events.mjs:1799
        "todo_item_updated" -> foldSessionLists(state, event, type) // events.mjs:1815
        "plan_updated" -> foldSessionLists(state, event, type) // events.mjs:1848
        "diff_updated" -> foldSessionLists(state, event, type) // events.mjs:1861
        "model_rerouted" -> foldSessionLists(state, event, type) // events.mjs:1867
        "model_fallback" -> foldSessionLists(state, event, type) // events.mjs:1884
        "review_started" -> foldSessionLists(state, event, type) // events.mjs:1928
        "review_completed" -> foldSessionLists(state, event, type) // events.mjs:1937
        "mcp_health_updated" -> foldSessionLists(state, event, type) // events.mjs:1946
        "context_compacted" -> foldNotices(state, event, type) // events.mjs:1968
        "task_started" -> foldBackground(state, event, type) // events.mjs:1986
        "task_progress" -> foldBackground(state, event, type) // events.mjs:2000
        "task_completed" -> foldBackground(state, event, type) // events.mjs:2018
        "background_tasks_changed" -> foldBackground(state, event, type) // events.mjs:2037
        "provider_notice" -> foldNotices(state, event, type) // events.mjs:2047
        "permission_denied" -> foldInteraction(state, event, type) // events.mjs:2072
        "turn_started" -> foldTurn(state, event, type) // events.mjs:2112
        "user_message_accepted" -> foldTurn(state, event, type) // events.mjs:2134
        "message_started" -> foldTurn(state, event, type) // events.mjs:2156
        "message_delta" -> foldTurn(state, event, type) // events.mjs:2163
        "message_completed" -> foldTurn(state, event, type) // events.mjs:2173
        "command_output_started" -> foldBackground(state, event, type) // events.mjs:2192
        "command_output_delta" -> foldBackground(state, event, type) // events.mjs:2210
        "command_output_completed" -> foldBackground(state, event, type) // events.mjs:2231
        "background_command_updated" -> foldBackground(state, event, type) // events.mjs:2247
        "background_command_output" -> foldBackground(state, event, type) // events.mjs:2271
        "spawned_run_updated" -> foldBackground(state, event, type) // events.mjs:2294
        "spawned_run_output" -> foldBackground(state, event, type) // events.mjs:2318
        "spawned_run_media" -> foldBackground(state, event, type) // events.mjs:2344
        "thinking_delta" -> foldTurn(state, event, type) // events.mjs:2368
        "thinking_completed" -> foldTurn(state, event, type) // events.mjs:2381
        "thinking_stop" -> foldTurn(state, event, type) // events.mjs:2391
        "tool_start" -> foldTurn(state, event, type) // events.mjs:2403
        "tool_output_delta" -> foldTurn(state, event, type) // events.mjs:2418
        "tool_progress" -> foldTurn(state, event, type) // events.mjs:2428
        "tool_end" -> foldTurn(state, event, type) // events.mjs:2478
        "approval_request" -> foldInteraction(state, event, type) // events.mjs:2495
        "question_request" -> foldInteraction(state, event, type) // events.mjs:2517
        "question_resolved" -> foldInteraction(state, event, type) // events.mjs:2529
        "question_cancelled" -> foldInteraction(state, event, type) // events.mjs:2530
        "question_answered" -> foldInteraction(state, event, type) // events.mjs:2540
        "approval_resolved" -> foldInteraction(state, event, type) // events.mjs:2564
        "approval_expired" -> foldInteraction(state, event, type) // events.mjs:2565
        "cancel_requested" -> foldTurn(state, event, type) // events.mjs:2575
        "cancelled" -> foldTurn(state, event, type) // events.mjs:2580
        "process_exit" -> foldTurn(state, event, type) // events.mjs:2593
        "usage" -> foldTurn(state, event, type) // events.mjs:2598
        "token_progress" -> foldTurn(state, event, type) // events.mjs:2615
        "turn_activity" -> foldTurn(state, event, type) // events.mjs:2628
        "subagent_message" -> foldTurn(state, event, type) // events.mjs:2652
        "warning" -> foldTurn(state, event, type) // events.mjs:2674
        "unknown_event" -> foldTurn(state, event, type) // events.mjs:2675
        "error" -> foldTurn(state, event, type) // events.mjs:2692
        "turn_end" -> foldTurn(state, event, type) // events.mjs:2721
        "background_interrupted" -> foldNotices(state, event, type) // events.mjs:2753
        "background_abandoned" -> foldNotices(state, event, type) // events.mjs:2754
        "turn_interrupted" -> foldNotices(state, event, type) // events.mjs:2784
        "external_advancement" -> foldNotices(state, event, type) // events.mjs:2826
        "notice_dismissed" -> foldNotices(state, event, type) // events.mjs:2852
        "queued_message_added" -> foldInteraction(state, event, type) // events.mjs:2863
        "queued_message_updated" -> foldInteraction(state, event, type) // events.mjs:2878
        "queued_message_removed" -> foldInteraction(state, event, type) // events.mjs:2888
        // events.mjs:2899 — background_pending (a journal-only breadcrumb) and any type this
        // reducer does not know.
        else -> state
    }
}

// events.mjs:1647
private fun foldApiRetry(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    return updateTurn(state) { turn ->
        turn.put(
            "apiRetry",
            JsObj.of(
                "attempt" to event["attempt"],
                "maxRetries" to event["maxRetries"].orJsNull(),
                "delayMs" to event["delayMs"].orJsNull(),
                "errorStatus" to event["errorStatus"].orJsNull(),
                "error" to event["error"].orJsNull(),
            ),
        )
    }
}

// events.mjs:550
internal fun clearResolvedApiRetry(state: JsObj, event: JsObj): JsObj {
    if (event["type"].str !in Limits.API_RETRY_RESOLVED_BY) return state
    val turnsById = state["turnsById"].obj!!
    val turn = turnsById.prop(event["turnId"]).obj
    if (!truthy(turn) || isNullish(turn!!["apiRetry"])) return state
    return state.put("turnsById", turnsById.put(jsToString(event["turnId"]), turn.put("apiRetry", JsNull)))
}

// events.mjs:1437
fun deriveSessionStatus(turn: JsObj?): String {
    if (turn == null || strictEquals(turn["status"], JsStr("done"))) return Limits.SESSION_STATUS.READY
    if (keyCount(turn["pendingApprovals"]) > 0) return Limits.SESSION_STATUS.WAITING
    if (keyCount(turn["pendingQuestions"]) > 0) return Limits.SESSION_STATUS.WAITING
    return Limits.SESSION_STATUS.ACTIVE
}

/** `{ ...next, status: deriveSessionStatus(currentTurn(next)) }` — the approval/question tail. */
internal fun withDerivedStatus(next: JsObj): JsObj = next.put("status", js(deriveSessionStatus(currentTurn(next))))

// events.mjs:1482
internal fun turnIsWorking(turn: JsObj?): Boolean {
    if (turn == null) return false
    val status = turn["status"].str
    if (status != "running" && status != "cancelling") return false
    if (keyCount(turn["pendingApprovals"]) > 0) return false
    if (keyCount(turn["pendingQuestions"]) > 0) return false
    return true
}

// events.mjs:1492
internal fun syncTurnRun(state: JsObj, event: JsObj): JsObj {
    val turnId = coalesce(event["turnId"], state["activeTurnId"])
    if (isNullish(turnId)) return state
    val turnsById = state["turnsById"].obj!!
    val turn = turnsById.prop(turnId).obj ?: return state

    val ts = nonNegativeFiniteNumber(event["ts"]) ?: return state

    val working = turnIsWorking(turn)
    if (working == !isNullish(turn["run"])) return state

    val key = jsToString(turnId)
    if (working) {
        val run = JsObj.of(
            "index" to turn["runCount"],
            "startedAt" to ts,
            "tokensStart" to coalesce(turn["liveTokens"], js(0)),
        )
        return state.put("turnsById", turnsById.put(key, turn.with("run" to run, "runCount" to js(turn.numberAt("runCount") + 1))))
    }

    val startedAt = turn["run"].obj?.get("startedAt").num ?: Double.NaN
    val elapsed = maxOf(0.0, ts.value - startedAt)
    return state.put(
        "turnsById",
        turnsById.put(key, turn.with("run" to JsNull, "activeMs" to js(turn.numberAt("activeMs") + elapsed))),
    )
}
