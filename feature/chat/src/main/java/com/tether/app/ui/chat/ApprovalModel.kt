package com.tether.app.ui.chat

import androidx.compose.runtime.Immutable
import com.tether.app.client.ConsentGuard
import com.tether.app.protocol.GrantedPermissions
import com.tether.app.protocol.TetherJson
import com.tether.app.protocol.fold.truthy
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue

/*
 * T6.3: the attention cards' view models, read off the v128 projection tree with the web's own
 * semantics (chat-view.tsx 400-498 PermissionDenialCard + denialCopy, 940-1151 QuestionCard +
 * buildQuestionAnswers + AnsweredQuestionCard, 1153-1325 ApprovalCard; denial-target-model.mjs
 * placeDenials / denialTarget). The typed legacy model is not used here: its tolerant decode drops
 * a whole `pendingQuestions` map when one prompt lacks a field, which would hide a request the turn
 * is stalled on.
 *
 * Nothing here decides anything: the cards call the client, whose ConsentGuard is the authority.
 */

/** Display bound for provider/agent text on a card (a key into `answers` always keeps the full text). */
internal const val CARD_TEXT_MAX = 4_000

private fun cut(s: String, max: Int = CARD_TEXT_MAX): String = if (s.length > max) s.substring(0, max) + "…" else s

private fun JsValue?.string(): String? = (this as? JsStr)?.value

/** `x && …` for a string field: present and non-empty. */
private fun JsValue?.truthyString(): String? = (this as? JsStr)?.value?.takeIf { it.isNotEmpty() }

private fun JsValue?.objects(): List<JsObj> = (this as? JsArr)?.mapNotNull { it as? JsObj }.orEmpty()

private fun JsValue?.strings(): List<String> = (this as? JsArr)?.mapNotNull { (it as? JsStr)?.value }.orEmpty()

// --- Approvals --------------------------------------------------------------------------------------

/** One normalized provider choice (events.mjs:658 normalizeApprovalChoices). */
@Immutable
internal data class ApprovalChoiceView(val choiceId: String, val label: String, val description: String?, val permissionGrant: String?)

/** `metadata.requestedPermissions` as the card lists it, plus the exact wire form ([exact]). */
@Immutable
internal data class RequestedPermissionsView(
    val read: List<String>,
    val write: List<String>,
    /** `requested.network?.enabled` truthy: the card offers a Network access box. */
    val network: Boolean,
    /** What an "exact" choice sends back: the requested object itself ([ConsentGuard.requestedPermissions]). */
    val exact: GrantedPermissions,
)

/**
 * One pending approval (`turn.pendingApprovals[requestId]`). [requestId] is the map key (I4: a
 * non-string `requestId` field never hides the card). [contentFp] is the card's identity
 * (CardStateStore: the request and its turn, no origin); the origin-bound fingerprint the client
 * checks is derived from [request] + [activeTurnId] at composition ([wireFingerprint]).
 */
@Immutable
internal data class ApprovalView(
    val requestId: String,
    val contentFp: String,
    val activeTurnId: String,
    val request: JsObj,
    val toolId: String,
    val name: String,
    /** `approval.input`; null when absent or null (then no ToolInput renders). */
    val input: JsValue?,
    val choices: List<ApprovalChoiceView>,
    val reason: String?,
    val cwd: String?,
    /** `protocol://host` (the protocol only when truthy). */
    val network: String?,
    val requested: RequestedPermissionsView?,
) {
    val hasExact: Boolean get() = choices.any { it.permissionGrant == "exact" }

    /** The confirmation box shows for any permission-granting choice (the web: only "exact"; I5). */
    val needsConfirm: Boolean get() = choices.any { it.permissionGrant == "exact" || it.permissionGrant == "subset" }
    val allowsSubset: Boolean get() = choices.any { it.permissionGrant == "subset" }
}

