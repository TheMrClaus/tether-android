package com.tether.app.ui.chat

import androidx.compose.runtime.Immutable
import com.tether.app.client.Proposals
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr

/** One field the server replaced in a suggestion (the card's "Suggested X is not available here; using Y"). */
@Immutable
data class ProposalReplacedView(val field: String, val code: String, val suggested: String)

/** The card's state, from the folded proposal; [Starting] is also what the device shows while its own Start is in flight. */
enum class ProposalState { Pending, Starting, Refused, Started, Here, Dismissed, Expired;
    /** Started, continued here, dismissed and expired are final: the card drops its actions. */
    val resolved: Boolean get() = this == Started || this == Here || this == Dismissed || this == Expired

    companion object {
        fun of(raw: String?): ProposalState = when (raw) {
            "starting" -> Starting
            "refused" -> Refused
            "started" -> Started
            "here" -> Here
            "dismissed" -> Dismissed
            "expired" -> Expired
            else -> Pending
        }
    }
}

/** A proposal as the transcript shows it (the folded `proposals` entry of a turn). The prompt is the server's and is never edited. */
@Immutable
data class ProposalView(
    val proposalId: String,
    val title: String,
    val prompt: String,
    val suggestions: JsObj,
    val replaced: List<ProposalReplacedView>,
    val state: ProposalState,
    val refusalCode: String?,
    val startedSessionId: String?,
    val startedSessionName: String?,
) {
    /** The Options row a refusal code marks (folder / harness / account), or null. */
    val faultRow: String? get() = if (state == ProposalState.Refused) Proposals.faultRow(refusalCode) else null
}

private fun JsObj.s(key: String): String? = (this[key] as? JsStr)?.value

/** The proposals of one turn, oldest first. */
internal fun proposalViews(turnObj: JsObj?): List<ProposalView> =
    (turnObj?.get("proposals") as? JsArr).orEmpty().mapNotNull { entry ->
        val o = entry as? JsObj ?: return@mapNotNull null
        val id = o.s("proposalId") ?: return@mapNotNull null
        ProposalView(
            proposalId = id,
            title = o.s("title").orEmpty(),
            prompt = o.s("prompt").orEmpty(),
            suggestions = o["suggestions"] as? JsObj ?: JsObj.EMPTY,
            replaced = (o["replaced"] as? JsArr).orEmpty().mapNotNull { r ->
                val ro = r as? JsObj ?: return@mapNotNull null
                ProposalReplacedView(ro.s("field") ?: return@mapNotNull null, ro.s("code").orEmpty(), ro.s("suggested").orEmpty())
            },
            state = ProposalState.of(o.s("state")),
            refusalCode = o.s("refusalCode"),
            startedSessionId = o.s("startedSessionId"),
            startedSessionName = o.s("startedSessionName"),
        )
    }

/** The wire name of the tool a proposal replaces in the transcript (any host's prefix). */
internal fun isProposeSessionTool(name: String?): Boolean = name == "propose_session" || name?.endsWith("__propose_session") == true

/** The Options rows of the card, in their order (spec 3.2). */
enum class ProposalRow(val word: String) {
    Folder("folder"), Worktree("worktree"), Harness("harness"), Account("account"), Model("model"), Effort("effort"), Mode("mode"), Approvals("approvals"),
}

/** The row words the Options panel draws (spec 6, exact copy). */
object ProposalCopy {
    private val REPLACED_ROW = mapOf(
        "provider" to ProposalRow.Harness, "profileId" to ProposalRow.Account, "model" to ProposalRow.Model, "reasoningEffort" to ProposalRow.Effort,
        "cwd" to ProposalRow.Folder, "worktree" to ProposalRow.Worktree, "permissionMode" to ProposalRow.Mode, "sandboxPolicy" to ProposalRow.Mode,
        "approvalPolicy" to ProposalRow.Mode, "approvalsReviewer" to ProposalRow.Mode, "toolApproval" to ProposalRow.Approvals,
    )
    private val MODE_FIELDS = listOf("permissionMode", "sandboxPolicy", "approvalPolicy", "approvalsReviewer")

    fun rowOfReplaced(field: String): ProposalRow? = REPLACED_ROW[field]

    /** The rows the agent filled (and the server kept): each carries the "Suggested" tag until the operator changes it. */
    fun suggestedRows(suggestions: JsObj): Set<ProposalRow> = buildSet {
        if (suggestions["cwd"] != null) add(ProposalRow.Folder)
        if (suggestions["useWorktree"] != null || suggestions["worktree"] != null) add(ProposalRow.Worktree)
        if (suggestions["provider"] != null) add(ProposalRow.Harness)
        if (suggestions["profileId"] != null) add(ProposalRow.Account)
        if (suggestions["model"] != null) add(ProposalRow.Model)
        if (suggestions["reasoningEffort"] != null) add(ProposalRow.Effort)
        if (MODE_FIELDS.any { suggestions[it] != null }) add(ProposalRow.Mode)
        if (suggestions["toolApproval"] != null) add(ProposalRow.Approvals)
    }

    fun replacedLine(row: ProposalRow, suggested: String, using: String): String = "Suggested ${row.word} “$suggested” is not available here; using “$using”."

    fun refusedRowLine(row: ProposalRow): String = "Not available for a new session. Choose another ${row.word}."

    /** The row a refusal's fault row names ("folder" | "harness" | "account"). */
    fun faultRow(name: String?): ProposalRow? = when (name) {
        "folder" -> ProposalRow.Folder
        "harness" -> ProposalRow.Harness
        "account" -> ProposalRow.Account
        else -> null
    }
}

/** What the card draws for a proposal given this device's own press in flight or refusal (kept out of the composable). */
internal class ProposalFace(val view: ProposalView, val state: ProposalState, val busy: Boolean, val refusalText: String?)

internal fun proposalFace(proposal: ProposalView, local: com.tether.app.client.ProposalLocalState?): ProposalFace {
    val refusal = local?.refusal
    val starting = local != null && refusal == null
    val state = when {
        proposal.state.resolved -> proposal.state
        starting -> ProposalState.Starting
        else -> proposal.state
    }
    val refusalText = refusal?.text
    val shown = if (state == ProposalState.Pending && refusalText != null) ProposalState.Refused else state
    val view = proposal.copy(state = shown, refusalCode = refusal?.code ?: proposal.refusalCode)
    return ProposalFace(view, state, state == ProposalState.Starting || starting, refusalText)
}
