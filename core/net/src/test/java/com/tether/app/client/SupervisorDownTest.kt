package com.tether.app.client

import okhttp3.WebSocket
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tether-android#6 (PP-1 S4, D-L115-2): the agent supervisor's down state. `error` code `supervisor_down`
 * naming a pending send (idempotencyKey) or queue-add (queueId) is FINAL for it: failed at once with its
 * text kept, never redelivered (not on a reconnect either). `supervisor_status` down/up is the "Agents are
 * restarting" state, per socket.
 */
class SupervisorDownTest {

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    private fun connected(): WebSocket {
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 1))
        h.await(client.projections) { it.containsKey("s1") }
        return ws
    }

    private fun key(frame: kotlinx.serialization.json.JsonObject, field: String) = frame[field]!!.jsonPrimitive.content

    private fun reconnect(ws: WebSocket): WebSocket {
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        val next = h.nextSocket()
        h.handshake(next)
        return next
    }

    /** After a reconnect and a snapshot that does not hold the key: nothing but the re-attach may go out. */
    private fun assertNothingRedelivered(ws: WebSocket) {
        val ws2 = reconnect(ws)
        assertEquals(listOf("attach"), h.framesUntilBarrier().map { it.type() })
        ws2.send(snapshotFrame("s1", 2))
        h.serverBarrier(ws2)
        val after = h.framesUntilBarrier()
        assertTrue("redelivered after the refusal: $after", after.none { it.type() == "send" || it.type() == "queue-add" })
    }

    @Test
    fun aSupervisorDownRefusalIsFinalForTheSendKeepsItsTextAndIsNeverRedelivered() {
        val ws = connected()
        h.client.send("s1", "keep my words")
        val sent = key(h.expectFrame("send"), "idempotencyKey")
        ws.send("""{"type":"error","message":"Agents are restarting — try again in a moment","code":"supervisor_down","idempotencyKey":"$sent","futureField":{"a":1}}""")
        val failed = h.await(h.client.failedSends) { it.isNotEmpty() }
        assertEquals(listOf("keep my words"), failed.map { it.text })
        assertEquals(FailedSendReason.SupervisorDown, failed.single().reason)
        assertEquals(sent, failed.single().key)
        assertTrue("still pending", h.client.pendingSends.value.isEmpty())
        assertNothingRedelivered(ws)
        assertEquals("the failed bubble survives the reconnect", listOf("keep my words"), h.client.failedSends.value.map { it.text })
    }

    @Test
    fun aSupervisorDownRefusalIsFinalForTheQueueAddByItsQueueId() {
        val ws = connected()
        h.client.queueAdd("s1", "queued words")
        val queued = key(h.expectFrame("queue-add"), "queueId")
        ws.send("""{"type":"error","message":"x","code":"supervisor_down","queueId":"$queued"}""")
        val failed = h.await(h.client.failedSends) { it.isNotEmpty() }
        assertEquals(listOf("queued words"), failed.map { it.text })
        assertEquals(FailedSendReason.SupervisorDown, failed.single().reason)
        assertNothingRedelivered(ws)
    }

    @Test
    fun anErrorWithoutTheCodeOrNamingAnotherKeyLeavesThePendingRecordAlone() {
        val ws = connected()
        h.client.send("s1", "still pending")
        val sent = key(h.expectFrame("send"), "idempotencyKey")
        // an old server's error (no code), a code with no key, and a key that is not ours: none touches it.
        ws.send("""{"type":"error","message":"nope","idempotencyKey":"$sent"}""")
        ws.send("""{"type":"error","message":"down","code":"supervisor_down"}""")
        ws.send("""{"type":"error","message":"down","code":"supervisor_down","idempotencyKey":"someone-else"}""")
        h.serverBarrier(ws)
        assertTrue(h.client.failedSends.value.isEmpty())
        assertEquals(listOf("still pending"), h.client.pendingSends.value.map { it.text })
    }

    @Test
    fun aDifferentNonNullCodeNamingOurKeyLeavesThePendingRecordAlone() {
        val ws = connected()
        h.client.send("s1", "still pending")
        val sent = key(h.expectFrame("send"), "idempotencyKey")
        ws.send("""{"type":"error","message":"nope","code":"some_other_code","idempotencyKey":"$sent"}""")
        h.serverBarrier(ws)
        assertTrue(h.client.failedSends.value.isEmpty())
        assertEquals(listOf("still pending"), h.client.pendingSends.value.map { it.text })
    }

    @Test
    fun supervisorStatusDownShowsTheRestartingStateUpClearsItAndASocketEndClearsIt() {
        val ws = connected()
        assertFalse(h.client.supervisorDown.value)
        ws.send("""{"type":"supervisor_status","state":"down"}""")
        h.await(h.client.supervisorDown) { it }
        ws.send("""{"type":"supervisor_status","state":"up"}""")
        h.await(h.client.supervisorDown) { !it }
        // down, then the server closes the socket (1012) after `up` is lost: the new socket starts up.
        ws.send("""{"type":"supervisor_status","state":"down"}""")
        h.await(h.client.supervisorDown) { it }
        val ws2 = reconnect(ws)
        assertFalse(h.client.supervisorDown.value)
        // an unknown state value and a stateless frame change nothing and break nothing.
        ws2.send("""{"type":"supervisor_status","state":"sideways"}""")
        ws2.send("""{"type":"supervisor_status"}""")
        h.serverBarrier(ws2)
        assertFalse(h.client.supervisorDown.value)
    }
}
