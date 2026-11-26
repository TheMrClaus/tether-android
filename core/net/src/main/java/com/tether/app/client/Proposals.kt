package com.tether.app.client

import com.tether.app.protocol.tree.JsObj
import kotlinx.serialization.json.JsonObject

// v175 (the new-session proposal card): the gate, the five refusal texts, and the settings a Start carries. The card itself is
// :feature:chat's; the wire is core/protocol's ClientMessage.ProposalFrame.

/** The first server protocol that journals `session_proposal` and answers the `proposal-*` frames. */
const val PROPOSAL_CARD_MIN_PROTOCOL = 175

/** The card shows only for the CONNECTED server's protocol (never the app's own hello number); below it the tool row stays. */
fun proposalCardSupported(serverProtocol: Int?): Boolean = serverProtocol != null && serverProtocol >= PROPOSAL_CARD_MIN_PROTOCOL

/** A refused proposal frame: [code] is the server's, [text] is the code's own sentence (never the raw detail). */
data class ProposalRefusal(val code: String?, val text: String)

/** What this device knows about a proposal frame it sent: the press in flight, or its refusal. Cleared by the server's state. */
data class ProposalLocalState(val kind: String, val requestId: String, val refusal: ProposalRefusal? = null) {
    val starting: Boolean get() = kind == "start" && refusal == null
}

object Proposals {
    /** `PROPOSAL_REFUSAL_TEXTS` (lib/session-proposal.ts), verbatim. */
    val REFUSAL_TEXTS: Map<String, String> = mapOf(
        "invalid_settings" to "The chosen settings are not valid for a new session.",
        "engine_unavailable" to "The chosen engine is not available on this server.",
        "profile_unavailable" to "The chosen account or profile is not available.",
        "folder_unavailable" to "The chosen folder is not available for a new session.",
        "proposal_closed" to "This proposal can no longer be started.",
    )

    /** The Options row a refusal code names (spec 5): folder, harness or account; the other codes mark no row. */
    fun faultRow(code: String?): String? = when (code) {
        "folder_unavailable" -> "folder"
        "engine_unavailable" -> "harness"
        "profile_unavailable" -> "account"
        else -> null
    }

    /**
     * An `error` frame's "<code>: <text>" as the card shows it: a known code reads its own sentence; anything else reads the
     * server's words, cleaned (the card never invents a refusal).
     */
    fun parseRefusal(message: String): ProposalRefusal {
        val code = message.substringBefore(':', "").trim()
        REFUSAL_TEXTS[code]?.let { return ProposalRefusal(code, it) }
        return ProposalRefusal(null, LabelText.error(message))
    }

    /**
     * The `settings` of a `proposal-start`: the create frame's own settings for the card's final form, key for key, minus the
     * keys a proposal start does not take (the frame's type, requestId, name; never the prompt, which the server holds).
     */
    fun startSettings(form: JsObj, entry: ProviderCatalogEntry, modified: JsObj, codexCurrent: HandoverDraftForm.CodexCurrent? = null): JsonObject {
        val create = CreateFrame.build(form, entry, modified, requestId = "x").toJsonObject()
        val kept = create.filterKeys { it != "type" && it != "requestId" && it != "name" && it != "prompt" && it != "text" }.toMutableMap()
        // The account is always named: null is the harness's default account (the server re-froze `profileId: string | null`), so a
        // card on the default account never starts on the proposer's.
        kept["profileId"] = entry.profileId?.takeIf { it.isNotEmpty() }?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull
        // Codex "Current (custom)": the agent's own triple goes out as it is (no preset reproduces it).
        if (entry.provider == "codex" && codexCurrent != null && (form["mode"] as? com.tether.app.protocol.tree.JsStr)?.value == HandoverDraftForm.CODEX_CURRENT_MODE) {
            codexCurrent.sandboxPolicy?.takeIf { it.isNotEmpty() }?.let { kept["sandboxPolicy"] = kotlinx.serialization.json.JsonPrimitive(it) }
            kept["approvalPolicy"] = codexCurrent.approvalPolicy?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull
            kept["approvalsReviewer"] = if (codexCurrent.approvalsReviewer == "auto_review") kotlinx.serialization.json.JsonPrimitive("auto_review") else kotlinx.serialization.json.JsonNull
        }
        return JsonObject(kept)
    }
}
