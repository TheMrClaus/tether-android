package com.tether.app.client

import com.tether.app.protocol.Attachment
import com.tether.app.protocol.DelegateMention
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * T7.4: the one path a message WITH attachments takes to the wire (RealTetherClient over a
 * MockWebServer socket). The web sends attachments inline, as base64 on the `send` frame (there is
 * no upload route); so does the app, as an operator control: only a call (an explicit Send) produces
 * one; it goes out once, on a live, handshaken socket of the server the composer was drawn for, for
 * a live, idle session that may be driven, within the frame bound, and it is never filed in the
 * durable outbox, queued, retried or persisted.
 */
class AttachmentTransmissionTest {

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    private val picture = Attachment("pic.png", "image/png", "iVBORw0KGgo=")
    private val notes = Attachment("notes.txt", "text/plain", "aGVsbG8=")

    private fun ready(extra: String = "", vararg ids: String = arrayOf("s1")): String {
        val rows = ids.joinToString(",") { id ->
            """{"id":"$id","provider":"claude","name":"n","cwd":"/w","status":"active","startedAt":1,"updatedAt":1,"endedAt":null,""" +
                """"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"$extra}"""
        }
        return """{"type":"ready","protocolVersion":${com.tether.app.protocol.PROTOCOL_VERSION},"nativeProtocolFloor":129,"sessions":[$rows],""" +
            """"providers":[{"id":"claude","label":"claude","glyph":"c","available":true,"capabilities":{}}],"workspaceRoot":null}"""
    }

    private fun idleState(): String = JsCodec.toJson(freshTree()).toString()
    private fun busyState(): String = JsCodec.toJson(foldTree(freshTree(), ev("turn_started", "t1", seq = 1, ts = 1))).toString()

