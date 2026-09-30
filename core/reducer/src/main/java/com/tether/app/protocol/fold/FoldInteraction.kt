package com.tether.app.protocol.fold

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.arr
import com.tether.app.protocol.tree.js
import com.tether.app.protocol.tree.num
import com.tether.app.protocol.tree.obj
import com.tether.app.protocol.tree.str

// T2.1 unit B (interaction): approvals 2495-2516, 2564-2574 with 635-729; questions
// 2517-2563; permission_denied 2072-2111 with 1278-1346; queued messages 2863-2900; rate
// limit, fast mode and resume 1665-1763 with 1184-1255.
//
// H1 seeded approval_request (+ its 635-728 normalizers), approval_resolved/expired,
// question_request, question_resolved/cancelled and rate_limit (+ clearRateLimitGrace, eventTs,
// offerRateLimitResume); unit B ported the rest.
internal fun foldInteraction(state: JsObj, event: JsObj, type: String): JsObj = when (type) {
    "rate_limit" -> foldRateLimit(state, event)
    "fast_mode" -> foldFastMode(state, event)
    "limit_hit" -> foldLimitHit(state, event)
    "rate_limit_resume_scheduled" -> foldResumeScheduled(state, event)
    "rate_limit_resume_dismissed" -> foldResumeDismissed(state, event)
    "rate_limit_resume_fired" -> foldResumeFired(state, event)
    "permission_denied" -> foldPermissionDenied(state, event)
    "approval_request" -> foldApprovalRequest(state, event)
    "question_request" -> foldQuestionRequest(state, event)
    "question_resolved", "question_cancelled" -> foldQuestionClosed(state, event)
    "question_answered" -> foldQuestionAnswered(state, event)
    "approval_resolved", "approval_expired" -> foldApprovalClosed(state, event)
    "queued_message_added" -> foldQueuedAdded(state, event)
    "queued_message_updated" -> foldQueuedUpdated(state, event)
    "queued_message_removed" -> foldQueuedRemoved(state, event)
    else -> state
}

// events.mjs:1696
private fun foldFastMode(state: JsObj, event: JsObj): JsObj {
    val disabledReason = event["disabledReason"].orJsNull()
    if (strictEquals(state["fastModeState"], event["state"]) &&
        strictEquals(state["fastModeDisabledReason"], disabledReason)
    ) {
        return state
    }
    return state.with("fastModeState" to event["state"], "fastModeDisabledReason" to disabledReason)
}

// events.mjs:1707
private fun foldLimitHit(state: JsObj, event: JsObj): JsObj {
    val offered = offerRateLimitResume(
        state,
        resetsAt = event["resetAt"],
        limitType = event["limitType"],
        stampedNow = eventTs(event),
    ) ?: return state
    return state.put("rateLimitResume", offered)
}

// events.mjs:1730
private fun foldResumeScheduled(state: JsObj, event: JsObj): JsObj {
    val current = state["rateLimitResume"]
    if (!truthy(current) || current !is JsObj) return state
    val resumeAt = event["resumeAt"]
    if (!strictEquals(current["resetsAt"], event["resetsAt"]) ||
        current["status"].str != "awaiting_choice" ||
        !isFiniteNumber(resumeAt) ||
        // `event.resetsAt + DELAY`: resetsAt is a number here (it === current.resetsAt).
        resumeAt.num != (event["resetsAt"].num ?: Double.NaN) + Limits.RATE_LIMIT_RESUME_DELAY_MS
    ) {
        return state
    }
    return state.put(
        "rateLimitResume",
        JsObj.of(
            "status" to js("scheduled"),
            "resetsAt" to event["resetsAt"],
            "resumeAt" to resumeAt,
            "limitType" to (if (truthy(current["limitType"])) current["limitType"] else null),
        ),
    )
}

// events.mjs:1747
private fun foldResumeDismissed(state: JsObj, event: JsObj): JsObj {
    val current = state["rateLimitResume"]
    if (!truthy(current) || current !is JsObj) return state
    if (!strictEquals(current["resetsAt"], event["resetsAt"]) || current["status"].str == "fired") return state
    return state.put("rateLimitResume", current.put("status", js("dismissed")))
}

