package com.tether.app.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-m7ef (tether PR #241, PROTOCOL 143) on the wire: the intent `worktree-inspect` (a create's or a
 * schedule save's setup check) and the `worktree-source` that carries its `setupPreview`
 * (RealTetherClient over a MockWebServer socket; use-draft-composer.ts 1bf4a465).
 */
class SetupConsentTransmissionTest {
    private val h = ConnectionHarness()
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val digest = "sha256:" + "ab".repeat(32)

    @After
    fun tearDown() {
        scope.cancel()
        h.close()
    }

    private fun connected(): Pair<RealTetherClient, WebSocket> {
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        return client to ws
    }

    private fun framesOf(type: String) = h.framesUntilBarrier().filter { it.type() == type }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    // --- the intent inspect ------------------------------------------------------------------------

    @Test
    fun theSetupCheckCarriesTheIntendedCreatesBlock() {
        val (client, _) = connected()
        val epoch = client.linkEpoch.value
        val block = com.tether.app.protocol.WorktreeCreateRequest("checkout-pr", prNumber = 42)
        assertTrue(client.inspectSetup("/srv/app", block, "chk-1", epoch))
        val sent = framesOf("worktree-inspect").single()
        assertEquals(setOf("type", "cwd", "requestId", "worktree"), sent.keys)
        assertEquals("/srv/app", sent["cwd"]!!.jsonPrimitive.content)
        assertEquals("chk-1", sent["requestId"]!!.jsonPrimitive.content)
        assertEquals("checkout-pr", sent["worktree"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals("42", sent["worktree"]!!.jsonObject["prNumber"]!!.jsonPrimitive.content)
        // Another socket, an empty folder, a token past the server's bound: nothing goes.
        assertFalse(client.inspectSetup("/srv/app", block, "chk-2", epoch + 1))
        assertFalse(client.inspectSetup("", block, "chk-3", epoch))
        assertFalse(client.inspectSetup("/srv/app", block, "x".repeat(65), epoch))
        assertTrue(framesOf("worktree-inspect").isEmpty())
    }

    @Test
    fun aWorktreeSourceWithASetupPreviewIsParsedWithItsConsent() {
        val (client, ws) = connected()
        val got = java.util.concurrent.CopyOnWriteArrayList<WorktreeSourceReply>()
        scope.launch { client.worktreeSources.collect { got += it } }
        ws.send(
            """{"type":"worktree-source","requestId":"chk-1","info":{"cwd":"/w","isRepo":true,"repoRoot":"/w","remote":"origin","remotes":["origin"],
               "currentBranch":"main","defaultBaseRef":"origin/main","branches":[],"configPresent":true,"configWarnings":[],"hasSetup":true,"hasTeardown":true,
               "declaredScripts":[],"setupPreview":{"mode":"branch-off","remote":"origin","baseRef":"origin/main","commit":"${"c".repeat(40)}",
               "commands":["pnpm install"],"teardown":["make clean"],"portScript":null,"portScriptSha256":null,"hiddenCharacters":false,
               "configPresent":true,"configWarnings":[],"digest":"$digest","consent":"$digest","scheduleConsent":"$digest","error":null}}}""",
        )
        waitFor("the answer") { got.isNotEmpty() }
        val preview = got.single().info.setupPreview!!
        assertEquals(listOf("pnpm install"), preview.commands)
        assertEquals(listOf("make clean"), preview.teardown)
        assertEquals(digest, preview.consent)
        assertEquals(digest, preview.scheduleConsent)
        assertTrue(SetupConsentSteps.create(got.single().info) is SetupConsentStep.Confirm)
    }
}
