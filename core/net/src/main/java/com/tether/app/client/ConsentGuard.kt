package com.tether.app.client

import com.tether.app.protocol.GrantedPermissions
import com.tether.app.protocol.tree.JsArr
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
 * No content from the request or the decision is logged or stored here.
 */
object ConsentGuard {

    /** `turnsById[activeTurnId].pendingApprovals[requestId]`, or null. */
    fun pendingApproval(tree: JsObj?, requestId: String): JsObj? = activeTurn(tree)?.let { pendingIn(it, "pendingApprovals", requestId) }

    /** `turnsById[activeTurnId].pendingQuestions[requestId]`, or null. */
    fun pendingQuestion(tree: JsObj?, requestId: String): JsObj? = activeTurn(tree)?.let { pendingIn(it, "pendingQuestions", requestId) }

    /**
     * True when the active turn already records an answer to [requestId] (`question_answered`, from
     * this device or another): the question is closed even before its `question_resolved` lands.
     */
    fun isAnswered(tree: JsObj?, requestId: String): Boolean =
        (activeTurn(tree)?.get("answeredQuestions") as? JsArr).orEmpty().any { ((it as? JsObj)?.get("requestId") as? JsStr)?.value == requestId }

    /** Null when [choiceId] / [decision] / [granted] is a valid decision on [request]. */
    fun checkApproval(request: JsObj, choiceId: String?, decision: String?, granted: GrantedPermissions?): ConsentResult? {
        if ((choiceId == null) == (decision == null)) return ConsentResult.InvalidChoice
        val choices = (request["choices"] as? JsArr).orEmpty().mapNotNull { it as? JsObj }
        if (decision != null) {
            if (granted != null || choices.isNotEmpty()) return ConsentResult.InvalidChoice
            return if (decision == "allow" || decision == "deny") null else ConsentResult.InvalidChoice
        }
        val choice = choices.firstOrNull { (it["choiceId"] as? JsStr)?.value == choiceId } ?: return ConsentResult.InvalidChoice
        val requested = requestedPermissions(request)
        return when ((choice["permissionGrant"] as? JsStr)?.value) {
            "exact" -> if (requested != null && granted == requested) null else ConsentResult.InvalidChoice
            "subset" -> if (requested != null && granted != null && isNonEmptySubset(granted, requested)) null else ConsentResult.InvalidChoice
            else -> if (granted == null) null else ConsentResult.InvalidChoice
        }
    }

    /** Null when every key of [answers] is one of [request]'s question texts. */
    fun checkQuestion(request: JsObj, answers: Map<String, String>): ConsentResult? {
        val texts = (request["questions"] as? JsArr).orEmpty().mapNotNullTo(HashSet()) { ((it as? JsObj)?.get("question") as? JsStr)?.value }
        return if (answers.keys.all { it in texts }) null else ConsentResult.InvalidChoice
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
        val active = (tree?.get("activeTurnId") as? JsStr)?.value ?: return null
        return (tree["turnsById"] as? JsObj)?.get(active) as? JsObj
    }

    private fun pendingIn(turn: JsObj, key: String, requestId: String): JsObj? = (turn[key] as? JsObj)?.get(requestId) as? JsObj

    private fun strings(value: JsValue?): List<String>? = (value as? JsArr)?.mapNotNull { (it as? JsStr)?.value }

    private fun JsArr?.orEmpty(): List<JsValue> = this ?: emptyList()
}

/**
 * The requests this process has decided, per server origin: each (origin, session, request) is
 * claimed at most once, so a double tap, a second card for the same request (the session tab and a
 * sub-agent tab), a recomposition or a reconnect can never put a second decision on the wire.
 * In memory only (a decision must not outlive the process that witnessed it, SYNC_DESIGN §5.4),
 * bounded to the newest [capacity] claims. Not thread-safe: the caller holds its lock.
 */
internal class ConsentLedger(private val capacity: Int = DEFAULT_CAPACITY) {
    private val claimed = LinkedHashSet<String>()

    /** True when [key] was not claimed yet (and now is). */
    fun claim(key: String): Boolean {
        if (!claimed.add(key)) return false
        while (claimed.size > capacity) claimed.remove(claimed.first())
        return true
    }

    fun contains(key: String): Boolean = key in claimed

    /** Undo a claim whose frame never reached the socket (nothing was transmitted). */
    fun release(key: String) {
        claimed.remove(key)
    }

    /** The claimed (session, request) keys of [origin], as [consentKey]s. */
    fun keysFor(origin: String?): Set<String> {
        if (origin == null) return emptySet()
        val prefix = "$origin$SEP"
        return claimed.asSequence().filter { it.startsWith(prefix) }.map { it.substring(prefix.length) }.toSet()
    }

    companion object {
        const val DEFAULT_CAPACITY = 1024
        private const val SEP = '\u0000'

        fun key(origin: String, sessionId: String, requestId: String): String = "$origin$SEP${consentKey(sessionId, requestId)}"
    }
}

/** The key [TetherClient.decidedRequests] carries for a (session, request) pair. */
fun consentKey(sessionId: String, requestId: String): String = "$sessionId\u0000$requestId"