// events.mjs:1753
private fun foldResumeFired(state: JsObj, event: JsObj): JsObj {
    val current = state["rateLimitResume"]
    if (!truthy(current) || current !is JsObj) return state
    val status = current["status"].str
    if (!strictEquals(current["resetsAt"], event["resetsAt"]) || (status != "scheduled" && status != "awaiting_choice")) {
        return state
    }
    return state.put("rateLimitResume", current.put("status", js("fired")))
}

// events.mjs:1278
private fun samePermissionDenial(a: JsObj, b: JsObj): Boolean =
    strictEquals(a["toolId"], b["toolId"]) &&
        strictEquals(a["name"], b["name"]) &&
        strictEquals(a["reason"], b["reason"]) &&
        strictEquals(a["reasonCode"].orJsNull(), b["reasonCode"].orJsNull()) &&
        strictEquals(a["error"].orJsNull(), b["error"].orJsNull()) &&
        truthy(a["subagent"]) == truthy(b["subagent"])

// events.mjs:1290
private fun upsertPermissionDenial(denials: JsArr, denial: JsObj): JsArr {
    val index = denials.indexOfFirst { strictEquals(it.obj?.get("toolId"), denial["toolId"]) }
    if (index == -1) return denials.add(denial)
    val existing = denials[index].obj!!
    var enriched = existing.with(
        "name" to jsOr(denial["name"], existing["name"]),
        "reason" to (if (strictEquals(existing["reason"], JsStr("unknown"))) denial["reason"] else existing["reason"]),
    )
    if (truthy(existing["reasonCode"]) || truthy(denial["reasonCode"])) {
        enriched = enriched.put("reasonCode", coalesce(existing["reasonCode"], denial["reasonCode"]))
    }
    if (truthy(existing["error"]) || truthy(denial["error"])) {
        enriched = enriched.put("error", coalesce(existing["error"], denial["error"]))
    }
    if (truthy(existing["subagent"]) || truthy(denial["subagent"])) enriched = enriched.put("subagent", JsBool.TRUE)
    if (samePermissionDenial(existing, enriched)) return denials
    return denials.set(index, enriched)
}

// events.mjs:1320
private fun permissionDenialReasonCode(value: JsValue?): String? {
    val s = value.str ?: return null
    return if (Limits.PERMISSION_DENIAL_REASON_CODE.matches(s)) s else null
}

// events.mjs:2072
private fun foldPermissionDenied(state: JsObj, event: JsObj): JsObj {
    val reasonCode = permissionDenialReasonCode(event["reasonCode"])
    val error = boundedDisplayText(event["error"], Limits.PROVIDER_PROJECTION_LIMITS.proseChars)
    val reason = event["reason"].str
    val denial = JsObj.of(
        "toolId" to event["toolId"],
        "name" to event["name"],
        "reason" to js(if (reason != null && reason in Limits.PERMISSION_DENIAL_REASON_VALUES) reason else "unknown"),
        "reasonCode" to jsOrUndefined(reasonCode?.takeIf { it.isNotEmpty() }),
        "error" to jsOrUndefined(error?.takeIf { it.isNotEmpty() }),
        "subagent" to (if (event["subagent"] === JsBool.TRUE) JsBool.TRUE else null),
    )
    val turnId = event["turnId"]
    if (isNullish(turnId) || !state["turnsById"].obj!!.has(jsToString(turnId))) {
        val current = state["unattributedPermissionDenials"].arr!!
        val permissionDenials = upsertPermissionDenial(current, denial)
        if (permissionDenials === current) return state
        return state.put("unattributedPermissionDenials", permissionDenials)
    }
    return updateTurnById(state, turnId) { turn ->
        val current = turn["permissionDenials"].arr!!
        val permissionDenials = upsertPermissionDenial(current, denial)
        if (permissionDenials === current) turn else turn.put("permissionDenials", permissionDenials)
    }
}

