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
// Identity (Revision 6): JS rebuilds a block/turn even when nothing changed; here a rebuilt
// block, subagent thread or warnings list that is structurally equal to what it replaces keeps
// the existing instance (see [upsertBlockKeep] / [keepIfEqual]), so an unchanged projection is
// returned as the same object.
internal fun foldTurn(state: JsObj, event: JsObj, type: String): JsObj = when (type) {
    "native_session_id" -> foldNativeSessionId(state, event)
    "turn_started" -> foldTurnStarted(state, event)
    "user_message_accepted" -> foldUserMessageAccepted(state, event)
    "message_started" -> foldMessageStarted(state, event)
    "message_delta" -> foldMessageDelta(state, event)
    "message_completed" -> foldMessageCompleted(state, event)
    "thinking_delta" -> foldThinkingDelta(state, event)
    "thinking_completed" -> foldThinkingCompleted(state, event)
    "thinking_stop" -> foldThinkingStop(state, event)
    "tool_start" -> foldToolStart(state, event)
    "tool_output_delta" -> foldToolOutputDelta(state, event)
    "tool_progress" -> foldToolProgress(state, event)
    "tool_end" -> foldToolEnd(state, event)
    "cancel_requested" -> foldCancelRequested(state, event)
    "cancelled" -> foldCancelled(state, event)
    "process_exit" -> foldProcessExit(state, event)
    "usage" -> foldUsage(state, event)
    "token_progress" -> foldTokenProgress(state, event)
    "turn_activity" -> foldTurnActivity(state, event)
    "subagent_message" -> foldSubagentMessage(state, event)
    "warning", "unknown_event" -> foldWarning(state, event, type)
    "error" -> foldError(state, event)
    "turn_end" -> foldTurnEnd(state, event)
    else -> state
}

