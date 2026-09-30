package com.tether.app.client

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T9.1 on the real client: the inspector's two reads (`worktree-scripts`, `change-request`) go out
 * as the web sends them (use-tether.ts:1496, 1508), and their replies fold like use-tether.ts
 * 918-921 / 940-941: a scripts snapshot keyed by its own sessionId, a change-request per session.
 */
class InspectorReadsClientTest {
    private val h = ConnectionHarness()

    @After fun tearDown() = h.close()

    private fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(TimeUnit.SECONDS.toMillis(20)) { flow.first(predicate) } }

    @Test fun theReadsGoOutAsTheWebSendsThem() {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)

        assertEquals(true, h.client.requestWorktreeScripts("s1"))
        val scripts = h.expectFrame("worktree-scripts")
        assertEquals(setOf("type", "sessionId"), scripts.keys)
        assertEquals(true, h.client.requestChangeRequest("s1"))
        assertEquals(setOf("type", "sessionId"), h.expectFrame("change-request").keys)
        assertEquals(true, h.client.requestChangeRequest("s1", refresh = true))
        val refresh = h.expectFrame("change-request")
        assertEquals("true", refresh["refresh"]!!.jsonPrimitive.content)
    }

    @Test fun repliesFoldPerSession() {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)

        ws.send("""{"type":"worktree-scripts","snapshot":{"sessionId":"s1","worktreePath":"/w","branch":"b","scripts":[],"setupStatus":"ok","setupLog":[],"configWarnings":[]}}""")
        ws.send("""{"type":"change-request","sessionId":"s1","changeRequest":{"number":7,"url":null,"state":"OPEN","isDraft":false},"unknown":false}""")
        ws.send("""{"type":"change-request","sessionId":"s2","changeRequest":null,"unknown":true}""")
        val scripts = await(h.client.worktreeScripts) { it["s1"] != null }
        assertEquals("/w", scripts["s1"]!!["worktreePath"]!!.jsonPrimitive.content)
        val crs = await(h.client.changeRequests) { it.size == 2 }
        assertEquals("7", crs["s1"]!!.changeRequest!!["number"]!!.jsonPrimitive.content)
        assertEquals(false, crs["s1"]!!.unknown)
        assertNull(crs["s2"]!!.changeRequest)
        assertEquals(true, crs["s2"]!!.unknown)

        // A snapshot with no sessionId has nowhere to go and is dropped.
        ws.send("""{"type":"worktree-scripts","snapshot":{"scripts":[]}}""")
        h.serverBarrier(ws)
        assertEquals(setOf("s1"), h.client.worktreeScripts.value.keys)
    }
}
