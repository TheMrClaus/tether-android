package com.tether.app.ui.chat

import androidx.compose.runtime.Immutable
import com.tether.app.client.LabelText
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr

/**
 * ta-ozn2.3 (tether #273, lib/handover-ui.mjs userMessageAttribution / firstUserTurnId at fe4aa8c0): the transcript
 * attribution of the two journaled handover rows. The server journals each as a user message carrying the v149 `from`
 * `{ sessionId, name }`: the successor's first row names the SOURCE session, the operator's instruction names THIS
 * session (name "Tether": a peer message can never come from the session itself). Label only, never operator input; a
 * peer message (any other `from`) is untouched. Pure.
 */
@Immutable
internal data class UserAttribution(val kind: Kind, val label: String) {
    enum class Kind { Instruction, Prompt }
}

internal const val HANDOVER_INSTRUCTION_LABEL = "Handover instruction · from Tether"
internal const val HANDOVER_PROMPT_LABEL_PREFIX = "Handover from "
internal const val HANDOVER_PREVIOUS_SESSION = "the previous session"

/** [block] is a user_message block's tree object; [sessionId] is the session the transcript belongs to. */
internal fun userMessageAttribution(
    block: JsObj?,
    sessionId: String?,
    handedOffFrom: String?,
    firstUserBlock: Boolean,
): UserAttribution? {
    if (block == null || (block["kind"] as? JsStr)?.value != "user_message" || sessionId == null) return null
    val from = block["from"] as? JsObj ?: return null
    val fromId = (from["sessionId"] as? JsStr)?.value ?: return null
    if (fromId == sessionId) return UserAttribution(UserAttribution.Kind.Instruction, HANDOVER_INSTRUCTION_LABEL)
    if (firstUserBlock && !handedOffFrom.isNullOrEmpty() && fromId == handedOffFrom) {
        val name = LabelText.label((from["name"] as? JsStr)?.value).trim().ifEmpty { HANDOVER_PREVIOUS_SESSION }
        return UserAttribution(UserAttribution.Kind.Prompt, HANDOVER_PROMPT_LABEL_PREFIX + name)
    }
    return null
}

/** The turn id whose user block is the transcript's FIRST (the successor's handover prompt row), or null. */
internal fun firstUserTurnId(state: JsObj?): String? {
    val order = state?.get("turnOrder") as? JsArr ?: return null
    val turns = state["turnsById"] as? JsObj ?: return null
    for (item in order) {
        val turnId = (item as? JsStr)?.value ?: continue
        val blocks = (turns[turnId] as? JsObj)?.get("blocksById") as? JsObj ?: continue
        if (blocks["user:$turnId"] != null) return turnId
    }
    return null
}
