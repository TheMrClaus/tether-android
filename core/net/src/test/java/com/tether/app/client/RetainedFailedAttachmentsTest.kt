package com.tether.app.client

import com.tether.app.protocol.Attachment
import com.tether.app.protocol.helpers.PendingInput as Web
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIB = 1024L * 1024L

/** `raw` bytes of canonical base64 zeros, as attachments of at most 9 MiB each (`raw` divisible by 3). */
private fun message(raw: Long, prefix: String = "f"): List<Attachment> {
    val per = 9 * MIB
    val out = ArrayList<Attachment>()
    var left = raw
    var i = 0
    while (left > 0) {
        val n = minOf(per, left)
        out += Attachment("$prefix$i.bin", "application/octet-stream", "A".repeat((n / 3 * 4).toInt()))
        left -= n
        i++
    }
    return out
}

/**
 * tth-87eo (AW.5, bar R4-a..R4-e): where the bytes of a failed attachment message live. The pure half (the bound, which
 * rows show Send again) is pinned on [RetainedAttachments] and [PendingInput.recordFailed]; the client half on a
 * RealTetherClient over a MockWebServer socket. A send is given up on here by eviction (MAX_RECORDS + 1), reason Expired.
 */
class RetainedFailedAttachmentsTest {
    private val h = ConnectionHarness()
    private val scopes = ArrayList<CoroutineScope>()
    private val dirs = ArrayList<File>()
    private var lastWs: okhttp3.WebSocket? = null

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        h.close()
        dirs.forEach { it.deleteRecursively() }
    }

    // --- the bound (pure) ------------------------------------------------------------------------

    private val full = 18 * MIB

    @Test
    fun aSecondFullFailureReleasesTheFirstAndTheNewestStaysUnderTheBound() {
        val r = RetainedAttachments()
        r.retain("a", message(full, "a"))
        assertEquals(emptySet<String>(), r.reconcile(setOf("a")))
        r.retain("b", message(full, "b"))
        assertEquals("the older message is released by the bound", setOf("a"), r.reconcile(setOf("a", "b")))
        assertEquals(setOf("b"), r.keys)
        assertTrue(r.totalBytes <= full)
    }

    @Test
    fun theBoundCountsBytesNotMessages() {
        val r = RetainedAttachments()
        r.retain("a", message(6 * MIB, "a"))
        r.retain("b", message(6 * MIB, "b"))
        assertEquals(emptySet<String>(), r.reconcile(setOf("a", "b")))
        assertEquals(12 * MIB, r.totalBytes)
        // 12 MiB twice: only the newer keeps its bytes (the AW.5 example).
        val q = RetainedAttachments()
        q.retain("a", message(12 * MIB, "a"))
        q.retain("b", message(12 * MIB, "b"))
        assertEquals(setOf("a"), q.reconcile(setOf("a", "b")))
    }

    @Test
    fun aRowThatCannotShowSendAgainNeverCountsTowardTheBound() {
        fun record(key: String, atts: List<Attachment>) = PendingInput.addRecord(PendingInput.emptyStore(), key, PendingInput.KIND_SEND, "s1", "t", 1L, atts).store.records.single()
        val a = record("a", message(6 * MIB, "a"))
        val b = record("b", message(13 * MIB, "b"))
        val r = RetainedAttachments()
        val rowsA = PendingInput.recordFailed(emptyList(), listOf(a), FailedSendReason.Link)
        assertTrue("a Link failure with attachments shows Send again", rowsA.single().canSendAgain)
        r.retainNew(emptySet(), rowsA, listOf(a))
        // B: a non-retryable Upload refusal: no Send again, so (the client's rule) nothing is retained for it.
        val rowsB = PendingInput.recordFailed(rowsA, listOf(b), FailedSendReason.Upload, mapOf("b" to ("An attachment is limited to 9 MiB." to false)))
        val rowB = rowsB.single { it.key == "b" }
        assertFalse("a non-retryable refusal shows no Send again", rowB.canSendAgain)
        assertTrue(rowsB.single { it.key == "a" }.canSendAgain)
        r.retainNew(setOf("a"), rowsB, listOf(b))
        assertEquals("only A's 6 MiB is retained", 6 * MIB, r.totalBytes)
        assertEquals(emptySet<String>(), r.reconcile(setOf("a", "b")))
        // A retryable Upload and an Expired one do; PhotoLost and a delegate mention never do.
        assertTrue(PendingInput.recordFailed(emptyList(), listOf(b), FailedSendReason.Upload, mapOf("b" to ("x" to true))).single().canSendAgain)
        assertTrue(PendingInput.recordFailed(emptyList(), listOf(b), FailedSendReason.Expired).single().canSendAgain)
        assertFalse(PendingInput.recordFailed(emptyList(), listOf(b), FailedSendReason.PhotoLost).single().canSendAgain)
        val mention = com.tether.app.protocol.DelegateMention("claude", "build")
        val withMention = PendingInput.addRecord(PendingInput.emptyStore(), "m", PendingInput.KIND_SEND, "s1", "t", 1L, message(6 * MIB, "m"), mention).store.records.single()
        assertFalse(PendingInput.recordFailed(emptyList(), listOf(withMention), FailedSendReason.Link).single().canSendAgain)
    }

    @Test
    fun releaseFollowsTheRowSoARowThatLeftReleasesItsBytes() {
        val r = RetainedAttachments()
        r.retain("a", message(6 * MIB, "a"))
        r.retain("b", message(6 * MIB, "b"))
        r.reconcile(setOf("b"))
        assertEquals(6 * MIB, r.totalBytes)
        r.reconcile(emptySet())
        assertEquals(0L, r.totalBytes)
    }

    // --- the client ------------------------------------------------------------------------------

    private fun connected(spoolRoot: File? = null): RealTetherClient {
        h.server.start()
        val url = h.server.url("/").toString().trimEnd('/')
        val settings = DiskSettings(InMemorySettings(url, initialCookie = "cookie"), url)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val client = RealTetherClient(
            settings = settings,
            httpClient = OkHttpClient(),
            scope = scope,
            clock = { h.now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = ManualScheduler(),
            attachmentSpool = spoolRoot?.let { AttachmentSpool(it) },
        )
        h.client = client
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        lastWs = ws
        h.handshake(ws)
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 1, """{"tetherSessionId":"s1","provider":"claude","cwd":"/w","status":"ready","turnOrder":[],"turnsById":{},"activeTurnId":null,"queuedMessages":[]}"""))
        h.await(client.projections) { it.containsKey("s1") }
        return client
    }

    private fun sendWithRetry(client: RealTetherClient, text: String, atts: List<Attachment>) {
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            val r = client.sendAttachments("s1", text, atts, null, client.consentOrigin.value)
            if (r == AttachmentSendResult.Sent) return
            assertTrue("send said $r", r == AttachmentSendResult.LinkBusy && System.currentTimeMillis() < deadline)
            Thread.sleep(50)
        }
    }

    /** Send a message with [atts] first, then enough text sends to evict it: its failed row. */
    private fun failedAttachmentRow(client: RealTetherClient, atts: List<Attachment>): FailedSend {
        sendWithRetry(client, "with files", atts)
        repeat(Web.MAX_RECORDS) { client.send("s1", "m$it") }
        return h.await(client.failedSends) { rows -> rows.any { it.text == "with files" } }.single { it.text == "with files" }
    }

    private val two = listOf(Attachment("a.png", "image/png", "iVBORw0KGgo="), Attachment("b.txt", "text/plain", "aGVsbG8="))
    private val twoBytes = 8L + 5L

    @Test
    fun aFailedMessageWithFilesKeepsThemInMemoryAndSendAgainFilesBothAttachments() {
        val client = connected()
        val row = failedAttachmentRow(client, two)
        assertTrue("shows Send again", row.canSendAgain)
        assertEquals(2, row.attachmentCount)
        assertEquals(twoBytes, client.retainedFailedBytes)
        client.sendAgain(row.key)
        h.await(client.pendingSends) { rows -> rows.any { it.key == row.key && it.attachmentCount == 2 } }
        assertTrue("the failed row is taken out", client.failedSends.value.none { it.key == row.key })
        assertEquals("the bytes moved into the new record", 0L, client.retainedFailedBytes)
        val sent = h.framesUntilBarrier().filter { it.type() == "send" && (it["idempotencyKey"] as? JsonPrimitive)?.content == row.key && it["attachments"] is JsonArray }
        val last = sent.last()
        assertEquals("both attachments ride one frame", 2, (last["attachments"] as JsonArray).size)
        assertEquals("a.png", ((last["attachments"] as JsonArray)[0] as JsonObject)["name"]!!.let { (it as JsonPrimitive).content })
    }

    @Test
    fun dismissReleasesTheBytes() {
        val client = connected()
        val row = failedAttachmentRow(client, two)
        assertEquals(twoBytes, client.retainedFailedBytes)
        client.dismissFailedSend(row.key)
        assertEquals(0L, client.retainedFailedBytes)
    }

    @Test
    fun anArchivedSessionDropReleasesTheBytes() {
        val client = connected()
        failedAttachmentRow(client, two)
        assertEquals(twoBytes, client.retainedFailedBytes)
        val ws = lastWs!!
        ws.send("""{"type":"session","session":{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"active","startedAt":1,"updatedAt":9,"endedAt":null,"exitCode":null,"pinned":false,"mode":"headless","runtimeArchived":true}}""")
        h.await(client.failedSends) { rows -> rows.none { it.sessionId == "s1" } }
        assertEquals(0L, client.retainedFailedBytes)
    }

    @Test
    fun theThirtyRowFallOffReleasesTheBytes() {
        val client = connected()
        failedAttachmentRow(client, two)
        // 30 more text-only failures push the first row off (MAX_FAILED_SENDS).
        repeat(MAX_FAILED_SENDS) { client.send("s1", "x$it") }
        h.await(client.failedSends) { rows -> rows.size == MAX_FAILED_SENDS && rows.none { it.text == "with files" } }
        assertEquals(0L, client.retainedFailedBytes)
    }

    @Test
    fun aFailedMessageIsNeverSpooledAndFourFullSendsStillAre() {
        val spoolRoot = java.nio.file.Files.createTempDirectory("r4spool").toFile().also { dirs += it }
        val client = connected(spoolRoot)
        val row = failedAttachmentRow(client, message(full, "a"))
        assertTrue(row.canSendAgain)
        assertEquals(full, client.retainedFailedBytes)
        // R4-d: after the give-up and its sweep no spool directory remains for the failed key.
        val deadline = System.currentTimeMillis() + 30_000
        while (File(spoolRoot, row.key).exists() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertFalse("no spool directory for the failed key", File(spoolRoot, row.key).exists())
        // R4-a: three full messages and a 16 MiB one are ALL kept on this device.
        val texts = listOf("one", "two", "three", "four")
        sendWithRetry(client, "one", message(full, "p"))
        sendWithRetry(client, "two", message(full, "q"))
        sendWithRetry(client, "three", message(full, "r"))
        sendWithRetry(client, "four", message(16 * MIB - 1, "s"))
        h.await(client.pendingSends) { rows -> texts.all { t -> rows.any { it.text == t } } }
        val rows = client.pendingSends.value.filter { it.text in texts }
        h.await(client.pendingSends) { cur -> cur.filter { it.text in texts }.all { it.keptOnDevice } }
        assertEquals(4, rows.size)
        assertNotNull(rows)
    }

    /** Send each message first, then enough text sends to evict them all oldest-first: their failed rows, no other failure rows. */
    private fun failedAttachmentRows(client: RealTetherClient, sends: List<Pair<String, List<Attachment>>>): List<FailedSend> {
        sends.forEach { (text, atts) -> sendWithRetry(client, text, atts) }
        repeat(Web.MAX_RECORDS) { client.send("s1", "m$it") }
        val texts = sends.map { it.first }
        h.await(client.failedSends) { rows -> texts.all { t -> rows.any { it.text == t } } }
        return texts.map { t -> client.failedSends.value.single { it.text == t } }
    }

    // BLOCKING (refuter): the flip lives in reconcileRetainedLocked; pin the ROW, not only the bytes.
    @Test
    fun aSecondFullFailureReleasesTheFirstRowAndItLosesSendAgainForGood() {
        val client = connected()
        failedAttachmentRows(client, listOf("first" to message(full, "a"), "second" to message(full, "b")))
        val rows = client.failedSends.value
        val first = rows.single { it.text == "first" }
        val second = rows.single { it.text == "second" }
        assertFalse("the released row no longer shows Send again", first.canSendAgain)
        assertTrue("the released row is marked released", first.released)
        assertTrue("the newest keeps Send again", second.canSendAgain)
        assertFalse(second.released)
        assertEquals("only the newest message's bytes remain", full, client.retainedFailedBytes)
    }

    // R4-e: 6 MiB (A) and 8 MiB (C) are 14 MiB, under the bound: both keep Send again.
    @Test
    fun twoMessagesUnderTheBoundBothKeepSendAgain() {
        val client = connected()
        failedAttachmentRows(client, listOf("A" to message(6 * MIB, "a"), "C" to message(8 * MIB, "c")))
        val rows = client.failedSends.value
        listOf("A", "C").forEach { t ->
            val row = rows.single { it.text == t }
            assertTrue("$t keeps Send again", row.canSendAgain)
            assertFalse("$t is not released", row.released)
        }
        assertEquals(14 * MIB, client.retainedFailedBytes)
    }

    // Exit: retractFailed (the server proves it accepted the key: a live ack takes the bubble back).
    @Test
    fun aRetractedFailedRowReleasesItsBytes() {
        val client = connected()
        val row = failedAttachmentRow(client, two)
        assertEquals(twoBytes, client.retainedFailedBytes)
        lastWs!!.send("""{"type":"event","sessionId":"s1","event":{"type":"turn_started","turnId":"t1","idempotencyKey":"${row.key}","seq":2,"ts":1000}}""")
        h.await(client.failedSends) { rows -> rows.none { it.key == row.key } }
        assertEquals(0L, client.retainedFailedBytes)
    }

    // Exit: clearFailedSendsLocked (a sign-out / stop drops every failed bubble).
    @Test
    fun aSignOutClearReleasesTheBytes() {
        val client = connected()
        failedAttachmentRow(client, two)
        assertEquals(twoBytes, client.retainedFailedBytes)
        client.stop()
        assertTrue(client.failedSends.value.isEmpty())
        assertEquals(0L, client.retainedFailedBytes)
    }
}
