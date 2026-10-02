package com.tether.app.client

/*
 * Multi-host node registry (protocol v109, tether issue #154 N0): the data layer
 * behind Settings -> Nodes (the screen itself is T10.3).
 *
 * Web reference: hooks/use-tether.ts keeps `nodes` (replaced by every `nodes`
 * frame) and `nodeResult` (the last `node-result`, whatever request it answers),
 * and sends node-add / node-remove / node-probe as thin fire-and-forget frames.
 * The native client does the same, and additionally tags each request with a
 * `requestId` (the server echoes it on the reply) so a caller can await its own
 * answer. Nothing is queued, persisted or retried, exactly as on the web.
 *
 * T10.3: every request names the server ORIGIN the caller drew its screen from
 * and goes out only on a live socket opened for that origin (the other
 * Settings writes' rule): a credential typed for one server never reaches
 * another. A reply can only end a request on the socket that carried it (the
 * waiters end, LinkLost, when that socket goes), so an answer on a later socket
 * never counts.
 */

/**
 * A peer's credential bundle, as pasted from `tether-control-token mint --kind node`.
 * It carries the peer's node BEARER, so it is a secret for the peer:
 *
 *  - not a data class, and [toString] is redacted: it cannot reach a log, an
 *    exception message or a crash report by string interpolation;
 *  - the text is not readable back from outside this module (the UI hands it
 *    over and forgets it, like the web form's `setCredential("")` after submit);
 *  - the client never stores it: it is encoded into ONE `node-add` frame, handed
 *    to the socket, and no reference is kept after the send (it never enters the
 *    durable send queue, the settings store, or any state flow).
 *
 * Leading/trailing whitespace is trimmed, as the web form does before sending.
 */
class NodeCredential(value: String) {
    internal val value: String = value.trim()

    /**
     * T10.3: whether this credential is [text] (trimmed, as sent), compared in constant time.
     * It confirms a guess and never gives the value back, so a caller outside this module (the
     * Settings screen's tests) can check what it handed over without the text being readable.
     * r2 (security F5): tests only.
     */
    @androidx.annotation.VisibleForTesting
    fun matches(text: String): Boolean =
        java.security.MessageDigest.isEqual(value.toByteArray(Charsets.UTF_8), text.trim().toByteArray(Charsets.UTF_8))

    override fun toString(): String = "NodeCredential(***)"
}

/**
 * The last `node-result` frame, exactly the web's `nodeResult` state
 * (use-tether.ts:211): [ok], the node it named ([nodeId], when any), the
 * server's readable status/error ([message], never a stack), and when it arrived
 * ([at], epoch ms, client clock). The web shows `message ?? (ok ? "Done." : "That did not work.")`.
 */
data class NodeActionResult(
    val ok: Boolean,
    val nodeId: String?,
    val message: String?,
    val at: Long,
)

/** How one [TetherClient.addNode] / [TetherClient.removeNode] / [TetherClient.probeNode] call ended. */
sealed interface NodeRequestOutcome {

    /**
     * The server's `node-result` for THIS request. [NodeActionResult.ok] may be
     * false: an unreadable or tampered bundle, "No such node.", a probe that
     * failed. The same value is also published as [TetherClient.nodeResult], and
     * the server broadcast the updated `nodes` list just before it.
     */
    data class Answered(val result: NodeActionResult) : NodeRequestOutcome

    /**
     * The server threw while handling this request and answered with a generic
     * `error` frame carrying its requestId. Like every error frame it is also
     * emitted on [TetherClient.errors] (the web shows it as its global error).
     */
    data class ServerError(val message: String) : NodeRequestOutcome

    /**
     * Refused before anything was sent. Either the credential is empty (the web
     * form keeps its button disabled then; nothing is emitted), or a field breaks
     * the server's own validator bounds (lib/protocol-validate.mjs), which the
     * web would surface as a global error; that [message] is emitted on
     * [TetherClient.errors] too.
     */
    data class Invalid(val message: String) : NodeRequestOutcome

