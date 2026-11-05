package com.tether.app.client

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-delivery §4.1d A.1 on a RealTetherClient: a pending row is `keptOnDevice` only when the slot holds (or would
 * hold) ITS record: the 256 KB drop takes the OLDEST text records too, a thrown slot write makes every record not kept
 * until a write succeeds, and a fresh send is kept from its first published row (never flips not-kept to kept).
 */
class KeptOnDeviceTest {
    private val h = ConnectionHarness()
    private lateinit var settings: DiskSettings
    private val scopes = ArrayList<CoroutineScope>()
    private val dirs = ArrayList<File>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        h.close()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun connected(spool: Boolean = false, spoolRoot: File? = null): RealTetherClient {
        h.server.start()
        val url = h.server.url("/").toString().trimEnd('/')
        settings = DiskSettings(InMemorySettings(url, initialCookie = "cookie"), url)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val dir = java.nio.file.Files.createTempDirectory("kept").toFile().also { dirs += it }
        val client = RealTetherClient(
            settings = settings,
            httpClient = OkHttpClient(),
            scope = scope,
            clock = { h.now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = ManualScheduler(),
            attachmentSpool = if (spoolRoot != null) AttachmentSpool(spoolRoot) else if (spool) AttachmentSpool(File(dir, "spool")) else null,
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
        return client
    }

    private fun rows(client: RealTetherClient) = client.pendingSends.value.sortedBy { it.key }

    @Test
    fun pastTheSlotsSizeCapTheOldestTextRecordIsNotKept() {
        val client = connected()
        val big = "x".repeat(100_000)
        client.send("s1", "first $big")
        client.send("s1", "second $big")
        client.send("s1", "third $big")
        // Three ~100 KB records pass 256 KB: the slot drops the oldest, so only that one is not kept.
        h.await(client.pendingSends) { it.size == 3 && it.count { r -> !r.keptOnDevice } == 1 }
        val byText = client.pendingSends.value.associateBy { it.text.substringBefore(' ') }
        assertFalse(byText.getValue("first").keptOnDevice)
        assertTrue(byText.getValue("second").keptOnDevice)
        assertTrue(byText.getValue("third").keptOnDevice)
    }

    @Test
    fun aThrownWriteMakesEveryRecordNotKeptUntilAWriteSucceeds() {
        val client = connected()
        client.send("s1", "one")
        h.await(settings.written) { it != null }
        h.await(client.pendingSends) { it.size == 1 && it.single().keptOnDevice }

        settings.beforeWrite = { throw java.io.IOException("disk full") }
        client.send("s1", "two")
        h.await(client.pendingSends) { it.size == 2 && it.none { r -> r.keptOnDevice } }

        settings.beforeWrite = null
        client.send("s1", "three")
        h.await(client.pendingSends) { it.size == 3 && it.all { r -> r.keptOnDevice } }
    }

    @Test
    fun aFreshSendIsKeptOnItsFirstPublishedRowEvenWhileItsWriteIsStillPending() {
        val client = connected()
        val gate = CompletableDeferred<Unit>()
        settings.beforeWrite = { gate.await() }
        client.send("s1", "fresh")
        h.await(client.pendingSends) { it.size == 1 }
        // The write has not completed (it is held), and the row is already kept: never derived from the write.
        assertTrue(client.pendingSends.value.single().keptOnDevice)
        settings.beforeWrite = null
        gate.complete(Unit)
    }

    @Test
    fun aSpoolIoErrorOnWriteLeavesTheRecordOutAndItFlipsToNotKept() {
        // The spool's root is a regular file, so its per-record directory cannot be made: put() hits an I/O error.
        val root = File.createTempFile("spool-root", "").also { dirs += it }
        val client = connected(spoolRoot = root)
        val photo = com.tether.app.protocol.Attachment("pic.png", "image/png", java.util.Base64.getEncoder().encodeToString(ByteArray(300) { it.toByte() }))
        client.sendAttachments("s1", "look", listOf(photo), null)
        h.await(client.pendingSends) { it.size == 1 }
        // The write dropped the record (nothing on disk): the row ends not kept.
        h.await(client.pendingSends) { it.size == 1 && !it.single().keptOnDevice }
    }
}
