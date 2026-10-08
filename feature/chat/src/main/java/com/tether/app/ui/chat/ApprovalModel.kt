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

/** Round 8: never splits a surrogate pair (a cut lands on a code-point boundary). Round 9: a bound of 0 or less keeps nothing. */
private fun cut(s: String, max: Int = CARD_TEXT_MAX): String = when {
    s.length <= max -> s
    max <= 0 -> "…"
    else -> s.substring(0, if (Character.isHighSurrogate(s[max - 1])) max - 1 else max) + "…"
}

private fun JsValue?.string(): String? = (this as? JsStr)?.value

/** `x && …` for a string field: present and non-empty. */
private fun JsValue?.truthyString(): String? = (this as? JsStr)?.value?.takeIf { it.isNotEmpty() }

private fun JsValue?.objects(): List<JsObj> = (this as? JsArr)?.mapNotNull { it as? JsObj }.orEmpty()

private fun JsValue?.strings(): List<String> = (this as? JsArr)?.mapNotNull { (it as? JsStr)?.value }.orEmpty()

// --- Approvals --------------------------------------------------------------------------------------

/** One normalized provider choice (events.mjs:658 normalizeApprovalChoices). */
@Immutable
internal data class ApprovalChoiceView(
    val choiceId: String,
    /** DISPLAY text (escaped, then cut): the key's words. The choice id and grant, not this, decide what a tap sends. */
    val label: String,
    /** DISPLAY text, as [label]. */
    val description: String?,
    val permissionGrant: String?,
)

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
    /** The tool name as the wire gave it (cut): it picks how [input] renders; the card draws it through SafeText (ta-28i: every bidi / invisible code point a styled token). */
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

    /** chat-view.tsx 90fbb9f :1270: the confirmation box shows only for an "exact" choice (ta-coik.5: as on the web). */
    val needsConfirm: Boolean get() = hasExact
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
            ApprovalChoiceView(
                id,
                displayLine(c["label"].string().orEmpty(), 200),
                c["description"].truthyString()?.let { displayLine(it, CARD_TEXT_MAX) },
                c["permissionGrant"].string(),
            )
        },
        // Round 8 (Low-2): raw here; the card escapes FIRST and cuts after (displayText / displayPath).
        reason = metadata?.get("reason").truthyString(),
        cwd = metadata?.get("cwd").truthyString(),
        network = network,
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

/**
 * LRI / PDI: each shown path is its own LEFT-TO-RIGHT isolate, so RTL text cannot reorder what is
 * around it and a path that STARTS with RTL letters (which FSI would resolve to right-to-left)
 * cannot flip its own quotes. [FSI] still isolates prose ([displayText]), whose direction is its own.
 */
internal const val LRI = '\u2066'
internal const val FSI = '\u2068'
internal const val PDI = '\u2069'

/** Round 7: the marker a path with a `.` or `..` segment carries. */
internal const val RELATIVE_MARKER = " (contains relative segments (..))"

/**
 * A server path as the card SHOWS it (never as it is granted: the grant carries the raw path).
 * - Escaped BY CATEGORY (L-C, round 7): every code point that could hide, reorder, fake a space, fake
 *   the quotes or fake the elision mark is written out as a visible `\uXXXX` (`\u{XXXXX}` above
 *   the BMP), see [needsEscape]; so is the backslash itself, so an escape cannot be faked. A
 *   combining mark is escaped from the third in a row, and every enclosing mark: see [escapeTokens].
 * - Never cut, like the web's `<code>{path}</code>`: every code point of the path is shown; a path with a
 *   `.` or `..` segment also says so: [RELATIVE_MARKER]. Nothing limits it here: the reducer already holds
 *   a path to 4096 code points and a list to 64 paths (the web enforces the same), and every row is shown.
 * - Quoted with curly quotes (always escaped inside), and isolated LRI…PDI, so a path cannot pose as
 *   part of the sentence ("/x; no network access", `/fake”; network access; read “/y`) nor let RTL
 *   letters reorder the separators around it.
 */
