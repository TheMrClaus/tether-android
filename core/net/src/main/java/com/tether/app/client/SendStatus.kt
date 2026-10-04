package com.tether.app.client

/**
 * ta-coik.19 (web issue #135, lib/pending-input.mjs 90fbb9f :367-426 `describePending`): one
 * unresolved operator send as the chat shows it, a "sending" or "waiting for link" ghost bubble.
 * "delivered" is absent by construction: acceptance removes the record, so the row disappears and
 * the real turn bubble folded from the journal takes its place.
 */
data class PendingSendRow(
    val key: String,
    val sessionId: String,
    /** [PendingInput.KIND_SEND] or [PendingInput.KIND_QUEUE]. */
    val kind: String,
    val text: String,
    val status: SendStatus,
    val attachmentCount: Int,
    val imageCount: Int,
    /** The raw size behind the attachments' base64 (pending-input.mjs :365 base64Bytes). */
    val bytes: Double,
)

/** pending-input.mjs :371-372: the two states of an unresolved send. */
enum class SendStatus {
    /** Filed and on the wire, awaiting the server's journaled proof. */
    Sending,

    /** The link is down or presumed half-open; the record goes out again on reconnect. */
    Waiting,
}

/**
 * use-tether.ts 90fbb9f :42-55: a send abandoned as undeliverable (retries exhausted, aged out or
 * evicted), carrying the operator's own text so it can be copied back. Kept until the operator
 * dismisses it, or until the server proves that key was accepted after all (:479-497).
 */
data class FailedSend(
    val key: String,
    val sessionId: String,
    val kind: String,
    val text: String,
    val attachmentCount: Int,
    val imageCount: Int,
    val bytes: Double,
    val reason: FailedSendReason,
)

/** use-tether.ts :54 `reason: "expired" | "link"`: which copy the failed bubble shows. */
enum class FailedSendReason { Expired, Link }

/** use-tether.ts :58 MAX_FAILED_SENDS: retained failed sends, oldest falling off first. */
const val MAX_FAILED_SENDS = 30
