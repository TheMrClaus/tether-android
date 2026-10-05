package com.tether.app.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-m7ef (tether PR #241, PROTOCOL 143 r3) on the wire: ending an isolated session through
 * `archive-inspect` / `archive-preview` / `kill` with its `teardownConsent` (RealTetherClient over a
 * MockWebServer socket; hooks/use-end-session.ts 1bf4a465).
 */
class EndSessionTransmissionTest {
    private val h = ConnectionHarness()
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val digest = "sha256:" + "ab".repeat(32)

    @After
    fun tearDown() {
        scope.cancel()
        h.close()
    }

    private fun session(id: String, worktree: String? = null) =
        """{"type":"session","session":{"id":"$id","provider":"claude","name":"Fix the build","cwd":"/w","status":"active","startedAt":1,"updatedAt":2,
           "endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"${if (worktree != null) ""","worktree":$worktree""" else ""}}}"""

    private val worktree = """{"path":"/w/.t/a","branch":"tether/a","status":"active","setupStatus":"none"}"""

    private fun connected(): Pair<RealTetherClient, WebSocket> {
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        ws.send(session("plain"))
        ws.send(session("iso", worktree))
        ws.send(session("gone", """{"path":"/w/.t/g","branch":"b","status":"removed"}"""))
        h.await(client.sessions) { list -> list.map { it.id }.containsAll(listOf("plain", "iso", "gone")) }
        return client to ws
    }

    private fun framesOf(type: String) = h.framesUntilBarrier().filter { it.type() == type }

    private fun previewFrame(requestId: String, sessionId: String = "iso", preview: String) =
        """{"type":"archive-preview","sessionId":"$sessionId","requestId":"$requestId","preview":$preview}"""

    private fun teardownPreview(consent: String = digest, commands: String = """["make clean"]""", extra: String = "") =
        """{"sessionId":"iso","commands":$commands,"commit":"${"c".repeat(40)}","worktreePath":"/w/.t/a","checkoutIntact":true,
           "checkoutChanged":false,"hiddenCharacters":false,"fingerprint":null,"nonce":null,"digest":${if (consent == "none") "null" else "\"$consent\""},
           "consent":"$consent","error":null$extra}"""

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    // --- ending a session ----------------------------------------------------------------------------

    @Test
    fun aSessionWithNoWorktreeOrARemovedOneIsKilledAtOnceWithNoConsentField() {
        val (client, _) = connected()
        val origin = client.consentOrigin.value
        client.endSession("plain", origin)
        client.endSession("gone", origin)
        client.endSession("unlisted", origin)
        val kills = framesOf("kill")
        assertEquals(listOf("plain", "gone", "unlisted"), kills.map { it["sessionId"]!!.jsonPrimitive.content })
        assertTrue("byte-identical to the pre-v143 frame", kills.all { it.keys == setOf("type", "sessionId") })
        assertTrue(framesOf("archive-inspect").isEmpty())
    }

    @Test
    fun anIsolatedSessionAsksFirstAndEndsAtOnceWithNoneWhenNothingWouldRun() {
        val (client, ws) = connected()
        client.endSession("iso", client.consentOrigin.value)
        val inspect = framesOf("archive-inspect").single()
        assertEquals(setOf("type", "sessionId", "requestId"), inspect.keys)
        assertEquals("iso", inspect["sessionId"]!!.jsonPrimitive.content)
        // Nothing is ended before the answer.
        assertTrue(framesOf("kill").isEmpty())
        assertNull(client.endConfirmation.value)
        ws.send(previewFrame(inspect["requestId"]!!.jsonPrimitive.content, preview = "null"))
        val kill = h.expectFrame("kill")
        assertEquals(setOf("type", "sessionId", "teardownConsent"), kill.keys)
        assertEquals("none", kill["teardownConsent"]!!.jsonPrimitive.content)
        assertNull(client.endConfirmation.value)
    }

    @Test
    fun aTeardownWaitsForApprovalAndRunsWithExactlyTheReportedConsent() {
        val (client, ws) = connected()
        client.endSession("iso", client.consentOrigin.value)
        val requestId = framesOf("archive-inspect").single()["requestId"]!!.jsonPrimitive.content
        ws.send(previewFrame(requestId, preview = teardownPreview(extra = ""","stopsSessions":[{"sessionId":"s2","name":"Home"}]""")))
        val pending = h.await(client.endConfirmation) { it != null }!!
        assertEquals("Fix the build", pending.sessionName)
        assertEquals(listOf("make clean"), pending.approval!!.commands)
        assertEquals("Ending this session also stops another session that can write its checkout: Home.", pending.approval!!.stopsSessionsNotice)
        assertTrue("nothing is ended until the owner decides", framesOf("kill").isEmpty())
        client.runTeardown()
        val kill = h.expectFrame("kill")
        assertEquals(digest, kill["teardownConsent"]!!.jsonPrimitive.content)
        assertEquals("iso", kill["sessionId"]!!.jsonPrimitive.content)
        assertNull(client.endConfirmation.value)
    }

    @Test
    fun endWithoutTeardownSendsNoneAndCancelSendsNothing() {
        val (client, ws) = connected()
        val origin = client.consentOrigin.value
        client.endSession("iso", origin)
        ws.send(previewFrame(framesOf("archive-inspect").single()["requestId"]!!.jsonPrimitive.content, preview = teardownPreview()))
        h.await(client.endConfirmation) { it != null }
        client.endWithoutTeardown()
        assertEquals("none", h.expectFrame("kill")["teardownConsent"]!!.jsonPrimitive.content)
        // A second end, cancelled: nothing is sent.
        client.endSession("iso", origin)
        ws.send(previewFrame(framesOf("archive-inspect").single()["requestId"]!!.jsonPrimitive.content, preview = teardownPreview()))
        h.await(client.endConfirmation) { it != null }
        client.cancelEnd()
        assertNull(client.endConfirmation.value)
        assertTrue(framesOf("kill").isEmpty())
    }

    @Test
    fun aReplyThatIsNotThePendingChecksOwnIsIgnored() {
        val (client, ws) = connected()
        client.endSession("iso", client.consentOrigin.value)
        val requestId = framesOf("archive-inspect").single()["requestId"]!!.jsonPrimitive.content
        ws.send(previewFrame("someone-else", preview = "null"))
        ws.send("""{"type":"archive-preview","sessionId":"iso","preview":null}""")
        h.serverBarrier(ws)
        assertTrue("no kill for a reply that is not ours", framesOf("kill").isEmpty())
        // A second tap while one end is pending is ignored: no second inspect.
        client.endSession("iso", client.consentOrigin.value)
        assertTrue(framesOf("archive-inspect").isEmpty())
        // The right one still works.
        ws.send(previewFrame(requestId, preview = "null"))
        assertEquals("none", h.expectFrame("kill")["teardownConsent"]!!.jsonPrimitive.content)
    }

    @Test
    fun aRefusedInspectOffersOnlyToEndWithoutTheTeardown() {
        val (client, ws) = connected()
        client.endSession("iso", client.consentOrigin.value)
        val requestId = framesOf("archive-inspect").single()["requestId"]!!.jsonPrimitive.content
        // An error echoing another request does not end the check; the inspect's own does.
        ws.send("""{"type":"error","message":"other thing","requestId":"someone-else"}""")
        h.serverBarrier(ws)
        assertNull(client.endConfirmation.value)
        ws.send("""{"type":"error","message":"Only an owner can inspect an archive.","requestId":"$requestId"}""")
        val pending = h.await(client.endConfirmation) { it != null }!!
        assertNull(pending.approval)
        assertEquals("Only an owner can inspect an archive.", pending.message)
        client.runTeardown()
        assertTrue("there is nothing to approve", framesOf("kill").isEmpty())
        client.endWithoutTeardown()
        assertEquals("none", h.expectFrame("kill")["teardownConsent"]!!.jsonPrimitive.content)
    }

    @Test
    fun aValidatorErrorWithNoRequestIdEndsTheCheckToo() {
        val (client, ws) = connected()
        client.endSession("iso", client.consentOrigin.value)
        framesOf("archive-inspect")
        ws.send("""{"type":"error","message":"archive-inspect.sessionId must be a non-empty string"}""")
        val pending = h.await(client.endConfirmation) { it != null }!!
        assertEquals("archive-inspect.sessionId must be a non-empty string", pending.message)
    }

    @Test
    fun aMalformedPreviewOffersOnlyToEndWithoutTheTeardown() {
        val (client, ws) = connected()
        client.endSession("iso", client.consentOrigin.value)
        val requestId = framesOf("archive-inspect").single()["requestId"]!!.jsonPrimitive.content
        ws.send("""{"type":"archive-preview","sessionId":"iso","requestId":"$requestId"}""")
        val pending = h.await(client.endConfirmation) { it != null }!!
        assertNull(pending.approval)
        assertEquals(TEARDOWN_CHECK_FAILED_COPY, pending.message)
    }

    @Test
    fun aDroppedLinkDropsThePendingEnd() {
        val (client, ws) = connected()
        client.endSession("iso", client.consentOrigin.value)
        val requestId = framesOf("archive-inspect").single()["requestId"]!!.jsonPrimitive.content
        ws.send(previewFrame(requestId, preview = teardownPreview()))
        h.await(client.endConfirmation) { it != null }
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        waitFor("the confirmation to go") { client.endConfirmation.value == null }
    }

    @Test
    fun anEndDrawnForAnotherServerSendsNothing() {
        val (client, _) = connected()
        client.endSession("iso", "https://elsewhere.example:443")
        assertTrue(framesOf("archive-inspect").isEmpty())
        assertNull(client.endConfirmation.value)
        assertNotNull(client.consentOrigin.value)
    }
}
