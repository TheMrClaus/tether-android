package com.tether.app.client

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ta-ozn2.3 (#273): the `handover-request` frame and the refusal guard over a real (MockWebServer) socket. */
class HandoverTransmissionTest {
    private val h = ConnectionHarness()
    private val server = LinkedBlockingQueue<String>()
    private val local = LinkedBlockingQueue<String>()
    private val refusals = LinkedBlockingQueue<Unit>()
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
        jobs += h.scope.launch(start = CoroutineStart.UNDISPATCHED) { client.handoverRefusals.collect { refusals.put(Unit) } }
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, readyWithSessions("s1"))
        return client to ws
    }

    private fun next(what: String): String = server.poll(20, TimeUnit.SECONDS).also { assertNotNull("expected $what", it) }!!

    @Test
    fun theFrameIsTypeAndSessionIdOnly() {
        val (client, _) = connected()
        assertTrue(client.handoverRequest("s1"))
        val frame = h.expectFrame("handover-request")
        assertEquals(setOf("type", "sessionId"), frame.keys)
    }

    @Test
    fun aKnownCodeIsRewrittenAndSignalsTheRefusal() {
        val (_, ws) = connected()
        ws.send("""{"type":"error","message":"plan_mode: the session is in plan mode"}""")
        assertEquals("Plan mode can't hand over — leave plan mode first.", next("the rewritten refusal"))
        assertNotNull("a refusal signal", refusals.poll(20, TimeUnit.SECONDS))
    }

    @Test
    fun aCodelessOwnerSignInRefusalAndAnUnrelatedCodedErrorStayVerbatim() {
        val (client, ws) = connected()
        // No request was sent: an unknown "foo_bar: x" is just an error.
        ws.send("""{"type":"error","message":"foo_bar: x"}""")
        assertEquals("foo_bar: x", next("an unrelated error, verbatim"))
        // Even straight after a request, the codeless owner-sign-in refusal does not parse as a refusal.
        assertTrue(client.handoverRequest("s1"))
        h.expectFrame("handover-request")
        ws.send("""{"type":"error","message":"Handing a session over needs an owner sign-in."}""")
        assertEquals("Handing a session over needs an owner sign-in.", next("the owner sign-in refusal, verbatim"))
        assertNull("neither is a handover refusal", refusals.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun anUnknownCodeWithinFiveSecondsOfARequestReadsGenericallyAndLaterItDoesNot() {
        val (client, ws) = connected()
        assertTrue(client.handoverRequest("s1"))
        h.expectFrame("handover-request")
        h.now.addAndGet(4_000)
        ws.send("""{"type":"error","message":"some_new_code: detail"}""")
        assertEquals(Handover.GENERIC_REASON, next("a recent unknown code"))
        assertNotNull(refusals.poll(20, TimeUnit.SECONDS))
        h.now.addAndGet(2_000)
        ws.send("""{"type":"error","message":"some_new_code: detail"}""")
        assertEquals("some_new_code: detail", next("a stale unknown code, verbatim"))
        assertNull(refusals.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aFailedSendSaysLinkReconnectingAndIsNotSent() {
        val client = h.newClient()
        jobs += h.scope.launch(start = CoroutineStart.UNDISPATCHED) { client.errors.collect { local.put(it) } }
        assertFalse(client.handoverRequest("s1"))
        assertNotNull("the client's own words", local.poll(20, TimeUnit.SECONDS))
    }
}
