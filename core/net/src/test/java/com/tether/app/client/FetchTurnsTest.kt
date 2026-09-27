package com.tether.app.client

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T6.1: `fetch-turns` goes out exactly as the web sends it (use-tether.ts:1528-1529:
 * `{ type: "fetch-turns", sessionId, fromIndex, toIndex }`, lib/protocol.ts:3535).
 */
class FetchTurnsTest {
    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    @Test
    fun fetchTurnsSendsTheWebFrame() {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)

        h.client.fetchTurns("s1", 0, 30)
        val frame = h.expectFrame("fetch-turns")
        assertEquals(setOf("type", "sessionId", "fromIndex", "toIndex"), frame.keys)
        assertEquals("s1", frame["sessionId"]!!.jsonPrimitive.content)
        assertEquals(0, frame["fromIndex"]!!.jsonPrimitive.int)
        assertEquals(30, frame["toIndex"]!!.jsonPrimitive.int)
    }
}
