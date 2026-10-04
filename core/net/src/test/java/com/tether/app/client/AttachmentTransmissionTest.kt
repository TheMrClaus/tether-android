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
import kotlinx.serialization.json.put
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
 * a live, idle session that may be driven, within the frame bound. ta-coik.3: like the web's
 * (use-tether.ts filePending :643-695) it is then kept in memory only, never persisted, and resent
 * under its same key after the session is reconciled on a new connection, in order with the other
 * sends to that session, until the web's MAX_TRIES / MAX_AGE_MS; offline it is refused (rolled back).
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

    /**
     * ta-coik.3: no app-only refusal while an earlier message to the session waits (the web's
     * filePending files it and drains); on the wire the earlier one stays ahead.
     */
    @Test
    fun anEarlierMessageStillWaitingNoLongerBlocksAndStaysAhead() {
        val (client, _) = connected()
        val origin = client.consentOrigin.value
        client.send("s1", "first")
        val first = h.expectFrame("send")
        assertEquals("first", first.str("text"))
        // "first" is in flight, unconfirmed: the attachments go too, after it.
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "second", listOf(picture), null, origin))
        val second = frames("send").single()
        assertEquals("second", second.str("text"))
        assertTrue(second.containsKey("attachments"))
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
        // 32 MiB of base64 plus the envelope: past MAX_SEND_FRAME_BYTES, the server's own bound
        // (protocol-validate LIMITS.WS_FRAME_BYTES; its ws `maxPayload` would drop it with 1009).
        val huge = Attachment("big.bin", "application/octet-stream", "A".repeat(AttachmentFrame.MAX_SEND_FRAME_BYTES.toInt()))
        assertEquals(AttachmentSendResult.TooLarge, client.sendAttachments("s1", "x", listOf(huge), null, origin))
        assertTrue(frames("send").isEmpty())
        assertEquals(ConnectionState.Connected, client.connection.value)
        assertEquals("the socket was never closed", null, h.serverCloses.poll(200, TimeUnit.MILLISECONDS))
        // Just inside the bound: it goes, whole (in fragments the server reassembles), and the link survives it.
        val fits = Attachment("fits.bin", "application/octet-stream", "A".repeat((AttachmentFrame.MAX_SEND_FRAME_BYTES - 4096).toInt()))
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "x", listOf(fits), null, origin))
        val sent = frames("send").single()
        assertEquals(fits.data.length, (((sent["attachments"] as JsonArray)[0] as JsonObject)["data"] as JsonPrimitive).content.length)
        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    /**
     * ta-coik.16: everything the web's caps allow goes. Before, the app refused a frame over 15 MiB
     * (OkHttp's 16 MiB queue), so a selection the web sends (up to 18 MB of files, 24 MiB of
     * base64) came back TooLarge. Here: the web's total, 24 MiB of base64 across three files each
     * under the 12 MiB per-file bound (protocol-validate ATTACHMENT_DATA_B64_BYTES), arrives
     * byte for byte, in order with the frames around it, and the link stays up.
     */
    @Test
    fun theWebsLargestSendGoesWholeAndInOrder() {
        val (client, _) = connected()
        val origin = client.consentOrigin.value
        val perFile = 8 * 1024 * 1024
        val files = listOf('a', 'b', 'c').map { c -> Attachment("$c.bin", "application/octet-stream", c.toString().repeat(perFile)) }
        assertEquals(24L * 1024 * 1024, files.sumOf { it.data.length.toLong() })
        client.pin("before", true)
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "large", files, null, origin))
        client.pin("after", true)
        val out = h.framesUntilBarrier().filter { it.type() == "pin" || it.type() == "send" }
        assertEquals(listOf("pin", "send", "pin"), out.map { it.type() })
        assertEquals(listOf("before", "after"), listOf(out[0].str("sessionId"), out[2].str("sessionId")))
        val got = (out[1]["attachments"] as JsonArray).map { (it as JsonObject)["data"] as JsonPrimitive }
        assertEquals(files.map { it.data }, got.map { it.content })
        assertEquals(ConnectionState.Connected, client.connection.value)
        assertEquals("the socket was never closed", null, h.serverCloses.poll(200, TimeUnit.MILLISECONDS))
    }

    /**
     * r2 (verifier L2): a frame the socket's send queue cannot take NOW (large frames still going
     * out ahead of it) is refused as LinkBusy before the socket is handed it; the socket would
     * otherwise close (1001) rather than queue past its bound (two of the largest frames). The server
     * stops reading (its reader is held on the first frame), so the frames after it stay queued in
     * the client.
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
            assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "second", listOf(Attachment("b.bin", "application/octet-stream", big)), null, origin))
            assertEquals(AttachmentSendResult.LinkBusy, client.sendAttachments("s1", "third", listOf(Attachment("c.bin", "application/octet-stream", big)), null, origin))
            assertEquals(ConnectionState.Connected, client.connection.value)
            assertEquals("the socket was closed", null, h.serverCloses.poll(200, TimeUnit.MILLISECONDS))
        } finally {
            h.onServerMessage = null
            release.countDown()
        }
        // Once the queue drains, the next one goes (nothing was lost or closed).
        val sent = h.framesUntilBarrier().filter { it.type() == "send" }
        assertEquals(listOf("hold", "first", "second"), sent.map { it.str("text") })
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

    // --- redelivery (ta-coik.3: use-tether.ts filePending / drainPending, pending-input.mjs) -----

    /** Drop [ws], reconnect, handshake; the session is re-attached and NOT yet reconciled. */
    private fun reconnect(client: RealTetherClient, ws: WebSocket): WebSocket {
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        client.reconnectIfIdle()
        val next = h.nextSocket()
        h.handshake(next, ready())
        h.expectFrame("attach")
        return next
    }

    /** The idle snapshot that reconciles s1 on [ws] (it holds none of our keys), then every send after it. */
    private fun reconcile(ws: WebSocket): List<JsonObject> {
        ws.send(snapshotFrame("s1", 5, idleState()))
        h.serverBarrier(ws)
        return frames("send")
    }

    @Test
    fun aSentAttachmentIsResentOnceUnderItsSameKeyOnlyAfterTheReconnectReconciles() {
        val (client, ws) = connected()
        collectErrors(client)
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "look", listOf(picture, notes), null, client.consentOrigin.value))
        val original = frames("send").single()
        val ws2 = reconnect(client, ws)
        // The exactly-once gate: nothing before the session's snapshot on this connection.
        h.serverBarrier(ws2)
        assertTrue("resent before the reconcile", frames("send").isEmpty())
        val resent = reconcile(ws2).single()
        assertEquals("the SAME key (server dedupe)", original.str("idempotencyKey"), resent.str("idempotencyKey"))
        assertEquals(original, resent)
        // Once per try: a later snapshot on the same connection does not send it again.
        ws2.send(snapshotFrame("s1", 5, idleState()))
        h.serverBarrier(ws2)
        assertTrue(frames("send").isEmpty())
        assertTrue("no lost-message notice: it is being delivered", errors.none { it.contains("could not be delivered") })
    }

    @Test
    fun aResendKeepsItsOrderWithTheOtherSendsToTheSession() {
        val (client, ws) = connected()
        val origin = client.consentOrigin.value
        client.send("s1", "first")
        h.expectFrame("send")
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "second", listOf(picture), null, origin))
        frames("send")
        client.send("s1", "third")
        h.expectFrame("send")
        val ws2 = reconnect(client, ws)
        assertEquals(listOf("first", "second", "third"), reconcile(ws2).map { it.str("text") })
    }

    @Test
    fun anAcceptedAttachmentIsNeverResent() {
        val (client, ws) = connected()
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "x", listOf(picture), null, client.consentOrigin.value))
        val key = frames("send").single().str("idempotencyKey")
        val ws2 = reconnect(client, ws)
        // The snapshot's turns hold its key: the durable acknowledgement, nothing goes out.
        val accepted = JsCodec.toJson(foldTree(freshTree(), ev("turn_started", "t1", seq = 1, ts = 1) { put("idempotencyKey", key) })).toString()
        ws2.send(snapshotFrame("s1", 5, accepted))
        h.serverBarrier(ws2)
        assertTrue(frames("send").isEmpty())
    }

    /**
     * The web's rollback (use-tether.ts filePending :675-692): a record with attachments the socket
     * did not take (here: closing, after the server's close was answered, before the client saw the
     * socket go) is withdrawn and refused, so the composer keeps it; it never goes out later.
     */
    @Test
    fun anAttachmentTheSocketDidNotTakeIsRolledBackAndNeverSentLater() {
        val (client, ws) = connected()
        val origin = client.consentOrigin.value
        val answer = java.util.concurrent.atomic.AtomicReference<AttachmentSendResult>()
        client.raceHook = { point, _ ->
            if (point == RacePoint.SocketClosing && answer.get() == null) {
                answer.set(client.sendAttachments("s1", "into a closing socket", listOf(picture), null, origin))
            }
        }
        h.enqueueConnect()
        ws.close(1000, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        client.raceHook = null
        assertEquals(AttachmentSendResult.NotConnected, answer.get())
        client.reconnectIfIdle()
        val ws2 = h.nextSocket()
        h.handshake(ws2, ready())
        h.expectFrame("attach")
        val after = reconcile(ws2)
        assertTrue("a rolled-back attachment went out: $after", after.isEmpty())
    }

    /** The gate alone (no sweeper): the web's MAX_TRIES attempts, then never again. */
    @Test
    fun itGoesOutAtMostTheWebsFiveTimes() {
        val (client, first) = connected()
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "five tries", listOf(picture), null, client.consentOrigin.value))
        val key = frames("send").single().str("idempotencyKey")
        var ws = first
        repeat(com.tether.app.protocol.helpers.PendingInput.MAX_TRIES - 1) {
            ws = reconnect(client, ws)
            assertEquals(listOf(key), reconcile(ws).map { it.str("idempotencyKey") })
        }
        ws = reconnect(client, ws)
        assertTrue("a sixth try went out", reconcile(ws).isEmpty())
    }

    @Test
    fun itIsGivenUpAfterTheWebsFiveTriesWithANotice() {
        val client = h.newClient(sweepIntervalMs = 50)
        h.enqueueConnect()
        client.start()
        var ws = h.nextSocket()
        h.handshake(ws, ready())
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 5, idleState()))
        h.await(client.liveSessions) { "s1" in it }
        collectErrors(client)
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "five tries", listOf(picture), null, client.consentOrigin.value))
        val key = frames("send").single().str("idempotencyKey")
        var tries = 1
        repeat(com.tether.app.protocol.helpers.PendingInput.MAX_TRIES - 1) {
            ws = reconnect(client, ws)
            val sent = reconcile(ws)
            assertEquals(listOf(key), sent.map { it.str("idempotencyKey") })
            tries++
        }
        assertEquals(com.tether.app.protocol.helpers.PendingInput.MAX_TRIES, tries)
        ws = reconnect(client, ws)
        assertTrue("a sixth try went out", reconcile(ws).isEmpty())
        awaitError { it.startsWith("This message and its 1 attachment could not be delivered and were not sent") }
        ws = reconnect(client, ws)
        assertTrue(reconcile(ws).isEmpty())
    }

    @Test
    fun itIsGivenUpAfterTheWebsTenMinutesWithANotice() {
        val client = h.newClient(sweepIntervalMs = 50)
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, ready())
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 5, idleState()))
        h.await(client.liveSessions) { "s1" in it }
        collectErrors(client)
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "aged", listOf(picture, notes), null, client.consentOrigin.value))
        frames("send")
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        h.now.addAndGet(com.tether.app.protocol.helpers.PendingInput.MAX_AGE_MS.toLong())
        awaitError { it.startsWith("This message and its 2 attachments could not be delivered and were not sent: \"aged\"") }
        h.enqueueConnect()
        client.reconnectIfIdle()
        val ws2 = h.nextSocket()
        h.handshake(ws2, ready())
        h.expectFrame("attach")
        assertTrue("an expired attachment went out", reconcile(ws2).isEmpty())
    }

    @Test
    fun aRecordWithAttachmentsIsNeverWrittenToDisk() {
        val (client, ws) = connected()
        val origin = serverOrigin(h.server.url("/").toString())!!
        val marker = Attachment("secret.bin", "application/octet-stream", "U0VDUkVUTUFSS0VS")
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "with bytes", listOf(marker), null, client.consentOrigin.value))
        frames("send")
        // A text record filed after it IS persisted: wait for that write, which carries the store as it
        // is (the attachment record included, in memory), then read the slot.
        client.send("s1", "plain words")
        h.expectFrame("send")
        val deadline = System.currentTimeMillis() + 20_000
        var raw: String? = null
        while (System.currentTimeMillis() < deadline) {
            raw = kotlinx.coroutines.runBlocking { h.settings.readPendingInput(origin) }
            if (raw?.contains("plain words") == true) break
            Thread.sleep(10)
        }
        assertTrue("the text record was persisted: $raw", raw!!.contains("plain words"))
        assertTrue("attachment bytes reached the disk: $raw", !raw.contains("U0VDUkVUTUFSS0VS") && !raw.contains("secret.bin") && !raw.contains("with bytes"))
        assertEquals(listOf("plain words"), PendingInput.fromPersisted(raw).records.map { it.text })
        // And it is still held in memory: a reconnect resends it, ahead of the text.
        val ws2 = reconnect(client, ws)
        assertEquals(listOf("with bytes", "plain words"), reconcile(ws2).map { it.str("text") })
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
    fun aConfirmedTurnLeavesNothingToWarnAboutOrResend() {
        val (client, ws) = connected()
        collectErrors(client)
        assertEquals(AttachmentSendResult.Sent, client.sendAttachments("s1", "x", listOf(picture), null, client.consentOrigin.value))
        val key = frames("send").single().str("idempotencyKey")
        ws.send(turnStartedEvent("s1", "t1", 6, key))
        h.await(client.projectionTrees) { trees -> trees["s1"].toString().contains(key!!) }
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        Thread.sleep(100)
        assertTrue(errors.none { it.contains("with attachments") || it.contains("could not be delivered") })
        // Acknowledged live (turn_started carried its key): nothing to resend on the next connection.
        h.enqueueConnect()
        client.reconnectIfIdle()
        val ws2 = h.nextSocket()
        h.handshake(ws2, ready())
        h.expectFrame("attach")
        assertTrue(reconcile(ws2).isEmpty())
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
