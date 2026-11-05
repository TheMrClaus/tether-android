package com.tether.app.client

import com.tether.app.protocol.model.QueuedMessage
import com.tether.app.protocol.helpers.QueueWait
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsCodec
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
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-delivery A3: photos attached while the agent works. A busy attach is ONE `queue-add` carrying the uploaded ids
 * (never a `send`, never inline bytes, never both); an upload that fails for good is the failed `upload` bubble with the
 * text kept and sends nothing, whatever the status (R2: a queue-add never falls back inline); a killed process resumes the
 * upload under the same id and still ends in one queue-add.
 */
class BusyPhotoQueueTest {
    private val h = ConnectionHarness()
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

    private class Rig(val client: RealTetherClient, val hub: AttachmentUploads, val ws: WebSocket)

    private fun busySnapshot() = JsCodec.toJson(foldTree(freshTree(), ev("turn_started", "t1", seq = 1, ts = 1))).toString()

    private fun rig(withLedger: Boolean = true): Rig {
        h.server.start()
        val url = h.server.url("/").toString().trimEnd('/')
        val settings = DiskSettings(InMemorySettings(url, initialCookie = "cookie"), url)
        val base = java.nio.file.Files.createTempDirectory("a3-client").toFile().also { dirs += it }
        val hub = AttachmentUploads(File(base, "uploads"), FakeUploadScheduler())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val client = RealTetherClient(
            settings = settings,
            httpClient = OkHttpClient(),
            scope = scope,
            clock = { h.now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = ManualScheduler(),
            attachmentSpool = AttachmentSpool(File(base, "spool")),
            attachmentUploads = if (withLedger) hub else null,
        )
        h.client = client
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 5, busySnapshot()))
        h.await(client.projections) { it["s1"]?.activeTurnId != null }
        return Rig(client, hub, ws)
    }

    private fun stored(id: String) = MockResponse().setBody("""{"id":"$id","name":"pic.png","mediaType":"image/png","bytes":${bytes.size},"sha256":"abc"}""")

    private fun framesOf(vararg types: String): List<JsonObject> = h.framesUntilBarrier().filter { it.type() in types }

    /** The next `send` / `queue-add` frame (pings and other frames are skipped). */
    private fun nextOf(vararg types: String): JsonObject {
        while (true) {
            val f = h.frame()
            if (f.type() in types) return f
        }
    }

    private fun uploaded(r: Rig): String = r.hub.begin(r.client.consentOrigin.value, "s1", photo.name, photo.mediaType, bytes)!!

    @Test
    fun busyPlusOnePhotoIsExactlyOneQueueAddWithIdsAndNoSendAndNoInlineBytes() {
        val r = rig()
        val id = uploaded(r)
        h.server.enqueue(stored(id))
        assertEquals(UploadRunResult.Done, runBlocking { r.hub.run(id) })
        assertEquals(AttachmentSendResult.Sent, r.client.sendUploaded("s1", "later", listOf(photo), listOf(id), null, r.client.consentOrigin.value))
        val frames = framesOf("send", "queue-add")
        val add = frames.single()
        assertEquals("queue-add", add.type())
        assertEquals(listOf(id), (add["attachmentIds"] as JsonArray).map { it.jsonPrimitive.content })
        assertFalse("never inline bytes", add.containsKey("attachments"))
        assertEquals("later", add["text"]!!.jsonPrimitive.content)
        // The row the server would echo for it reads V4 (never steered with photos), through the real producer's ids.
        val row = QueuedMessage(queueId = add["queueId"]!!.jsonPrimitive.content, text = "later", attachmentIds = listOf(id))
        assertEquals(QueueWait.QUEUED_TURN_END_COPY, QueueWait.queuedLabel(true, row))
    }

    @Test
    fun aBusyQueueAddWhosePhotoIsStillUploadingIsHeldAndThenGoesOutAsOneQueueAdd() {
        val r = rig()
        val id = uploaded(r)
        assertEquals(AttachmentSendResult.Sent, r.client.sendUploaded("s1", "later", listOf(photo), listOf(id), null, r.client.consentOrigin.value))
        assertTrue("held while uploading", framesOf("send", "queue-add").isEmpty())
        h.server.enqueue(stored(id))
        runBlocking { r.hub.run(id) }
        val frame = nextOf("send", "queue-add")
        assertEquals("queue-add", frame.type())
        assertEquals(listOf(id), (frame["attachmentIds"] as JsonArray).map { it.jsonPrimitive.content })
    }

    @Test
    fun busyWithNothingUploadedOrWithAMentionSendsNothingAndWithdrawsNothingInline() {
        val r = rig(withLedger = false)
        assertEquals(AttachmentSendResult.NotQueueable, r.client.sendUploaded("s1", "later", listOf(photo), listOf("00000000-0000-4000-8000-000000000000"), null, r.client.consentOrigin.value))
        assertEquals(AttachmentSendResult.NotQueueable, r.client.sendAttachments("s1", "later", listOf(photo), null, r.client.consentOrigin.value))
        assertTrue(framesOf("send", "queue-add").isEmpty())
        assertTrue(r.client.pendingSends.value.isEmpty())
    }

    @Test
    fun aBusyUploadThatFailsIsTheFailedUploadBubbleWithTheTextAndNeverAnyFrame() {
        val r = rig()
        val id = uploaded(r)
        assertEquals(AttachmentSendResult.Sent, r.client.sendUploaded("s1", "keep this text", listOf(photo), listOf(id), null, r.client.consentOrigin.value))
        h.server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"That attachment id already holds different bytes.","code":"c"}"""))
        runBlocking { r.hub.run(id) }
        h.await(r.client.failedSends) { it.isNotEmpty() }
        val failed = r.client.failedSends.value.single()
        assertEquals(FailedSendReason.Upload, failed.reason)
        assertEquals("keep this text", failed.text)
        assertTrue(framesOf("send", "queue-add").isEmpty())
    }

    private fun failsWithoutAnyFrame(status: Int) {
        val r = rig()
        val id = uploaded(r)
        assertEquals(AttachmentSendResult.Sent, r.client.sendUploaded("s1", "later", listOf(photo), listOf(id), null, r.client.consentOrigin.value))
        h.server.enqueue(MockResponse().setResponseCode(status).setBody(if (status == 404 || status == 405) "Not found" else """{"error":"no","code":"c"}"""))
        runBlocking { r.hub.run(id) }
        // A refusal (and a missing route, for a queued message) ends in the failed `upload` bubble; a 5xx is retried later.
        if (status != 500) {
            h.await(r.client.failedSends) { it.isNotEmpty() }
            assertEquals(FailedSendReason.Upload, r.client.failedSends.value.single().reason)
        }
        val frames = framesOf("send", "queue-add")
        assertTrue("status $status: no frame at all, in particular no inline one: $frames", frames.isEmpty())
    }

    @Test fun a404OfABusyQueueAddNeverFallsBackInline() = failsWithoutAnyFrame(404)
    @Test fun a405OfABusyQueueAddNeverFallsBackInline() = failsWithoutAnyFrame(405)
    @Test fun a409OfABusyQueueAddNeverFallsBackInline() = failsWithoutAnyFrame(409)
    @Test fun a413OfABusyQueueAddNeverFallsBackInline() = failsWithoutAnyFrame(413)
    @Test fun a401OfABusyQueueAddNeverFallsBackInline() = failsWithoutAnyFrame(401)
    @Test fun a403OfABusyQueueAddNeverFallsBackInline() = failsWithoutAnyFrame(403)
    @Test fun a500OfABusyQueueAddNeverFallsBackInline() = failsWithoutAnyFrame(500)

    @Test
    fun aKilledProcessResumesABusyQueuedUploadWithTheSameIdAndEndsInOneQueueAdd() {
        val server = MockWebServer().also { it.start() }
        try {
            val base = java.nio.file.Files.createTempDirectory("a3-resume").toFile().also { dirs += it }
            val origin = "http://uploads.test"
            fun hub(): AttachmentUploads = AttachmentUploads(File(base, "u"), FakeUploadScheduler()).also {
                it.bind(AttachmentUploader(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()), {
                    UploadAuthority.Paired(origin, server.url("/")) { b -> b.header("Authorization", "Bearer tok") }
                }) {}
            }
            val first = hub()
            val id = first.begin(origin, "s1", "p.png", "image/png", bytes)!!
            assertTrue(first.bind(listOf(id), "q-1", PendingInput.KIND_QUEUE, origin, "s1"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            assertEquals(UploadRunResult.Retry, runBlocking { first.run(id) })
            val firstPut: RecordedRequest = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
            val second = hub()
            server.enqueue(MockResponse().setBody("""{"id":"$id","name":"p.png","mediaType":"image/png","bytes":${bytes.size},"sha256":"x"}"""))
            assertEquals(UploadRunResult.Done, runBlocking { second.run(id) })
            val secondPut: RecordedRequest = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
            assertEquals(id, firstPut.requestUrl!!.queryParameter("id"))
            assertEquals("the SAME id after the process died", id, secondPut.requestUrl!!.queryParameter("id"))
            val gate = second.gate("q-1")
            assertTrue(gate is UploadGate.Ready)
            assertEquals(listOf(id), (gate as UploadGate.Ready).ids)
        } finally {
            server.shutdown()
        }
    }
}