internal fun displayPath(path: String): String = displayPathChunks(path, Int.MAX_VALUE).single()

/** ta-57l: the card's lazy list draws a long path in pieces of at most this many characters (see [displayPathChunks]). */
internal const val GRANT_CHUNK_CHARS = 1_200

/**
 * [displayPath] in pieces, so a row of 36,000 escaped characters (4096 tag characters) is laid out a
 * piece at a time, only as it scrolls into view. Every piece is cut at an escape boundary and is its own
 * balanced LRI…PDI island (a right-to-left letter at the start of a piece cannot flip it); the pieces
 * hold exactly the text of [displayPath] (quotes, escapes, "…") and a relative path's [RELATIVE_MARKER]
 * ends the last one. With [chunk] = Int.MAX_VALUE the one piece is [displayPath] itself.
 */
internal fun displayPathChunks(path: String, chunk: Int = GRANT_CHUNK_CHARS): List<String> {
    val cps = path.codePoints().toArray()
    val relative = hasRelativeSegment(path)
    val body = ArrayList<String>(cps.size + 2)
    body += "“"
    body += escapeTokens(cps, 0, cps.size)
    body += "”"
    val pieces = ArrayList<String>()
    val sb = StringBuilder()
    for (t in body) {
        if (sb.isNotEmpty() && sb.length + t.length > chunk) {
            pieces += sb.toString()
            sb.setLength(0)
        }
        sb.append(t)
    }
    pieces += sb.toString()
    return pieces.mapIndexed { i, piece -> "$LRI$piece$PDI" + if (relative && i == pieces.lastIndex) RELATIVE_MARKER else "" }
}

/**
 * The grant card's rows, each path as the pieces [displayPathChunks] makes of it (read, then write, in
 * request order). EVERY requested path is a row with its full text; the only bounds are the reducer's own
 * (4096 code points a path, 64 a list), which the web enforces too.
 */
@Immutable
internal data class GrantRows(val read: List<List<String>>, val write: List<List<String>>)

internal fun grantRows(requested: RequestedPermissionsView): GrantRows {
    val cache = HashMap<String, List<String>>()
    fun take(list: List<String>) = list.map { path -> cache.getOrPut(path) { displayPathChunks(path) } }
    return GrantRows(take(requested.read), take(requested.write))
}

/** Round 7/8 (context lines): server text escaped like a path, isolated FSI…PDI, at most [max] escaped characters then a real "…". */
internal fun displayText(text: String, max: Int = DISPLAY_TEXT_MAX): String = "$FSI${displayLine(text, max)}$PDI"

/**
 * Server text escaped FIRST, then cut at an escape boundary at [max] escaped characters; the "…" is
 * outside the escaped text (never escaped). No isolate: for a key's label, a name, a description.
 */
internal fun displayLine(text: String, max: Int = DISPLAY_TEXT_MAX): String {
    val cps = text.codePoints().toArray()
    val tokens = escapeTokens(cps, 0, cps.size)
    val total = tokens.sumOf { it.length }
    return if (total <= max) tokens.joinToString("") else headOf(tokens, max) + "…"
}

/**
 * ta-d2cx: the tool name for the card's SafeText line: bidi / invisible code points stay raw here (SafeText
 * draws them as warning-ink tokens, ta-28i), but a combining mark that [escapeTokens] escapes for a path (the
 * 3rd and later of a run, every enclosing mark) is written out as `\uXXXX` the same way, from that same helper.
 */
internal fun displayName(name: String): String {
    val cps = name.codePoints().toArray()
    if (cps.none(::isMark)) return name
    val tokens = escapeTokens(cps, 0, cps.size)
    val sb = StringBuilder(name.length)
    for (k in cps.indices) {
        if (isMark(cps[k]) && tokens[k] != String(Character.toChars(cps[k])) && !needsEscape(cps[k])) sb.append(tokens[k]) else sb.appendCodePoint(cps[k])
    }
    return sb.toString()
}