// events.mjs:1611
private fun foldNativeSessionId(state: JsObj, event: JsObj): JsObj {
    val rawCapabilities = event["cliCapabilities"]
    val capabilities: JsArr? = if (rawCapabilities is JsArr) rawCapabilities.filterKeep { it is JsStr } else null
    val version = event["cliVersion"].str?.takeIf { it.isNotEmpty() }
    val inventory = normalizeProjectedCliInventory(event["cliInventory"], state["cliInventory"])
    val idUnchanged = strictEquals(state["nativeSessionId"], event["nativeSessionId"])
    val capabilitiesUnchanged = capabilities == null || sameStringList(state["cliCapabilities"], capabilities)
    val versionUnchanged = version == null || strictEquals(state["cliVersion"], JsStr(version))
    val inventoryUnchanged = inventory == null || sameCliInventory(state["cliInventory"], inventory)
    if (idUnchanged && capabilitiesUnchanged && versionUnchanged && inventoryUnchanged) return state
    return state.with(
        "nativeSessionId" to event["nativeSessionId"],
        "cliCapabilities" to (capabilities ?: state["cliCapabilities"]),
        "cliVersion" to (version?.let { js(it) } ?: state["cliVersion"]),
        "cliInventory" to (inventory ?: state["cliInventory"]),
    )
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

// events.mjs:2134
private fun foldUserMessageAccepted(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val engineText = boundedDisplayText(event["engineText"], Limits.PROVIDER_PROJECTION_LIMITS.commandChars)
    val blockId = JsStr("user:${jsToString(event["turnId"])}")
    val attachments = event["attachments"]
    return updateTurn(state) { turn ->
        upsertBlockKeep(turn, blockId) {
            JsObj.of(
                "blockId" to blockId,
                "kind" to js("user_message"),
                "text" to event["text"],
            ).spread(
                if (engineText != null && !strictEquals(JsStr(engineText), event["text"])) {
                    JsObj.of("engineText" to js(engineText))
                } else {
                    JsObj.EMPTY
                },
            ).spread(blockEventTs(event))
                .spread(if (attachments is JsArr && attachments.isNotEmpty()) JsObj.of("attachments" to attachments) else JsObj.EMPTY)
        }
    }
}

// events.mjs:2156
private fun foldMessageStarted(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    return updateTurn(state) { turn ->
        upsertBlock(turn, event["blockId"]) { existing ->
            existing ?: JsObj.of(
                "blockId" to event["blockId"],
                "kind" to js("message"),
                "text" to js(""),
                "done" to JsBool.FALSE,
            ).spread(blockEventTs(event))
        }
    }
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
        upsertBlockKeep(turn, event["blockId"]) {
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

// events.mjs:2368
private fun foldThinkingDelta(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    return updateTurn(state) { turn ->
        upsertBlock(turn, event["blockId"]) { existing ->
            val base = existing ?: JsObj.of("blockId" to event["blockId"], "kind" to js("thinking"), "done" to JsBool.FALSE)
            val previous = coalesce(existing?.get("text"), JsStr(""))
            base.put("text", js(jsToString(previous) + jsToString(event["text"])))
        }
    }
}

// events.mjs:2381
private fun foldThinkingCompleted(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    return updateTurn(state) { turn ->
        upsertBlockKeep(turn, event["blockId"]) {
            JsObj.of("blockId" to event["blockId"], "kind" to js("thinking"), "text" to event["text"], "done" to JsBool.TRUE)
        }
    }
}

// events.mjs:2391
private fun foldThinkingStop(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val active = state["turnsById"].obj!!.prop(state["activeTurnId"]).obj
    if (!truthy(active?.get("blocksById").obj?.prop(event["blockId"]))) return state
    return updateTurn(state) { turn ->
        upsertBlock(turn, event["blockId"]) { existing -> (existing ?: JsObj.EMPTY).put("done", JsBool.TRUE) }
    }
}

// events.mjs:2403
private fun foldToolStart(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    return updateTurn(state) { turn ->
        upsertBlockKeep(turn, event["toolId"]) {
            JsObj.of(
                "blockId" to event["toolId"],
                "kind" to js("tool"),
                "name" to event["name"],
                "input" to event["input"],
                "output" to JsNull,
                "isError" to JsBool.FALSE,
                "done" to JsBool.FALSE,
            )
        }
    }
}

// events.mjs:2418
private fun foldToolOutputDelta(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    return updateTurn(state) { turn ->
        upsertBlock(turn, event["toolId"]) { existing ->
            val base = existing ?: JsObj.of("blockId" to event["toolId"], "kind" to js("tool"), "done" to JsBool.FALSE)
            val previous = coalesce(existing?.get("output"), JsStr(""))
            base.put("output", js(jsToString(previous) + jsToString(event["chunk"])))
        }
    }
}

// events.mjs:2428
private fun foldToolProgress(state: JsObj, event: JsObj): JsObj {
    val elapsed = event["elapsedSeconds"]
    if (!isNullish(event["parentToolUseId"])) {
        val turn = state["turnsById"].obj!!.prop(event["turnId"]).obj
        val parent = turn?.get("blocksById").obj?.prop(event["parentToolUseId"]).obj
        val child = if (parent?.get("kind").str == "tool") {
            parent!!["subagent"].obj?.get("entries").obj?.prop(event["toolId"])
        } else {
            JsNull
        }
        if (
            turn == null ||
            parent == null ||
            !truthy(child) ||
            child.obj?.get("kind").str != "tool" ||
            child.obj!!["done"] == JsBool.TRUE ||
            strictEquals(child.obj!!["elapsedSeconds"], elapsed)
        ) {
            return state
        }
        val subagent = parent["subagent"].obj!!
        val entries = subagent["entries"].obj!!
        return updateTurnById(state, event["turnId"]) { current ->
            val blocksById = current["blocksById"].obj!!
            current.put(
                "blocksById",
                blocksById.put(
                    jsToString(event["parentToolUseId"]),
                    parent.put(
                        "subagent",
                        subagent.put("entries", entries.put(jsToString(event["toolId"]), child.obj!!.put("elapsedSeconds", elapsed))),
                    ),
                ),
            )
        }
    }
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val tool = state["turnsById"].obj!!.prop(event["turnId"]).obj?.get("blocksById").obj?.prop(event["toolId"])
    if (
        !truthy(tool) ||
        tool.obj?.get("kind").str != "tool" ||
        tool.obj!!["done"] == JsBool.TRUE ||
        strictEquals(tool.obj!!["elapsedSeconds"], elapsed)
    ) {
        return state
    }
    return updateTurn(state) { turn ->
        turn.put(
            "blocksById",
            turn["blocksById"].obj!!.put(jsToString(event["toolId"]), tool.obj!!.put("elapsedSeconds", elapsed)),
        )
    }
}

// events.mjs:2478
private fun foldToolEnd(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    return updateTurn(state) { turn ->
        upsertBlockKeep(turn, event["toolId"]) { existing ->
            val withoutProgress = clearElapsedProgress(existing ?: JsObj.of("blockId" to event["toolId"], "kind" to js("tool")))
            withoutProgress.with(
                "output" to event["output"],
                "isError" to event["isError"],
                "done" to JsBool.TRUE,
            ).spread(if (event["interrupted"] == JsBool.TRUE) JsObj.of("interrupted" to JsBool.TRUE) else JsObj.EMPTY)
        }
    }
}

// events.mjs:2575
private fun foldCancelRequested(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    return updateTurn(state) { turn -> turn.put("status", js("cancelling")) }
}

// events.mjs:2580
private fun foldCancelled(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val next = updateTurn(state) { turn ->
        turn.with(
            "status" to js("done"),
            "outcome" to js(Limits.TURN_OUTCOMES.CANCELLED),
        ).spread(
            if (isNullish(turn["errorCause"]) && event["cause"].str == "interrupted") {
                JsObj.of("errorCause" to js("interrupted"))
            } else {
                JsObj.EMPTY
            },
        )
    }
    return next.with(
        "status" to js(Limits.SESSION_STATUS.READY),
        "activeTurnId" to JsNull,
        "lastTurnOutcome" to js(Limits.TURN_OUTCOMES.CANCELLED),
    )
}

// events.mjs:2593
private fun foldProcessExit(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    return updateTurn(state) { turn ->
        turn.put("exit", keepIfEqual(turn["exit"], JsObj.of("code" to event["code"], "signal" to event["signal"])))
    }
}

// events.mjs:2598
private fun foldUsage(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    return updateTurn(state) { turn ->
        val usage = JsObj.of(
            "model" to event["model"],
            "rawModel" to event["rawModel"],
            "perTurnTokens" to event["perTurnTokens"],
            "cumulativeTokens" to event["cumulativeTokens"],
            "contextWindow" to event["contextWindow"],
            "estimatedCostUSD" to event["estimatedCostUSD"],
            "numTurns" to event["numTurns"],
            "modelUsages" to event["modelUsages"],
        )
        turn.put("usage", keepIfEqual(turn["usage"], usage))
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

// events.mjs:2628 — deliberately not guarded by isOpenCurrentTurn.
private fun foldTurnActivity(state: JsObj, event: JsObj): JsObj {
    val turnsById = state["turnsById"].obj!!
    val turn = turnsById.prop(event["turnId"])
    if (!truthy(turn)) return state
    val activeMs = nonNegativeFiniteNumber(event["activeMs"])
    val runCount = nonNegativeFiniteNumber(event["runCount"])
    if (activeMs == null && runCount == null) return state
    val next = turn.obj!!
        .spread(if (activeMs == null) JsObj.EMPTY else JsObj.of("activeMs" to activeMs))
        .spread(if (runCount == null) JsObj.EMPTY else JsObj.of("runCount" to runCount))
    return state.put("turnsById", turnsById.put(jsToString(event["turnId"]), next))
}

// events.mjs:2652 — may patch a completed turn; never touches status/outcome/activeTurnId.
private fun foldSubagentMessage(state: JsObj, event: JsObj): JsObj =
    updateTurnById(state, event["turnId"]) { turn ->
        val blocksById = turn["blocksById"].obj!!
        val parent = blocksById.prop(event["parentToolUseId"])
        if (!truthy(parent) || parent.obj?.get("kind").str != "tool") return@updateTurnById turn
        val parentObj = parent.obj!!
        val subagent = keepIfEqual(parentObj["subagent"], foldSubagentItems(parentObj["subagent"], event["items"], event["usage"]))
        turn.put("blocksById", blocksById.put(jsToString(event["parentToolUseId"]), parentObj.put("subagent", subagent)))
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
            "warnings" to keepIfEqual(turn["warnings"], turn["warnings"].arr!!.add(warning).slice(-Limits.MAX_WARNINGS)),
        )
    }
}

// events.mjs:2692
private fun foldError(state: JsObj, event: JsObj): JsObj {
    val authExpired = event["cause"].str == "auth_expired"
    val latch = authExpired && state["accountAuth"].obj?.get("status").str != "expired"
    val accountAuth = if (latch) {
        JsObj.of("status" to js("expired"), "turnId" to coalesce(event["turnId"], JsNull))
    } else {
        state["accountAuth"]
    }
    if (isNullish(event["turnId"])) return state.with("lastError" to event["message"], "accountAuth" to accountAuth)
    if (!isOpenCurrentTurn(state, event["turnId"])) {
        return if (!latch) state else state.put("accountAuth", accountAuth)
    }
    val next = updateTurn(state) { turn ->
        turn.put("error", coalesce(turn["error"], event["message"])).spread(
            if (isNullish(turn["errorCause"]) && truthy(event["cause"])) JsObj.of("errorCause" to event["cause"]) else JsObj.EMPTY,
        )
    }
    return if (!latch) next else next.put("accountAuth", accountAuth)
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

// events.mjs:1398
internal fun clearElapsedProgress(tool: JsObj): JsObj = tool.remove("elapsedSeconds")

// events.mjs:1405 — a new `{ order, entries, usage? }` thread; `existing` is never mutated.
internal fun foldSubagentItems(existing: JsValue?, items: JsValue?, usage: JsValue?): JsObj {
    val prior = existing.takeIf { truthy(it) }?.obj
    var order = prior?.get("order").arr ?: JsArr.EMPTY
    var entries = prior?.get("entries").obj ?: JsObj.EMPTY
    for (itemValue in items.arr ?: JsArr.EMPTY) {
        val item = itemValue.obj ?: JsObj.EMPTY
        val key = jsToString(item["key"])
        val prev = entries[key]
        if (prev == null) order = order.add(item["key"] ?: JsNull)
        when (item["kind"].str) {
            "message" -> entries = entries.put(
                key,
                JsObj.of("key" to item["key"], "kind" to js("message"), "text" to item["text"]),
            )
            "thinking" -> entries = entries.put(
                key,
                JsObj.of("key" to item["key"], "kind" to js("thinking"), "text" to item["text"]),
            )
            "tool" -> entries = entries.put(
                key,
                (prev.obj ?: JsObj.EMPTY).with(
                    "key" to item["key"],
                    "kind" to js("tool"),
                    "name" to item["name"],
                    "input" to item["input"],
                    "done" to coalesce(prev.obj?.get("done"), JsBool.FALSE),
                ),
            )
            "tool_result" -> {
                val withoutProgress = clearElapsedProgress(prev.obj ?: JsObj.of("key" to item["key"], "kind" to js("tool")))
                entries = entries.put(
                    key,
                    withoutProgress.with(
                        "output" to item["output"],
                        "isError" to item["isError"],
                        "done" to JsBool.TRUE,
                    ).spread(if (item["interrupted"] == JsBool.TRUE) JsObj.of("interrupted" to JsBool.TRUE) else JsObj.EMPTY),
                )
            }
        }
    }
    val carried = coalesce(usage, prior?.get("usage"))
    return JsObj.of(
        "order" to order,
        "entries" to entries,
        "usage" to (if (truthy(carried)) carried else null),
    )
}

// ---- private identity helpers (candidates for hoisting into TurnOps.kt) ----

/** A rebuilt value that is structurally equal to the one it replaces keeps the old instance. */
private fun keepIfEqual(existing: JsValue?, next: JsValue): JsValue = if (existing != null && existing == next) existing else next

/** [upsertBlock] whose rebuilt block keeps the existing instance when it is structurally equal. */
private fun upsertBlockKeep(turn: JsObj, blockId: JsValue?, build: (JsObj?) -> JsObj): JsObj =
    upsertBlock(turn, blockId) { existing ->
        val next = build(existing)
        if (existing != null && existing == next) existing else next
    }
