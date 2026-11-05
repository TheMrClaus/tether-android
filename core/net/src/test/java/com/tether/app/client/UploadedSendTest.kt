package com.tether.app.client

import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-delivery A2 end to end on a RealTetherClient: a photo uploaded at pick goes out as `attachmentIds` and never as
 * inline bytes; a message whose photos are still uploading is held (never sent half-way) and goes out by id once they
 * are stored; a failed upload is a failed `upload` bubble; the spooled bytes live until the server acknowledged the send.
 */
class UploadedSendTest {
    private val h = ConnectionHarness()
    private lateinit var settings: DiskSettings
    private val scopes = ArrayList<CoroutineScope>()
    private val dirs = ArrayList<File>()
    private val bytes = ByteArray(1500) { (it % 199).toByte() }
    private val photo = com.tether.app.protocol.Attachment("pic.png", "image/png", Base64.getEncoder().encodeToString(bytes))

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        h.close()
        dirs.forEach { it.deleteRecursively() }
    }

    private class Rig(val client: RealTetherClient, val hub: AttachmentUploads, val spool: File, val uploads: File, val scheduler: FakeUploadScheduler, val ws: WebSocket)

    private fun rig(): Rig {
        h.server.start()
        val url = h.server.url("/").toString().trimEnd('/')
        settings = DiskSettings(InMemorySettings(url, initialCookie = "cookie"), url)
        val base = java.nio.file.Files.createTempDirectory("a2-client").toFile().also { dirs += it }
        val spool = File(base, "spool")
        val uploads = File(base, "uploads")
        val scheduler = FakeUploadScheduler()
        val hub = AttachmentUploads(uploads, scheduler)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val client = RealTetherClient(
            settings = settings,
            httpClient = OkHttpClient(),
            scope = scope,
            clock = { h.now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = ManualScheduler(),
            attachmentSpool = AttachmentSpool(spool),
            attachmentUploads = hub,
        )
        h.client = client
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 1, """{"tetherSessionId":"s1","provider":"claude","cwd":"/w","status":"ready","turnOrder":[],"turnsById":{},"activeTurnId":null,"queuedMessages":[]}"""))
        h.await(client.projections) { it.containsKey("s1") }
        return Rig(client, hub, spool, uploads, scheduler, ws)
    }

    private fun stored(id: String) = MockResponse().setBody("""{"id":"$id","name":"pic.png","mediaType":"image/png","bytes":${bytes.size},"sha256":"abc"}""")

    /** The next `send` frame (the half-open probe's ping, sent when a message is filed, is skipped). */
    private fun nextSend(): JsonObject {
        while (true) {
            val f = h.frame()
            if (f.type() == "send") return f
        }
    }

    private fun awaitDir(dir: File, predicate: (List<String>) -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate(dir.list()?.toList().orEmpty())) return
            Thread.sleep(10)
        }
        throw AssertionError("dir never matched: ${dir.list()?.toList()}")
    }

    @Test
    fun anUploadedPhotoGoesOutAsAttachmentIdsAndNeverAsInlineBytesAndItsFilesLiveUntilTheServerAcknowledges() {
        val r = rig()
        val origin = r.client.consentOrigin.value
        val id = r.hub.begin(origin, "s1", photo.name, photo.mediaType, bytes)!!
        h.server.enqueue(stored(id))
        assertEquals(UploadRunResult.Done, runBlocking { r.hub.run(id) })

        assertEquals(AttachmentSendResult.Sent, r.client.sendUploaded("s1", "look", listOf(photo), listOf(id), null, origin))
        val frame = h.expectFrame("send")
        assertEquals(listOf(id), (frame["attachmentIds"] as JsonArray).map { it.jsonPrimitive.content })
        assertFalse("never both: no inline bytes on the frame", frame.containsKey("attachments"))
        val key = frame["idempotencyKey"]!!.jsonPrimitive.content

        // The upload finished, the send is on the wire, but nothing has acknowledged it: the bytes are kept (a restart redelivers).
        h.await(settings.written) { it != null && it.contains(key) }
        assertTrue(File(r.uploads, "$id/data.bin").isFile)
        awaitDir(r.spool) { it == listOf(key) }

        r.ws.send(turnStartedEvent("s1", "t1", 2, key))
        awaitDir(r.uploads) { it.isEmpty() }
        awaitDir(r.spool) { it.isEmpty() }
    }

    @Test
    fun aMessageWhosePhotoIsStillUploadingIsHeldThenGoesOutByIdWhenStored() {
        val r = rig()
        val origin = r.client.consentOrigin.value
        val id = r.hub.begin(origin, "s1", photo.name, photo.mediaType, bytes)!!
        assertEquals(AttachmentSendResult.Sent, r.client.sendUploaded("s1", "look", listOf(photo), listOf(id), null, origin))
        h.serverBarrier(r.ws)
        assertTrue("held: nothing is sent while the photo is uploading", h.framesUntilBarrier().none { it.type() == "send" })
        // V1 (spec 4.1b I item 4): with a spool the device really keeps the bytes, so the row may say so.
        h.await(r.client.pendingSends) { rows -> rows.singleOrNull()?.let { it.uploadPercent != null && it.keptOnDevice } == true }

        h.server.enqueue(stored(id))
        runBlocking { r.hub.run(id) }
        val frame = nextSend()
        assertEquals(listOf(id), (frame["attachmentIds"] as JsonArray).map { it.jsonPrimitive.content })
        assertFalse(frame.containsKey("attachments"))
        h.await(r.client.pendingSends) { rows -> rows.singleOrNull()?.uploadPercent == null }
    }

    @Test
    fun aFailedUploadIsAFailedUploadBubbleWithTheServersReasonAndNothingIsSent() {
        val r = rig()
        val origin = r.client.consentOrigin.value
        val id = r.hub.begin(origin, "s1", photo.name, photo.mediaType, bytes)!!
        assertEquals(AttachmentSendResult.Sent, r.client.sendUploaded("s1", "look", listOf(photo), listOf(id), null, origin))
        h.server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"That attachment id already holds different bytes.","code":"c"}"""))
        runBlocking { r.hub.run(id) }
        h.await(r.client.failedSends) { it.isNotEmpty() }
        val failed = r.client.failedSends.value.single()
        assertEquals(FailedSendReason.Upload, failed.reason)
        assertEquals("That attachment id already holds different bytes.", failed.detail)
        assertEquals("look", failed.text)
        assertFalse(failed.retryable)
        h.serverBarrier(r.ws)
        assertTrue(h.framesUntilBarrier().none { it.type() == "send" })
        assertTrue(r.client.pendingSends.value.isEmpty())
        // The record is gone, so its upload goes with it.
        awaitDir(r.uploads) { it.isEmpty() }
    }

    @Test
    fun aMissingRouteOnAnIdleSendFallsBackToTheInlineFrameAsItAlwaysDid() {
        val r = rig()
        val origin = r.client.consentOrigin.value
        val id = r.hub.begin(origin, "s1", photo.name, photo.mediaType, bytes)!!
        assertEquals(AttachmentSendResult.Sent, r.client.sendUploaded("s1", "look", listOf(photo), listOf(id), null, origin))
        h.server.enqueue(MockResponse().setResponseCode(404).setBody("Not found"))
        runBlocking { r.hub.run(id) }
        val frame = nextSend()
        assertFalse(frame.containsKey("attachmentIds"))
        val inline = frame["attachments"] as JsonArray
        assertEquals(photo.data, (inline.single() as JsonObject)["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun aSignOutCancelsTheUploadsAndSweepsTheirFiles() {
        val r = rig()
        val origin = r.client.consentOrigin.value
        val id = r.hub.begin(origin, "s1", photo.name, photo.mediaType, bytes)!!
        assertEquals(AttachmentSendResult.Sent, r.client.sendUploaded("s1", "look", listOf(photo), listOf(id), null, origin))
        r.client.stop()
        assertEquals(1, r.scheduler.cancelledAll)
        assertNull(r.hub.state(id))
        awaitDir(r.uploads) { it.isEmpty() }
        awaitDir(r.spool) { it.isEmpty() }
    }

    @Test
    fun withNoLedgerTheSendIsInlineAsBefore() {
        h.newClient()
        val client = h.client
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 1, """{"tetherSessionId":"s1","provider":"claude","cwd":"/w","status":"ready","turnOrder":[],"turnsById":{},"activeTurnId":null,"queuedMessages":[]}"""))
        h.await(client.projections) { it.containsKey("s1") }
        assertNull(client.uploads)
        assertEquals(AttachmentSendResult.Sent, client.sendUploaded("s1", "look", listOf(photo), listOf("00000000-0000-4000-8000-000000000000"), null, client.consentOrigin.value))
        val frame = h.expectFrame("send")
        assertFalse(frame.containsKey("attachmentIds"))
        assertNotNull(frame["attachments"])
    }
}