/** Round 8: the longest a context value (reason, network host) shows, in escaped characters. */
internal const val DISPLAY_TEXT_MAX = 2_000

private fun headOf(tokens: Array<String>, budget: Int): String {
    val out = StringBuilder()
    for (t in tokens) {
        if (out.length + t.length > budget) break
        out.append(t)
    }
    return out.toString()
}

/**
 * A `.` or `..` segment, with `/` or `\` as the separator. Windows reads more as the same thing: a
 * segment of dots (and trailing spaces) such as `...` or `.. `, whose trailing dots and spaces the
 * file system drops, and a drive-relative `C:..` / `C:.`.
 */
internal fun hasRelativeSegment(path: String): Boolean = path.split('/', '\\').any(::isRelativeSegment)

private fun isRelativeSegment(segment: String): Boolean {
    val body = if (segment.length >= 2 && segment[1] == ':' && segment[0].let { it in 'a'..'z' || it in 'A'..'Z' }) segment.substring(2) else segment
    val trimmed = body.trimEnd(' ')
    return trimmed.isNotEmpty() && trimmed.all { it == '.' }
}


private const val HEX = "0123456789ABCDEF"

/** A combining mark (Mn, Mc or Me). */
private fun isMark(cp: Int): Boolean = when (Character.getType(cp)) {
    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
    else -> false
}

/** The longest run of combining marks shown as themselves: NFD Vietnamese needs 2, an Indic cluster 2-3. The 3rd and later are escaped. */
internal const val MARKS_SHOWN = 2

/**
 * The shown form of cps[from, to): each code point as itself or an escape ([escapeToken]). A run of
 * combining marks counts from the code point before [from] too (a cut never restarts it), and the
 * ([MARKS_SHOWN] + 1)th and later mark of a run is escaped (stacked marks overdraw the lines around);
 * every enclosing mark (Me) is escaped, whatever its place.
 */
internal fun escapeTokens(cps: IntArray, from: Int, to: Int): Array<String> {
    var run = 0
    var i = from - 1
    while (i >= 0 && isMark(cps[i])) { run++; i-- }
    return Array(to - from) { k ->
        val cp = cps[from + k]
        if (isMark(cp)) {
            run++
            if (run > MARKS_SHOWN || Character.getType(cp) == Character.ENCLOSING_MARK.toInt()) escapeCodePoint(cp) else escapeToken(cp)
        } else {
            run = 0
            escapeToken(cp)
        }
    }
}

/** One code point as shown: itself, or `\uXXXX` / `\u{X…}` (hex built by hand: no String.format on a 500k-code-point card). */
private fun escapeToken(cp: Int): String = if (needsEscape(cp)) escapeCodePoint(cp) else String(Character.toChars(cp))

private fun escapeCodePoint(cp: Int): String {
    val sb = StringBuilder(10).append("\\u")
    if (cp > 0xFFFF) {
        sb.append('{')
        var started = false
        for (shift in 20 downTo 0 step 4) {
            val d = (cp shr shift) and 0xF
            if (d != 0 || started || shift == 0) {
                sb.append(HEX[d])
                started = true
            }
        }
        sb.append('}')
    } else {
        for (shift in 12 downTo 0 step 4) sb.append(HEX[(cp shr shift) and 0xF])
    }
    return sb.toString()
}

