package com.tether.app.client

import com.tether.app.protocol.helpers.CodexModePresets
import com.tether.app.protocol.helpers.DraftForm
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import kotlinx.serialization.json.JsonPrimitive

/**
 * v175 (#293): where the proposal card's Options rows start. A row the agent suggested starts on the suggestion; a row it left
 * out starts on the default the design names: folder, harness, account, model and effort from the proposing session, Mode and
 * Approvals from the operator's own new-session defaults, worktree off. Pure: no I/O.
 */
object ProposalSeed {
    class Result(
        val form: JsObj,
        /** Every seeded field carries its modified flag, so a later catalog refresh cannot overwrite any and Start sends them all. */
        val modified: JsObj,
        val codexCurrent: HandoverDraftForm.CodexCurrent?,
        /** A suggested model the catalog does not list: kept as typed, shown, never a pin. */
        val extraModel: String?,
        val key: String,
    )

    private fun JsObj.s(key: String): String? = (this[key] as? JsStr)?.value

    private fun operatorMode(entry: ProviderCatalogEntry, preferences: JsObj): String {
        val remembered = ((preferences["providerPreferences"] as? JsObj)?.get(entry.key) as? JsObj)?.s("mode")?.trim()
        return remembered?.takeIf { it.isNotEmpty() } ?: DraftForm.defaultModeFor(JsStr(entry.provider))
    }

    /** Null when no row of the suggested harness and account is on this server (the card then has no editable rows). */
    fun of(suggestions: JsObj, entries: List<ProviderCatalogEntry>, session: AgentSession, preferences: JsObj): Result? {
        val provider = suggestions.s("provider") ?: session.provider
        val sameHarness = provider == session.provider
        val profileId = suggestions.s("profileId") ?: if (suggestions.s("provider") == null || sameHarness) session.profileId else null
        val probe = AgentSession(id = "proposal", provider = provider, name = "the proposal", cwd = "", status = "idle", startedAt = 0, updatedAt = 0, profileId = profileId)
        val entry = Handover.entryForSource(entries, probe) ?: return null
        val fromSession = sameHarness && (entry.profileId.orEmpty()) == session.profileId.orEmpty()
        val model = suggestions.s("model") ?: if (fromSession) session.model.orEmpty() else ""
        val effort = suggestions.s("reasoningEffort") ?: if (fromSession) session.reasoningEffort.orEmpty() else ""
        val cwd = suggestions.s("cwd") ?: session.cwd
        val opMode = operatorMode(entry, preferences)
        val base = CodexModePresets.codexModePreset(JsStr(opMode))
        val baseSandbox = (base["sandboxPolicy"] as? JsStr)?.value
        val basePolicy = (base["approvalPolicy"] as? JsStr)?.value
        val baseReviewer = (base["approvalsReviewer"] as? JsStr)?.value
        val reviewer = suggestions.s("approvalsReviewer") ?: baseReviewer
        val source = AgentSession(
            id = "proposal", provider = entry.provider, name = "the proposal", cwd = cwd, status = "idle", startedAt = 0, updatedAt = 0,
            profileId = entry.profileId, model = model, reasoningEffort = effort,
            permissionMode = suggestions.s("permissionMode") ?: opMode,
            sandboxPolicy = suggestions.s("sandboxPolicy") ?: baseSandbox,
            approvalPolicy = suggestions.s("approvalPolicy") ?: basePolicy,
            approvalsReviewer = if (reviewer == "auto_review") JsonPrimitive("auto_review") else null,
        )
        val seed = HandoverDraftForm.seedFromSource(entry, source)
        var mode = seed.mode
        if (entry.provider == "antigravity") {
            val perm = suggestions.s("permissionMode")
            mode = if (perm == "acceptEdits" || perm == "plan" || perm == "bypassPermissions") perm else DraftForm.defaultModeFor(JsStr("antigravity"))
        }
        val worktree = suggestions["worktree"] as? JsObj
        val useWorktree = (suggestions["useWorktree"] as? JsBool)?.value ?: (worktree != null)
        val toolApproval = suggestions.s("toolApproval")?.takeIf { DraftApprovals.isChoice(it) } ?: DraftApprovals.DEFAULT
        fun w(key: String): String = worktree?.get(key).let { (it as? JsStr)?.value ?: (it as? JsNum)?.let { n -> n.value.toLong().toString() } }.orEmpty()
        val form = DraftForm.INITIAL_DRAFT_FORM
            .put("key", JsStr(entry.key)).put("model", JsStr(seed.model)).put("reasoningEffort", JsStr(seed.reasoningEffort))
            .put("mode", JsStr(mode)).put("autoMode", JsBool.FALSE).put("toolApproval", JsStr(toolApproval)).put("cwd", JsStr(cwd))
            .put("useWorktree", JsBool.of(useWorktree))
            .put("worktreeMode", JsStr(w("mode").ifEmpty { "branch-off" }))
            .put("worktreeBaseRef", JsStr(w("baseRef"))).put("worktreeBranch", JsStr(w("branch")))
            .put("worktreeSlug", JsStr(w("slug"))).put("worktreePr", JsStr(w("prNumber")))
        val modified = DraftForm.INITIAL_USER_MODIFIED.with(
            "key" to JsBool.TRUE, "model" to JsBool.TRUE, "reasoningEffort" to JsBool.TRUE, "mode" to JsBool.TRUE,
            "autoMode" to JsBool.TRUE, "cwd" to JsBool.TRUE, "useWorktree" to JsBool.TRUE, "worktree" to JsBool.TRUE,
        )
        return Result(form, modified, if (mode == HandoverDraftForm.CODEX_CURRENT_MODE) seed.codexCurrent else null, seed.extraModel, entry.key)
    }
}
