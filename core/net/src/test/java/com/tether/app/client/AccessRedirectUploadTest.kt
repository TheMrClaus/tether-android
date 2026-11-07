package com.tether.app.client

import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * tth-xyp9 diagnosis: what the app does when an SSO gateway in front of the server (Cloudflare Access on
 * tether.example.test) answers `PUT /api/attachments` with the 302 the live probe recorded, while the WebSocket
 * (exempted) works. Diagnostic only; asserts the observed behaviour, prints the state.
 */
class AccessRedirectUploadTest {
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

    private fun access302() = MockResponse().setResponseCode(302)
        .setHeader("Location", "https://team.cloudflareaccess.example")
        .setHeader("Www-Authenticate", "Cloudflare-Access resource_metadata=\"https://tether.example.test/.well-known/cloudflare-access-protected-resource/api/attachments\"")
        .setHeader("Content-Type", "text/html; charset=UTF-8")
        .setBody("<a href=\"https://team.cloudflareaccess.example\">Found</a>")

    private fun rig(): Triple<RealTetherClient, AttachmentUploads, okhttp3.WebSocket> {
        h.server.start()
        val url = h.server.url("/").toString().trimEnd('/')
        val settings = DiskSettings(InMemorySettings(url, initialCookie = "cookie"), url)
        val base = java.nio.file.Files.createTempDirectory("xyp9").toFile().also { dirs += it }
        val hub = AttachmentUploads(File(base, "uploads"), FakeUploadScheduler())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val client = RealTetherClient(
            settings = settings, httpClient = OkHttpClient(), scope = scope, clock = { h.now.get() },
            backoff = testBackoff(), sweepIntervalMs = 3_600_000, scheduler = ManualScheduler(),
            attachmentSpool = AttachmentSpool(File(base, "spool")), attachmentUploads = hub,
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
        return Triple(client, hub, ws)
    }

    /**
     * RED (tth-xyp9). The app's real order: the photo uploads at pick, the gateway's 302 lands first (Failed,
     * no holder), then the user taps Send. The result is `NotConnected`, which the composer words as "The secure
     * link is reconnecting - your attachments were not sent. Try again in a moment." (AttachSheet.kt:356): the
     * link is fine, the upload was refused, and every retry repeats it. Expected: the refusal is surfaced as the
     * failed-upload bubble ("The server redirected the upload.") like the send-first order below.
     */
    @Test
    fun redPickThenAccess302ThenSendMislabelsTheRefusedUploadAsALinkProblem() {
        val (client, hub, _) = rig()
        val origin = client.consentOrigin.value
        val id = hub.begin(origin, "s1", photo.name, photo.mediaType, bytes)!!
        h.server.enqueue(access302())
        assertEquals(UploadRunResult.Done, runBlocking { hub.run(id) })
        assertEquals(UploadPhase.Failed, hub.state(id)?.phase)
        val res = client.sendUploaded("s1", "look", listOf(photo), listOf(id), null, origin)
        assertNotEquals("a refused upload must not read as a link problem", AttachmentSendResult.NotConnected, res)
    }

    /** Characterisation: send tapped while the upload is in flight, then the 302 lands: a visible failed-upload bubble, nothing held. */
    @Test
    fun sendThenAccess302IsAVisibleFailedUploadBubbleNotAHeldMessage() {
        val (client, hub, _) = rig()
        val origin = client.consentOrigin.value
        val id = hub.begin(origin, "s1", photo.name, photo.mediaType, bytes)!!
        assertEquals(AttachmentSendResult.Sent, client.sendUploaded("s1", "look", listOf(photo), listOf(id), null, origin))
        h.server.enqueue(access302())
        assertEquals(UploadRunResult.Done, runBlocking { hub.run(id) })
        h.await(client.failedSends) { it.isNotEmpty() }
        assertEquals("The server redirected the upload.", client.failedSends.value.single().detail)
        assertEquals(true, client.pendingSends.value.isEmpty())
    }
}
