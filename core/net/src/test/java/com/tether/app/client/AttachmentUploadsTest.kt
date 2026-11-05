package com.tether.app.client

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scheduler the tests hand the ledger: it records what WorkManager would have been asked; a test calls [AttachmentUploads.run] itself. */
class FakeUploadScheduler : UploadScheduler {
    val enqueued = ArrayList<String>()
    val cancelled = ArrayList<String>()
    var cancelledAll = 0
    override fun enqueue(id: String) { enqueued += id }
    override fun cancel(id: String) { cancelled += id }
    override fun cancelAll() { cancelledAll++ }
}

private fun storedJson(id: String, bytes: Int) = """{"id":"$id","name":"pic.png","mediaType":"image/png","bytes":$bytes,"sha256":"abc"}"""

/**
 * ta-delivery A2 on a MockWebServer: the ledger + uploader against the route contract of tether dlv-L5a b95fad2b
 * (`lib/server/attachments-http.ts`): the id is the client UUID, the same id again is a 200, a 409 is a refusal, a 404/405
 * with no `code` is an old server, `400 upload_interrupted` is transient, the credential is the paired Bearer and a
 * redirect is never followed.
 */
class AttachmentUploadsTest {
    private val server = MockWebServer()
    private val roots = ArrayList<File>()
    private val origin = "http://uploads.test"
    private val bytes = ByteArray(2048) { (it % 251).toByte() }

    @After
    fun tearDown() {
        server.shutdown()
        roots.forEach { it.deleteRecursively() }
    }

    private fun root(): File = File(java.nio.file.Files.createTempDirectory("a2-uploads").toFile(), "u").also { roots += it.parentFile }

    private fun authority(token: String = "tok", originValue: String = origin): suspend () -> UploadAuthority = {
        UploadAuthority.Paired(originValue, server.url("/")) { it.header("Authorization", "Bearer $token") }
    }

    private fun hub(root: File, scheduler: FakeUploadScheduler = FakeUploadScheduler(), auth: suspend () -> UploadAuthority = authority()): AttachmentUploads =
        AttachmentUploads(root, scheduler).also {
            it.bind(AttachmentUploader(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()), auth) {}
        }

    private fun put(): RecordedRequest = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!

    @Test
    fun aPickedPhotoIsSpooledQueuedAndPutWithItsOwnIdAsAStreamedBodyUnderThePairedBearer() {
        server.start()
        val scheduler = FakeUploadScheduler()
        val hub = hub(root(), scheduler)
        val id = hub.begin(origin, "s1", "my pic.png", "image/png", bytes)!!
        assertEquals(listOf(id), scheduler.enqueued)
        server.enqueue(MockResponse().setBody(storedJson(id, bytes.size)))
        assertEquals(UploadRunResult.Done, runBlocking { hub.run(id) })
        val request = put()
        assertEquals("PUT", request.method)
        assertEquals("/api/attachments", request.requestUrl!!.encodedPath)
        assertEquals("s1", request.requestUrl!!.queryParameter("sessionId"))
        assertEquals("my pic.png", request.requestUrl!!.queryParameter("name"))
        assertEquals("image/png", request.requestUrl!!.queryParameter("mediaType"))
        assertEquals(id, request.requestUrl!!.queryParameter("id"))
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        assertEquals(bytes.size.toString(), request.getHeader("Content-Length"))
        assertNull("never chunked", request.getHeader("Transfer-Encoding"))
        assertArrayEquals(bytes, request.body.readByteArray())
        assertEquals(UploadPhase.Stored, hub.state(id)!!.phase)
    }

    @Test
    fun aRedirectIsNeverFollowedAndTheCredentialStaysOnItsOrigin() {
        server.start()
        val elsewhere = MockWebServer().also { it.start() }
        try {
            val hub = hub(root())
            val id = hub.begin(origin, "s1", "p.png", "image/png", bytes)!!
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", elsewhere.url("/api/attachments").toString()))
            assertEquals(UploadRunResult.Done, runBlocking { hub.run(id) })
            put()
            assertEquals("no request reached the other origin", 0, elsewhere.requestCount)
            assertEquals(UploadPhase.Failed, hub.state(id)!!.phase)
        } finally {
            elsewhere.shutdown()
        }
    }

