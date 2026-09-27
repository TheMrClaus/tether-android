package com.tether.app.protocol.fold

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.js
import com.tether.app.protocol.tree.num
import com.tether.app.protocol.tree.obj
import com.tether.app.protocol.tree.str

// T2.1 unit B (interaction): approvals 2495-2516, 2564-2574 with 635-729; questions
// 2517-2563; permission_denied 2072-2111 with 1278-1346; queued messages 2863-2900; rate
// limit, fast mode and resume 1665-1763 with 1184-1255.
//
// H1 seeded the cases its acceptance cases (run-bookkeeping, api-retry) drive —
// approval_request (+ its 635-728 normalizers), approval_resolved/expired, question_request,
// question_resolved/cancelled, rate_limit (+ clearRateLimitGrace, eventTs,
// offerRateLimitResume) — ported in full. Every other label falls to `else` until unit B.
internal fun foldInteraction(state: JsObj, event: JsObj, type: String): JsObj = when (type) {
    "rate_limit" -> foldRateLimit(state, event)
    "approval_request" -> foldApprovalRequest(state, event)
    "question_request" -> foldQuestionRequest(state, event)
    "question_resolved", "question_cancelled" -> foldQuestionClosed(state, event)
    "approval_resolved", "approval_expired" -> foldApprovalClosed(state, event)
    else -> state // not yet ported (unit B)
}

// events.mjs:1665
private fun foldRateLimit(state: JsObj, event: JsObj): JsObj {
    var next = JsObj.of(
        "status" to event["status"],
        "limitType" to event["limitType"].orJsNull(),
        "utilization" to event["utilization"].orJsNull(),
        "resetsAt" to event["resetsAt"].orJsNull(),
    )
    val grace = event["grace"].str
    if (grace != null && grace in Limits.RATE_LIMIT_GRACE) next = next.put("grace", js(grace))
    val prev = state["rateLimit"]
    val unchanged = truthy(prev) && prev is JsObj &&
        strictEquals(prev["status"], next["status"]) &&
        strictEquals(prev["limitType"], next["limitType"]) &&
        strictEquals(prev["utilization"], next["utilization"]) &&
        strictEquals(prev["resetsAt"], next["resetsAt"]) &&
        strictEquals(prev["grace"], next["grace"])
    var rateLimitResume: JsValue = state["rateLimitResume"].orJsNull()
    val offered = if (next["status"].str == "rejected") {
        offerRateLimitResume(state, resetsAt = next["resetsAt"], limitType = next["limitType"], stampedNow = eventTs(event))
    } else {
        null
    }
    if (offered != null) rateLimitResume = offered
    if (unchanged && strictEquals(rateLimitResume, state["rateLimitResume"])) return state
    return state.with("rateLimit" to next, "rateLimitResume" to rateLimitResume)
}

// events.mjs:2495
private fun foldApprovalRequest(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val choices = normalizeApprovalChoices(event["choices"])
    val metadata = normalizeApprovalMetadata(event["metadata"])
    val approval = JsObj.of(
        "requestId" to event["requestId"],
        "toolId" to event["toolId"],
        "name" to event["name"],
        "input" to event["input"],
        "choices" to choices,
        "metadata" to metadata,
    )
    val next = updateTurn(state) { turn ->
        turn.put("pendingApprovals", turn["pendingApprovals"].obj!!.put(jsToString(event["requestId"]), approval))
    }
    return withDerivedStatus(next)
}

// events.mjs:2517
private fun foldQuestionRequest(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val next = updateTurn(state) { turn ->
        val question = JsObj.of("requestId" to event["requestId"], "toolId" to event["toolId"], "questions" to event["questions"])
        turn.put("pendingQuestions", turn["pendingQuestions"].obj!!.put(jsToString(event["requestId"]), question))
    }
    return withDerivedStatus(next)
}

// events.mjs:2529
private fun foldQuestionClosed(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val next = updateTurn(state) { turn ->
        turn.put("pendingQuestions", turn["pendingQuestions"].obj!!.remove(jsToString(event["requestId"])))
    }
    return withDerivedStatus(next)
}

// events.mjs:2564
private fun foldApprovalClosed(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val next = updateTurn(state) { turn ->
        turn.put("pendingApprovals", turn["pendingApprovals"].obj!!.remove(jsToString(event["requestId"])))
    }
    return withDerivedStatus(next)
}

// events.mjs:635
internal fun normalizeGrantedPermissions(value: JsValue?): JsObj? {
    if (value !is JsObj) return null
    var result = JsObj.EMPTY
    val fs = value["fileSystem"]
    if (fs is JsObj) {
        val limits = Limits.PROVIDER_PROJECTION_LIMITS
        val read = normalizeStringList(fs["read"], limit = limits.paths, maxChars = limits.pathChars)
        val write = normalizeStringList(fs["write"], limit = limits.paths, maxChars = limits.pathChars)
        result = result.put("fileSystem", JsObj.of("read" to read, "write" to write))
    }
    val network = value["network"]
    if (network is JsObj && network["enabled"] is JsBool) {
        result = result.put("network", JsObj.of("enabled" to network["enabled"]))
    }
    return result
}