    /**
     * The link is not live, so nothing was sent: the web's send() refusal
     * ([NodeRegistryRules.NOT_SENT_MESSAGE], also emitted on [TetherClient.errors]).
     * Not queued and not retried.
     */
    data object NotSent : NodeRequestOutcome

    /**
     * The frame went out but the link dropped before an answer. The server may
     * or may not have acted; the `nodes` list the next connection receives shows
     * the truth. Not retried.
     */
    data object LinkLost : NodeRequestOutcome

    /**
     * No answer within [NodeRegistryRules.REQUEST_TIMEOUT_MS]. Not retried. A late
     * `node-result` still lands in [TetherClient.nodeResult], as on the web.
     */
    data object TimedOut : NodeRequestOutcome
}

/** The server's bounds for the node frames, checked before sending. */
object NodeRegistryRules {
    /**
     * Longer than the server can take: node-add and node-probe await a peer probe
     * of two sequential fetches, each bounded by 8 s (lib/node-client.mjs
     * DEFAULT_TIMEOUT_MS), before the server answers.
     */
    const val REQUEST_TIMEOUT_MS: Long = 45_000

    /** use-tether.ts send(): the socket is not OPEN. */
    const val NOT_SENT_MESSAGE: String = "The secure link is reconnecting. Your input was not sent."

    const val EMPTY_CREDENTIAL_MESSAGE: String = "Paste the credential bundle from the other host."

    // lib/protocol-validate.mjs "node-add" / "node-remove" / "node-probe". JS
    // `.length` counts UTF-16 units, as Kotlin's does; label/baseUrl are bounded
    // in UTF-8 BYTES (isBoundedString).
    const val CREDENTIAL_MAX_LENGTH: Int = 4096
    const val LABEL_MAX_BYTES: Int = 64
    const val BASE_URL_MAX_BYTES: Int = 512
    const val NODE_ID_MAX_LENGTH: Int = 128

    /**
     * The web's node-add as the form + hook build it (components/nodes-settings.tsx
     * submit(), use-tether.ts addNode): label and baseUrl are trimmed and omitted
     * when empty. Returns the fields to send, or the refusal. Messages never
     * include the credential.
     */
    internal fun nodeAdd(credential: NodeCredential, label: String?, baseUrl: String?): NodeAddFields {
        val secret = credential.value
        if (secret.isEmpty()) return NodeAddFields.Refused(EMPTY_CREDENTIAL_MESSAGE, emit = false)
        if (secret.length > CREDENTIAL_MAX_LENGTH) {
            return NodeAddFields.Refused("node-add.credential must be a bounded non-empty string", emit = true)
        }
        val l = label?.trim()?.takeIf { it.isNotEmpty() }
        if (l != null && l.utf8Bytes() > LABEL_MAX_BYTES) {
            return NodeAddFields.Refused("node-add.label must be a bounded string (<=64 chars)", emit = true)
        }
        val b = baseUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (b != null && b.utf8Bytes() > BASE_URL_MAX_BYTES) {
            return NodeAddFields.Refused("node-add.baseUrl must be a bounded string (<=512 chars)", emit = true)
        }
        return NodeAddFields.Ok(l, b)
    }

    /** node-remove / node-probe `nodeId`: a bounded non-empty string. Null = acceptable. */
    internal fun nodeIdProblem(frameType: String, nodeId: String): String? =
        if (nodeId.isEmpty() || nodeId.length > NODE_ID_MAX_LENGTH) "$frameType.nodeId must be a bounded non-empty string" else null

    private fun String.utf8Bytes(): Int = toByteArray(Charsets.UTF_8).size
}

internal sealed interface NodeAddFields {
    data class Ok(val label: String?, val baseUrl: String?) : NodeAddFields
    data class Refused(val message: String, val emit: Boolean) : NodeAddFields
}
