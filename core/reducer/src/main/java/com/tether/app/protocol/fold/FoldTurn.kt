package com.tether.app.protocol.fold

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.arr
import com.tether.app.protocol.tree.js
import com.tether.app.protocol.tree.num
import com.tether.app.protocol.tree.obj
import com.tether.app.protocol.tree.str

// T2.1 unit A (turn and content stream): events.mjs 1611-1632, 2112-2191, 2368-2494,
// 2575-2751; helpers 496-510, 1370-1436.
//
// H1 seeded the cases its own acceptance cases (run-bookkeeping, api-retry) drive —
// turn_started, message_delta, message_completed, token_progress, warning/unknown_event,
// turn_end — ported in full. Every other label falls to `else` until unit A ports it.
internal fun foldTurn(state: JsObj, event: JsObj, type: String): JsObj = when (type) {
    "turn_started" -> foldTurnStarted(state, event)
    "message_delta" -> foldMessageDelta(state, event)
    "message_completed" -> foldMessageCompleted(state, event)
    "token_progress" -> foldTokenProgress(state, event)
    "warning", "unknown_event" -> foldWarning(state, event, type)
    "turn_end" -> foldTurnEnd(state, event)
    else -> state // not yet ported (unit A)
}

// events.mjs:2112
private fun foldTurnStarted(state: JsObj, event: JsObj): JsObj {
    val turnsById = state["turnsById"].obj!!
    val turnKey = jsToString(event["turnId"])
    if (turnsById.has(turnKey)) return state
    val active = state["activeTurnId"]
    if (truthy(active) && !strictEquals(turnsById.prop(active).obj?.get("status"), JsStr("done"))) return state
    val turn = newTurnProjection(
        event["turnId"],
        event["idempotencyKey"],
        event["continuation"],
        nonNegativeFiniteNumber(event["ts"]) ?: JsNull,
        normalizeCommandRunMeta(event["commandRun"]),
    )
    return clearRateLimitGrace(state).with(
        "status" to js(Limits.SESSION_STATUS.ACTIVE),
        // `activeTurnId: event.turnId` — an undefined turnId drops the key, as in JS.
        "activeTurnId" to event["turnId"],
        "turnOrder" to state["turnOrder"].arr!!.add(event["turnId"] ?: JsNull),
        "turnsById" to turnsById.put(turnKey, turn),
    )
}

// events.mjs:2163
private fun foldMessageDelta(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    return updateTurn(state) { turn ->
        upsertBlock(turn, event["blockId"]) { existing ->
            val base = existing ?: JsObj.of("blockId" to event["blockId"], "kind" to js("message"), "done" to JsBool.FALSE)
            val previous = coalesce(existing?.get("text"), JsStr(""))
            base.put("text", js(jsToString(previous) + jsToString(event["text"])))
        }
    }
}

// events.mjs:2173
private fun foldMessageCompleted(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    return updateTurn(state) { turn ->
        upsertBlock(turn, event["blockId"]) {
            JsObj.of(
                "blockId" to event["blockId"],
                "kind" to js("message"),
                "text" to event["text"],
                "done" to JsBool.TRUE,
            ).spread(blockEventTs(event))
                .spread(if (event["aborted"] == JsBool.TRUE) JsObj.of("aborted" to JsBool.TRUE) else JsObj.EMPTY)
        }
    }
}

// events.mjs:2615
private fun foldTokenProgress(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val tokens = nonNegativeFiniteNumber(event["tokens"]) ?: return state
    return updateTurn(state) { turn ->
        val live = turn["liveTokens"]
        val next = if (isNullish(live)) tokens.value else maxOf(live.num ?: Double.NaN, tokens.value)
        if (live.num == next) turn else turn.put("liveTokens", js(next))
    }
}

// events.mjs:2674
private fun foldWarning(state: JsObj, event: JsObj, type: String): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val legacyError = if (type == "unknown_event") legacyUnknownErrorMessage(event["raw"]) else null
    return updateTurn(state) { turn ->
        val warning = JsObj.of("type" to js(type), "message" to event["message"], "raw" to event["raw"])
        turn.with(
            // `turn.error ?? legacyError ?? undefined`
            "error" to coalesce(turn["error"], legacyError?.let { js(it) }),
            "warnings" to turn["warnings"].arr!!.add(warning).slice(-Limits.MAX_WARNINGS),
        )
    }
}

// events.mjs:2721
private fun foldTurnEnd(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val next = updateTurn(state) { turn ->
        val completed = turn.with(
            "status" to js("done"),
            "outcome" to event["outcome"],
        ).spread(
            if (isNullish(turn["errorCause"]) && truthy(event["cause"])) JsObj.of("errorCause" to event["cause"]) else JsObj.EMPTY,
        )
        if (state["provider"].str == "codex" && event["outcome"].str == Limits.TURN_OUTCOMES.OK) {
            putCodexFinalReportLast(completed)
        } else {
            completed
        }
    }
    val accountAuth: JsValue? = when {
        event["outcome"].str == Limits.TURN_OUTCOMES.OK -> JsNull
        event["cause"].str == "auth_expired" && state["accountAuth"].obj?.get("status").str != "expired" ->
            JsObj.of("status" to js("expired"), "turnId" to event["turnId"])
        else -> state["accountAuth"]
    }
    return clearRateLimitGrace(next).with(
        "status" to js(Limits.SESSION_STATUS.READY),
        "activeTurnId" to JsNull,
        "lastTurnOutcome" to event["outcome"],
        "accountAuth" to accountAuth,
    )
}

// events.mjs:496
internal fun legacyUnknownErrorMessage(raw: JsValue?): String? {
    val record = raw.obj ?: return null
    val message = record["message"].str
    if (record["type"].str == "error" && message != null && jsTrim(message).isNotEmpty()) return message
    val errorMessage = record["error"].obj?.get("message").str
    if (record["type"].str == "turn.failed" && errorMessage != null && jsTrim(errorMessage).isNotEmpty()) {
        return errorMessage
    }
    return null
}

// events.mjs:1370
internal fun putCodexFinalReportLast(turn: JsObj): JsObj {
    val blocks = turn["blocks"].arr!!
    val blocksById = turn["blocksById"].obj!!
    var reportIndex = -1
    for (index in blocks.indices.reversed()) {
        if (blocksById.prop(blocks[index]).obj?.get("kind").str == "message") {
            reportIndex = index
            break
        }
    }
    if (reportIndex < 0 || reportIndex == blocks.size - 1) return turn
    val reportId = blocks[reportIndex]
    val reordered = ArrayList<JsValue>(blocks.size)
    reordered.addAll(blocks.subList(0, reportIndex))
    reordered.addAll(blocks.subList(reportIndex + 1, blocks.size))
    reordered.add(reportId)
    return turn.put("blocks", JsArr.of(reordered))
}