internal fun approvalView(requestId: String, obj: JsObj, activeTurnId: String, sessionId: String = ""): ApprovalView {
    val metadata = obj["metadata"] as? JsObj
    val network = (metadata?.get("network") as? JsObj)?.let { n ->
        val host = n["host"].string() ?: return@let null
        (n["protocol"].truthyString()?.let { "$it://" } ?: "") + host
    }
    val requestedObj = metadata?.get("requestedPermissions") as? JsObj
    val requested = requestedObj?.let { r ->
        val fs = r["fileSystem"] as? JsObj
        RequestedPermissionsView(
            read = fs?.get("read").strings(),
            write = fs?.get("write").strings(),
            network = truthy((r["network"] as? JsObj)?.get("enabled")),
            exact = checkNotNull(ConsentGuard.requestedPermissions(obj)),
        )
    }
    val input = obj["input"]?.takeUnless { it === JsNull }
    return ApprovalView(
        requestId = requestId,
        contentFp = ContentFingerprints.of(sessionId, activeTurnId, obj),
        activeTurnId = activeTurnId,
        request = obj,
        toolId = obj["toolId"].string().orEmpty(),
        name = cut(obj["name"].string().orEmpty(), 200),
        input = input,
        choices = obj["choices"].objects().mapNotNull { c ->
            val id = c["choiceId"].string() ?: return@mapNotNull null
            ApprovalChoiceView(id, cut(c["label"].string().orEmpty(), 200), c["description"].truthyString()?.let(::cut), c["permissionGrant"].string())
        },
        reason = metadata?.get("reason").truthyString()?.let(::cut),
        cwd = metadata?.get("cwd").truthyString()?.let(::cut),
        network = network?.let(::cut),
        requested = requested,
    )
}

/**
 * `subsetGrant()` (chat-view.tsx:1178-1188): the ticked read/write paths and network, or null
 * when nothing is ticked (a subset choice is then disabled).
 */
internal fun subsetGrant(read: Collection<String>, write: Collection<String>, network: Boolean): GrantedPermissions? {
    if (read.isEmpty() && write.isEmpty() && !network) return null
    return GrantedPermissions(
        fileSystemRead = read.toList().takeIf { it.isNotEmpty() },
        fileSystemWrite = write.toList().takeIf { it.isNotEmpty() },
        hasFileSystem = read.isNotEmpty() || write.isNotEmpty(),
        networkEnabled = if (network) true else null,
    )
}

/** I5: [grant] ticks every requested path and the requested network (compared as sets, whatever the tick order). */
internal fun isFullGrant(grant: GrantedPermissions, requested: RequestedPermissionsView?): Boolean {
    if (requested == null) return false
    return grant.fileSystemRead.orEmpty().toSet() == requested.read.toSet() &&
        grant.fileSystemWrite.orEmpty().toSet() == requested.write.toSet() &&
        (grant.networkEnabled == true) == requested.network
}

/** L-3 / L-B: a shown path keeps its first [DISPLAY_PATH_HEAD] and last [DISPLAY_PATH_TAIL] code points. */
internal const val DISPLAY_PATH_HEAD = 60
internal const val DISPLAY_PATH_TAIL = 99
internal const val DISPLAY_PATH_MAX = DISPLAY_PATH_HEAD + 1 + DISPLAY_PATH_TAIL

/**
 * A server path as the card SHOWS it (never as it is granted: the grant carries the raw path).
 * - Escaped BY CATEGORY (L-C): every code point that could hide, reorder, fake a space or fake the
 *   quotes around it is written out as a visible `\uXXXX` (`\u{XXXXX}` above the BMP), see
 *   [needsEscape]; so is the backslash itself, so an escape cannot be faked.
 * - Cut in the MIDDLE (L-B): the head and the TAIL (which decides the scope: a trailing `/../..`)
 *   both stay, with "…" between them.
 * - Quoted with curly quotes, which themselves are always escaped inside, so a path cannot pose as
 *   part of the sentence ("/x; no network access", `/fake”; network access; read “/y`).
 */
internal fun displayPath(path: String): String {
    val cps = path.codePoints().toArray()
    val shown = if (cps.size <= DISPLAY_PATH_MAX) {
        render(cps, 0, cps.size)
    } else {
        render(cps, 0, DISPLAY_PATH_HEAD) + "…" + render(cps, cps.size - DISPLAY_PATH_TAIL, cps.size)
    }
    return "\u201C$shown\u201D"
}

