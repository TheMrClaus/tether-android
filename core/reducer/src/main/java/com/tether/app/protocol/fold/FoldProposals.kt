package com.tether.app.protocol.fold

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.arr
import com.tether.app.protocol.tree.obj
import com.tether.app.protocol.tree.str

// v175 (events.ts session_proposal / session_proposal_updated): the new-session proposal. A proposal is journaled in the
// proposing turn and every later state change is a session-level update; a terminal state is never left, so a replay and a
// duplicate are no-ops. Not an arm of the reference's reduceEvent label list (the vendored reference predates v175): reached
// from the dispatcher's fallthrough, like the v156 event.

private const val PROPOSALS_PER_TURN = 20
private const val PROPOSAL_PROMPT_CHARS = 65_536
private const val PROPOSAL_TITLE_CHARS = 80
private val PROPOSAL_STATES = setOf("pending", "starting", "refused", "started", "here", "dismissed", "expired")
private val PROPOSAL_TERMINAL = setOf("started", "here", "dismissed", "expired")
private val PROPOSAL_REFUSALS = setOf("invalid_settings", "engine_unavailable", "profile_unavailable", "folder_unavailable", "proposal_closed")
private val PROPOSAL_REPLACED_FIELDS = setOf(
    "provider", "profileId", "model", "reasoningEffort", "cwd", "worktree", "permissionMode", "sandboxPolicy",
    "toolApproval", "approvalPolicy", "approvalsReviewer",
)
private val PROPOSAL_MODES = setOf("default", "acceptEdits", "plan", "dontAsk", "bypassPermissions")
private val PROPOSAL_SANDBOXES = setOf("read-only", "workspace-write", "off")
private val PROPOSAL_APPROVALS = setOf("ask", "auto", "yolo")

internal fun foldProposal(state: JsObj, event: JsObj, type: String): JsObj = when (type) {
    "session_proposal" -> foldSessionProposal(state, event)
    "session_proposal_updated" -> foldSessionProposalUpdated(state, event)
    else -> state
}

private fun sanitizeSuggestions(value: JsObj?): JsObj {
    var out = JsObj.EMPTY
    if (value == null) return out
    (value["provider"] as? JsStr)?.takeIf { it.value.isNotEmpty() }?.let { out = out.put("provider", it) }
    for (key in listOf("profileId", "model", "reasoningEffort", "cwd")) {
        boundedDisplayText(value[key], 4096)?.takeIf { it.isNotEmpty() }?.let { out = out.put(key, JsStr(it)) }
    }
    (value["useWorktree"] as? JsBool)?.let { out = out.put("useWorktree", it) }
    value["worktree"].obj?.let { out = out.put("worktree", it) }
    (value["permissionMode"] as? JsStr)?.takeIf { it.value in PROPOSAL_MODES }?.let { out = out.put("permissionMode", it) }
    (value["sandboxPolicy"] as? JsStr)?.takeIf { it.value in PROPOSAL_SANDBOXES }?.let { out = out.put("sandboxPolicy", it) }
    (value["toolApproval"] as? JsStr)?.takeIf { it.value in PROPOSAL_APPROVALS }?.let { out = out.put("toolApproval", it) }
    if (value["approvalPolicy"].str == "never") out = out.put("approvalPolicy", JsStr("never"))
    if (value["approvalsReviewer"].str == "auto_review") out = out.put("approvalsReviewer", JsStr("auto_review"))
    return out
}

private fun sanitizeReplaced(value: JsArr?): JsArr {
    if (value == null) return JsArr.EMPTY
    val out = value.take(16).mapNotNull { entry ->
        val raw = entry.obj ?: return@mapNotNull null
        val field = raw["field"].str
        val code = raw["code"].str
        if (field !in PROPOSAL_REPLACED_FIELDS || code !in PROPOSAL_REFUSALS) return@mapNotNull null
        JsObj.of("field" to JsStr(field!!), "code" to JsStr(code!!), "suggested" to JsStr(boundedDisplayText(raw["suggested"], 200) ?: ""))
    }
    return JsArr.of(out)
}

/** The resolution keys a state carries: refusalCode only with "refused", the started session only with "started". */
private fun resolution(event: JsObj, state: String): JsObj {
    var out = JsObj.EMPTY
    if (state == "refused" && event["refusalCode"].str in PROPOSAL_REFUSALS) out = out.put("refusalCode", event["refusalCode"])
    if (state == "started") {
        boundedIdentifier(event["startedSessionId"])?.let { out = out.put("startedSessionId", JsStr(it)) }
        boundedDisplayText(event["startedSessionName"], PROPOSAL_TITLE_CHARS)?.takeIf { it.isNotEmpty() }?.let { out = out.put("startedSessionName", JsStr(it)) }
    }
    return out
}

private fun hasProposal(state: JsObj, proposalId: String): Boolean =
    state["turnsById"].obj!!.values.any { turn ->
        turn.obj?.get("proposals").arr?.any { it.obj?.get("proposalId").str == proposalId } == true
    }

private fun foldSessionProposal(state: JsObj, event: JsObj): JsObj {
    val proposalId = boundedIdentifier(event["proposalId"])
    val title = boundedDisplayText(event["title"], PROPOSAL_TITLE_CHARS)
    val prompt = boundedDisplayText(event["prompt"], PROPOSAL_PROMPT_CHARS)
    if (proposalId.isNullOrEmpty() || title.isNullOrEmpty() || prompt.isNullOrEmpty()) return state
    if (hasProposal(state, proposalId)) return state
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val proposalState = event["state"].str?.takeIf { it in PROPOSAL_STATES } ?: "pending"
    val proposal = JsObj.of(
        "proposalId" to JsStr(proposalId),
        "title" to JsStr(title),
        "prompt" to JsStr(prompt),
        "suggestions" to sanitizeSuggestions(event["suggestions"].obj),
        "replaced" to sanitizeReplaced(event["replaced"].arr),
        "state" to JsStr(proposalState),
    ).spread(resolution(event, proposalState))
    return updateTurn(state) { turn ->
        turn.put("proposals", (turn["proposals"].arr ?: JsArr.EMPTY).add(proposal).slice(-PROPOSALS_PER_TURN))
    }
}

private fun foldSessionProposalUpdated(state: JsObj, event: JsObj): JsObj {
    val proposalId = boundedIdentifier(event["proposalId"])
    val next = event["state"].str
    if (proposalId.isNullOrEmpty() || next == null || next !in PROPOSAL_STATES) return state
    val order = state["turnOrder"].arr ?: return state
    for (index in order.indices.reversed()) {
        val turnId = order[index]
        val turn = state["turnsById"].obj!!.prop(turnId).obj ?: continue
        val proposals = turn["proposals"].arr ?: continue
        val found = proposals.indexOfFirst { it.obj?.get("proposalId").str == proposalId }
        if (found < 0) continue
        val current = proposals[found].obj!!
        if (current["state"].str in PROPOSAL_TERMINAL) return state
        val updated = current.remove("refusalCode").put("state", JsStr(next)).spread(resolution(event, next))
        return updateTurnById(state, turnId) { it.put("proposals", proposals.set(found, updated)) }
    }
    return state
}
