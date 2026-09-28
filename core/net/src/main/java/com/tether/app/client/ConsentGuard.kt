package com.tether.app.client

import com.tether.app.protocol.GrantedPermissions
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue

/**
 * T6.3: what became of one operator decision (an `approval` or a `question` answer).
 *
 * Only [Sent] means a frame went on the wire. Every other value means nothing was transmitted.
 */
enum class ConsentResult {
    /** The frame was handed to the live socket, exactly once for this request. */
    Sent,

    /** This process already sent a decision for the request. Nothing is sent a second time. */
    AlreadyDecided,

    /** No live connection. Nothing is held for later (the T13.3 outbox owns that, SYNC_DESIGN §5.4). */
    NotConnected,

    /** Connected, but the session's projection is not yet confirmed by a snapshot on this connection. */
    NotLive,

    /** The session is read-only or handed off: Tether does not answer for it. */
    Locked,

    /** The request is not pending in the current state (resolved, expired, cancelled, or unknown). */
    NotPending,

    /** The choice, decision, grant or answer keys are not ones the request offered. */
    InvalidChoice,
}

/**
 * T6.3 security semantics (SYNC_DESIGN §5.1 I2/I3) as pure checks over the v128 projection tree,
 * run at the one point where an approval or answer can reach the wire (RealTetherClient).
 *
 * A decision is valid only for a request pending in the ACTIVE turn of the current state (the only
 * requests the web shows, chat-view.tsx:1927-1928), and only with what that request offered:
 * - approval with `choiceId`: one of its normalized `choices`. A `permissionGrant: "exact"` choice
 *   carries exactly the requested permissions, a `"subset"` choice a non-empty subset of them, any
 *   other choice none (chat-view.tsx:1186-1205).
 * - approval with `decision`: "allow" or "deny", and only when the request offers no choices
 *   (chat-view.tsx:1287-1320 renders the fallback pair only then).
 * - question: every answered key is one of the request's question texts (buildQuestionAnswers
 *   keys the map by `q.question`). Values are the operator's own picks and "Other" text.
 *
 * A decision is also bound to the exact request it was shown for (SYNC_DESIGN §5.4): the card sends
 * the [fingerprint] of what it rendered, the client recomputes it under its lock, and a re-raised
 * request (same id, different content or turn) or another server never matches.
 *
 * No content from the request or the decision is logged or stored here.
 */
object ConsentGuard {

    /** `turnsById[activeTurnId].pendingApprovals[requestId]` (the map key is the id), or null. */
    fun pendingApproval(tree: JsObj?, requestId: String): JsObj? = activeTurn(tree)?.let { pendingIn(it, "pendingApprovals", requestId) }

    /** `turnsById[activeTurnId].pendingQuestions[requestId]`, or null. */
    fun pendingQuestion(tree: JsObj?, requestId: String): JsObj? = activeTurn(tree)?.let { pendingIn(it, "pendingQuestions", requestId) }

    /** The tree's `activeTurnId`, or null. */
    fun activeTurnId(tree: JsObj?): String? = (tree?.get("activeTurnId") as? JsStr)?.value