private fun render(cps: IntArray, from: Int, to: Int): String {
    val out = StringBuilder()
    for (i in from until to) {
        val cp = cps[i]
        when {
            !needsEscape(cp) -> out.appendCodePoint(cp)
            cp > 0xFFFF -> out.append("\\u{%X}".format(cp))
            else -> out.append("\\u%04X".format(cp))
        }
    }
    return out.toString()
}

/**
 * L-C: escape when the code point's general category is CONTROL, FORMAT, LINE_SEPARATOR,
 * PARAGRAPH_SEPARATOR, SURROGATE, PRIVATE_USE or UNASSIGNED, or a SPACE_SEPARATOR other than U+0020;
 * plus the variation selectors (U+FE00-FE0F, U+E0100-E01EF), the Hangul fillers (U+115F, U+1160,
 * U+3164, U+FFA0), the curly quotes U+201C / U+201D and the backslash.
 */
internal fun needsEscape(cp: Int): Boolean {
    when (Character.getType(cp)) {
        Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(), Character.SURROGATE.toInt(), Character.PRIVATE_USE.toInt(),
        Character.UNASSIGNED.toInt(),
        -> return true
        Character.SPACE_SEPARATOR.toInt() -> return cp != 0x20
    }
    return cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 ||
        cp == 0x201C || cp == 0x201D || cp == 0x5C
}

/** What one choice key sends: its id and (for a permission-granting choice) the grant, or null when it is disabled. */
internal data class ApprovalPick(val choiceId: String, val granted: GrantedPermissions?)

/** The ApprovalCard's `disabled` + `choose` rules for [choice] (chat-view.tsx:1190-1205, 1276-1280). */
internal fun pickFor(
    view: ApprovalView,
    choice: ApprovalChoiceView,
    confirmed: Boolean,
    subset: GrantedPermissions?,
): ApprovalPick? = when (choice.permissionGrant) {
    // Round 4 (coordinator decision, stricter than the web): EVERY permission-granting choice needs
    // the confirmation, and the confirmation names what is ticked, so "Allow all" also needs every
    // box ticked (what it grants is what was confirmed).
    "exact" -> view.requested?.exact?.takeIf { confirmed && subset != null && isFullGrant(subset, view.requested) }?.let { ApprovalPick(choice.choiceId, it) }
    "subset" -> subset?.takeIf { confirmed }?.let { ApprovalPick(choice.choiceId, it) }
    else -> ApprovalPick(choice.choiceId, null)
}

/**
 * The confirmation's words: what a grant of the ticked [read] / [write] paths and [network] gives
 * (never colour alone; read aloud as the checkbox's label).
 */
internal fun grantSummary(read: List<String>, write: List<String>, network: Boolean): String {
    val parts = buildList {
        if (read.isNotEmpty()) add("read ${read.joinToString(", ") { displayPath(it) }}")
        if (write.isNotEmpty()) add("write ${write.joinToString(", ") { displayPath(it) }}")
        if (network) add("network access")
    }
    return if (parts.isEmpty()) "Confirm these permissions: none selected." else "Confirm these permissions: ${parts.joinToString("; ")}."
}

// --- Questions --------------------------------------------------------------------------------------

@Immutable
internal data class QuestionOptionView(val label: String, val description: String?)

@Immutable
internal data class QuestionPromptView(
    val question: String,
    val header: String?,
    val multiSelect: Boolean,
    val options: List<QuestionOptionView>,
    /** The prompt's index in the request's `questions` array (ConsentGuard.questionSlots indexes by it). */
    val index: Int = 0,
)

/** One pending AskUserQuestion (`turn.pendingQuestions[requestId]`); id and identity as for [ApprovalView]. */
@Immutable
internal data class QuestionRequestView(
    val requestId: String,
    val contentFp: String,
    val activeTurnId: String,
    val request: JsObj,
    val toolId: String,
    val prompts: List<QuestionPromptView>,
)

/** The origin-bound fingerprint the client checks ([ConsentGuard.fingerprint]); "" origin when there is no live socket. */
internal fun wireFingerprint(origin: String?, activeTurnId: String, request: JsObj): String =
    ConsentGuard.fingerprint(origin.orEmpty(), activeTurnId, request)

