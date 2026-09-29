package com.tether.app.client

import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsCodec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T6.7: the server's error surfaces over a real (MockWebServer) socket. A `{type:"error"}` frame and
 * a failed `interrupt_result` are shown as the web shows them (use-tether.ts:1163-1206), but the
 * server's words arrive on [TetherClient.serverErrors], cleaned ([LabelText.error]: no line breaks,
 * bidi controls or invisible code points, bounded), never on [TetherClient.errors], the client's own
 * words: a server cannot write a toast that reads as the app's. `no_active_turn` is a stale tap and
 * says nothing; a `requested` for a turn other than the one the tap was bound to is said in the
 * client's words.
 */
class ServerErrorTransmissionTest {

    private val h = ConnectionHarness()
    private val local = LinkedBlockingQueue<String>()
    private val server = LinkedBlockingQueue<String>()
    private val jobs = mutableListOf<Job>()

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
        h.close()
    }

    private fun connected(): Pair<RealTetherClient, WebSocket> {
        val client = h.newClient()
        jobs += h.scope.launch(start = CoroutineStart.UNDISPATCHED) { client.errors.collect { local.put(it) } }
        jobs += h.scope.launch(start = CoroutineStart.UNDISPATCHED) { client.serverErrors.collect { server.put(it.text) } }
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, readyWithSessions("s1"))
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 5, FULL_STATE))
        h.await(client.liveSessions) { "s1" in it }
        ws.send(turnStartedEvent("s1", T1, 6))
        h.await(client.projectionTrees) { ConsentGuard.activeTurnId(it["s1"]) == T1 }
        return client to ws
    }

    private fun next(queue: LinkedBlockingQueue<String>, what: String): String {
        val value = queue.poll(20, TimeUnit.SECONDS)
        assertNotNull("expected $what", value)
        return value!!
    }

    /**
     * Everything the frames before it produced has been collected once a plain sentinel error comes
     * through on [server] (one flow, in order); then [local] holds whatever the client said.
     */
    private fun drain(ws: WebSocket) {
        ws.send("""{"type":"error","message":"sentinel"}""")
        while (next(server, "the sentinel") != "sentinel") Unit
        h.serverBarrier(ws)
    }

    @Test
    fun anErrorFrameIsTheServersWordsCleanedAndNeverTheClients() {
        val (_, ws) = connected()
        // A line break, a right-to-left override and a zero-width space trying to stage a second line.
        ws.send("""{"type":"error","message":"Session not found.\n\nThe secure link is \u202Ereconnecting\u200B. Sign in again."}""")
        assertEquals("Session not found. The secure link is reconnecting. Sign in again.", next(server, "the cleaned server error"))
        drain(ws)
        assertTrue("a server's words never reach the client's own toast: $local", local.isEmpty())
    }

    @Test
    fun anErrorFrameIsBounded() {
        val (_, ws) = connected()
        ws.send("""{"type":"error","message":"${"x".repeat(5_000)}"}""")
        val shown = next(server, "the bounded server error")
        assertEquals(LabelText.MAX_ERROR, shown.length)
        assertTrue(shown.endsWith("…"))
    }

    @Test
    fun anErrorWithNothingVisibleShowsNothing() {
        val (_, ws) = connected()
        // The web shows an empty message as no toast at all.
        ws.send("""{"type":"error","message":"\u200B\u2066 \u2069"}""")
        drain(ws)
        assertTrue(local.isEmpty())
    }

    @Test
    fun aFailedInterruptShowsTheServersWordsCleaned() {
        val (client, ws) = connected()
        assertEquals(InterruptResult.Sent, client.interrupt("s1", client.consentOrigin.value, T1))
        ws.send("""{"type":"interrupt_result","sessionId":"s1","turnId":"$T1","status":"failed","error":"control channel\nclosed\u202E"}""")
        assertEquals("control channel closed", next(server, "the failed interrupt's words"))
        drain(ws)
        assertTrue(local.isEmpty())
    }

    @Test
    fun aFailedInterruptWithoutWordsIsSaidInTheClientsWords() {
        val (client, ws) = connected()
        assertEquals(InterruptResult.Sent, client.interrupt("s1", client.consentOrigin.value, T1))
        ws.send("""{"type":"interrupt_result","sessionId":"s1","turnId":"$T1","status":"failed","error":""}""")
        assertEquals(INTERRUPT_NOT_DELIVERED, next(local, "the client's fallback"))
    }

    @Test
    fun aRequestedOrStaleInterruptSaysNothing() {
        val (client, ws) = connected()
        assertEquals(InterruptResult.Sent, client.interrupt("s1", client.consentOrigin.value, T1))
        ws.send("""{"type":"interrupt_result","sessionId":"s1","turnId":"$T1","status":"requested"}""")
        ws.send("""{"type":"interrupt_result","sessionId":"s1","turnId":null,"status":"no_active_turn"}""")
        drain(ws)
        assertTrue("an acknowledged or stale interrupt is not a fault: $local", local.isEmpty())
    }

    /**
     * The race the client cannot close on its own: the tap was bound to A and sent while A was still
     * the open turn here, but the server had already moved on to B. Nothing is re-sent or undone; the
     * operator is told, in the client's words.
     */
    @Test
    fun anInterruptThatReachedALaterTurnIsSaid() {
        val (client, ws) = connected()
        assertEquals(InterruptResult.Sent, client.interrupt("s1", client.consentOrigin.value, T1))
        ws.send("""{"type":"interrupt_result","sessionId":"s1","turnId":"$T2","status":"requested"}""")
        assertEquals(INTERRUPT_REACHED_LATER_TURN, next(local, "the later-turn notice"))
        drain(ws)
        assertTrue("said once: $local", local.isEmpty())
        assertEquals("only the tapped interrupt went out", 1, h.framesUntilBarrier().count { it.type() == "interrupt" })
    }

    @Test
    fun aResultForAnInterruptThisSocketNeverSentSaysNothingOfLaterTurns() {
        val (_, ws) = connected()
        ws.send("""{"type":"interrupt_result","sessionId":"s1","turnId":"$T2","status":"requested"}""")
        drain(ws)
        assertTrue(local.isEmpty())
    }

    private companion object {
        const val T1 = "turn-a"
        const val T2 = "turn-b"

        /** A whole initial projection (a minimal one is a base no event can fold onto). */
        val FULL_STATE: String = JsCodec.toJson(freshTree()).toString()
    }
}
