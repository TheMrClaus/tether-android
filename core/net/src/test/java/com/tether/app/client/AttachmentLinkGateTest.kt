package com.tether.app.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T7.4 r2 (verifier L2): every clause of the link half of the attachment send gate, alone. Today
 * most are implied by the live check (a session is confirmed live only on an open, handshaken socket
 * of the outbox's server, and every path that ends one of those also empties the live set), so a
 * black-box test cannot reach a state where only one of them decides; each is kept as defence in
 * depth, and each is pinned here, so removing any one of them fails a test.
 */
class AttachmentLinkGateTest {
    private val a = "http://a.example:80"
    private val b = "http://b.example:80"

    private fun link(
        socketBound: Boolean = true,
        socketOrigin: String? = a,
        socketOpen: Boolean = true,
        handshakeDone: Boolean = true,
        pendingLoaded: Boolean = true,
        halted: Boolean = false,
        pendingOrigin: String? = a,
        sessionLive: Boolean = true,
    ) = AttachmentLink(socketBound, socketOrigin, socketOpen, handshakeDone, pendingLoaded, halted, pendingOrigin, sessionLive)

    @Test
    fun aLiveHandshakenSocketOfTheOutboxsServerDrawnForItMaySend() {
        assertNull(attachmentLinkRefusal(link(), a))
    }

    @Test
    fun noSocketIsNotConnected() = assertEquals(AttachmentSendResult.NotConnected, attachmentLinkRefusal(link(socketBound = false), a))

    @Test
    fun aSocketWithNoOriginIsNotConnected() = assertEquals(AttachmentSendResult.NotConnected, attachmentLinkRefusal(link(socketOrigin = null, pendingOrigin = null), null))

    @Test
    fun aSocketNotOpenYetIsNotConnected() = assertEquals(AttachmentSendResult.NotConnected, attachmentLinkRefusal(link(socketOpen = false), a))

    @Test
    fun aSocketBeforeItsHandshakeIsNotConnected() = assertEquals(AttachmentSendResult.NotConnected, attachmentLinkRefusal(link(handshakeDone = false), a))

    @Test
    fun anOutboxNotLoadedYetIsNotConnected() = assertEquals(AttachmentSendResult.NotConnected, attachmentLinkRefusal(link(pendingLoaded = false), a))

    @Test
    fun aHaltedClientIsNotConnected() = assertEquals(AttachmentSendResult.NotConnected, attachmentLinkRefusal(link(halted = true), a))

    @Test
    fun drawnForAnotherServerOrNoneIsNotLive() {
        assertEquals(AttachmentSendResult.NotLive, attachmentLinkRefusal(link(), b))
        assertEquals(AttachmentSendResult.NotLive, attachmentLinkRefusal(link(), null))
    }

    @Test
    fun anOutboxBoundToAnotherServerThanTheSocketIsNotLive() {
        assertEquals(AttachmentSendResult.NotLive, attachmentLinkRefusal(link(pendingOrigin = b), a))
        assertEquals(AttachmentSendResult.NotLive, attachmentLinkRefusal(link(pendingOrigin = null), a))
    }

    @Test
    fun aSessionNotConfirmedOnThisSocketIsNotLive() = assertEquals(AttachmentSendResult.NotLive, attachmentLinkRefusal(link(sessionLive = false), a))

    @Test
    fun theQueueTakesAFrameUpToTheSocketsBoundAndNotOneByteMore() {
        val bound = AttachmentFrame.SOCKET_QUEUE_BYTES
        assertNull(attachmentQueueRefusal(0, AttachmentFrame.MAX_SEND_FRAME_BYTES))
        assertNull(attachmentQueueRefusal(bound - 100, 100))
        assertEquals(AttachmentSendResult.LinkBusy, attachmentQueueRefusal(bound - 100, 101))
        assertNull(attachmentQueueRefusal(AttachmentFrame.MAX_SEND_FRAME_BYTES, AttachmentFrame.MAX_SEND_FRAME_BYTES))
        assertEquals(AttachmentSendResult.LinkBusy, attachmentQueueRefusal(AttachmentFrame.MAX_SEND_FRAME_BYTES + 1, AttachmentFrame.MAX_SEND_FRAME_BYTES))
    }
}
