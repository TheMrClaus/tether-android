package com.tether.app.client

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-9wdf / ta-74gu (v151): `handover-request` with a successor, `handover-cancel`, and the requestId-first routing of a
 * refusal (wave-check W34 #2, use-tether.ts:1247-1265) over a real (MockWebServer) socket. Red first: none of the
 * send methods or the routing existed.
 */
class HandoverV151TransmissionTest {
    private val h = ConnectionHarness()
    private val server = LinkedBlockingQueue<String>()
    private val local = LinkedBlockingQueue<String>()
    private val refusals = LinkedBlockingQueue<Unit>()
    private val stopRefusals = LinkedBlockingQueue<String>()
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
        jobs += h.scope.launch(start = CoroutineStart.UNDISPATCHED) { client.handoverStopRefusals.collect { stopRefusals.put(it) } }
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, readyWithSessions("s1"))
        return client to ws
    }

    private fun next(what: String): String = server.poll(20, TimeUnit.SECONDS).also { assertNotNull("expected $what", it) }!!

    private fun refuse(ws: WebSocket, requestId: String, message: String) =
        ws.send("""{"type":"error","message":"$message","requestId":"$requestId"}""")

    private val successor = buildJsonObject {
        put("provider", "claude")
        put("cwd", "/w")
        put("approvalPolicy", JsonNull)
    }

    // ---- frames ----
    @Test
    fun theSettingsFrameCarriesSuccessorAndRequestIdAndTheAsIsFrameStaysTypeAndSessionIdOnly() {
        val (client, _) = connected()
        assertTrue(client.handoverRequestWithSettings("s1", "r1", successor))
        val frame = h.expectFrame("handover-request")
        assertEquals(setOf("type", "sessionId", "requestId", "successor"), frame.keys)
        assertEquals(JsonPrimitive("r1"), frame["requestId"])
        assertEquals(successor, frame["successor"])
        assertTrue(client.handoverRequest("s1"))
        assertEquals(setOf("type", "sessionId"), h.expectFrame("handover-request").keys)
    }

    @Test
    fun theCancelFrameIsTypeSessionIdAndRequestId() {
        val (client, _) = connected()
        assertTrue(client.handoverCancel("s1", "stop-1"))
        val frame = h.expectFrame("handover-cancel")
        assertEquals(setOf("type", "sessionId", "requestId"), frame.keys)
        assertEquals(JsonPrimitive("stop-1"), frame["requestId"])
    }

    @Test
    fun aFailedCancelSendIsLinkReconnectingAndAFailedComposerSendSaysNothingGlobal() {
        val client = h.newClient()
        jobs += h.scope.launch(start = CoroutineStart.UNDISPATCHED) { client.errors.collect { local.put(it) } }
        assertFalse(client.handoverRequestWithSettings("s1", "r1", successor))
        assertNull("the composer says it inline", local.poll(300, TimeUnit.MILLISECONDS))
        assertFalse(client.handoverCancel("s1", "stop-1"))
        assertNotNull("a failed stop says so", local.poll(20, TimeUnit.SECONDS))
    }

    // ---- routing (i): a stop ----
    @Test
    fun aStopRefusalReadsTheStopCopyKeepsTheClaimsAndSignalsTheSession() {
        val (client, ws) = connected()
        assertTrue(client.handoverCancel("s1", "stop-1"))
        h.expectFrame("handover-cancel")
        refuse(ws, "stop-1", "handover_in_progress: The handover is already running and cannot be stopped.")
        assertEquals("Too late to stop — the new session is already starting.", next("the stop copy"))
        assertEquals("s1", stopRefusals.poll(20, TimeUnit.SECONDS))
        assertNull("claims stay: no handoverRefusals", refusals.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun handoverInProgressOnAStopNeverReadsHandoverUnderWay() {
        val (client, ws) = connected()
        client.handoverCancel("s1", "stop-1"); h.expectFrame("handover-cancel")
        refuse(ws, "stop-1", "handover_in_progress: x")
        assertFalse(next("a stop refusal").contains("Handover under way"))
    }

    @Test
    fun notPendingAndAnUnknownStopCodeReadTheirOwnCopy() {
        val (client, ws) = connected()
        client.handoverCancel("s1", "stop-1"); h.expectFrame("handover-cancel")
        refuse(ws, "stop-1", "not_pending: There is no handover to stop.")
        assertEquals("There’s no handover to stop — it already ended.", next("not_pending"))
        client.handoverCancel("s1", "stop-2"); h.expectFrame("handover-cancel")
        refuse(ws, "stop-2", "some_new_code: x")
        assertEquals("This session can't hand over right now.", next("default"))
    }

    @Test
    fun aStopRequestIdIsConsumedOnce() {
        val (client, ws) = connected()
        client.handoverCancel("s1", "stop-1"); h.expectFrame("handover-cancel")
        refuse(ws, "stop-1", "not_pending: x"); next("first")
        stopRefusals.poll(20, TimeUnit.SECONDS)
        refuse(ws, "stop-1", "not_pending: x")
        assertEquals("a second echo is just an error", "not_pending: x", next("verbatim"))
        assertNull(stopRefusals.poll(300, TimeUnit.MILLISECONDS))
    }

    // ---- routing (iii): a live composer is inline only ----
    @Test
    fun aComposerRefusalIsInlineOnlyNoToastAndTheClaimDrops() {
        val (client, ws) = connected()
        assertTrue(client.handoverRequestWithSettings("s1", "r1", successor)); h.expectFrame("handover-request")
        refuse(ws, "r1", "engine_unavailable: x")
        assertNotNull("the claim drops", refusals.poll(20, TimeUnit.SECONDS))
        assertNull("no global toast", server.poll(500, TimeUnit.MILLISECONDS))
        assertNull(stopRefusals.poll(100, TimeUnit.MILLISECONDS))
    }

    // ---- routing (ii): an abandoned composer ----
    @Test
    fun anAbandonedComposerRefusalIsACouldntHandOverToastAndDropsTheClaim() {
        val (client, ws) = connected()
        client.handoverRequestWithSettings("s1", "r1", successor); h.expectFrame("handover-request")
        client.abandonHandoverRequest("r1")
        refuse(ws, "r1", "folder_unavailable: x")
        assertEquals("Couldn't hand over: Tether can't use that folder. Pick another folder and try again.", next("the abandoned toast"))
        assertNotNull(refusals.poll(20, TimeUnit.SECONDS))
    }

    @Test
    fun anAbandonedUnknownCodeReadsTheGenericRefusal() {
        val (client, ws) = connected()
        client.handoverRequestWithSettings("s1", "r1", successor); h.expectFrame("handover-request")
        client.abandonHandoverRequest("r1")
        refuse(ws, "r1", "a_future_code: x")
        assertEquals(Handover.GENERIC_REFUSAL, next("generic"))
    }

    // use-tether.ts:871-872: a `created` frame carrying the requestId forgets the composer and abandoned ids
    @Test
    fun aCreatedFrameForTheRequestIdForgetsItSoALaterRefusalFallsThrough() {
        val (client, ws) = connected()
        client.handoverRequestWithSettings("s1", "r1", successor); h.expectFrame("handover-request")
        client.handoverRequestWithSettings("s1", "r2", successor); h.expectFrame("handover-request")
        client.abandonHandoverRequest("r2")
        for (id in listOf("r1", "r2")) {
            ws.send("""{"type":"created","requestId":"$id","session":{"id":"c$id","provider":"claude","name":"n","cwd":"/w","status":"active","startedAt":1,"updatedAt":1,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"}}""")
        }
        val until = System.currentTimeMillis() + 20_000
        while (client.sessions.value.size < 3 && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(3, client.sessions.value.size)
        refuse(ws, "r1", "plan_mode: x")
        assertEquals("Plan mode can't hand over \u2014 leave plan mode first.", next("readError, not the composer route"))
        assertNotNull(refusals.poll(20, TimeUnit.SECONDS))
        refuse(ws, "r2", "plan_mode: x")
        assertEquals("Plan mode can't hand over \u2014 leave plan mode first.", next("readError, not the abandoned toast"))
    }

    // ---- routing (iv): anything else is today's reading ----
    @Test
    fun anUnknownRequestIdFallsThroughToTheV150Reading() {
        val (_, ws) = connected()
        refuse(ws, "nobody", "plan_mode: x")
        assertEquals("Plan mode can't hand over — leave plan mode first.", next("readError"))
        assertNotNull(refusals.poll(20, TimeUnit.SECONDS))
        refuse(ws, "nobody", "foo_bar: x")
        assertEquals("foo_bar: x", next("verbatim"))
    }

    @Test
    fun aCodelessErrorWithNoRequestIdIsUntouched() {
        val (_, ws) = connected()
        ws.send("""{"type":"error","message":"Handing a session over needs an owner sign-in."}""")
        assertEquals("Handing a session over needs an owner sign-in.", next("verbatim"))
    }

    @Test
    fun theSnapshotCounterMovesOnEveryReady() {
        val (client, _) = connected()
        assertEquals(1L, client.snapshotSeq.value)
    }
}