internal fun questionView(requestId: String, obj: JsObj, activeTurnId: String, sessionId: String = ""): QuestionRequestView {
    val raw = obj["questions"] as? JsArr
    val prompts = raw.orEmpty().withIndex().mapNotNull { (index, value) ->
        val q = value as? JsObj ?: return@mapNotNull null
        // The prompt text is the answers map's key, so it is kept whole (never cut).
        val text = q["question"].string() ?: return@mapNotNull null
        QuestionPromptView(
            question = text,
            header = q["header"].truthyString()?.let(::cut),
            multiSelect = truthy(q["multiSelect"]),
            options = q["options"].objects().mapNotNull { o ->
                val label = o["label"].string() ?: return@mapNotNull null
                QuestionOptionView(label, o["description"].truthyString()?.let(::cut))
            },
            index = index,
        )
    }
    return QuestionRequestView(requestId, ContentFingerprints.of(sessionId, activeTurnId, obj), activeTurnId, obj, obj["toolId"].string().orEmpty(), prompts)
}

/** `isAnswered(q)`: a pick, or non-blank "Other" text. */
internal fun isPromptAnswered(q: QuestionPromptView, picks: Map<String, List<String>>, other: Map<String, String>): Boolean =
    picks[q.question].orEmpty().isNotEmpty() || jsTrim(other[q.question].orEmpty()).isNotEmpty()

/** `toggle(q, label)`: multi-select adds/removes; single-select picks one or clears it. */
internal fun togglePick(current: List<String>, q: QuestionPromptView, label: String): List<String> = togglePick(current, q.multiSelect, label)

/** The same toggle over any pick key (labels, or option indices in the card store). */
internal fun <T> togglePick(current: List<T>, multi: Boolean, pick: T): List<T> = when {
    multi -> if (pick in current) current.filter { it != pick } else current + pick
    pick in current -> emptyList()
    else -> listOf(pick)
}

@Immutable
internal data class AnsweredItemView(val header: String?, val question: String, val answer: String?)

/** A v104 `turn.answeredQuestions[]` record: the operator's settled reply. */
@Immutable
internal data class AnsweredView(val requestId: String, val toolId: String, val items: List<AnsweredItemView>, val response: String?)

internal fun answeredView(obj: JsObj): AnsweredView = AnsweredView(
    requestId = obj["requestId"].string().orEmpty(),
    toolId = obj["toolId"].string().orEmpty(),
    items = obj["items"].objects().map { i ->
        AnsweredItemView(i["header"].truthyString()?.let(::cut), cut(i["question"].string().orEmpty()), i["answer"].truthyString()?.let(::cut))
    },
    response = obj["response"].truthyString()?.let(::cut),
)

// --- Permission denials -----------------------------------------------------------------------------

/** One `PermissionDenialProjection` (events.mjs:2072 foldPermissionDenied). */
@Immutable
internal data class DenialView(val toolId: String, val name: String, val reason: String, val reasonCode: String?, val error: String?, val subagent: Boolean)

internal fun denialView(obj: JsObj): DenialView = DenialView(
    toolId = obj["toolId"].string().orEmpty(),
    name = cut(obj["name"].string().orEmpty(), 200),
    reason = obj["reason"].string() ?: "unknown",
    reasonCode = obj["reasonCode"].string(),
    error = obj["error"].string(),
    subagent = obj["subagent"] === JsBool.TRUE || truthy(obj["subagent"]),
)

/** chat-view.tsx:402-416 DENIAL_COPY. */
internal val DENIAL_COPY: Map<String, String> = mapOf(
    "classifier" to "Claude Code’s safety classifier refused this call.",
    "safety_check" to "Claude Code’s command safety check refused this call.",
    "rule" to "A Claude Code permission rule refused this call.",
    "hook" to "A PreToolUse hook refused this call.",
    "mode" to "This session’s permission mode refused this call. Change the mode to allow it.",
    "sandbox" to "The Bash sandbox refused this call. Change the session’s sandbox tier to allow it.",
    "working_dir" to "The path is outside this session’s allowed directories.",
    "prompt_tool" to "An external permission-prompt tool refused this call.",
    "async_agent" to "This background agent is not allowed to request this tool.",
    "other" to "Claude Code refused this call without naming a cause.",
    "unknown" to "Claude Code refused this call for a reason this Tether build does not recognise yet.",
)

