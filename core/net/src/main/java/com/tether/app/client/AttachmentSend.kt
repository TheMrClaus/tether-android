package com.tether.app.client

import com.tether.app.protocol.Attachment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * T7.4: the size rules of a `send` that carries attachments (v15). The web sends attachments inline,
 * as base64 on the WebSocket `send` frame (lib/attachment-draft.ts, chat-view.tsx addFiles); there
 * is no upload route. The app does the same, with one native bound the web does not need.
 *
 * OkHttp's WebSocket CLOSES the connection (code 1001) when one outgoing message would take its send
 * queue past [OKHTTP_QUEUE_BYTES] (RealWebSocket.MAX_QUEUE_SIZE, 16 MiB). The web's own caps (18 MB
 * of files, about 24 MB once base64-encoded) would pass that, so the app also bounds the ENCODED
 * frame: at most [MAX_SEND_FRAME_BYTES], and never more than the queue can take at the moment of the
 * send. A frame over either bound is refused before anything goes out, so an oversized frame never
 * reaches the socket. The queue check and the send are taken together under the client's lock, but
 * other frames (the resync `attach`, pings, the durable outbox's sends) are handed to the socket
 * outside that lock, so in a rare race one of them can grow the queue between the check and the
 * send. OkHttp's own check then refuses the frame: it closes the socket (1001) and nothing of the
 * frame is sent; the send reports [AttachmentSendResult.NotConnected] and is withdrawn (the web's
 * rollback: the composer keeps the message and its files). The server's own frame bound (protocol-validate
 * LIMITS.WS_FRAME_BYTES, 32 MiB, its `maxPayload`) is not announced to the client; the app's bound
 * is below it, so it is never the one that decides.
 *
 * Logged divergence (coordinator decision, T7.4): a set of large non-image files the web accepts
 * can be refused here (the image shrink below keeps pictures well inside the bound).
 */
object AttachmentFrame {
    /** OkHttp RealWebSocket.MAX_QUEUE_SIZE: a send that would pass it closes the socket. */
    const val OKHTTP_QUEUE_BYTES: Long = 16L * 1024 * 1024

    /** The largest `send` frame with attachments the app puts on the wire (1 MiB below the queue bound). */
    const val MAX_SEND_FRAME_BYTES: Long = OKHTTP_QUEUE_BYTES - 1L * 1024 * 1024

    /**
     * What the attachments of one message may take in the frame while they are staged: the frame
     * bound less a reserve for the text and the envelope (a 64 KB message JSON-escaped at worst six
     * bytes a character is 384 KB). The send itself measures the real frame.
     */
    const val STAGING_BUDGET_BYTES: Long = MAX_SEND_FRAME_BYTES - 512L * 1024

    /** The JSON an attachment adds to the frame: `{"name":…,"mediaType":…,"data":…},` at most. */
    fun wireBytes(attachment: Attachment): Long =
        utf8Length(attachment.data) + 6L * utf8Length(attachment.name) + 6L * utf8Length(attachment.mediaType) + 40L

    /** What [attachments] add to a frame, by [wireBytes]. */
    fun wireBytes(attachments: List<Attachment>): Long = attachments.sumOf { wireBytes(it) }

    /** The UTF-8 size of [text], counted without encoding it (a frame can be megabytes). */
    fun utf8Length(text: String): Long {
        var n = 0L
        var i = 0
        val len = text.length
        while (i < len) {
            val c = text[i]
            when {
                c.code < 0x80 -> n += 1
                c.code < 0x800 -> n += 2
                Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(text[i + 1]) -> {
                    n += 4
                    i++
                }
                // A lone surrogate is encoded as '?' (String.toByteArray / okio encodeUtf8): 1 byte.
                Character.isSurrogate(c) -> n += 1
                else -> n += 3
            }
            i++
        }
        return n
    }
}

/**
 * T7.4 r2: the link half of [TetherClient.sendAttachments]'s gate, as the client holds it under its
 * lock: the socket (bound, its origin, open, handshaken), the outbox (loaded, and bound to the
 * socket's server), whether connecting is halted (stopped, a version halt, backgrounded), and
 * whether the session is confirmed live on this socket. Pure, so every clause is tested alone
 * ([attachmentLinkRefusal]): today most of them are implied by the live check (a session is only
 * live on an open, handshaken socket of the outbox's server), and each is kept as defence in depth.
 */
