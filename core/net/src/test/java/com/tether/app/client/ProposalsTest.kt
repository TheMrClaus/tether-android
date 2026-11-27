package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v175: the proposal card's gate, refusal texts, Start settings, and the requestId-keyed routing of a refusal over a real socket. */
class ProposalsTest {
    private val h = ConnectionHarness()
    private val toasts = LinkedBlockingQueue<String>()
    private val jobs = mutableListOf<Job>()

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
        h.close()
    }

    @Test
    fun theCardShowsOnlyFromServerProtocol175() {
        assertFalse(proposalCardSupported(174))
        assertTrue(proposalCardSupported(175))
        assertTrue(proposalCardSupported(176))
        assertFalse(proposalCardSupported(null))
    }

    @Test
    fun everyRefusalCodeReadsItsOwnSentenceNotTheServersDetail() {
        for ((code, text) in Proposals.REFUSAL_TEXTS) {
            assertEquals(ProposalRefusal(code, text), Proposals.parseRefusal("$code: /home/someone/raw detail"))
        }
        assertEquals("The chosen folder is not available for a new session.", Proposals.parseRefusal("folder_unavailable: x").text)
        assertEquals("This proposal can no longer be started.", Proposals.parseRefusal("proposal_closed: x").text)
    }

    @Test
    fun anUnknownCodeReadsTheServersWordsCleaned() {
        val refusal = Proposals.parseRefusal("something_new: It did not work")
        assertNull(refusal.code)
        assertTrue(refusal.text.contains("It did not work"))
    }

    @Test
    fun aRefusalNamesTheRowItBelongsTo() {
        assertEquals("folder", Proposals.faultRow("folder_unavailable"))
        assertEquals("harness", Proposals.faultRow("engine_unavailable"))
        assertEquals("account", Proposals.faultRow("profile_unavailable"))
        assertNull(Proposals.faultRow("invalid_settings"))
        assertNull(Proposals.faultRow("proposal_closed"))
    }

    private fun connected(): Pair<RealTetherClient, WebSocket> {
        val client = h.newClient()
        jobs += h.scope.launch(start = CoroutineStart.UNDISPATCHED) { client.serverErrors.collect { toasts.put(it.text) } }
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, readyWithSessions("s1"))
        return client to ws
    }

    private fun awaitLocal(client: RealTetherClient, id: String, until: (ProposalLocalState?) -> Boolean): ProposalLocalState? {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            val v = client.proposalLocal.value[id]
            if (until(v)) return v
            Thread.sleep(20)
        }
        return client.proposalLocal.value[id]
    }

    @Test
    fun aStartPressIsInFlightUntilItsRequestIdIsRefusedAndTheRefusalIsInlineNotAToast() {
        val (client, ws) = connected()
        val settings = JsonObject(mapOf("provider" to JsonPrimitive("claude"), "cwd" to JsonPrimitive("/w")))
        assertTrue(client.sendProposal(ClientMessage.ProposalFrame("start", "s1", "p1", settings, "r1")))
        val frame = h.expectFrame("proposal-start")
        assertEquals(setOf("type", "sessionId", "proposalId", "settings", "requestId"), frame.keys)
        assertTrue(client.proposalLocal.value["p1"]!!.starting)
        ws.send("""{"type":"error","message":"profile_unavailable: detail","requestId":"r1"}""")
        val held = awaitLocal(client, "p1") { it?.refusal != null }!!
        assertEquals(ProposalRefusal("profile_unavailable", "The chosen account or profile is not available."), held.refusal)
        assertFalse(held.starting)
        assertNull("an inline refusal is no toast", toasts.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aRefusalOfAnotherRequestIdLeavesTheProposalAlone() {
        val (client, ws) = connected()
        assertTrue(client.sendProposal(ClientMessage.ProposalFrame("here", "s1", "p1", null, "r1")))
        h.expectFrame("proposal-here")
        ws.send("""{"type":"error","message":"proposal_closed: x","requestId":"other"}""")
        assertTrue("an unrelated refusal still shows", toasts.poll(20, TimeUnit.SECONDS) != null)
        assertNull(client.proposalLocal.value["p1"]!!.refusal)
    }

    @Test
    fun aFailedSendIsRefusedLocallyWithTheLinkSentence() {
        val client = h.newClient()
        assertFalse(client.sendProposal(ClientMessage.ProposalFrame("dismiss", "s1", "p1", null, "r1")))
        assertTrue(client.proposalLocal.value["p1"]!!.refusal!!.text.contains("reconnecting"))
    }
}