/** chat-view.tsx:424-441 denialCopy. */
internal fun denialCopy(d: DenialView): String {
    if (d.reason == "unknown" && !d.error.isNullOrEmpty()) return cut(d.error)
    if (d.reason == "unknown" && d.reasonCode.isNullOrEmpty()) return "A permission rule or PreToolUse hook refused this call."
    return DENIAL_COPY[d.reason] ?: DENIAL_COPY.getValue("unknown")
}

/** denial-target-model.mjs DENIAL_TARGET_KEYS: the one field that names a call, first match wins. */
internal val DENIAL_TARGET_KEYS: List<Pair<String, String>> = listOf(
    "command" to "Command",
    "file_path" to "File",
    "path" to "Path",
    "url" to "URL",
    "pattern" to "Pattern",
    "prompt" to "Prompt",
)

internal const val DENIAL_TARGET_MAX = 300

@Immutable
internal data class DenialTarget(val label: String, val value: String)

private fun truncateTarget(value: String): String = if (value.length > DENIAL_TARGET_MAX) value.substring(0, DENIAL_TARGET_MAX) + "…" else value

/** denial-target-model.mjs denialTarget. */
internal fun denialTarget(input: JsValue?): DenialTarget? {
    if (input is JsStr) return if (jsTrim(input.value).isNotEmpty()) DenialTarget("Input", truncateTarget(input.value)) else null
    val obj = input as? JsObj ?: return null
    for ((key, label) in DENIAL_TARGET_KEYS) {
        val value = obj[key] as? JsStr ?: continue
        if (jsTrim(value.value).isNotEmpty()) return DenialTarget(label, truncateTarget(value.value))
    }
    return null
}

/** denial-target-model.mjs denialAnchor: the block a denial sits after (a sub-agent call: its parent Task block). */
internal fun denialAnchor(turn: JsObj?, toolId: String): String? {
    if (turn == null || toolId.isEmpty()) return null
    val byId = turn["blocksById"] as? JsObj
    if (((byId?.get(toolId) as? JsObj)?.get("kind") as? JsStr)?.value == "tool") return toolId
    for (blockId in turn["blocks"].strings()) {
        val entries = ((byId?.get(blockId) as? JsObj)?.get("subagent") as? JsObj)?.get("entries") as? JsObj
        if (((entries?.get(toolId) as? JsObj)?.get("kind") as? JsStr)?.value == "tool") return blockId
    }
    return null
}

/** denial-target-model.mjs deniedToolInput. */
internal fun deniedToolInput(turn: JsObj?, toolId: String): JsValue? {
    val blockId = denialAnchor(turn, toolId) ?: return null
    val byId = turn!!["blocksById"] as JsObj
    val input = if (blockId == toolId) {
        (byId[toolId] as JsObj)["input"]
    } else {
        ((((byId[blockId] as JsObj)["subagent"] as JsObj)["entries"] as JsObj)[toolId] as JsObj)["input"]
    }
    return input?.takeUnless { it === JsNull }
}

/** Where a turn's denials go: after an anchor block, or at the turn's tail. */
@Immutable
internal data class TurnDenials(val byBlock: Map<String, List<DenialView>>, val trailing: List<DenialView>)

/** [placeDenials]' result; [homeless] trail the whole transcript. */
@Immutable
internal data class DenialPlacement(val byTurn: Map<String, TurnDenials>, val homeless: List<DenialView>) {
    companion object {
        val EMPTY = DenialPlacement(emptyMap(), emptyList())
    }
}

/**
 * denial-target-model.mjs placeDenials: each turn's denials anchored to the call they refused;
 * unattributed ones to the newest turn that ran the call (lateDenialAnchor); a toolId placed once.
 */
