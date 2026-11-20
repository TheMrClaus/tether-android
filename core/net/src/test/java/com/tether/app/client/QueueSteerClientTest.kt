package com.tether.app.client

import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v165: Steer on one waiting message, over a real socket (RealTetherClient on a MockWebServer): the `queue-steer` frame, the four
 * answers, the row-specific interrupt, and the protocol gate (a server below 165 never sees either).
 */
class QueueSteerClientTest {
    private val opened = mutableListOf<ConnectionHarness>()
    private lateinit var h: ConnectionHarness

    @After fun tearDown() = opened.forEach { it.close() }

    private fun readyAt(protocol: Int) = readyWithSessions("s1", "s2")
        .replace("\"protocolVersion\":${com.tether.app.protocol.PROTOCOL_VERSION}", "\"protocolVersion\":$protocol")

    private fun connected(protocol: Int): Pair<RealTetherClient, WebSocket> {
        h = ConnectionHarness().also(opened::add)
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, readyAt(protocol))
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 5, JsCodec.toJson(freshTree()).toString()))
        h.await(client.liveSessions) { "s1" in it }
        ws.send(turnStartedEvent("s1", TURN, 6))
        h.await(client.projectionTrees) { ConsentGuard.activeTurnId(it["s1"]) == TURN }
        return client to ws
    }

    private fun framesOf(type: String): List<JsonObject> = h.framesUntilBarrier().filter { it.type() == type }

    private fun answer(ws: WebSocket, status: String) {
        ws.send("""{"type":"queue_steer_result","sessionId":"s1","queueId":"q1","status":"$status"}""")
        h.serverBarrier(ws)
    }

    @Test fun aSteerPressSendsTheFrameAndMarksTheRowPending() {
        val (client, _) = connected(165)
        assertTrue(client.queueSteer("s1", "q1"))
        val frames = framesOf("queue-steer")
        assertEquals(1, frames.size)
        assertEquals(setOf("type", "sessionId", "queueId"), frames[0].keys)
        assertEquals("q1", frames[0]["queueId"]?.jsonPrimitive?.content)
        assertEquals(mapOf("q1" to QueueRowSteer.Pending), client.queueSteerStates.value)
    }

    @Test fun aServerBelow165NeverSeesAQueueSteerFrame() {
        val (client, _) = connected(164)
        assertFalse(client.queueSteer("s1", "q1"))
        assertTrue(framesOf("queue-steer").isEmpty())
        assertEquals(emptyMap<String, QueueRowSteer>(), client.queueSteerStates.value)
    }

    @Test fun notSteerableLeavesTheRowRefusedAndNeverInterrupts() {
        val (client, ws) = connected(165)
        client.queueSteer("s1", "q1")
        h.framesUntilBarrier()
        answer(ws, "not_steerable")
        assertEquals(mapOf("q1" to QueueRowSteer.Refused), client.queueSteerStates.value)
        assertTrue("a refused steer never interrupts by itself", framesOf("interrupt").isEmpty())
    }

    @Test fun steeredUncertainUnavailableAndAnUnknownStatusClearThePendingMark() {
        for (status in listOf("steered", "uncertain", "unavailable", "something-new")) {
            val (client, ws) = connected(165)
            client.queueSteer("s1", "q1")
            h.framesUntilBarrier()
            answer(ws, status)
            assertEquals("status $status", emptyMap<String, QueueRowSteer>(), client.queueSteerStates.value)
        }
    }

    @Test fun aRefusedRowIsForgottenWhenItsTurnEnds() {
        val (client, ws) = connected(165)
        client.queueSteer("s1", "q1")
        h.framesUntilBarrier()
        answer(ws, "not_steerable")
        client.forgetQueueSteer("s1")
        assertEquals(emptyMap<String, QueueRowSteer>(), client.queueSteerStates.value)
    }

    @Test fun theRowSpecificInterruptNamesTheRowOnlyAt165() {
        val (c165, _) = connected(165)
        assertEquals(InterruptResult.Sent, c165.interrupt("s1", c165.consentOrigin.value, TURN, stopBackground = false, queueId = "q2"))
        val on = framesOf("interrupt")
        assertEquals(setOf("type", "sessionId", "queueId"), on[0].keys)
        assertEquals("q2", on[0]["queueId"]?.jsonPrimitive?.content)

        val (c164, _) = connected(164)
        assertEquals(InterruptResult.Sent, c164.interrupt("s1", c164.consentOrigin.value, TURN, stopBackground = false, queueId = "q2"))
        assertEquals("a 164 server gets the plain frame", setOf("type", "sessionId"), framesOf("interrupt")[0].keys)
    }

    @Test fun aPlainStopStaysAPlainInterruptAt165() {
        val (client, _) = connected(165)
        assertEquals(InterruptResult.Sent, client.interrupt("s1", client.consentOrigin.value, TURN))
        assertEquals(setOf("type", "sessionId"), framesOf("interrupt")[0].keys)
    }

    private companion object { const val TURN = "turn-a" }
}