    @Test
    fun aKilledProcessResumesWithTheSameIdAndEndsWithOneStoredFileAndOneSend() {
        server.start()
        val root = root()
        val first = hub(root)
        val id = first.begin(origin, "s1", "p.png", "image/png", bytes)!!
        // Send was tapped: the pending record owns the upload (a photo only picked does not outlive its process).
        assertTrue(first.bind(listOf(id), "rec-1", PendingInput.KIND_SEND, origin, "s1"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals("a dropped connection is transient: WorkManager retries", UploadRunResult.Retry, runBlocking { first.run(id) })
        val firstPut = put()
        // The process dies. A new one opens the same ledger and WorkManager runs the same work again.
        val second = hub(root)
        assertEquals(UploadPhase.Pending, second.state(id)!!.phase)
        // The server had already stored it before the connection dropped: the same id with the same bytes is a 200.
        server.enqueue(MockResponse().setBody(storedJson(id, bytes.size)))
        assertEquals(UploadRunResult.Done, runBlocking { second.run(id) })
        val secondPut = put()
        assertEquals(id, firstPut.requestUrl!!.queryParameter("id"))
        assertEquals("the SAME id on the resumed attempt", id, secondPut.requestUrl!!.queryParameter("id"))
        assertEquals(listOf(id), second.idsFor("rec-1"))
        assertTrue(second.gate("rec-1") is UploadGate.Ready)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aPhotoOnlyPickedDoesNotSurviveAProcessRestart() {
        server.start()
        val root = root()
        val first = hub(root)
        val id = first.begin(origin, "s1", "p.png", "image/png", bytes)!!
        assertTrue(File(root, "$id/data.bin").isFile)
        val second = hub(root)
        assertNull(second.state(id))
        assertFalse(File(root, id).exists())
    }

    @Test
    fun a409IsAFailureWithTheServersReasonAndIsNeverRetried() {
        server.start()
        val hub = hub(root())
        val id = hub.begin(origin, "s1", "p.png", "image/png", bytes)!!
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"That attachment id already holds different bytes.","code":"attachment_id_conflict"}"""))
        assertEquals("Done, not Retry: no automatic retry", UploadRunResult.Done, runBlocking { hub.run(id) })
        val state = hub.state(id)!!
        assertEquals(UploadPhase.Failed, state.phase)
        assertEquals("That attachment id already holds different bytes.", state.reason)
        assertFalse(state.retryable)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun a409UploadInProgressIsTransientAndRetriesTheSameIdWhileAnIdConflictStaysFinal() {
        server.start()
        val hub = hub(root())
        val id = hub.begin(origin, "s1", "p.png", "image/png", bytes)!!
        // tether lib/attachment-store.ts: a same-id PUT already in flight is 409 upload_in_progress ("retry in a moment").
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"That attachment is already being uploaded; retry in a moment.","code":"upload_in_progress"}"""))
        assertEquals(UploadRunResult.Retry, runBlocking { hub.run(id) })
        assertNotNull("not failed: the ledger row is still pending", hub.state(id))
        assertEquals(UploadPhase.Pending, hub.state(id)!!.phase)
        server.enqueue(MockResponse().setBody(storedJson(id, bytes.size)))
        assertEquals(UploadRunResult.Done, runBlocking { hub.run(id) })
        assertEquals(UploadPhase.Stored, hub.state(id)!!.phase)
        assertEquals("the SAME id both times", id, put().requestUrl!!.queryParameter("id"))
        assertEquals(id, put().requestUrl!!.queryParameter("id"))
        // The cap still ends it.
        val other = hub.begin(origin, "s1", "q.png", "image/png", bytes)!!
        repeat(AttachmentUploads.MAX_ATTEMPTS) {
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"retry in a moment","code":"upload_in_progress"}"""))
        }
        repeat(AttachmentUploads.MAX_ATTEMPTS - 1) { assertEquals(UploadRunResult.Retry, runBlocking { hub.run(other) }) }
        assertEquals(UploadRunResult.Done, runBlocking { hub.run(other) })
        assertEquals(UploadPhase.Failed, hub.state(other)!!.phase)
        assertTrue(hub.state(other)!!.retryable)
    }

    @Test
    fun theUploaderItselfRefusesAnotherOriginAndASignedOutAuthorityBeforeAnyRequest() {
        server.start()
        val file = File(root().also { it.mkdirs() }, "f.bin").also { it.writeBytes(bytes) }
        val uploader = AttachmentUploader(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build())
        val paired = UploadAuthority.Paired("http://third.test", server.url("/")) { it.header("Authorization", "Bearer tok") }
        fun up(a: UploadAuthority) = uploader.upload(a, origin, "s1", "id-1", "p.png", "image/png", file, { _, _ -> })
        assertEquals(UploadOutcome.WrongServer, up(paired))
        assertEquals(UploadOutcome.WrongServer, up(UploadAuthority.SignedOut))
        assertEquals("no request, so no credential, left the device", 0, server.requestCount)
    }

    @Test
    fun aRefusalIs413401403NeverInlineAndA429Or5xxMayBeSentAgain() {
        server.start()
        for ((code, retryable) in listOf(413 to false, 401 to false, 403 to false, 429 to true, 503 to true)) {
            val hub = hub(root())
            val id = hub.begin(origin, "s1", "p.png", "image/png", bytes)!!
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":"no $code","code":"x"}"""))
            runBlocking { hub.run(id) }
            put()
            val state = hub.state(id)!!
            assertEquals("code $code", UploadPhase.Failed, state.phase)
            assertEquals("code $code", retryable, state.retryable)
        }
    }

    @Test
    fun anInterruptedUploadAnswerIsTransientAndGivesUpAfterTheCap() {
        server.start()
        val hub = hub(root())
        val id = hub.begin(origin, "s1", "p.png", "image/png", bytes)!!
        repeat(AttachmentUploads.MAX_ATTEMPTS - 1) {
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"The upload was interrupted.","code":"upload_interrupted"}"""))
            assertEquals(UploadRunResult.Retry, runBlocking { hub.run(id) })
        }
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"The upload was interrupted.","code":"upload_interrupted"}"""))
        assertEquals(UploadRunResult.Done, runBlocking { hub.run(id) })
        assertEquals(UploadPhase.Failed, hub.state(id)!!.phase)
        assertTrue(hub.state(id)!!.retryable)
    }

    @Test
    fun aDroppedConnectionIsTransientAndItsFinalReasonIsThePrintedLinkWasDown() {
        server.start()
        val hub = hub(root())
        val id = hub.begin(origin, "s1", "p.png", "image/png", bytes)!!
        repeat(AttachmentUploads.MAX_ATTEMPTS) {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            runBlocking { hub.run(id) }
        }
        val state = hub.state(id)!!
        assertEquals(UploadPhase.Failed, state.phase)
        assertEquals("The link was down.", state.reason)
        assertTrue(state.retryable)
    }

    @Test
    fun aMissingRouteOnTheFirstPutLetsAnIdleSendGoInlineButNeverAQueuedMessage() {
        server.start()
        val hub = hub(root())
        val a = hub.begin(origin, "s1", "a.png", "image/png", bytes)!!
        val b = hub.begin(origin, "s1", "b.png", "image/png", bytes)!!
        hub.bind(listOf(a), "send-rec", PendingInput.KIND_SEND, origin, "s1")
        hub.bind(listOf(b), "queue-rec", PendingInput.KIND_QUEUE, origin, "s1")
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not found"))
        server.enqueue(MockResponse().setResponseCode(405).setBody("nope"))
        runBlocking { hub.run(a); hub.run(b) }
        assertEquals(UploadGate.Inline, hub.gate("send-rec"))
        assertTrue(hub.gate("queue-rec") is UploadGate.Failed)
    }

    @Test
    fun aNotFoundThatNamesASessionIsARefusalNotAMissingRoute() {
        server.start()
        val hub = hub(root())
        val id = hub.begin(origin, "s1", "a.png", "image/png", bytes)!!
        hub.bind(listOf(id), "send-rec", PendingInput.KIND_SEND, origin, "s1")
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"That session is not available.","code":"session_not_found"}"""))
        runBlocking { hub.run(id) }
        assertTrue(hub.gate("send-rec") is UploadGate.Failed)
    }

    @Test
    fun aMissingRouteAfterAnEarlierAttemptIsAFailureNotAnInlineFallback() {
        server.start()
        val hub = hub(root())
        val id = hub.begin(origin, "s1", "a.png", "image/png", bytes)!!
        hub.bind(listOf(id), "send-rec", PendingInput.KIND_SEND, origin, "s1")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not found"))
        runBlocking { hub.run(id); hub.run(id) }
        assertTrue(hub.gate("send-rec") is UploadGate.Failed)
    }

    @Test
    fun aSignOutCancelsTheWorkAndSweepsEveryUploadedFile() {
        server.start()
        val root = root()
        val scheduler = FakeUploadScheduler()
        val hub = hub(root, scheduler)
        val picked = hub.begin(origin, "s1", "a.png", "image/png", bytes)!!
        val bound = hub.begin(origin, "s1", "b.png", "image/png", bytes)!!
        hub.bind(listOf(bound), "rec", PendingInput.KIND_SEND, origin, "s1")
        hub.cancelAll()
        assertEquals(1, scheduler.cancelledAll)
        assertNull(hub.state(picked))
        assertNull(hub.state(bound))
        assertTrue("nothing left on disk", root.list().isNullOrEmpty())
    }

    @Test
    fun aServerSwitchCancelsOtherOriginsAndAWorkerNeverUploadsForAnotherServer() {
        server.start()
        val root = root()
        val scheduler = FakeUploadScheduler()
        val hub = hub(root, scheduler)
        val mine = hub.begin(origin, "s1", "a.png", "image/png", bytes)!!
        val theirs = hub.begin("http://other.test", "s1", "b.png", "image/png", bytes)!!
        val boundTheirs = hub.begin("http://other.test", "s1", "c.png", "image/png", bytes)!!
        hub.bind(listOf(boundTheirs), "rec-other", PendingInput.KIND_SEND, "http://other.test", "s1")
        hub.cancelOtherOrigins(origin)
        assertNotNull(hub.state(mine))
        assertNull(hub.state(theirs))
        // A bound upload keeps a failed ledger row (no bytes) so its record can never go inline by accident.
        assertTrue(hub.gate("rec-other") is UploadGate.Failed)
        assertFalse(File(root, "$boundTheirs/data.bin").exists())
        // And a worker that wakes up on another server than the one the photo was picked for sends nothing.
        val stray = hub.begin(origin, "s1", "d.png", "image/png", bytes)!!
        val onOther = hub(root, scheduler, authority(originValue = "http://third.test"))
        assertEquals(UploadRunResult.Gone, runBlocking { onOther.run(stray) })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aSpooledFileIsDeletedOnlyAfterTheRecordThatOwnsItIsGoneNeverAtUploadEnd() {
        server.start()
        val root = root()
        val hub = hub(root)
        val id = hub.begin(origin, "s1", "a.png", "image/png", bytes)!!
        hub.bind(listOf(id), "rec", PendingInput.KIND_SEND, origin, "s1")
        server.enqueue(MockResponse().setBody(storedJson(id, bytes.size)))
        runBlocking { hub.run(id) }
        assertEquals(UploadPhase.Stored, hub.state(id)!!.phase)
        assertTrue("the upload finished but the server has not acknowledged the send", File(root, "$id/data.bin").isFile)
        assertEquals("a record still referenced keeps its file", 0, hub.sweepBound(setOf("rec")))
        assertTrue(File(root, "$id/data.bin").isFile)
        assertEquals(1, hub.sweepBound(emptySet()))
        assertFalse(File(root, id).exists())
    }

    @Test
    fun theCapsArePreCheckedSoNothingOverNineMiBIsEverSent() {
        server.start()
        val hub = hub(root())
        assertNull(hub.begin(origin, "s1", "big.png", "image/png", ByteArray((AttachmentUploader.MAX_FILE_BYTES + 1).toInt())))
        assertNull(hub.begin(origin, "s1", "empty.png", "image/png", ByteArray(0)))
        assertNull("no server known: stays inline", hub.begin(null, "s1", "a.png", "image/png", bytes))
        val ids = (1..10).map { hub.begin(origin, "s1", "$it.png", "image/png", bytes)!! }
        assertTrue(hub.bind(ids, "ten", PendingInput.KIND_SEND, origin, "s1"))
        val eleven = (1..11).map { hub.begin(origin, "s1", "$it.png", "image/png", bytes)!! }
        assertFalse("more than 10 files a message is refused (inline parity)", hub.bind(eleven, "eleven", PendingInput.KIND_SEND, origin, "s1"))
        assertEquals(0, server.requestCount)
    }
}