internal fun placeDenials(state: JsObj?): DenialPlacement {
    if (state == null) return DenialPlacement.EMPTY
    val order = state["turnOrder"].strings()
    val turns = state["turnsById"] as? JsObj
    val byBlock = LinkedHashMap<String, LinkedHashMap<String, MutableList<DenialView>>>()
    val trailing = LinkedHashMap<String, MutableList<DenialView>>()
    val placed = HashSet<String>()
    val homeless = ArrayList<DenialView>()
    fun slot(turnId: String) = byBlock.getOrPut(turnId) { LinkedHashMap() }.also { trailing.getOrPut(turnId) { ArrayList() } }
    for (turnId in order) {
        val turn = turns?.get(turnId) as? JsObj
        for (obj in turn?.get("permissionDenials").objects()) {
            val denial = denialView(obj)
            placed.add(denial.toolId)
            val blockId = denialAnchor(turn, denial.toolId)
            val target = slot(turnId)
            if (blockId != null) target.getOrPut(blockId) { ArrayList() }.add(denial) else trailing.getValue(turnId).add(denial)
        }
    }
    for (obj in state["unattributedPermissionDenials"].objects()) {
        val denial = denialView(obj)
        if (!placed.add(denial.toolId)) continue
        var anchored = false
        for (i in order.indices.reversed()) {
            val blockId = denialAnchor(turns?.get(order[i]) as? JsObj, denial.toolId) ?: continue
            slot(order[i]).getOrPut(blockId) { ArrayList() }.add(denial)
            anchored = true
            break
        }
        if (!anchored) homeless.add(denial)
    }
    val result = byBlock.mapValues { (turnId, blocks) -> TurnDenials(blocks.mapValues { it.value.toList() }, trailing[turnId].orEmpty().toList()) }
    return DenialPlacement(result, homeless)
}

/** lateDenialToolInput: the refused call's input from the newest turn that ran it. */
internal fun lateDenialToolInput(state: JsObj?, toolId: String): JsValue? {
    val order = state?.get("turnOrder").strings()
    val turns = state?.get("turnsById") as? JsObj
    for (i in order.indices.reversed()) {
        val turn = turns?.get(order[i]) as? JsObj
        if (denialAnchor(turn, toolId) != null) return deniedToolInput(turn, toolId)
    }
    return null
}

// --- The tree ---------------------------------------------------------------------------------------

/** The projection tree to read cards from: the client's, or (previews, typed-only callers) the typed projection's. */
internal fun cardTree(projection: SessionProjection, tree: JsObj?): JsObj =
    tree ?: JsCodec.fromJson(TetherJson.encodeToJsonElement(SessionProjection.serializer(), projection)) as JsObj

/** `turnsById[activeTurnId]` of [tree]. */
internal fun activeTurnOf(tree: JsObj?): JsObj? {
    val state = tree ?: return null
    val active = state["activeTurnId"].string() ?: return null
    return (state["turnsById"] as? JsObj)?.get(active) as? JsObj
}

/**
 * The active turn's pending approvals, in insertion order (`Object.values(activeTurn.pendingApprovals)`).
 * [sessionId] is the CLIENT's key for the session (I-1: the card identity never trusts the tree's own
 * `tetherSessionId`); null only for callers without one (tests, previews), which fall back to it.
 */
internal fun pendingApprovals(tree: JsObj?, sessionId: String? = null): List<ApprovalView> {
    val turnId = ConsentGuard.activeTurnId(tree) ?: return emptyList()
    val map = activeTurnOf(tree)?.get("pendingApprovals") as? JsObj ?: return emptyList()
    val sessionId = sessionId ?: tree!!["tetherSessionId"].string().orEmpty()
    return map.entries.mapNotNull { (key, value) -> (value as? JsObj)?.let { approvalView(key, it, turnId, sessionId) } }
}

/** The active turn's pending questions ([sessionId] as for [pendingApprovals]). */
internal fun pendingQuestions(tree: JsObj?, sessionId: String? = null): List<QuestionRequestView> {
    val turnId = ConsentGuard.activeTurnId(tree) ?: return emptyList()
    val map = activeTurnOf(tree)?.get("pendingQuestions") as? JsObj ?: return emptyList()
    val sessionId = sessionId ?: tree!!["tetherSessionId"].string().orEmpty()
    return map.entries.mapNotNull { (key, value) -> (value as? JsObj)?.let { questionView(key, it, turnId, sessionId) } }
}

/** The request ids the active turn already records an answer for. */
internal fun answeredRequestIds(tree: JsObj?): Set<String> =
    activeTurnOf(tree)?.get("answeredQuestions").objects().mapNotNullTo(HashSet()) { it["requestId"].string() }