// events.mjs:658
internal fun normalizeApprovalChoices(choices: JsValue?): JsArr? {
    if (choices !is JsArr) return null
    val limits = Limits.PROVIDER_PROJECTION_LIMITS
    val normalized = ArrayList<JsValue>()
    val seen = HashSet<String>()
    for (choice in choices) {
        if (normalized.size >= limits.approvalChoices) break
        val record = choice.obj
        val choiceId = boundedApprovalChoiceId(record?.get("choiceId"))
        val label = boundedDisplayText(record?.get("label"), limits.labelChars)
        if (choiceId.isNullOrEmpty() || label.isNullOrEmpty() || choiceId in seen) continue
        seen.add(choiceId)
        val description = boundedDisplayText(record?.get("description"), limits.proseChars)
        val grant = record?.get("permissionGrant").str
        val permissionGrant = if (grant == "exact" || grant == "subset") grant else null
        normalized.add(
            JsObj.of(
                "choiceId" to js(choiceId),
                "label" to js(label),
                "description" to jsOrUndefined(description),
                "permissionGrant" to jsOrUndefined(permissionGrant),
            ),
        )
    }
    return JsArr.of(normalized)
}

// events.mjs:683
internal fun normalizeApprovalMetadata(metadata: JsValue?): JsObj? {
    if (metadata !is JsObj) return null
    val provider = metadata["provider"].str
    val kind = metadata["kind"].str
    if (provider == null || provider !in Limits.PROVIDER_IDS || kind == null || kind !in Limits.APPROVAL_KINDS) return null
    val limits = Limits.PROVIDER_PROJECTION_LIMITS
    var normalized = JsObj.of("provider" to js(provider), "kind" to js(kind))
    val reason = boundedDisplayText(metadata["reason"], limits.proseChars)
    val command = boundedDisplayText(metadata["command"], limits.commandChars)
    val cwd = boundedDisplayText(metadata["cwd"], limits.pathChars)
    val paths = normalizeStringList(metadata["paths"], limit = limits.paths, maxChars = limits.pathChars)
    normalized = normalized.with(
        "reason" to jsOrUndefined(reason),
        "command" to jsOrUndefined(command),
        "cwd" to jsOrUndefined(cwd),
        "paths" to paths,
    )
    val network = metadata["network"]
    if (network is JsObj) {
        val host = boundedDisplayText(network["host"], limits.labelChars)
        if (!host.isNullOrEmpty()) {
            var projected = JsObj.of("host" to js(host))
            val protocol = boundedDisplayText(network["protocol"], limits.labelChars)
            if (protocol != null) projected = projected.put("protocol", js(protocol))
            val port = network["port"]
            if (isInteger(port) && port.num!! >= 0 && port.num!! <= 65_535) projected = projected.put("port", port)
            normalized = normalized.put("network", projected)
        }
    }
    normalized = normalized.put("requestedPermissions", normalizeGrantedPermissions(metadata["requestedPermissions"]))
    normalized = normalized.put(
        "permissionPatterns",
        normalizeStringList(metadata["permissionPatterns"], limit = limits.paths, maxChars = limits.commandChars),
    )
    normalized = normalized.put(
        "alwaysAllowPatterns",
        normalizeStringList(metadata["alwaysAllowPatterns"], limit = limits.paths, maxChars = limits.commandChars),
    )
    return normalized
}

// events.mjs:1195 — same object when there is nothing to clear.
internal fun clearRateLimitGrace(state: JsObj): JsObj {
    val rateLimit = state["rateLimit"]
    if (!truthy(rateLimit) || rateLimit !is JsObj || !rateLimit.has("grace")) return state
    return state.put("rateLimit", rateLimit.remove("grace"))
}

// events.mjs:1227 — the journal-stamped ts, or null when absent.
internal fun eventTs(event: JsObj?): JsNum? {
    val ts = event?.get("ts")
    return if (isFiniteNumber(ts)) ts as JsNum else null
}

// events.mjs:1243 — the `awaiting_choice` offer, or null when none is warranted.
internal fun offerRateLimitResume(state: JsObj, resetsAt: JsValue?, limitType: JsValue?, stampedNow: JsNum?): JsObj? {
    if (stampedNow == null || !isFiniteNumber(resetsAt) || resetsAt.num!! <= 0) return null
    val reset = resetsAt.num!!
    val horizon = reset - stampedNow.value
    if (!(horizon > 0 && horizon <= Limits.RATE_LIMIT_RESUME_MAX_HORIZON_MS)) return null
    if (strictEquals(state["rateLimitResume"].obj?.get("resetsAt"), resetsAt)) return null
    return JsObj.of(
        "status" to js("awaiting_choice"),
        "resetsAt" to resetsAt,
        "resumeAt" to js(reset + Limits.RATE_LIMIT_RESUME_DELAY_MS),
        "limitType" to (if (truthy(limitType)) limitType else null),
    )
}