// events.mjs:2540
private fun foldQuestionAnswered(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val items = event["items"] as? JsArr ?: JsArr.EMPTY
    return updateTurn(state) { turn ->
        val existing = turn["answeredQuestions"] as? JsArr ?: JsArr.EMPTY
        if (existing.any { strictEquals(it.obj?.get("requestId"), event["requestId"]) }) {
            turn
        } else {
            val answered = JsObj.of(
                "requestId" to event["requestId"],
                "toolId" to event["toolId"],
                "items" to items,
                "response" to (if (truthy(event["response"])) event["response"] else null),
            )
            turn.put("answeredQuestions", existing.add(answered))
        }
    }
}

private fun hasQueueId(state: JsObj, queueId: JsValue?): Boolean =
    state["queuedMessages"].arr!!.any { strictEquals(it.obj?.get("queueId"), queueId) }

// events.mjs:2887 (v135). v133 (issue #211): `origin` / `noticeKind` are copied verbatim when
// truthy, exactly like `flushMode` — the fold does not validate them (readers normalize).
private fun foldQueuedAdded(state: JsObj, event: JsObj): JsObj {
    if (hasQueueId(state, event["queueId"])) return state
    val message = JsObj.of(
        "queueId" to event["queueId"],
        "text" to event["text"],
        "flushMode" to (if (truthy(event["flushMode"])) event["flushMode"] else null),
        "origin" to (if (truthy(event["origin"])) event["origin"] else null),
        "noticeKind" to (if (truthy(event["noticeKind"])) event["noticeKind"] else null),
    )
    return state.put("queuedMessages", state["queuedMessages"].arr!!.add(message))
}

// events.mjs:2878
private fun foldQueuedUpdated(state: JsObj, event: JsObj): JsObj {
    if (!hasQueueId(state, event["queueId"])) return state
    var queued = state["queuedMessages"].arr!!
    for (i in queued.indices) {
        val m = queued[i]
        if (strictEquals(m.obj?.get("queueId"), event["queueId"])) queued = queued.set(i, m.obj!!.put("text", event["text"]))
    }
    return state.put("queuedMessages", queued)
}

// events.mjs:2912 (79c3d37)
private fun foldQueuedRemoved(state: JsObj, event: JsObj): JsObj {
    if (!hasQueueId(state, event["queueId"])) return state
    // v130 (S13.1-C): remember the id (bounded, deduped, newest last) so a snapshot proves it was
    // accepted even after it left the queue. `?? []`: a projection folded before v130 has no list.
    val queueId = event["queueId"] ?: JsNull
    val prior = state["removedQueueIds"].let { if (it == null || it is JsNull) JsArr.EMPTY else it.arr!! }
    val removed = prior.filterKeep { !strictEquals(it, queueId) }.add(queueId)
    return state.with(
        "queuedMessages" to state["queuedMessages"].arr!!.filterKeep { !strictEquals(it.obj?.get("queueId"), event["queueId"]) },
        "removedQueueIds" to (if (removed.size > Limits.MAX_REMOVED_QUEUE_IDS) removed.slice(-Limits.MAX_REMOVED_QUEUE_IDS) else removed),
    )
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
    // v131: the journal-stamped `ts` of this request (never a clock read); a KEY only when the
    // event is stamped, so an unstamped fold keeps the pre-v131 shape.
    val createdAt = nonNegativeFiniteNumber(event["ts"])
    val approval = JsObj.of(
        "requestId" to event["requestId"],
        "toolId" to event["toolId"],
        "name" to event["name"],
        "input" to event["input"],
        "choices" to choices,
        "metadata" to metadata,
        "createdAt" to createdAt,
    )
    val next = updateTurn(state) { turn ->
        turn.put("pendingApprovals", turn["pendingApprovals"].obj!!.put(jsToString(event["requestId"]), approval))
    }
    return withDerivedStatus(next)
}

// events.mjs:2517
private fun foldQuestionRequest(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    // v131: journal-stamped creation time, same rule as approval_request.
    val createdAt = nonNegativeFiniteNumber(event["ts"])
    val next = updateTurn(state) { turn ->
        val question = JsObj.of(
            "requestId" to event["requestId"],
            "toolId" to event["toolId"],
            "questions" to event["questions"],
            "createdAt" to createdAt,
        )
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
