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
internal enum class ProposalState { Pending, Starting, Refused, Started, Here, Dismissed, Expired;
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
internal data class ProposalView(
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
