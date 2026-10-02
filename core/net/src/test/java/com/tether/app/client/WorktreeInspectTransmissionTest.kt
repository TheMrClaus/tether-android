package com.tether.app.client

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-23f (T8.1 slice 5): `worktree-inspect` and `worktree-source` on the wire (RealTetherClient over a
 * MockWebServer socket). The inspect goes out only on the live socket it was drawn on, with its cwd
 * and requestId; every `worktree-source` of the live socket is passed on with its echo and stamped
 * with that socket's epoch (the composer matches its own); one from a socket that went is not.
 */
class WorktreeInspectTransmissionTest {

    private val h = ConnectionHarness()
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

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

    private fun inspects() = h.framesUntilBarrier().filter { it.type() == "worktree-inspect" }

    private fun collect(client: RealTetherClient): MutableList<WorktreeSourceReply> {
        val got = CopyOnWriteArrayList<WorktreeSourceReply>()
        scope.launch { client.worktreeSources.collect { got += it } }
        return got
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private val source = """{"type":"worktree-source","requestId":"req-1","info":{"cwd":"/srv/app","isRepo":true,"repoRoot":"/srv/app",""" +
        """"remote":"origin","remotes":["origin"],"currentBranch":"main","defaultBaseRef":"origin/main","branches":["origin/main","main"],""" +
        """"configPresent":true,"configWarnings":[],"hasSetup":true,"hasTeardown":false,"declaredScripts":[{"name":"test","type":"script","port":null}]}}"""

    @Test
    fun theInspectCarriesItsFolderAndTokenOnTheDrawnSocketOnly() {
        val (client, _) = connected()
        val epoch = client.linkEpoch.value
        assertTrue(client.inspectWorktree("/srv/app", "req-1", epoch))
        val sent = inspects().single()
        assertEquals(setOf("type", "cwd", "requestId"), sent.keys)
        assertEquals("/srv/app", (sent["cwd"] as JsonPrimitive).content)
        assertEquals("req-1", (sent["requestId"] as JsonPrimitive).content)
        // Drawn on another socket, an empty folder, a token past the server's bound: nothing goes.
        assertFalse(client.inspectWorktree("/srv/app", "req-2", epoch + 1))
        assertFalse(client.inspectWorktree("", "req-3", epoch))
        assertFalse(client.inspectWorktree("/srv/app", "x".repeat(65), epoch))
        assertTrue("positive control: 64 characters is within the bound", client.inspectWorktree("/srv/app", "y".repeat(64), epoch))
        assertEquals(1, inspects().size)
    }

    @Test
    fun theAnswerIsPassedOnWithItsEchoAndItsSocket() {
        val (client, ws) = connected()
        val got = collect(client)
        ws.send(source)
        waitFor("the answer") { got.isNotEmpty() }
        val reply = got.single()
        assertEquals("req-1", reply.requestId)
        assertEquals(client.linkEpoch.value, reply.linkEpoch)
        assertTrue(reply.info.isRepo)
        assertTrue(reply.info.hasSetup)
        assertEquals("origin/main", reply.info.defaultBaseRef)
        assertEquals(listOf(WorktreeDeclaredScript("test", "script", null)), reply.info.declaredScripts)
    }

    @Test
    fun anAnswerOnASocketThatWentIsNotPassedOn() {
        val (client, old) = connected()
        val got = collect(client)
        val first = client.linkEpoch.value
        h.enqueueConnect()
        old.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        val next = h.nextSocket()
        h.handshake(next)
        h.await(client.linkEpoch) { it > first }
        // The old socket's late answer is dropped; the live one's is stamped with the new socket.
        old.send(source)
        next.send(source.replace("req-1", "req-2"))
        waitFor("the live socket's answer") { got.isNotEmpty() }
        Thread.sleep(50)
        assertEquals(listOf("req-2"), got.map { it.requestId })
        assertEquals(client.linkEpoch.value, got.single().linkEpoch)
    }

    @Test
    fun noInspectGoesOutWhileDisconnected() {
        val (client, ws) = connected()
        val epoch = client.linkEpoch.value
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        assertFalse(client.inspectWorktree("/srv/app", "req-1", epoch))
    }
}