    /**
     * SYNC_DESIGN §5.4: the identity of the exact request a card shows. SHA-256 (lower-case hex) of
     * the UTF-8 bytes of the canonical JSON of `{"activeTurnId": …, "origin": …, "request": …}`,
     * where `request` is the pending object as the v128 reducer holds it
     * (`pendingApprovals[requestId]` / `pendingQuestions[requestId]`) and `origin` is the server
     * origin of the live socket. Canonical JSON = [JsCodec.canonical]: object keys sorted by UTF-16
     * code units at every depth, no whitespace, strings JSON-escaped, numbers as JS
     * `Number.prototype.toString` (`JSON.stringify`), non-finite numbers as `null`.
     */
    fun fingerprint(origin: String, activeTurnId: String, request: JsObj): String {
        val canonical = JsCodec.canonical(JsObj.of("activeTurnId" to JsStr(activeTurnId), "origin" to JsStr(origin), "request" to request))
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * True when the active turn already records an answer to [requestId] (`question_answered`, from
     * this device or another): the question is closed even before its `question_resolved` lands.
     */
    fun isAnswered(tree: JsObj?, requestId: String): Boolean =
        (activeTurn(tree)?.get("answeredQuestions") as? JsArr).orEmpty().any { ((it as? JsObj)?.get("requestId") as? JsStr)?.value == requestId }

    /**
     * Null when [choiceId] / [decision] / [granted] is a valid decision on [request]. The grant is
     * judged in its WIRE form: a value that does not survive encode → decode unchanged (for example
     * paths with `hasFileSystem = false`, which would encode to `{}`) is refused, and an exact grant
     * must encode to exactly the requested object.
     */
    fun checkApproval(request: JsObj, choiceId: String?, decision: String?, granted: GrantedPermissions?): ConsentResult? {
        if ((choiceId == null) == (decision == null)) return ConsentResult.InvalidChoice
        val wire = granted?.let { GrantedPermissions.from(it.toJsonObject()) }
        if (wire != granted) return ConsentResult.InvalidChoice
        val choices = (request["choices"] as? JsArr).orEmpty().mapNotNull { it as? JsObj }
        if (decision != null) {
            if (wire != null || choices.isNotEmpty()) return ConsentResult.InvalidChoice
            return if (decision == "allow" || decision == "deny") null else ConsentResult.InvalidChoice
        }
        val choice = choices.firstOrNull { (it["choiceId"] as? JsStr)?.value == choiceId } ?: return ConsentResult.InvalidChoice
        val requested = requestedPermissions(request)
        return when ((choice["permissionGrant"] as? JsStr)?.value) {
            "exact" -> if (requested != null && wire != null && wire.toJsonObject() == requested.toJsonObject()) null else ConsentResult.InvalidChoice
            "subset" -> if (requested != null && wire != null && isNonEmptySubset(wire, requested)) null else ConsentResult.InvalidChoice
            else -> if (wire == null) null else ConsentResult.InvalidChoice
        }
    }

    /**
     * Round 4: a card's IDENTITY (its saved state's key, and its lazy row's): SHA-256 hex of the
     * canonical JSON of `{"kind":"card","sessionId":…,"activeTurnId":…,"request":…}`. No server
     * origin (a drop and reconnect keep it), but the session (identical content in two sessions is two
     * cards). Distinct from [fingerprint], which binds the WIRE decision to the server as well.
     */
    fun cardIdentity(sessionId: String, activeTurnId: String, request: JsObj): String {
        val canonical = JsCodec.canonical(
            JsObj.of(
                "kind" to JsStr("card"),
                "sessionId" to JsStr(sessionId),
                "activeTurnId" to JsStr(activeTurnId),
                "request" to request,
            ),
        )
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Longest "Other" text an answer may carry (the card cuts its field there). */
    const val MAX_OTHER_CHARS = 4_000

    /**
     * How a question request's prompts map to answer SLOTS, as the web keys its state (by question
     * text, chat-view.tsx:965-1128): prompts repeating a text share one slot (its first prompt's
     * index), and a slot's options are the union of its prompts' labels in first-seen order, so a
     * pick is the same LABEL whichever page it was made on.
     */
    class QuestionSlots(val slotOf: List<Int>, val texts: Map<Int, String>, val labels: Map<Int, List<String>>, val single: Map<Int, Boolean>)

    fun questionSlots(request: JsObj): QuestionSlots {
        val prompts = (request["questions"] as? JsArr).orEmpty().map { it as? JsObj }
        val textOf = prompts.map { (it?.get("question") as? JsStr)?.value }
        val slotOf = textOf.mapIndexed { i, t -> if (t == null) i else textOf.indexOf(t) }
        val texts = LinkedHashMap<Int, String>()
        val labels = LinkedHashMap<Int, MutableList<String>>()
        val single = LinkedHashMap<Int, Boolean>()
        prompts.forEachIndexed { i, p ->
            val text = textOf[i] ?: return@forEachIndexed
            val slot = slotOf[i]
            texts.putIfAbsent(slot, text)
            val union = labels.getOrPut(slot) { ArrayList() }
            (p?.get("options") as? JsArr).orEmpty().forEach { o ->
                val label = ((o as? JsObj)?.get("label") as? JsStr)?.value ?: return@forEach
                if (label !in union) union.add(label)
            }
            // A slot takes several picks only when every prompt of it is multi-select.
            single[slot] = (single[slot] ?: false) || (p?.get("multiSelect") as? JsBool)?.value != true
        }
        return QuestionSlots(slotOf, texts, labels, single)
    }

    /** One slot's answer as the card holds it: indices into [QuestionSlots.labels] of [slot], and the "Other" text. */
    data class QuestionPick(val slot: Int, val picks: List<Int>, val other: String)

    /** The `question` frame's payload (`answers.answers` / `answers.response`), built here from the request. */
    data class QuestionReply(val answers: Map<String, String>, val response: String?)

    /**
     * L2: the answer is BUILT here, from the request and the operator's indices, exactly as the web's
     * `buildQuestionAnswers` builds it (chat-view.tsx:940-957): prompts in order; a [skipped] slot
     * left out; the slot's picked labels in pick order, the trimmed "Other" text last and also a line
     * of `response`; an empty result left out. Nothing is parsed. Null (refuse) when an index is not
     * one the request offered: a slot that is not a slot, a repeated slot, a pick out of range or
     * repeated, two picks on a single-select slot, or an "Other" text over [MAX_OTHER_CHARS].
     */
    fun buildAnswers(request: JsObj, picks: List<QuestionPick>, skipped: Set<Int>): QuestionReply? {
        val slots = questionSlots(request)
        val bySlot = HashMap<Int, QuestionPick>()
        for (p in picks) {
            val labels = slots.labels[p.slot] ?: return null
            if (slots.slotOf.getOrNull(p.slot) != p.slot || bySlot.put(p.slot, p) != null) return null
            if (p.picks.any { it !in labels.indices } || p.picks.toSet().size != p.picks.size) return null
            if (slots.single[p.slot] == true && p.picks.size > 1) return null
            if (p.other.length > MAX_OTHER_CHARS) return null
        }
        if (skipped.any { slots.slotOf.getOrNull(it) != it }) return null
        val answers = LinkedHashMap<String, String>()
        var response: String? = null
        // Every prompt in order, a repeated text included: the web does the same (its `response`
        // then carries that slot's Other text once per prompt).
        for (slot in slots.slotOf) {
            val text = slots.texts[slot] ?: continue
            if (slot in skipped) continue
            val pick = bySlot[slot]
            val parts = pick?.picks.orEmpty().map { slots.labels.getValue(slot)[it] }.toMutableList()
            val extra = com.tether.app.protocol.fold.jsTrim(pick?.other.orEmpty())
            if (extra.isNotEmpty()) {
                parts.add(extra)
                response = if (response != null) "$response\n$extra" else extra
            }
            if (parts.isEmpty()) continue
            answers[text] = parts.joinToString(", ")
        }
        return QuestionReply(answers, response)
    }

    /**
     * The request's `metadata.requestedPermissions` as the wire type, exactly as the web sends it
     * back for an "exact" grant (`grantedPermissions: requested`), or null when it has none.
     */
    fun requestedPermissions(request: JsObj): GrantedPermissions? {
        val requested = (request["metadata"] as? JsObj)?.get("requestedPermissions") as? JsObj ?: return null
        val fs = requested["fileSystem"] as? JsObj
        return GrantedPermissions(
            fileSystemRead = fs?.let { strings(it["read"]) },
            fileSystemWrite = fs?.let { strings(it["write"]) },
            hasFileSystem = fs != null,
            networkEnabled = ((requested["network"] as? JsObj)?.get("enabled") as? JsBool)?.value,
        )
    }

    /**
     * [granted] only narrows [requested]: read and write paths drawn from the requested lists, the
     * network only when it was requested (and never an explicit `false`, which the web never
     * sends), and at least one permission in all (chat-view.tsx:1178-1188 `subsetGrant`).
     */
    private fun isNonEmptySubset(granted: GrantedPermissions, requested: GrantedPermissions): Boolean {
        val read = granted.fileSystemRead.orEmpty()
        val write = granted.fileSystemWrite.orEmpty()
        if (!requested.fileSystemRead.orEmpty().containsAll(read)) return false
        if (!requested.fileSystemWrite.orEmpty().containsAll(write)) return false
        if (granted.hasFileSystem && read.isEmpty() && write.isEmpty()) return false
        when (granted.networkEnabled) {
            null -> Unit
            true -> if (requested.networkEnabled != true) return false
            false -> return false
        }
        return read.isNotEmpty() || write.isNotEmpty() || granted.networkEnabled == true
    }

    private fun activeTurn(tree: JsObj?): JsObj? {
        val active = activeTurnId(tree) ?: return null
        return (tree!!["turnsById"] as? JsObj)?.get(active) as? JsObj
    }

    private fun pendingIn(turn: JsObj, key: String, requestId: String): JsObj? = (turn[key] as? JsObj)?.get(requestId) as? JsObj

    private fun strings(value: JsValue?): List<String>? = (value as? JsArr)?.mapNotNull { (it as? JsStr)?.value }

    private fun JsArr?.orEmpty(): List<JsValue> = this ?: emptyList()
}

/**
 * The decisions this process has sent, each bound to (origin, session, active turn, request,
 * fingerprint): claimed at most once, so a double tap, a second card for the same request (the
 * session tab and a sub-agent tab), a recomposition or a reconnect can never put a second decision
 * for the same request on the wire, while a re-raised request (new fingerprint) is decidable once.
 * In memory only (a decision must not outlive the process that witnessed it, SYNC_DESIGN §5.4).
 *
 * Soft-bounded to [capacity]: past it, the oldest claims whose request is no longer pending
 * ([stillPending] false) are evicted; a claim that may still be pending (or whose server cannot be
 * checked) is never evicted. Not thread-safe: the caller holds its lock.
 */
internal class ConsentLedger(private val capacity: Int = DEFAULT_CAPACITY) {

    /** One sent decision; [epoch] = the socket epoch it went out on. */
    data class Entry(val origin: String, val sessionId: String, val activeTurnId: String, val requestId: String, val fingerprint: String, val epoch: Long) {
        val key: String get() = listOf(origin, sessionId, activeTurnId, requestId, fingerprint).joinToString(SEP)
    }

    private val claimed = LinkedHashMap<String, Entry>()

    fun contains(entry: Entry): Boolean = entry.key in claimed

    /** True when [entry] was not claimed yet (and now is). */
    fun claim(entry: Entry, stillPending: (Entry) -> Boolean): Boolean {
        if (claimed.containsKey(entry.key)) return false
        claimed[entry.key] = entry
        if (claimed.size > capacity) {
            val it = claimed.values.iterator()
            while (claimed.size > capacity && it.hasNext()) {
                val old = it.next()
                if (old !== entry && !stillPending(old)) it.remove()
            }
        }
        return true
    }

    /** Undo a claim whose frame never reached the socket (nothing was transmitted). */
    fun release(entry: Entry) {
        claimed.remove(entry.key)
    }

    val size: Int get() = claimed.size

    /** The [consentKey]s decided on [origin]. */
    fun keysFor(origin: String?): Set<String> =
        if (origin == null) emptySet() else claimed.values.filter { it.origin == origin }.mapTo(HashSet()) { consentKey(it.sessionId, it.requestId, it.fingerprint) }

    /** The [consentKey]s decided on [origin] on an EARLIER socket than [epoch] (delivery unconfirmed). */
    fun unconfirmedFor(origin: String?, epoch: Long): Set<String> =
        if (origin == null) emptySet() else claimed.values.filter { it.origin == origin && it.epoch < epoch }.mapTo(HashSet()) { consentKey(it.sessionId, it.requestId, it.fingerprint) }

    companion object {
        const val DEFAULT_CAPACITY = 1024
        private const val SEP = "\u0000"
    }
}

/** The key [TetherClient.decidedRequests] carries for a (session, request, fingerprint). */
fun consentKey(sessionId: String, requestId: String, fingerprint: String): String = "$sessionId\u0000$requestId\u0000$fingerprint"
