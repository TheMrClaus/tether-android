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
    /**
     * ta-delivery A2: percent (0..99) of this message's photos that reached the server, while they are still
     * uploading in the background; null when nothing of it is uploading.
     */
    val uploadPercent: Int? = null,
    /**
     * ta-delivery V1 (spec 4.1b I item 4): true only when this record's attachments sit in the on-disk spool
     * (RealTetherClient's `attachmentSpool != null`), so "kept on this device" is true. False = held in memory only.
     */
    val keptOnDevice: Boolean = false,
)

/** pending-input.mjs :371-372: the two states of an unresolved send. */
enum class SendStatus {
    /** Filed and on the wire, awaiting the server's journaled proof. */
    Sending,

    /** tth-xyp9: the photos are still uploading, so nothing is on the wire yet (the web's "uploading", which outranks "waiting"). */
    Uploading,

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
    /** ta-delivery A2: for [FailedSendReason.Upload], the server's own reason ("An attachment is limited to 9 MiB."). */
    val detail: String? = null,
    /** ta-delivery A2: a later Send again may work (a transient reason), as opposed to a refusal that would repeat. */
    val retryable: Boolean = false,
    /**
     * ta-delivery A9 / tth-87eo: [TetherClient.sendAgain] can re-dispatch this one: the client still holds ALL of its
     * content. Text alone, or attachments whose bytes it retained in memory (reasons Expired, Link, and a retryable
     * Upload), and never a delegate mention, never [FailedSendReason.PhotoLost]. False once the retention bound
     * released the bytes ([released]).
     */
    val canSendAgain: Boolean = false,
    /**
     * tth-87eo (AW.5): the one-message memory bound released this row's attachment bytes, so it lost Send again for good.
     * Only this mark (never `canSendAgain == false`, which a delegate-mention row also is) picks the released label.
     */
    val released: Boolean = false,
)

/**
 * use-tether.ts :54 `reason: "expired" | "link"`: which copy the failed bubble shows. [PhotoLost] is
 * the app's own (ta-delivery A1): the record's spooled attachment files were gone at restore. [Upload] (A2): a photo's
 * upload to the server failed for good; [FailedSend.detail] says why.
 */
enum class FailedSendReason { Expired, Link, PhotoLost, Upload }

/** use-tether.ts :58 MAX_FAILED_SENDS: retained failed sends, oldest falling off first. */
const val MAX_FAILED_SENDS = 30
