package com.tether.app.client

import com.tether.app.protocol.Attachment
import com.tether.app.protocol.helpers.PendingInput as Web
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsCodec
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-coik.19 (web issue #135 at 90fbb9f): the client's send states, as the chat draws them.
 * `pendingSends` is lib/pending-input.mjs describePending over the store (use-tether.ts :409-424
 * syncPendingState): `sending` while on the wire, `waiting` while the link is down, the record is
 * not (re)transmitted yet, or nothing came back for UNACKED_CLOSE_MS. `failedSends` is
 * use-tether.ts :450-497: a send given up on, until dismissed, retracted by a later proof of
 * acceptance, or dropped with its archived session.
 */
class SendStatusClientTest {

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    private val picture = Attachment("pic.png", "image/png", "iVBORw0KGgo=") // 8 bytes behind it
    private val notes = Attachment("notes.txt", "text/plain", "aGVsbG8=") // 5 bytes behind it

    private fun ready(archived: Boolean = false): String =
        """{"type":"ready","protocolVersion":${com.tether.app.protocol.PROTOCOL_VERSION},"nativeProtocolFloor":129,"sessions":[""" +
            sessionJson(archived) +
            """],"providers":[{"id":"claude","label":"claude","glyph":"c","available":true,"capabilities":{}}],"workspaceRoot":null}"""

    private fun sessionJson(archived: Boolean): String =
        """{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"active","startedAt":1,"updatedAt":1,"endedAt":null,""" +
            """"exitCode":null,"pinned":false,"runtimeArchived":$archived,"mode":"headless"}"""

    private fun idleState(): String = JsCodec.toJson(freshTree()).toString()

    /** A journal that holds one turn started under [key]: the durable proof it was accepted. */
    private fun acceptedState(key: String): String =
        """{"tetherSessionId":"s1","provider":"claude","cwd":"/w","status":"ready","turnOrder":["t1"],""" +
            """"turnsById":{"t1":{"turnId":"t1","status":"completed","idempotencyKey":"$key","blocks":[],"blocksById":{}}},""" +
            """"activeTurnId":null,"queuedMessages":[]}"""

    private fun connected(sweepIntervalMs: Long = 3_600_000): Pair<RealTetherClient, WebSocket> {
        val client = h.newClient(sweepIntervalMs = sweepIntervalMs)
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, ready())
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 5, idleState()))
        h.await(client.liveSessions) { "s1" in it }
        return client to ws
    }

    private fun sentKey(): String = (h.expectFrame("send")["idempotencyKey"] as JsonPrimitive).content

    private fun dropLink(client: RealTetherClient, ws: WebSocket) {
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
    }

    private fun reconnect(client: RealTetherClient): WebSocket {
        h.enqueueConnect()
        client.reconnectIfIdle()
        val next = h.nextSocket()
        h.handshake(next, ready())
        h.expectFrame("attach")
        return next
    }

    /** A text send given up on: filed, the link drops, and it ages out (MAX_AGE_MS) on the sweep. */
    private fun givenUp(text: String): Pair<RealTetherClient, String> {
        val (client, ws) = connected(sweepIntervalMs = 50)
        client.send("s1", text)
        val key = sentKey()
        dropLink(client, ws)
        h.now.addAndGet(Web.MAX_AGE_MS.toLong())
        h.await(client.failedSends) { list -> list.any { it.key == key } }
        return client to key
    }

    // --- sending / waiting --------------------------------------------------------------------

    @Test
    fun aSendOnALiveLinkReadsSendingUntilItsTurnStarts() {
        val (client, ws) = connected()
        client.send("s1", "hello")
        val key = sentKey()
        val row = h.await(client.pendingSends) { it.isNotEmpty() }.single()
        assertEquals(PendingSendRow(key, "s1", PendingInput.KIND_SEND, "hello", SendStatus.Sending, 0, 0, 0.0), row)
        // use-tether.ts :1050-1063: the live ack clears it; the real turn bubble takes its place.
        ws.send(turnStartedEvent("s1", "t1", 6, key))
        h.await(client.pendingSends) { it.isEmpty() }
        assertTrue(client.failedSends.value.isEmpty())
    }

    @Test
    fun aQueuedMessageIsARowToo() {
        val (client, _) = connected()
        client.queueAdd("s1", "later")
        h.expectFrame("queue-add")
        val row = h.await(client.pendingSends) { it.isNotEmpty() }.single()
        assertEquals(PendingInput.KIND_QUEUE, row.kind)
        assertEquals("later", row.text)
        assertEquals(SendStatus.Sending, row.status)
    }

    @Test
    fun aDroppedLinkReadsWaitingUntilTheReconnectSendsItAgain() {
        val (client, ws) = connected()
        client.send("s1", "through the tunnel")
        val key = sentKey()
        h.await(client.pendingSends) { rows -> rows.singleOrNull()?.status == SendStatus.Sending }
        dropLink(client, ws)
        // use-tether.ts :727-729 / pending-input.mjs :397: link down -> waiting.
        h.await(client.pendingSends) { rows -> rows.singleOrNull()?.status == SendStatus.Waiting }
        val ws2 = reconnect(client)
        // Open again, but not re-sent before the session's snapshot: still waiting.
        h.serverBarrier(ws2)
        assertEquals(SendStatus.Waiting, client.pendingSends.value.single().status)
        ws2.send(snapshotFrame("s1", 5, idleState()))
        assertEquals(key, sentKey())
        h.await(client.pendingSends) { rows -> rows.singleOrNull()?.status == SendStatus.Sending }
    }

    @Test
    fun noFrameBackForTheWatchdogWindowReadsWaiting() {
        val (client, _) = connected(sweepIntervalMs = 50)
        client.send("s1", "into the void")
        sentKey()
        h.await(client.pendingSends) { rows -> rows.singleOrNull()?.status == SendStatus.Sending }
        // pending-input.mjs :399-400: in flight past UNACKED_CLOSE_MS with no inbound frame either.
        h.now.addAndGet(Web.UNACKED_CLOSE_MS.toLong() + 1)
        h.await(client.pendingSends) { rows -> rows.singleOrNull()?.status == SendStatus.Waiting }
    }

    @Test
    fun anAttachmentSendCarriesItsCountsAndSize() {
        val (client, _) = connected()
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "look", listOf(picture, notes), null, client.consentOrigin.value))
        sentKey()
        val row = h.await(client.pendingSends) { it.isNotEmpty() }.single()
        assertEquals(2, row.attachmentCount)
        assertEquals(1, row.imageCount)
        assertEquals(13.0, row.bytes, 0.0)
        assertEquals(SendStatus.Sending, row.status)
    }

    @Test
    fun anAttachmentSendRefusedOfflineShowsNoBubble() {
        val (client, ws) = connected()
        dropLink(client, ws)
        // use-tether.ts :677-683: the composer keeps the draft and the chips; no bubble at all.
        assertEquals(AttachmentSendResult.NotConnected, client.sendAttachments("s1", "x", listOf(picture), null, client.consentOrigin.value))
        assertTrue(client.pendingSends.value.isEmpty())
        assertTrue(client.failedSends.value.isEmpty())
    }

    // --- not delivered ------------------------------------------------------------------------

    @Test
    fun aSendGivenUpOnBecomesAFailedBubbleWithItsTextUntilDismissed() {
        val (client, key) = givenUp("lost words")
        val failed = client.failedSends.value.single()
        assertEquals(FailedSend(key, "s1", PendingInput.KIND_SEND, "lost words", 0, 0, 0.0, FailedSendReason.Expired), failed)
        assertTrue("the record is gone from the pending rows", client.pendingSends.value.isEmpty())
        client.dismissFailedSend("not-a-key")
        assertEquals(1, client.failedSends.value.size)
        client.dismissFailedSend(key)
        assertTrue(client.failedSends.value.isEmpty())
    }

    @Test
    fun anAttachmentSendGivenUpOnKeepsItsCounts() {
        val (client, ws) = connected(sweepIntervalMs = 50)
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "aged", listOf(picture, notes), null, client.consentOrigin.value))
        sentKey()
        dropLink(client, ws)
        h.now.addAndGet(Web.MAX_AGE_MS.toLong())
        val failed = h.await(client.failedSends) { it.isNotEmpty() }.single()
        assertEquals("aged", failed.text)
        assertEquals(2, failed.attachmentCount)
        assertEquals(1, failed.imageCount)
        assertEquals(13.0, failed.bytes, 0.0)
        assertEquals(FailedSendReason.Expired, failed.reason)
    }

    @Test
    fun aSnapshotProvingTheKeyWasAcceptedTakesTheFailedBubbleBack() {
        val (client, key) = givenUp("it did land")
        val ws = reconnect(client)
        ws.send(snapshotFrame("s1", 6, acceptedState(key)))
        h.await(client.failedSends) { it.isEmpty() }
    }

    @Test
    fun aLiveAckOfTheKeyTakesTheFailedBubbleBack() {
        val (client, key) = givenUp("acked late")
        val ws = reconnect(client)
        ws.send(snapshotFrame("s1", 5, idleState()))
        h.serverBarrier(ws)
        assertEquals("an idle snapshot proves nothing", 1, client.failedSends.value.size)
        ws.send(turnStartedEvent("s1", "t1", 6, key))
        h.await(client.failedSends) { it.isEmpty() }
    }

    @Test
    fun anArchivedSessionDropsItsFailedBubbles() {
        val (client, _) = givenUp("archived")
        val ws = reconnect(client)
        ws.send("""{"type":"session","session":${sessionJson(archived = true)}}""")
        h.await(client.failedSends) { it.isEmpty() }
    }

    @Test
    fun signingOutDropsTheFailedBubbles() {
        val (client, _) = givenUp("signed out")
        client.stop()
        h.await(client.failedSends) { it.isEmpty() }
    }

    // --- the pure record (use-tether.ts :450-471) ----------------------------------------------

    @Test
    fun recordFailedKeepsTheFirstCopyOfAKeyAndTheNewestThirty() {
        var store = PendingInput.emptyStore()
        for (i in 0 until 40) store = PendingInput.addRecord(store, "k$i", PendingInput.KIND_SEND, "s1", "t$i", 1L).store
        val records = store.records
        var failed = PendingInput.recordFailed(emptyList(), records.take(1), FailedSendReason.Link)
        failed = PendingInput.recordFailed(failed, records.take(1), FailedSendReason.Expired)
        assertEquals(listOf(FailedSendReason.Link), failed.map { it.reason })
        failed = PendingInput.recordFailed(failed, records.drop(1), FailedSendReason.Expired)
        assertEquals(MAX_FAILED_SENDS, failed.size)
        assertEquals((10 until 40).map { "k$it" }, failed.map { it.key })
        assertTrue(PendingInput.recordFailed(failed, emptyList(), FailedSendReason.Expired) === failed)
    }
}