/**
 * Escape when the code point's general category is CONTROL, FORMAT, LINE_SEPARATOR,
 * PARAGRAPH_SEPARATOR, SURROGATE, PRIVATE_USE, UNASSIGNED or a quote punctuation (Pi / Pf), or a
 * SPACE_SEPARATOR other than U+0020; any DEFAULT_IGNORABLE_CODE_POINT (ICU; [IGNORABLE_FALLBACK] if
 * ICU cannot answer); the variation selectors (U+FE00-FE0F, U+E0100-E01EF); the Hangul fillers
 * (U+115F, U+1160, U+3164, U+FFA0); the braille blank U+2800; the quote look-alikes U+201C, U+201D,
 * U+201E, U+201F, U+02EE, U+2033, U+FF02, and (round 10) U+2036, U+02DD, U+02BA, U+05F4,
 * U+301D-301F, U+3003; the ellipsis U+2026 and its look-alikes U+2025, U+22EF, U+FE19, U+1D159 (so a
 * fake elision mark cannot appear); and the backslash. (A combining mark's place in a run is
 * [escapeTokens]'s to judge, not this per-code-point test.)
 */
internal fun needsEscape(cp: Int): Boolean {
    when (Character.getType(cp)) {
        Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(), Character.SURROGATE.toInt(), Character.PRIVATE_USE.toInt(),
        Character.UNASSIGNED.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        -> return true
        Character.SPACE_SEPARATOR.toInt() -> return cp != 0x20
    }
    if (isDefaultIgnorable(cp)) return true
    return cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 ||
        cp == 0x2800 || cp == 0x201C || cp == 0x201D || cp == 0x201E || cp == 0x201F || cp == 0x02EE || cp == 0x2033 || cp == 0xFF02 ||
        cp == 0x2036 || cp == 0x02DD || cp == 0x02BA || cp == 0x05F4 || cp in 0x301D..0x301F || cp == 0x3003 ||
        cp == 0x2026 || cp == 0x2025 || cp == 0x22EF || cp == 0xFE19 || cp == 0x1D159 || cp == 0x5C
}

/** Default-ignorable code points ICU might not be asked about: combining grapheme joiner, Khmer / Mongolian ignorables. */
internal val IGNORABLE_FALLBACK: Set<Int> = setOf(0x034F, 0x17B4, 0x17B5, 0x180B, 0x180C, 0x180D, 0x180F)

/** ICU's DEFAULT_IGNORABLE_CODE_POINT for [cp], or null when ICU cannot answer (a plain JVM). */
internal fun icuDefaultIgnorable(cp: Int): Boolean? =
    runCatching { android.icu.lang.UCharacter.hasBinaryProperty(cp, android.icu.lang.UProperty.DEFAULT_IGNORABLE_CODE_POINT) }.getOrNull()

private fun isDefaultIgnorable(cp: Int): Boolean = cp in IGNORABLE_FALLBACK || icuDefaultIgnorable(cp) == true

/** What one choice key sends: its id and (for a permission-granting choice) the grant, or null when it is disabled. */
internal data class ApprovalPick(val choiceId: String, val granted: GrantedPermissions?)

/** The ApprovalCard's `disabled` + `choose` rules for [choice] (chat-view.tsx:1190-1205, 1276-1280). */
internal fun pickFor(
    view: ApprovalView,
    choice: ApprovalChoiceView,
    confirmed: Boolean,
    subset: GrantedPermissions?,
): ApprovalPick? = when (choice.permissionGrant) {
    // chat-view.tsx 90fbb9f :1191-1205, 1286-1289 (ta-coik.5: the web's rule): "exact" grants the
    // complete request once its box is ticked, whatever the path boxes say; "subset" grants the
    // ticked paths, with no confirmation, and is disabled with nothing ticked.
    "exact" -> view.requested?.exact?.takeIf { confirmed }?.let { ApprovalPick(choice.choiceId, it) }
    "subset" -> subset?.let { ApprovalPick(choice.choiceId, it) }
    else -> ApprovalPick(choice.choiceId, null)
}

/** chat-view.tsx 90fbb9f :1278, the confirmation's words. */
internal const val EXACT_CONFIRM_COPY = "Confirm the complete permission expansion shown above."

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

/** Round 9: through the code-point-safe [cut]. */
private fun truncateTarget(value: String): String = cut(value, DENIAL_TARGET_MAX)

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
