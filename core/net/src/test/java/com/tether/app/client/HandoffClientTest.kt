package com.tether.app.client

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8.5 on the real client: the takeover's frames as use-tether.ts 90fbb9f sends them —
 * `handoff-brief` { sourceId, targetId } (:1889-1893) and `handoff` { sourceId, targetId, engineText }
 * (:1899-1901) — the `handoff-brief` reply kept by source (:1194-1198, a second replaces the first),
 * a refusal shown as the server's error with no brief, the brief dropped client-side (:1906-1913),
 * a frame the link could not carry said so, and a sign-out emptying the briefs.
 */
class HandoffClientTest {
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

    private fun briefFrame(source: String, instruction: String) =
        """{"type":"handoff-brief","sourceId":"$source","brief":{"session":{"id":"$source"}},"markdown":"# $source","instruction":"$instruction"}"""

    @Test fun theBriefRequestAndTheHandoffGoOutAsTheWebSendsThem() {
        connected()
        assertEquals(true, h.client.requestHandoffBrief("src", "tgt"))
        val request = h.expectFrame("handoff-brief")
        assertEquals(setOf("type", "sourceId", "targetId"), request.keys)
        assertEquals("src", request["sourceId"]!!.jsonPrimitive.content)
        assertEquals("tgt", request["targetId"]!!.jsonPrimitive.content)

        assertEquals(true, h.client.handoff("src", "new-1", "Resume the work."))
        val handoff = h.expectFrame("handoff")
        assertEquals(setOf("type", "sourceId", "targetId", "engineText"), handoff.keys)
        assertEquals("src", handoff["sourceId"]!!.jsonPrimitive.content)
        assertEquals("new-1", handoff["targetId"]!!.jsonPrimitive.content)
        assertEquals("Resume the work.", handoff["engineText"]!!.jsonPrimitive.content)
    }

    @Test fun theReplyIsKeptBySourceAndASecondReplacesTheFirst() {
        val ws = connected()
        ws.send(briefFrame("a", "Resume a."))
        ws.send(briefFrame("b", "Resume b."))
        val briefs = await(h.client.handoffBriefs) { it.size == 2 }
        assertEquals("Resume a.", briefs["a"]!!.instruction)
        assertEquals("# a", briefs["a"]!!.markdown)
        assertEquals("a", briefs["a"]!!.sourceId)
        ws.send(briefFrame("a", "Resume a, again."))
        await(h.client.handoffBriefs) { it["a"]?.instruction == "Resume a, again." }
        assertEquals(setOf("a", "b"), h.client.handoffBriefs.value.keys)
        h.client.clearHandoffBrief("a")
        assertEquals(setOf("b"), h.client.handoffBriefs.value.keys)
    }

    /** use-tether.ts :1199-1200: a refused brief is the server's `error`, shown; no brief lands. */
    @Test fun aRefusedBriefIsTheServersErrorAndNoBrief() {
        val ws = connected()
        val toast = h.scope.async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { withTimeout(20_000) { h.client.serverErrors.first().text } }
        h.client.requestHandoffBrief("gone", "tgt")
        h.expectFrame("handoff-brief")
        ws.send("""{"type":"error","message":"Session not found."}""")
        assertTrue(runBlocking { toast.await() }.contains("Session not found."))
        assertEquals(emptyMap<String, HandoffBriefReading>(), h.client.handoffBriefs.value)
    }

    @Test fun logoutEmptiesTheBriefs() {
        val ws = connected()
        ws.send(briefFrame("a", "Resume a."))
        await(h.client.handoffBriefs) { it.size == 1 }
        h.server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("{}")) // POST /api/auth/logout
        runBlocking { h.client.logout() }
        assertEquals(emptyMap<String, HandoffBriefReading>(), h.client.handoffBriefs.value)
    }

    /** use-tether.ts :337-341: a frame the link could not carry says so, in the web's words. */
    @Test fun framesNotSentSaySo() {
        h.newClient()
        val errors = CopyOnWriteArrayList<String>()
        val job = h.scope.launch(start = CoroutineStart.UNDISPATCHED) { h.client.errors.collect { errors += it } }
        try {
            assertEquals(false, h.client.requestHandoffBrief("src", "tgt"))
            assertEquals(false, h.client.handoff("src", "tgt", "x"))
            runBlocking { withTimeout(20_000) { while (errors.size < 2) kotlinx.coroutines.delay(10) } }
            assertEquals(List(2) { "The secure link is reconnecting. Your input was not sent." }, errors.toList())
        } finally {
            job.cancel()
        }
    }
}
