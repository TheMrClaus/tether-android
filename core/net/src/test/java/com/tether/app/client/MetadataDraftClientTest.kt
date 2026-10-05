package com.tether.app.client

import com.tether.app.protocol.MetadataDraft
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * T8.5 on the real client: "Draft commit message" / "Draft pull request" send the web's
 * `metadata-draft-request` (use-tether.ts:1867-1875) with a fresh requestId, and both replies fold
 * into ONE console-wide list as use-tether.ts:1171-1193 does: the kind from the payload's shape,
 * a transport error as `ok: false`, a reply for a listed id replaces it at the end, at most 8 kept.
 * Dismissal is client-side (1877-1881); a sign-out empties the list, a reconnect keeps it.
 */
class MetadataDraftClientTest {
    private val h = ConnectionHarness()

    @After fun tearDown() = h.close()

    private fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(TimeUnit.SECONDS.toMillis(20)) { flow.first(predicate) } }

    private fun connected(): okhttp3.WebSocket {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        return ws
    }

    private fun ids(): List<String> = h.client.metadataDrafts.value.map { it.requestId }

    @Test fun theRequestGoesOutAsTheWebSendsIt() {
        connected()
        val minted = ArrayDeque(listOf("r1", "r2"))
        h.client.draftRequestIds = { minted.removeFirst() }

        assertEquals(true, h.client.requestMetadataDraft("commitMessage", "s1"))
        val commit = h.expectFrame("metadata-draft-request")
        assertEquals(setOf("type", "requestId", "draftKind", "sessionId"), commit.keys)
        assertEquals("r1", commit["requestId"]!!.jsonPrimitive.content)
        assertEquals("commitMessage", commit["draftKind"]!!.jsonPrimitive.content)
        assertEquals("s1", commit["sessionId"]!!.jsonPrimitive.content)

        assertEquals(true, h.client.requestMetadataDraft("pullRequest", "s2"))
        val pr = h.expectFrame("metadata-draft-request")
        assertEquals("r2", pr["requestId"]!!.jsonPrimitive.content)
        assertEquals("pullRequest", pr["draftKind"]!!.jsonPrimitive.content)
        assertEquals("s2", pr["sessionId"]!!.jsonPrimitive.content)
    }

    @Test fun eachRequestMintsAFreshId() {
        connected()
        h.client.requestMetadataDraft("commitMessage", "s1")
        h.client.requestMetadataDraft("commitMessage", "s1")
        val a = h.expectFrame("metadata-draft-request")["requestId"]!!.jsonPrimitive.content
        val b = h.expectFrame("metadata-draft-request")["requestId"]!!.jsonPrimitive.content
        assertNotEquals(a, b)
    }

    @Test fun repliesFoldIntoOneListByTheirShape() {
        val ws = connected()
        ws.send("""{"type":"metadata-draft-result","requestId":"c","result":{"ok":true,"text":"fix: x"}}""")
        ws.send("""{"type":"metadata-draft-result","requestId":"p","result":{"ok":true,"title":"T","body":"B"}}""")
        ws.send("""{"type":"metadata-draft-result","requestId":"f","result":{"ok":false,"error":"No staged changes."}}""")
        ws.send("""{"type":"metadata-draft-error","requestId":"e","error":"Session not found."}""")
        val drafts = await(h.client.metadataDrafts) { it.size == 4 }
        assertEquals(
            listOf(
                PendingMetadataDraft("c", MetadataDraft.CommitMessage("fix: x")),
                PendingMetadataDraft("p", MetadataDraft.PullRequest("T", "B")),
                PendingMetadataDraft("f", MetadataDraft.Failure("No staged changes.")),
                PendingMetadataDraft("e", MetadataDraft.Failure("Session not found.")),
            ),
            drafts,
        )

        // A second reply for "c" replaces it, at the end.
        ws.send("""{"type":"metadata-draft-result","requestId":"c","result":{"ok":true,"text":"fix: y"}}""")
        await(h.client.metadataDrafts) { it.lastOrNull()?.requestId == "c" }
        assertEquals(listOf("p", "f", "e", "c"), ids())
        assertEquals(MetadataDraft.CommitMessage("fix: y"), h.client.metadataDrafts.value.last().draft)
    }

    @Test fun atMostEightAreKeptTheOldestDropped() {
        val ws = connected()
        repeat(10) { i -> ws.send("""{"type":"metadata-draft-error","requestId":"r$i","error":"x"}""") }
        await(h.client.metadataDrafts) { it.lastOrNull()?.requestId == "r9" }
        assertEquals((2..9).map { "r$it" }, ids())
    }

    @Test fun dismissDropsOnlyThatDraft() {
        val ws = connected()
        ws.send("""{"type":"metadata-draft-error","requestId":"a","error":"x"}""")
        ws.send("""{"type":"metadata-draft-error","requestId":"b","error":"y"}""")
        await(h.client.metadataDrafts) { it.size == 2 }
        h.client.dismissMetadataDraft("b")
        assertEquals(listOf("a"), ids())
        h.client.dismissMetadataDraft("nope")
        assertEquals(listOf("a"), ids())
    }

    @Test fun aReconnectKeepsTheDrafts() {
        val ws = connected()
        ws.send("""{"type":"metadata-draft-error","requestId":"a","error":"x"}""")
        await(h.client.metadataDrafts) { it.size == 1 }
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        h.handshake(h.nextSocket())
        assertEquals(listOf("a"), ids())
    }

    @Test fun logoutEmptiesTheDrafts() {
        val ws = connected()
        ws.send("""{"type":"metadata-draft-error","requestId":"a","error":"x"}""")
        await(h.client.metadataDrafts) { it.size == 1 }
        h.server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("{}")) // POST /api/auth/logout
        runBlocking { h.client.logout() }
        assertEquals(emptyList<String>(), ids())
    }

    /** use-tether.ts:337-341: a request the link could not carry says so, in the web's words. */
    @Test fun aRequestNotSentSaysSo() {
        h.newClient()
        val errors = CopyOnWriteArrayList<String>()
        val job = h.scope.launch(start = CoroutineStart.UNDISPATCHED) { h.client.errors.collect { errors += it } }
        try {
            assertEquals(false, h.client.requestMetadataDraft("pullRequest", "s1"))
            runBlocking { withTimeout(20_000) { while (errors.isEmpty()) kotlinx.coroutines.delay(10) } }
            assertEquals(listOf("The secure link is reconnecting. Your input was not sent."), errors.toList())
        } finally {
            job.cancel()
        }
    }
}