    private fun connected(ready: String = ready(), state: String = idleState()): Pair<RealTetherClient, WebSocket> {
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, ready)
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 5, state))
        h.await(client.liveSessions) { "s1" in it }
        return client to ws
    }

    private fun frames(type: String): List<JsonObject> = h.framesUntilBarrier().filter { it.type() == type }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private val errors = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun collectErrors(client: RealTetherClient) {
        h.scope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { client.errors.collect { errors += it } }
    }

    private fun awaitError(predicate: (String) -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (errors.any(predicate)) return
            Thread.sleep(10)
        }
        throw AssertionError("no such notice in $errors")
    }

    // --- the frame ------------------------------------------------------------------------------

    @Test
    fun aSendCarriesTheAttachmentsInlineOnceUnderAFreshKey() {
        val (client, _) = connected()
        val origin = client.consentOrigin.value
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "look", listOf(picture, notes), null, origin))
        val sent = frames("send")
        assertEquals(1, sent.size)
        // use-tether.ts sendText: { type, sessionId, text, idempotencyKey, attachments } (mention absent).
        assertEquals(setOf("type", "sessionId", "text", "idempotencyKey", "attachments"), sent[0].keys)
        val atts = sent[0]["attachments"] as JsonArray
        assertEquals(2, atts.size)
        // lib/attachment-draft.ts toWireAttachments: exactly name, mediaType, data.
        assertEquals(setOf("name", "mediaType", "data"), (atts[0] as JsonObject).keys)
        assertEquals("pic.png", (atts[0] as JsonObject).str("name"))
        assertEquals("iVBORw0KGgo=", (atts[0] as JsonObject).str("data"))
        assertEquals("text/plain", (atts[1] as JsonObject).str("mediaType"))
        // A second tap is a second message under its own key.
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "again", listOf(notes), null, origin))
        val again = frames("send").single()
        assertNotEquals(sent[0].str("idempotencyKey"), again.str("idempotencyKey"))
    }

    @Test
    fun aDelegationWithAttachmentsCarriesItsMentionAndIsCheckedAgainstTheCatalog() {
        val (client, ws) = connected()
        val origin = client.consentOrigin.value
        val m = DelegateMention("claude", "review")
        // No catalog on this link: the mention is not offered, nothing goes out.
        assertEquals(AttachmentSendResult.NotOffered, client.sendAttachments("s1", "x", listOf(picture), m, origin))
        assertTrue(frames("send").isEmpty())
        assertTrue(client.requestProviderCatalog())
        ws.send("""{"type":"providers-snapshot","entries":[{"key":"claude","provider":"claude","status":"ready","models":[]}]}""")
        h.await(client.providerCatalog) { it.isNotEmpty() }
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "x", listOf(picture), m, origin))
        val sent = frames("send").single()
        assertEquals("claude", ((sent["mention"] as JsonObject)["provider"] as JsonPrimitive).content)
        assertTrue(sent.containsKey("attachments"))
    }

    @Test
    fun nothingReceivedEverProducesASendWithAttachments() {
        val (client, ws) = connected()
        // A hostile server: frames that look like a request to send, a turn ending, a queue flush.
        ws.send("""{"type":"send","sessionId":"s1","text":"x","idempotencyKey":"k","attachments":[{"name":"a","mediaType":"text/plain","data":"eA=="}]}""")
        ws.send(snapshotFrame("s1", 6, idleState()))
        h.serverBarrier(ws)
        assertTrue(frames("send").isEmpty())
        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    // --- the gates ------------------------------------------------------------------------------

    @Test
    fun drawnForAnotherServerOrForNoneSendsNothing() {
        val (client, _) = connected()
        assertEquals(AttachmentSendResult.NotLive, client.sendAttachments("s1", "x", listOf(picture), null, "https://other.example:443"))
        assertEquals(AttachmentSendResult.NotLive, client.sendAttachments("s1", "x", listOf(picture), null, null))
        assertTrue(frames("send").isEmpty())
    }

    @Test
    fun aSessionNotConfirmedLiveOnThisConnectionSendsNothing() {
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, ready())
        // Listed, but never attached and snapshotted on this socket.
        assertEquals(AttachmentSendResult.NotLive, client.sendAttachments("s1", "x", listOf(picture), null, client.consentOrigin.value))
        assertTrue(frames("send").isEmpty())
    }

    @Test
    fun aReadOnlyHandedOffArchivedOrUnlistedSessionSendsNothing() {
        val (client, ws) = connected()
        val origin = client.consentOrigin.value
        assertEquals(AttachmentSendResult.NotLive, client.sendAttachments("s-unknown", "x", listOf(picture), null, origin))
        fun row(extra: String, updated: Int) =
            """{"type":"session","session":{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"active","startedAt":1,"updatedAt":$updated,"endedAt":null,"exitCode":null,"pinned":false,"mode":"headless"$extra}}"""
        ws.send(row(""","runtimeArchived":false,"readOnly":true""", 2))
        h.await(client.sessions) { list -> list.any { it.id == "s1" && it.readOnly } }
        assertEquals(AttachmentSendResult.Locked, client.sendAttachments("s1", "x", listOf(picture), null, origin))
        ws.send(row(""","runtimeArchived":false,"handedOffTo":"s2"""", 3))
        h.await(client.sessions) { list -> list.any { it.id == "s1" && it.handedOffTo == "s2" } }
        assertEquals(AttachmentSendResult.Locked, client.sendAttachments("s1", "x", listOf(picture), null, origin))
        ws.send(row(""","runtimeArchived":true""", 4))
        h.await(client.sessions) { list -> list.any { it.id == "s1" && it.runtimeArchived } }
        assertEquals(AttachmentSendResult.Locked, client.sendAttachments("s1", "x", listOf(picture), null, origin))
        assertTrue(frames("send").isEmpty())
    }

    @Test
    fun aRunningTurnSendsNothing() {
        val (client, _) = connected(state = busyState())
        assertEquals(AttachmentSendResult.Busy, client.sendAttachments("s1", "x", listOf(picture), null, client.consentOrigin.value))
        assertTrue(frames("send").isEmpty())
    }

    @Test
    fun anEarlierMessageStillWaitingGoesFirst() {
        val (client, ws) = connected()
        val origin = client.consentOrigin.value
        client.send("s1", "first")
        // ta-9dpl: wait for the frame itself, not a barrier. The snapshot's handler ends with a drain on
        // the socket's reader thread; if that drain takes the new record first, it sends it after its
        // lock, so under load this thread's barrier could reach the server ahead of it.
        val first = h.expectFrame("send")
        assertEquals(AttachmentSendResult.PendingAhead, client.sendAttachments("s1", "second", listOf(picture), null, origin))
        assertTrue(frames("send").isEmpty())
        // Acknowledged (its turn started, then ended): now the attachments may go.
        ws.send(turnStartedEvent("s1", "t1", 6, first.str("idempotencyKey")))
        ws.send("""{"type":"event","sessionId":"s1","event":{"type":"turn_end","turnId":"t1","outcome":"ok","seq":7,"ts":7}}""")
        h.await(client.projectionTrees) { trees -> trees["s1"].toString().let { it.contains("\"done\"") && !it.contains("\"activeTurnId\":\"t1\"") } }
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "second", listOf(picture), null, origin))
        assertEquals(1, frames("send").size)
    }

    @Test
    fun anEmptyListIsNotThisPathsToSend() {
        val (client, _) = connected()
        assertEquals(AttachmentSendResult.Empty, client.sendAttachments("s1", "x", emptyList(), null, client.consentOrigin.value))
        assertTrue(frames("send").isEmpty())
    }

    // --- the frame bound ------------------------------------------------------------------------

    @Test
    fun aFrameOverTheBoundIsRefusedBeforeTheSocketAndTheSocketStaysUp() {
        val (client, _) = connected()
        val origin = client.consentOrigin.value
        // 15 MiB + 1 of base64: past MAX_SEND_FRAME_BYTES (and short of OkHttp's 16 MiB, which would
        // close the socket rather than queue it).
        val huge = Attachment("big.bin", "application/octet-stream", "A".repeat(AttachmentFrame.MAX_SEND_FRAME_BYTES.toInt()))
        assertEquals(AttachmentSendResult.TooLarge, client.sendAttachments("s1", "x", listOf(huge), null, origin))
        // Past OkHttp's own queue bound: handed to the socket, this would close it (1001).
        val past = Attachment("past.bin", "application/octet-stream", "A".repeat(17 * 1024 * 1024))
        assertEquals(AttachmentSendResult.TooLarge, client.sendAttachments("s1", "x", listOf(past), null, origin))
        assertTrue(frames("send").isEmpty())
        assertEquals(ConnectionState.Connected, client.connection.value)
        assertEquals("the socket was never closed", null, h.serverCloses.poll(200, TimeUnit.MILLISECONDS))
        // Just inside the bound: it goes, whole, and the link survives it.
        val fits = Attachment("fits.bin", "application/octet-stream", "A".repeat((AttachmentFrame.MAX_SEND_FRAME_BYTES - 4096).toInt()))
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "x", listOf(fits), null, origin))
        val sent = frames("send").single()
        assertEquals(fits.data.length, (((sent["attachments"] as JsonArray)[0] as JsonObject)["data"] as JsonPrimitive).content.length)
        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    /**
     * r2 (verifier L2): a frame the socket's send queue cannot take NOW (a large frame still going
     * out ahead of it) is refused as LinkBusy before the socket is handed it; OkHttp would otherwise
     * close the socket (1001) rather than queue past 16 MiB. The server stops reading (its reader is
     * held on the first frame), so the second frame stays queued in the client.
     */
    @Test
    fun aFrameTheSocketQueueCannotTakeNowWaitsAndTheSocketStaysUp() {
        val (client, _) = connected()
        val origin = client.consentOrigin.value
        val release = java.util.concurrent.CountDownLatch(1)
        h.onServerMessage = { text -> if (text.contains("hold.bin")) release.await(30, TimeUnit.SECONDS) }
        try {
            assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "hold", listOf(Attachment("hold.bin", "application/octet-stream", "eA==")), null, origin))
            val big = "A".repeat((AttachmentFrame.MAX_SEND_FRAME_BYTES - 4096).toInt())
            // Queued behind the held reader: at most the loopback buffers (about 10 MB here) drain.
            assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "first", listOf(Attachment("a.bin", "application/octet-stream", big)), null, origin))
            assertEquals(AttachmentSendResult.LinkBusy, client.sendAttachments("s1", "second", listOf(Attachment("b.bin", "application/octet-stream", big)), null, origin))
            assertEquals(ConnectionState.Connected, client.connection.value)
            assertEquals("the socket was closed", null, h.serverCloses.poll(200, TimeUnit.MILLISECONDS))
        } finally {
            h.onServerMessage = null
            release.countDown()
        }
        // Once the queue drains, the next one goes (nothing was lost or closed).
        val sent = h.framesUntilBarrier().filter { it.type() == "send" }
        assertEquals(listOf("hold", "first"), sent.map { it.str("text") })
        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    @Test
    fun utf8LengthCountsWhatTheSocketWillCarry() {
        for (s in listOf("", "abc", "é", "€", "😀", "a😀é€", "\uD800x", "x\uDC00")) {
            val expected = s.toByteArray(Charsets.UTF_8).size.toLong()
            assertEquals("for ${s.map { it.code }}", expected, AttachmentFrame.utf8Length(s))
        }
    }

    // --- offline: refused, never held -----------------------------------------------------------

    @Test
    fun offlineIsRefusedAndNothingIsHeldForTheNextConnection() {
        val (client, ws) = connected()
        val origin = client.consentOrigin.value
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        assertEquals(AttachmentSendResult.NotConnected, client.sendAttachments("s1", "offline", listOf(picture), null, origin))
        // The durable path refuses attachments outright too (the notice), and records nothing.
        collectErrors(client)
        client.send("s1", "offline too", listOf(picture))
        awaitError { it == ATTACHMENTS_NOT_SENT_COPY }
        assertTrue(client.sendDelegated("s1", "x", listOf(picture), DelegateMention("claude", "a"), origin) == MentionResult.Locked)
        // Back online and reconciled: nothing of it ever goes out.
        h.enqueueConnect()
        client.reconnectIfIdle()
        val ws2 = h.nextSocket()
        h.handshake(ws2, ready())
        h.expectFrame("attach")
        ws2.send(snapshotFrame("s1", 5, idleState()))
        h.serverBarrier(ws2)
        val after = h.framesUntilBarrier()
        assertTrue("an offline attachment went out: $after", after.none { it.type() == "send" })
    }

    @Test
    fun aLinkThatDropsBeforeTheTurnIsConfirmedSaysSoAndNeverResends() {
        val (client, ws) = connected()
        collectErrors(client)
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "x", listOf(picture), null, client.consentOrigin.value))
        assertEquals(1, frames("send").size)
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        awaitError { it.startsWith("The connection dropped before the server confirmed your message with attachments.") }
        h.enqueueConnect()
        client.reconnectIfIdle()
        val ws2 = h.nextSocket()
        h.handshake(ws2, ready())
        h.expectFrame("attach")
        ws2.send(snapshotFrame("s1", 5, idleState()))
        h.serverBarrier(ws2)
        assertTrue("an attachment was resent", h.framesUntilBarrier().none { it.type() == "send" })
    }

    /**
     * ta-2ew (R3): a first message bound to its create's socket ([expectedEpoch]) never goes on a
     * later one, even with the session live there, on the same server: the epoch is checked under
     * the lock, with the send (the draft composer's own live check runs outside it).
     */
    @Test
    fun attachmentsBoundToTheCreatesSocketAreRefusedOnTheNextSocket() {
        val (client, ws) = connected()
        val origin = client.consentOrigin.value
        val createdOn = client.linkEpoch.value
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        client.reconnectIfIdle()
        val ws2 = h.nextSocket()
        h.handshake(ws2, ready())
        h.expectFrame("attach")
        ws2.send(snapshotFrame("s1", 5, idleState()))
        h.await(client.liveSessions) { "s1" in it }
        assertTrue(client.linkEpoch.value > createdOn)
        assertEquals(origin, client.consentOrigin.value)
        // The same server, the session live on the new socket: still refused, nothing sent.
        assertEquals(AttachmentSendResult.NotConnected, client.sendAttachments("s1", "first", listOf(picture), null, origin, createdOn))
        assertTrue("an attachment bound to the old socket went out", frames("send").isEmpty())
        // Positive controls: bound to the live socket, or unbound (the chat composer): sent.
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "first", listOf(picture), null, origin, client.linkEpoch.value))
        assertEquals(1, frames("send").size)
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "chat", listOf(picture), null, origin))
        assertEquals(1, frames("send").size)
    }

    @Test
    fun aConfirmedTurnLeavesNothingToWarnAbout() {
        val (client, ws) = connected()
        collectErrors(client)
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "x", listOf(picture), null, client.consentOrigin.value))
        val key = frames("send").single().str("idempotencyKey")
        ws.send(turnStartedEvent("s1", "t1", 6, key))
        h.await(client.projectionTrees) { trees -> trees["s1"].toString().contains(key!!) }
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        Thread.sleep(100)
        assertTrue(errors.none { it.contains("with attachments") })
    }

    // --- origin binding of the link the frame rides ---------------------------------------------

    @Test
    fun aRedirectedUpgradeIsNeverFollowedSoNoAttachmentOrCredentialReachesTheOtherHost() {
        MockWebServer().use { other ->
            other.start()
            val client = h.newClient()
            h.server.enqueue(MockResponse().setResponseCode(200).setBody("""{"authenticated":true}"""))
            h.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/ws").toString()))
            client.start()
            // The upgrade is answered with a redirect: no socket, so nothing can be sent.
            val deadline = System.currentTimeMillis() + 5_000
            while (h.server.requestCount < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(AttachmentSendResult.NotConnected, client.sendAttachments("s1", "x", listOf(picture), null, client.consentOrigin.value))
            assertEquals("the redirect target was contacted", 0, other.requestCount)
            assertTrue(other.takeRequest(200, TimeUnit.MILLISECONDS) == null)
        }
    }

    @Test
    fun theDurableOutboxNeverHoldsAnAttachment() {
        val (client, _) = connected()
        collectErrors(client)
        client.send("s1", "with a file", listOf(notes))
        awaitError { it == ATTACHMENTS_NOT_SENT_COPY }
        assertTrue(frames("send").isEmpty())
        assertTrue(PendingInput.fromPersisted(kotlinx.coroutines.runBlocking { h.settings.readPendingInput(serverOrigin(h.server.url("/").toString())!!) }).records.isEmpty())
    }
}