internal class AttachmentLink(
    val socketBound: Boolean,
    val socketOrigin: String?,
    val socketOpen: Boolean,
    val handshakeDone: Boolean,
    val pendingLoaded: Boolean,
    val halted: Boolean,
    val pendingOrigin: String?,
    val sessionLive: Boolean,
)

/** Why [link] may not carry a message with attachments drawn for [expectedOrigin] (null: it may). */
internal fun attachmentLinkRefusal(link: AttachmentLink, expectedOrigin: String?): AttachmentSendResult? = when {
    !link.socketBound || link.socketOrigin == null || !link.socketOpen || !link.handshakeDone || !link.pendingLoaded || link.halted ->
        AttachmentSendResult.NotConnected
    expectedOrigin == null || expectedOrigin != link.socketOrigin || link.pendingOrigin != link.socketOrigin -> AttachmentSendResult.NotLive
    !link.sessionLive -> AttachmentSendResult.NotLive
    else -> null
}

/** OkHttp closes the socket rather than queue past its bound: a frame that would pass it now waits. */
internal fun attachmentQueueRefusal(queuedBytes: Long, frameBytes: Long): AttachmentSendResult? =
    if (queuedBytes + frameBytes > AttachmentFrame.OKHTTP_QUEUE_BYTES) AttachmentSendResult.LinkBusy else null

/** What an attempt to send a message with attachments came to ([TetherClient.sendAttachments]). */
enum class AttachmentSendResult {
    /** Handed to the live socket of the server the composer was drawn for. */
    Sent,

    /** No live, handshaken socket (offline, reconnecting), or the frame could not be handed to it. */
    NotConnected,

    /** Connected, but the session is not confirmed live on this connection, or the composer was drawn for another server. */
    NotLive,

    /** The session is read-only, handed off, archived, or not listed (fail closed). */
    Locked,

    /** A turn is running: attachments ride an idle send only (the server never queues them). */
    Busy,

    /** The encoded frame is over [AttachmentFrame.MAX_SEND_FRAME_BYTES]. */
    TooLarge,

    /** The socket is still sending a large frame: this one would not fit its queue now. */
    LinkBusy,

    /** The delegate mention is not offered by the current catalog (T7.3's rule). */
    NotOffered,

    /** Nothing to send (no attachments: that is [TetherClient.send]'s path). */
    Empty,
}

/**
 * One file or picture staged in the composer: the wire [attachment] (its `data` base64, already
 * shrunk when it is a large picture) and [sizeBytes], the raw bytes that go out (the web's
 * `AttachmentDraft.size`, for the total cap and the size label). [id] is a stable key.
 */
data class StagedAttachment(val id: Long, val attachment: Attachment, val sizeBytes: Long)

/**
 * T7.4: the composer's staged attachments. They belong to ONE session on ONE server ([origin],
 * [sessionId]) and live in memory only: they survive a rotation (the view model holds this) but not
 * process death (the web and T1.3 never persist attachment bytes, SYNC_DESIGN §5.2), and they are
 * never sent by anything but an explicit Send.
 *
 * [items] answers only for the session and server they were staged for, so a server switch or a
 * session switch can never show (or send) another's; [clear] drops them (server switch, sign-out,
 * the session locking).
 */
class StagedAttachments {
    data class Set(val origin: String?, val sessionId: String, val items: List<StagedAttachment>)

    private val state = MutableStateFlow<Set?>(null)
    val current: StateFlow<Set?> = state.asStateFlow()
    private var nextId = 1L

    /** Bumped by every [clear]: a pick still being read when the set was dropped is not added after it. */
    @Volatile var generation: Long = 0L
        private set

    fun items(origin: String?, sessionId: String?): List<StagedAttachment> {
        val s = state.value ?: return emptyList()
        return if (sessionId != null && s.sessionId == sessionId && s.origin == origin) s.items else emptyList()
    }

    /** A fresh id for a newly staged item. */
    @Synchronized
    fun newId(): Long = nextId++

    /** Replace what is staged for ([origin], [sessionId]) with [items]; another session's set is dropped. */
    fun set(origin: String?, sessionId: String, items: List<StagedAttachment>) {
        state.value = if (items.isEmpty()) null else Set(origin, sessionId, items)
    }

    fun remove(origin: String?, sessionId: String, id: Long) {
        val s = state.value ?: return
        if (s.origin != origin || s.sessionId != sessionId) return
        set(origin, sessionId, s.items.filterNot { it.id == id })
    }

    fun clear() {
        generation++
        state.value = null
    }
}
